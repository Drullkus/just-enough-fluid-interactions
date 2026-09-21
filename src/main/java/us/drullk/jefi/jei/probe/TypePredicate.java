package us.drullk.jefi.jei.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.jetbrains.annotations.Nullable;

import net.neoforged.neoforge.fluids.FluidInteractionRegistry.HasFluidInteraction;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * The fluid type a registered predicate tests for, when the predicate is NeoForge's own. The constructors of
 * {@link InteractionInformation} that take a {@link FluidType} build one predicate: the fluid type at the
 * neighbor position equals the captured type. Only a fluid of that type passes it. So the registry tier sweeps
 * that type's fluids first, and every candidate only when none of them passes. A predicate a mod wrote itself
 * is any code, and the tier sweeps every candidate for it.
 */
final class TypePredicate {
    private TypePredicate() {
    }

    /**
     * The captured type of NeoForge's own predicate, or null for any other predicate. A lambda is a nestmate of
     * the class that declares it, and a nested record shares its outer class's nest, so the test is against the
     * registry's nest.
     */
    static @Nullable FluidType typeOf(HasFluidInteraction predicate) {
        Class<?> clazz = predicate.getClass();
        if (clazz.getNestHost() != InteractionInformation.class.getNestHost()) {
            return null;
        }
        FluidType found = null;
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (found != null || field.getType() != FluidType.class || !field.trySetAccessible()) {
                return null;
            }
            try {
                found = (FluidType) field.get(predicate);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return found;
    }
}
