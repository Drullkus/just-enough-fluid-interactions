package us.drullk.jefi.devtest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.Fixtures;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * This class builds one probed recipe alternative in a real level. It compares the result with what the probe
 * claimed.
 *
 * <p>The probe settles every arrangement in a sandbox level that mirrors a real one. This class builds the same
 * arrangement in a {@link ServerLevel} and lets the real game run it. That shows whether the mirror is faithful.
 * A mismatch means the sandbox and a level disagree. Finding mismatches is the reason this run exists.
 *
 * <p>The arrangement holds the recipe's own content: the source state, one neighbor alternative and the
 * recipe's conditions. It also holds the {@link Fixtures} a level needs for that arrangement to exist at all.
 * The same code builds these fixtures for the sandbox, so both sides face the same question.
 */
final class GroundCheck {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private GroundCheck() {
    }

    /** One source state of a recipe against one of its neighbor alternatives, or against nothing when it has none. */
    record Alternative(FluidInteractionRecipe recipe, FluidState source, @Nullable Placement neighbor) {
        String describe() {
            return Texts.form(source).getString() + ", " + (neighbor != null ? neighbor.describe().getString() : "no neighbor");
        }
    }

    /** Where the level and the probe disagree: an offset from the source and the two states at it. */
    record Mismatch(BlockPos offset, BlockState expected, BlockState found) {
    }

    /** Every alternative of every recipe a probe claims to have exercised, in recipe order. */
    static List<Alternative> alternatives(List<FluidInteractionRecipe> recipes) {
        List<Alternative> alternatives = new ArrayList<>();
        for (FluidInteractionRecipe recipe : recipes) {
            if (recipe.isFailure() || recipe.results().isEmpty()) {
                continue;
            }
            for (FluidState source : recipe.sources()) {
                if (recipe.neighbors().isEmpty()) {
                    alternatives.add(new Alternative(recipe, source, null));
                    continue;
                }
                for (Placement neighbor : recipe.neighbors()) {
                    alternatives.add(new Alternative(recipe, source, neighbor));
                }
            }
        }
        return alternatives;
    }

    /**
     * Whether every part of this arrangement is something a level can hold. A fluid registered without a liquid
     * block of its own exists only in a bucket. So no level can ever hold the state the probe recorded.
     */
    static boolean placeable(Alternative alternative) {
        if (Placement.ofFluid(alternative.source()).block().isAir()) {
            return false;
        }
        Placement neighbor = alternative.neighbor();
        return neighbor == null || !neighbor.block().isAir();
    }

    /** The recipe's own content, keyed by offset from the source: source state, neighbor and conditions. */
    static Map<BlockPos, Placement> content(Alternative alternative) {
        Map<BlockPos, Placement> content = new LinkedHashMap<>();
        content.put(BlockPos.ZERO, Placement.ofFluid(alternative.source()));
        if (alternative.neighbor() != null) {
            content.put(alternative.recipe().neighborOffset(), alternative.neighbor());
        }
        alternative.recipe().conditions().forEach(content::putIfAbsent);
        return content;
    }

    /**
     * Places one arrangement with its fixtures. The position named last goes last. For a probed alternative,
     * that position is the source. A level runs the hooks of the block it places before it notifies that
     * block's neighbors. So the fluid poured last is the one whose own rule runs first. That fluid is the one
     * every tier probes as the source.
     */
    static void place(ServerLevel level, BlockPos origin, Map<BlockPos, Placement> content, BlockPos neighborOffset, BlockPos last) {
        Fixtures.around(content, neighborOffset).forEach((offset, placement) -> set(level, origin.offset(offset), placement.block()));
        content.forEach((offset, placement) -> {
            if (!offset.equals(last)) {
                set(level, origin.offset(offset), placement.block());
            }
        });
        Placement lastPlacement = content.get(last);
        if (lastPlacement != null) {
            set(level, origin.offset(last), lastPlacement.block());
        }
    }

    /**
     * Where the settled level disagrees with the recipe. Every result offset must hold the result block. Every
     * other content position is a catalyst the recipe leaves alone, so it must still hold what was placed. The
     * exception: a placement the probe recorded as flowing can settle at any level of the same fluid.
     */
    static List<Mismatch> verify(ServerLevel level, BlockPos origin, Alternative alternative) {
        List<Mismatch> mismatches = new ArrayList<>();
        Map<BlockPos, BlockState> results = alternative.recipe().results();
        results.forEach((offset, expected) -> {
            BlockState found = level.getBlockState(origin.offset(offset));
            if (!found.equals(expected)) {
                mismatches.add(new Mismatch(offset, expected, found));
            }
        });
        content(alternative).forEach((offset, placement) -> {
            if (results.containsKey(offset)) {
                return;
            }
            BlockState found = level.getBlockState(origin.offset(offset));
            if (!survived(placement, found)) {
                mismatches.add(new Mismatch(offset, placement.block(), found));
            }
        });
        return mismatches;
    }

    private static boolean survived(Placement placement, BlockState found) {
        FluidState placed = placement.effectiveFluid();
        if (placed.isEmpty()) {
            return found.getBlock() == placement.block().getBlock();
        }
        FluidState there = found.getFluidState();
        if (there.isEmpty() || FluidInteractionRecipe.stillForm(there) != FluidInteractionRecipe.stillForm(placed)) {
            return false;
        }
        return !placed.isSource() || there.isSource();
    }

    /** The states the level holds at the given offsets, as one line, for the arrangements worth reporting whole. */
    static String describe(ServerLevel level, BlockPos origin, Collection<BlockPos> offsets) {
        List<String> parts = new ArrayList<>(offsets.size());
        for (BlockPos offset : offsets) {
            parts.add(offset.toShortString() + "=" + level.getBlockState(origin.offset(offset)));
        }
        return String.join(", ", parts);
    }

    /** Empties one cell so the fluids of the batch before it cannot reach the arrangement placed in it. */
    static void clear(ServerLevel level, BlockPos origin, int radius, int below, int above) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -below; y <= above; y++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, AIR, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }
}
