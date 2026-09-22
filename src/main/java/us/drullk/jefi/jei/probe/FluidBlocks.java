package us.drullk.jefi.jei.probe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Whether a level can hold a fluid at all.
 *
 * <p>A fluid registered without a block of its own exists only in a bucket or a tank. No level can ever come
 * into a state that holds it. So no rule keyed on it runs anywhere, and no tier can probe it. Every tier asks
 * this before it offers a fluid as a source or as a candidate.
 */
public final class FluidBlocks {
    /** The answer per type. The fluid registry is frozen before any probe runs, so an answer never changes. */
    private static final Map<FluidType, Boolean> BY_TYPE = new ConcurrentHashMap<>();

    private FluidBlocks() {
    }

    /** Whether the fluid has a block of its own, which is the block its default state turns into. */
    public static boolean hasBlock(Fluid fluid) {
        return !fluid.defaultFluidState().createLegacyBlock().isAir();
    }

    /** Whether any fluid of the type has a block. Only such a block lets the type stand anywhere at all. */
    public static boolean hasBlock(FluidType type) {
        return BY_TYPE.computeIfAbsent(type, FluidBlocks::scan);
    }

    private static boolean scan(FluidType type) {
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid != Fluids.EMPTY && fluid.getFluidType() == type && hasBlock(fluid)) {
                return true;
            }
        }
        return false;
    }
}
