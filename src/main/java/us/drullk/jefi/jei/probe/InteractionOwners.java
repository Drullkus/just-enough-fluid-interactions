package us.drullk.jefi.jei.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Best-effort attribution of a registered fluid interaction to the mod that registered it.
 *
 * <p>The registry keeps no owner. Two kinds of evidence are available from a registered
 * predicate/action pair:
 * <ul>
 *     <li>the class declaring the lambda, whose module or package identifies a mod file. FML offers no
 *     class-to-mod lookup, so the mapping is built from {@link ModList#getModFiles()};</li>
 *     <li>the registry objects a lambda captured. {@code InteractionInformation}'s convenience constructors
 *     build their lambdas inside NeoForge's own class, so class evidence for those points at NeoForge no
 *     matter who called them; the captured result block or fluid type still carries the caller's namespace.</li>
 * </ul>
 * Class evidence naming a third-party mod wins, then a captured namespace, then the source fluid type's
 * namespace, and only then the declaring class's own mod.
 */
public final class InteractionOwners {
    private static final String MINECRAFT = "minecraft";
    private static final String NEOFORGE = "neoforge";
    /** Captured lambdas nest a few levels deep; deeper reflection buys nothing but time. */
    private static final int MAX_CAPTURE_DEPTH = 4;

    private InteractionOwners() {
    }

    /** The mod id credited with an interaction, or {@code null} when nothing identifies one. */
    public static @Nullable String of(FluidType sourceType, InteractionInformation interaction) {
        Evidence evidence = new Evidence();
        evidence.scan(interaction.predicate(), 0);
        evidence.scan(interaction.interaction(), 0);

        for (String id : evidence.classes) {
            if (isThirdParty(id)) {
                return id;
            }
        }
        for (String namespace : evidence.namespaces) {
            if (isThirdParty(namespace)) {
                return namespace;
            }
        }
        String sourceNamespace = namespaceOfKey(NeoForgeRegistries.FLUID_TYPES.getKey(sourceType));
        if (sourceNamespace != null && isThirdParty(sourceNamespace)) {
            return sourceNamespace;
        }
        return evidence.classes.isEmpty() ? null : evidence.classes.iterator().next();
    }

    public static String describe(@Nullable String owner) {
        return owner != null ? owner : "unknown";
    }

    private static boolean isThirdParty(String id) {
        return !MINECRAFT.equals(id) && !NEOFORGE.equals(id) && ModList.get().isLoaded(id);
    }

    /** The mod owning a class, by module name and then by package; falls back to the raw module name. */
    static @Nullable String modOf(Class<?> clazz) {
        Class<?> host = clazz.getNestHost();
        Module module = host.getModule();
        String moduleName = module != null ? module.getName() : null;
        if (moduleName != null) {
            String byModule = Index.BY_MODULE.get(moduleName);
            if (byModule != null) {
                return byModule;
            }
        }
        String byPackage = Index.BY_PACKAGE.get(host.getPackageName());
        return byPackage != null ? byPackage : moduleName;
    }

    private static @Nullable String namespaceOf(Object value) {
        return switch (value) {
            case BlockState state -> namespaceOfKey(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            case Block block -> namespaceOfKey(BuiltInRegistries.BLOCK.getKey(block));
            case FluidState state -> namespaceOfKey(BuiltInRegistries.FLUID.getKey(state.getType()));
            case Fluid fluid -> namespaceOfKey(BuiltInRegistries.FLUID.getKey(fluid));
            case FluidType type -> namespaceOfKey(NeoForgeRegistries.FLUID_TYPES.getKey(type));
            case ItemStack stack -> namespaceOfKey(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            case Item item -> namespaceOfKey(BuiltInRegistries.ITEM.getKey(item));
            case Holder<?> holder -> holder.unwrapKey().map(key -> key.location().getNamespace()).orElse(null);
            default -> null;
        };
    }

    private static @Nullable String namespaceOfKey(@Nullable ResourceLocation key) {
        return key != null ? key.getNamespace() : null;
    }

    /** Mod ids gathered from a predicate/action pair, in the order they were found. */
    private static final class Evidence {
        final Set<String> classes = new LinkedHashSet<>();
        final Set<String> namespaces = new LinkedHashSet<>();
        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        void scan(@Nullable Object value, int depth) {
            if (value == null || depth > MAX_CAPTURE_DEPTH || !visited.add(value)) {
                return;
            }
            String namespace = namespaceOf(value);
            if (namespace != null) {
                namespaces.add(namespace);
                return;
            }
            Class<?> clazz = value.getClass();
            String mod = modOf(clazz);
            if (mod != null) {
                classes.add(mod);
            }
            if (!clazz.isSynthetic()) {
                return;
            }
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                    continue;
                }
                try {
                    scan(field.get(value), depth + 1);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // A capture that cannot be read simply yields no evidence.
                }
            }
        }
    }

    /** Built on first use, once the mod list exists. */
    private static final class Index {
        static final Map<String, String> BY_MODULE = new HashMap<>();
        static final Map<String, String> BY_PACKAGE = new HashMap<>();

        static {
            for (IModFileInfo info : ModList.get().getModFiles()) {
                List<IModInfo> mods = info.getMods();
                if (mods.isEmpty()) {
                    continue;
                }
                String id = mods.getFirst().getModId();
                String moduleName = info.moduleName();
                if (moduleName != null) {
                    BY_MODULE.putIfAbsent(moduleName, id);
                }
                try {
                    for (String pkg : info.getFile().getSecureJar().moduleDataProvider().descriptor().packages()) {
                        BY_PACKAGE.putIfAbsent(pkg, id);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // Mod files without a readable descriptor are matched by module name alone.
                }
            }
        }
    }
}
