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
 * Approximate attribution of a registered fluid interaction to the mod that registered it.
 *
 * <p>The registry keeps no owner. A registered predicate and action pair gives two kinds of evidence:
 * <ul>
 *     <li>the class that declares the lambda. Its module or package identifies a mod file. FML offers no
 *     class-to-mod lookup, so {@link ModList#getModFiles()} supplies the mapping;</li>
 *     <li>the registry objects a lambda captures. {@code InteractionInformation}'s convenience constructors
 *     build their lambdas inside NeoForge's own class. Class evidence for those points at NeoForge, whatever
 *     mod calls them. The captured result block or fluid type still carries the caller's namespace.</li>
 * </ul>
 * Class evidence that names a third-party mod wins. Then comes a captured namespace, then the namespace of the
 * source fluid type, and last the mod of the declaring class.
 */
public final class InteractionOwners {
    private static final String MINECRAFT = "minecraft";
    private static final String NEOFORGE = "neoforge";
    /** Captured lambdas nest a few levels deep. Deeper reflection costs time and finds nothing. */
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

    /** The mod credited with the own behavior of a fluid, for a rule in the fluid's class. */
    public static @Nullable String ofFluid(Fluid fluid) {
        return ofRegistered(fluid.getClass(), BuiltInRegistries.FLUID.getKey(fluid));
    }

    /** The mod credited with the own behavior of a block, for a rule in the update hooks of a liquid block. */
    public static @Nullable String ofBlock(Block block) {
        return ofRegistered(block.getClass(), BuiltInRegistries.BLOCK.getKey(block));
    }

    /** The class evidence a lambda gives, then the registry namespace. A third-party mod wins over both. */
    private static @Nullable String ofRegistered(Class<?> clazz, @Nullable ResourceLocation key) {
        String byClass = modOf(clazz);
        if (byClass != null && isThirdParty(byClass)) {
            return byClass;
        }
        String namespace = namespaceOfKey(key);
        if (namespace != null && isThirdParty(namespace)) {
            return namespace;
        }
        return byClass != null ? byClass : namespace;
    }

    public static String describe(@Nullable String owner) {
        return owner != null ? owner : "unknown";
    }

    private static boolean isThirdParty(String id) {
        return !MINECRAFT.equals(id) && !NEOFORGE.equals(id) && ModList.get().isLoaded(id);
    }

    /** The mod that owns a class, found by module name and then by package. The fallback is the raw module name. */
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

    /** The mod ids from a predicate and action pair, in the order the scan finds them. */
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
                    // A capture the scan cannot read gives no evidence.
                }
            }
        }
    }

    /** The first use builds the index, after the mod list exists. */
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
                    // The module name alone matches a mod file with no readable descriptor.
                }
            }
        }
    }
}
