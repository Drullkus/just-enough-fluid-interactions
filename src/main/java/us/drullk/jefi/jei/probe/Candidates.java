package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * What the tiers put beside a source: every fluid a level can hold, and the default state of every block.
 *
 * <p>A fluid without a block of its own is in no list ({@link FluidBlocks#hasBlock}). A liquid block is in no
 * block list. A block that holds a fluid state is in no block list. The fluids are the candidates for those
 * positions.
 */
final class Candidates {
    /** Every fluid in still form, then in a full flowing form when it has one. */
    final List<Placement> fluids;
    private final Map<FluidType, List<Placement>> fluidsByType = new HashMap<>();
    /** The default state of every block that holds no fluid. */
    final List<Placement> blocks;
    /** The still form of every fluid, then the blocks. The multi-position search of the registry tier tries these. */
    final List<Placement> stillFluidsAndBlocks;
    /** The states a tier probes as the source, per type: each still fluid with a block, then its full flowing form. */
    private final Map<FluidType, List<FluidState>> sourcesByType = new HashMap<>();

    Candidates() {
        List<Placement> fluids = new ArrayList<>();
        List<Placement> stillFluids = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            FluidState still = fluid.defaultFluidState();
            if (fluid == Fluids.EMPTY || !still.isSource() || !FluidBlocks.hasBlock(fluid)) {
                continue;
            }
            Placement source = Placement.ofFluid(still);
            stillFluids.add(source);
            fluids.add(source);
            List<FluidState> sources = sourcesByType.computeIfAbsent(fluid.getFluidType(), key -> new ArrayList<>());
            sources.add(still);
            FluidState flow = flowingForm(fluid);
            if (flow != null) {
                fluids.add(Placement.ofFluid(flow));
                sources.add(flow);
            }
        }
        List<Placement> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!state.isAir() && !(block instanceof LiquidBlock) && state.getFluidState().isEmpty()) {
                blocks.add(Placement.ofBlock(state));
            }
        }
        List<Placement> search = new ArrayList<>(stillFluids.size() + blocks.size());
        search.addAll(stillFluids);
        search.addAll(blocks);
        this.fluids = List.copyOf(fluids);
        for (Placement placement : this.fluids) {
            fluidsByType.computeIfAbsent(placement.effectiveFluid().getFluidType(), key -> new ArrayList<>()).add(placement);
        }
        this.blocks = List.copyOf(blocks);
        this.stillFluidsAndBlocks = List.copyOf(search);
    }

    /** Both forms of every fluid of one type, in the order of {@link #fluids}. Empty for a type without a block. */
    List<Placement> fluidsOf(FluidType type) {
        return fluidsByType.getOrDefault(type, List.of());
    }

    /**
     * The states of one type a tier probes as the source. The list holds each still fluid with a block. It also
     * holds the full flowing form of that fluid when it has one. Empty for a type without a block.
     */
    List<FluidState> sourceStates(FluidType type) {
        return sourcesByType.getOrDefault(type, List.of());
    }

    /**
     * A full flowing state of a still fluid, or null when it has none. Some modded fluids register a flowing
     * fluid without the level properties. So only {@code trySetValue} narrows the state.
     */
    static @Nullable FluidState flowingForm(Fluid fluid) {
        if (!(fluid instanceof FlowingFluid flowing)) {
            return null;
        }
        FluidState flow = flowing.getFlowing().defaultFluidState()
                .trySetValue(FlowingFluid.LEVEL, 7)
                .trySetValue(FlowingFluid.FALLING, false);
        return !flow.isEmpty() && !flow.isSource() ? flow : null;
    }
}
