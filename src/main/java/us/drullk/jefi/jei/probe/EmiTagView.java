package us.drullk.jefi.jei.probe;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/** The tags that EMI 1.1.24 can show in place of a slot's entries. */
public final class EmiTagView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAMESPACE = "emi";
    private static final String DIRECTORY = "tag/exclusions";
    private static final String EVERY_REGISTRY = "exclusions";
    private static final ResourceLocation HIDDEN = ResourceLocation.fromNamespaceAndPath("c", "hidden_from_recipe_viewers");

    private final Set<ResourceLocation> global = new HashSet<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> byRegistry = new HashMap<>();

    private EmiTagView() {
    }

    /** Every exclusion file in pack order. A file with {@code "replace": true} drops the files before it. */
    public static EmiTagView load(ResourceManager manager) {
        EmiTagView view = new EmiTagView();
        for (ResourceLocation id : manager.listResources(DIRECTORY, file -> file.getPath().endsWith(".json")).keySet()) {
            if (!NAMESPACE.equals(id.getNamespace())) {
                continue;
            }
            for (Resource resource : manager.getResourceStack(id)) {
                try (Reader reader = resource.openAsReader()) {
                    view.read(GsonHelper.parse(reader));
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Could not read the EMI tag exclusions {} from {}", id, resource.sourcePackId(), e);
                }
            }
        }
        return view;
    }

    /** EMI stops reading a file at its first bad entry and keeps what it read before. */
    private void read(JsonObject json) {
        if (GsonHelper.getAsBoolean(json, "replace", false)) {
            global.clear();
            byRegistry.clear();
        }
        for (String key : json.keySet()) {
            if (!GsonHelper.isArrayNode(json, key)) {
                continue;
            }
            ResourceLocation registry = EVERY_REGISTRY.equals(key) ? null : ResourceLocation.parse(key);
            for (JsonElement element : GsonHelper.getAsJsonArray(json, key)) {
                ResourceLocation tag = ResourceLocation.parse(element.getAsString());
                add(registry, tag);
                if ("c".equals(tag.getNamespace())) {
                    add(registry, ResourceLocation.fromNamespaceAndPath("forge", tag.getPath()));
                }
            }
        }
    }

    private void add(@Nullable ResourceLocation registry, ResourceLocation tag) {
        if (registry == null) {
            global.add(tag);
        } else {
            byRegistry.computeIfAbsent(registry, key -> new HashSet<>()).add(tag);
        }
    }

    /** The values EMI can show this tag in place of, or an empty list when EMI leaves the tag out. */
    public <T> List<T> values(Registry<T> registry, HolderSet.Named<T> tag) {
        ResourceLocation id = tag.key().location();
        if (global.contains(id) || byRegistry.getOrDefault(registry.key().location(), Set.of()).contains(id)) {
            return List.of();
        }
        List<T> values = values(registry, tag.stream());
        List<T> hidden = registry.getTag(TagKey.create(registry.key(), HIDDEN)).map(set -> values(registry, set.stream())).orElse(List.of());
        return Set.copyOf(hidden).containsAll(values) ? List.of() : values;
    }

    private static <T> List<T> values(Registry<T> registry, Stream<Holder<T>> holders) {
        return holders.map(Holder::value)
                .filter(value -> !registry.key().equals(Registries.FLUID) || value instanceof Fluid fluid && fluid.isSource(fluid.defaultFluidState()))
                .toList();
    }

    /** The values of a tag that EMI can show in place of slot entries, in EMI's order. */
    public record Match(List<?> values) {
        /** True when the tag holds every entry of the slot. EMI then shows the tag, not the list. */
        public boolean whole(int entryCount) {
            return values.size() == entryCount;
        }
    }

    /** A tag that holds two or more of these fluids or items, in EMI's order, or null. */
    public @Nullable Match wholeOrPartialTag(Set<Object> entries) {
        if (entries.size() < 2) {
            return null;
        }
        if (entries.stream().allMatch(Item.class::isInstance)) {
            return match(BuiltInRegistries.ITEM, entries);
        }
        if (entries.stream().allMatch(Fluid.class::isInstance)) {
            return match(BuiltInRegistries.FLUID, entries);
        }
        return null;
    }

    private <T> @Nullable Match match(Registry<T> registry, Set<Object> entries) {
        return registry.getTags()
                .map(pair -> values(registry, pair.getSecond()))
                .filter(values -> values.size() >= 2 && entries.containsAll(values))
                .findFirst()
                .map(Match::new)
                .orElse(null);
    }
}
