package us.drullk.jefi.jei.probe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Whether a fluid is something a level can hold at all.
 *
 * <p>A fluid registered without a block of its own exists only in a bucket or a tank: no level can ever be put
 * into a state that holds it, so no rule keyed on it can be exercised anywhere and nothing about it can be
 * probed. Every tier asks this before it offers a fluid as a source or as a candidate.
 */
public final class FluidBlocks {

    private FluidBlocks() {
    }

    /** Whether the fluid has a block of its own, which is the block its default state turns into. */
    public static boolean hasBlock(Fluid fluid) {
        return !fluid.defaultFluidState().createLegacyBlock().isAir();
    }

    /** Whether any fluid of the type has a block, which is what lets the type stand anywhere at all. */
    public static boolean hasBlock(FluidType type) {
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid != Fluids.EMPTY && fluid.getFluidType() == type && hasBlock(fluid)) {
                return true;
            }
        }
        return false;
    }
}
