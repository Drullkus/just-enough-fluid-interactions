package us.drullk.jefi.jei.probe;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * The neighbor tier: the hardening rules a fluid implements in the update hooks of its liquid block.
 *
 * <p>This is the channel a level runs first. A level that places a block calls {@code onPlace} on it. It calls
 * {@code neighborChanged} on the blocks around it. Both occur on the tick of the placement. {@code LiquidBlock}
 * runs the interaction registry from there. A rule in a {@code LiquidBlock} subclass therefore runs before the
 * scheduled spread tick of any fluid. The quiet sandbox delivers no block updates, so the tier calls the hooks
 * directly.
 *
 * <p>The tier probes only a block that declares {@code neighborChanged}, {@code onPlace} or {@code updateShape}
 * below {@link LiquidBlock}. {@code LiquidBlock} itself runs the interaction registry in those hooks, which is
 * the registry tier. A rule outside the block's own classes leaves no trace in its class chain.
 * {@code forceProbe} is for such a rule.
 *
 * <p>The candidate sits below, beside or above the source. These hooks obey no convention about the direction
 * they look in, so the tier probes all three. The candidates are the fluids, in both forms. The tier never calls
 * the hooks of the candidate. A plain {@code LiquidBlock} runs the registry. The tier probes every other block
 * as a source in its own turn.
 */
public final class NeighborProber extends RuleProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<BlockPos> TARGETS = List.of(BELOW_OFFSET, FluidInteractionRecipe.NEIGHBOR_OFFSET, ABOVE_OFFSET);

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** The hooks a level calls on the tick a neighbor is placed. */
    private static final Set<String> UPDATE_METHODS = Set.of("neighborChanged", "onPlace", "updateShape");

    /**
     * Where the class walk stops. {@link LiquidBlock} is the channel of the interaction registry.
     * {@code Block} is the inert default. A declaration at or above either is not a rule of the fluid's own.
     */
    private static final Set<String> BASE_BLOCKS = Set.of(
            "net.minecraft.world.level.block.LiquidBlock",
            "net.minecraft.world.level.block.Block",
            "net.minecraft.world.level.block.state.BlockBehaviour");

    public NeighborProber(SandboxLevel level, Settler settler, Candidates candidates) {
        super(LOGGER, level, settler, candidates.fluids, "fluid neighbors", "the update hooks of", "neighbor/");
    }

    @Override
    List<BlockPos> targets() {
        return TARGETS;
    }

    @Override
    boolean declaresHook(FluidState state) {
        Class<?> block = state.createLegacyBlock().getBlock().getClass();
        return !declared(block, UPDATE_METHODS, type -> BASE_BLOCKS.contains(type.getName())).isEmpty();
    }

    @Override
    List<Placement> candidates(FluidState source, List<FluidState> probed) {
        return fluidCandidates();
    }

    @Override
    @Nullable String owner(FluidState source) {
        return InteractionOwners.ofBlock(source.createLegacyBlock().getBlock());
    }

    /**
     * Tells the source that its neighbor changed, then that a level placed it. {@code LiquidBlock} routes both
     * hooks to the same rule.
     */
    @Override
    void callHook(SandboxLevel level, FluidState source, BlockPos target, Placement candidate) {
        BlockState sourceState = source.createLegacyBlock();
        BlockPos candidatePos = SandboxLevel.ORIGIN.offset(target);
        sourceState.handleNeighborChanged(level, SandboxLevel.ORIGIN, candidate.block().getBlock(), candidatePos, false);
        sourceState.onPlace(level, SandboxLevel.ORIGIN, AIR, false);
    }

    /** A hook that rewrites the source or the candidate fluid changes nothing a recipe shows. */
    @Override
    Set<Fluid> ownFluids(FluidState source, Placement candidate) {
        Set<Fluid> own = new LinkedHashSet<>();
        own.add(FluidInteractionRecipe.stillForm(source));
        if (candidate.isFluid()) {
            own.add(FluidInteractionRecipe.stillForm(candidate.effectiveFluid()));
        }
        return own;
    }

    @Override
    BlockPos primaryResultOffset(BlockPos target) {
        return BlockPos.ZERO;
    }
}
