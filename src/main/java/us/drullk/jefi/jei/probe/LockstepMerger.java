package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.ModList;

/**
 * Merges the recipes of one mod with one {@link Shape} into one recipe of rows. A row shows one entry per linked
 * slot. A source or neighbor list that every row shares is one free slot.
 *
 * <p>A group without a free slot splits by a list its members share.
 *
 * <p>A group of more than {@link #MAX_ROWS} rows splits by neighbor entries, then by source fluids.
 *
 * <p>EMI shows a whole tag in place of its entries. So under EMI, a group that fills a tag stays apart.
 */
final class LockstepMerger {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** JEI shows at most this many entries of one slot. */
    static final int MAX_ROWS = 100;

    private LockstepMerger() {
    }

    static List<FluidInteractionRecipe> merge(List<FluidInteractionRecipe> recipes, Set<String> withinMods) {
        if (withinMods.isEmpty()) {
            return recipes;
        }
        boolean emi = ModList.get().isLoaded("emi");
        List<List<FluidInteractionRecipe>> groups = new ArrayList<>();
        Map<Shape, List<FluidInteractionRecipe>> byShape = new HashMap<>();
        for (FluidInteractionRecipe recipe : recipes) {
            if (recipe.isFailure() || recipe.owner() == null || !withinMods.contains(recipe.owner())) {
                groups.add(List.of(recipe));
                continue;
            }
            List<FluidInteractionRecipe> group = byShape.get(Shape.of(recipe));
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
                byShape.put(Shape.of(recipe), group);
            }
            group.add(recipe);
        }
        List<FluidInteractionRecipe> merged = new ArrayList<>(groups.size());
        int members = 0;
        int lockstep = 0;
        for (List<FluidInteractionRecipe> whole : groups) {
            for (List<FluidInteractionRecipe> group : parts(whole)) {
                if (group.size() == 1) {
                    merged.add(group.getFirst());
                    continue;
                }
                List<FluidInteractionRecipe> rows = rows(group);
                if (rows.size() > MAX_ROWS) {
                    LOGGER.debug("Kept {} recipe(s) of {} from {} apart: {} rows", group.size(), group.getFirst().owner(),
                            group.getFirst().id(), rows.size());
                    merged.addAll(group);
                    continue;
                }
                TagKey<?> tag = emi ? filledTag(group.getFirst(), rows) : null;
                if (tag != null) {
                    LOGGER.debug("Kept {} recipe(s) of {} from {} apart: EMI shows the tag {} in place of their entries",
                            group.size(), group.getFirst().owner(), group.getFirst().id(), tag.location());
                    merged.addAll(group);
                    continue;
                }
                merged.add(build(group, rows, group.getFirst().id(), group.getFirst().owner()));
                members += group.size();
                lockstep++;
            }
        }
        LOGGER.debug("Merged {} recipe(s) of {} into {} lockstep recipe(s)", members, withinMods, lockstep);
        return merged;
    }

    private static List<List<FluidInteractionRecipe>> parts(List<FluidInteractionRecipe> group) {
        List<List<FluidInteractionRecipe>> parts = new ArrayList<>();
        byList(group).forEach(part -> parts.addAll(bySize(part)));
        return parts;
    }

    /** Without a free slot, one part per list that several members share. */
    private static List<List<FluidInteractionRecipe>> byList(List<FluidInteractionRecipe> group) {
        if (group.size() == 1) {
            return List.of(group);
        }
        List<FluidInteractionRecipe> rows = rows(group);
        if (FluidInteractionRecipe.sharesSources(rows) || FluidInteractionRecipe.sharesNeighbors(rows)) {
            return List.of(group);
        }
        Map<List<Object>, Integer> holders = new HashMap<>();
        for (FluidInteractionRecipe member : group) {
            for (boolean source : new boolean[] {true, false}) {
                List<Object> key = listKey(member, source);
                if (key != null) {
                    holders.merge(key, 1, Integer::sum);
                }
            }
        }
        Map<List<Object>, List<FluidInteractionRecipe>> parts = new LinkedHashMap<>();
        for (FluidInteractionRecipe member : group) {
            List<Object> key = listKey(member, true);
            if (key == null || holders.get(key) < 2) {
                key = listKey(member, false);
            }
            if (key == null || holders.get(key) < 2) {
                key = List.of();
            }
            parts.computeIfAbsent(key, k -> new ArrayList<>()).add(member);
        }
        if (parts.size() > 1) {
            LOGGER.debug("Split {} recipe(s) of {} from {} into {} part(s) by the lists they share: {}", group.size(),
                    group.getFirst().owner(), group.getFirst().id(), parts.size(),
                    parts.values().stream().map(part -> part.getFirst().id() + " (" + part.size() + ")").toList());
        }
        return List.copyOf(parts.values());
    }

    /** The slot and its shared list of two or more entries, or null. */
    private static @Nullable List<Object> listKey(FluidInteractionRecipe member, boolean source) {
        Set<Set<Object>> lists = new HashSet<>();
        member.rowsOrSelf().forEach(row -> lists.add(Set.copyOf(entries(row, source))));
        Set<Object> list = lists.iterator().next();
        return lists.size() == 1 && list.size() > 1 ? List.of(source, list) : null;
    }

    /**
     * The group itself when its rows fit. Otherwise its members split by neighbor entries, then by source
     * fluids, in member order.
     */
    private static List<List<FluidInteractionRecipe>> bySize(List<FluidInteractionRecipe> group) {
        if (group.size() == 1 || rows(group).size() <= MAX_ROWS) {
            return List.of(group);
        }
        List<List<FluidInteractionRecipe>> parts = new ArrayList<>();
        for (List<FluidInteractionRecipe> byNeighbors : split(group, FluidInteractionRecipe::neighborEntries)) {
            if (byNeighbors.size() == 1 || rows(byNeighbors).size() <= MAX_ROWS) {
                parts.add(byNeighbors);
            } else {
                parts.addAll(split(byNeighbors, FluidInteractionRecipe::sourceFluids));
            }
        }
        return parts;
    }

    private static List<List<FluidInteractionRecipe>> split(List<FluidInteractionRecipe> group,
                                                            Function<FluidInteractionRecipe, List<?>> entries) {
        Map<Set<?>, List<FluidInteractionRecipe>> parts = new LinkedHashMap<>();
        group.forEach(member -> parts.computeIfAbsent(Set.copyOf(entries.apply(member)), key -> new ArrayList<>()).add(member));
        return List.copyOf(parts.values());
    }

    /**
     * The members' rows in member order, without exact repeats. A source or neighbor list that pairs with every
     * other entry of the rows becomes one free slot.
     */
    static List<FluidInteractionRecipe> rows(List<FluidInteractionRecipe> group) {
        Map<List<Object>, FluidInteractionRecipe> rows = new LinkedHashMap<>();
        for (FluidInteractionRecipe member : group.stream().flatMap(recipe -> recipe.rowsOrSelf().stream()).toList()) {
            Map<Object, List<Placement>> neighbors = new LinkedHashMap<>();
            member.neighbors().forEach(neighbor -> neighbors.computeIfAbsent(neighbor.slotEntry(), key -> new ArrayList<>()).add(neighbor));
            List<List<Placement>> entries = neighbors.isEmpty() ? List.of(List.of()) : List.copyOf(neighbors.values());
            for (Fluid fluid : member.sourceFluids()) {
                List<FluidState> sources = member.sources().stream().filter(state -> FluidInteractionRecipe.stillForm(state) == fluid).toList();
                for (List<Placement> neighbor : entries) {
                    FluidInteractionRecipe row = new FluidInteractionRecipe(member.sourceType(), member.id(), sources,
                            List.copyOf(neighbor), member.neighborOffset(), member.conditions(), member.results(), null,
                            member.owner(), member.inert());
                    rows.putIfAbsent(exact(row, true, true), row);
                }
            }
        }
        return free(free(List.copyOf(rows.values()), true), false);
    }

    /** Joins rows that differ only in the source, or only in the neighbor, when every such group holds every value. */
    private static List<FluidInteractionRecipe> free(List<FluidInteractionRecipe> rows, boolean source) {
        List<Object> all = rows.stream().flatMap(row -> entries(row, source).stream()).distinct().toList();
        if (all.size() < 2) {
            return rows;
        }
        Map<List<Object>, List<FluidInteractionRecipe>> groups = new LinkedHashMap<>();
        for (FluidInteractionRecipe row : rows) {
            groups.computeIfAbsent(exact(row, !source, source), k -> new ArrayList<>()).add(row);
        }
        for (List<FluidInteractionRecipe> joined : groups.values()) {
            Set<Object> held = new HashSet<>();
            joined.forEach(row -> held.addAll(entries(row, source)));
            if (held.size() != all.size()) {
                return rows;
            }
        }
        List<FluidInteractionRecipe> free = new ArrayList<>(groups.size());
        for (List<FluidInteractionRecipe> joined : groups.values()) {
            FluidInteractionRecipe first = joined.getFirst();
            List<FluidState> sources = source
                    ? joined.stream().flatMap(row -> row.sources().stream())
                            .sorted(Comparator.comparingInt(state -> all.indexOf(FluidInteractionRecipe.stillForm(state)))).toList()
                    : first.sources();
            List<Placement> neighbors = source
                    ? first.neighbors()
                    : joined.stream().flatMap(row -> row.neighbors().stream())
                            .sorted(Comparator.comparingInt(neighbor -> all.indexOf(neighbor.slotEntry()))).toList();
            free.add(new FluidInteractionRecipe(first.sourceType(), first.id(), sources, neighbors, first.neighborOffset(),
                    first.conditions(), first.results(), null, first.owner(), first.inert()));
        }
        return free;
    }

    private static List<Object> entries(FluidInteractionRecipe row, boolean source) {
        return source ? List.copyOf(row.sourceFluids()) : row.neighborEntries();
    }

    /** A row's placements and results, with the source or neighbor left out when not kept. */
    private static List<Object> exact(FluidInteractionRecipe row, boolean source, boolean neighbor) {
        return Arrays.asList(source ? row.sources() : null, neighbor ? row.neighbors() : null, row.conditions(), row.results());
    }

    /** A merge of several members into rows. The union of their sources and neighbors is the recipe's. */
    static FluidInteractionRecipe build(List<FluidInteractionRecipe> group, List<FluidInteractionRecipe> rows, ResourceLocation id,
                                        @Nullable String owner) {
        FluidInteractionRecipe first = group.getFirst();
        Set<FluidState> sources = new LinkedHashSet<>();
        Set<Placement> neighbors = new LinkedHashSet<>();
        Set<FluidState> inertSources = new LinkedHashSet<>();
        Set<Placement> inertNeighbors = new LinkedHashSet<>();
        for (FluidInteractionRecipe member : group) {
            sources.addAll(member.sources());
            neighbors.addAll(member.neighbors());
            inertSources.addAll(member.inert().sources());
            inertNeighbors.addAll(member.inert().neighbors());
        }
        inertSources.removeAll(sources);
        inertNeighbors.removeAll(neighbors);
        return new FluidInteractionRecipe(first.sourceType(), id, List.copyOf(sources), List.copyOf(neighbors),
                first.neighborOffset(), first.conditions(), first.results(), null, owner,
                new InertForms(List.copyOf(inertSources), List.copyOf(inertNeighbors)), rows);
    }

    /** A tag of at least two values that one slot's entries fill, or null. */
    private static @Nullable TagKey<?> filledTag(FluidInteractionRecipe first, List<FluidInteractionRecipe> rows) {
        List<Function<FluidInteractionRecipe, Object>> slots = new ArrayList<>();
        if (!FluidInteractionRecipe.sharesSources(rows)) {
            slots.add(row -> row.sourceFluids().getFirst());
        }
        if (!first.neighbors().isEmpty() && !FluidInteractionRecipe.sharesNeighbors(rows)) {
            slots.add(row -> row.neighbors().getFirst().slotEntry());
        }
        for (BlockPos offset : first.conditions().keySet()) {
            slots.add(row -> row.conditions().get(offset).slotEntry());
        }
        for (BlockPos offset : first.results().keySet()) {
            slots.add(row -> Placement.ofBlock(row.results().get(offset)).slotEntry());
        }
        for (Function<FluidInteractionRecipe, Object> slot : slots) {
            Set<Object> entries = new HashSet<>();
            rows.forEach(row -> entries.add(slot.apply(row)));
            TagKey<?> tag = filledTag(entries);
            if (tag != null) {
                return tag;
            }
        }
        return null;
    }

    /** EMI reads a slot as a tag only when every entry is an item, or every entry is a fluid. */
    private static @Nullable TagKey<?> filledTag(Set<Object> entries) {
        if (entries.size() < 2) {
            return null;
        }
        if (entries.stream().allMatch(Item.class::isInstance)) {
            return filledTag(BuiltInRegistries.ITEM, entries);
        }
        if (entries.stream().allMatch(Fluid.class::isInstance)) {
            return filledTag(BuiltInRegistries.FLUID, entries);
        }
        return null;
    }

    private static <T> @Nullable TagKey<T> filledTag(Registry<T> registry, Set<Object> entries) {
        return registry.getTags()
                .filter(pair -> fills(pair.getSecond(), entries))
                .map(pair -> pair.getFirst())
                .findFirst()
                .orElse(null);
    }

    private static <T> boolean fills(HolderSet.Named<T> tag, Set<Object> entries) {
        if (tag.size() < 2) {
            return false;
        }
        for (Holder<T> holder : tag) {
            if (!entries.contains(holder.value())) {
                return false;
            }
        }
        return true;
    }

    /** What the recipes of one merge share. Fluids and blocks can differ. */
    private record Shape(@Nullable String owner, String tier, BlockPos neighborOffset, Set<BlockPos> conditions,
                         Set<BlockPos> results, int forms, int neighborKinds) {
        static Shape of(FluidInteractionRecipe recipe) {
            return new Shape(recipe.owner(), tier(recipe.id()), recipe.neighborOffset(), Set.copyOf(recipe.conditions().keySet()),
                    Set.copyOf(recipe.results().keySet()), RecipeMerger.forms(recipe), RecipeMerger.neighborKinds(recipe));
        }

        private static String tier(ResourceLocation id) {
            String path = id.getPath();
            return path.startsWith("spread/") ? "spread" : path.startsWith("neighbor/") ? "neighbor" : "registry";
        }
    }
}
