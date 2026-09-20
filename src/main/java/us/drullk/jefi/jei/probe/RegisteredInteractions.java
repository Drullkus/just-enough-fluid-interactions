package us.drullk.jefi.jei.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Read access to {@link FluidInteractionRegistry}'s private interaction map.
 *
 * <p>The mod ships an access transformer. The transformer makes the field public at runtime, in development and
 * in production. The source still cannot name the field directly. ModDevGradle applies access transformers while
 * it recompiles Minecraft's sources. It then merges NeoForge's own classes in without a change. So the compiler
 * still sees the field as private. A reflective lookup bridges that gap. It prefers the transformed public
 * field. It falls back to {@code setAccessible} only when the transformer did not apply.
 */
public final class RegisteredInteractions {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final @Nullable Map<FluidType, List<InteractionInformation>> INTERACTIONS = lookup();

    private RegisteredInteractions() {
    }

    /** The live registry map, or an empty map when the lookup failed. */
    public static Map<FluidType, List<InteractionInformation>> get() {
        return INTERACTIONS != null ? INTERACTIONS : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<FluidType, List<InteractionInformation>> lookup() {
        try {
            Field field = FluidInteractionRegistry.class.getDeclaredField("INTERACTIONS");
            if (!Modifier.isPublic(field.getModifiers())) {
                LOGGER.warn("The access transformer for FluidInteractionRegistry.INTERACTIONS was not applied; falling back to reflective access");
                field.setAccessible(true);
            }
            return (Map<FluidType, List<InteractionInformation>>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Cannot read FluidInteractionRegistry.INTERACTIONS; no fluid interactions will be shown in JEI", e);
            return null;
        }
    }
}
