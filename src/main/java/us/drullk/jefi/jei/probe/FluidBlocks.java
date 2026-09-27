package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

/** Only a fluid whose own block holds it can exist in a level. */
public final class FluidBlocks {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The cached answer per fluid type, fixed once the registry freezes. */
    private static final Map<FluidType, Boolean> BY_TYPE = new ConcurrentHashMap<>();

    static {
        logMismatchedBlocks();
    }

    private FluidBlocks() {
    }

    /** True only when the fluid's legacy block reports back that same fluid. */
    public static boolean hasBlock(Fluid fluid) {
        BlockState block = fluid.defaultFluidState().createLegacyBlock();
        if (block.isAir()) {
            return false;
        }
        return block.getFluidState().getType().isSame(fluid);
    }

    /** True when any fluid of the type has its own block. */
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

    /** Reports the fluids a non-air legacy block disqualifies for holding a different fluid. */
    private static void logMismatchedBlocks() {
        List<String> mismatched = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid == Fluids.EMPTY) {
                continue;
            }
            if (!fluid.defaultFluidState().createLegacyBlock().isAir() && !hasBlock(fluid)) {
                mismatched.add(BuiltInRegistries.FLUID.getKey(fluid).toString());
            }
        }
        LOGGER.debug("Skipped {} fluid(s) whose block holds a different fluid: {}", mismatched.size(), mismatched);
    }
}
