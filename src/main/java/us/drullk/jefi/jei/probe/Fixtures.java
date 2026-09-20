package us.drullk.jefi.jei.probe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * What has to surround an arrangement for a level to hold it at all.
 *
 * <p>Two things: a still source feeding every flowing part, since a flow with nothing above or beside it drains
 * within a tick and the arrangement would be gone before any scheduled tick ran, and bedrock under everything
 * that would otherwise fall. A flow is fed from the side and away from the rest of the arrangement, the way the
 * recipe scenes draw one, because a source placed on top of a flow pours through the arrangement once the
 * interaction has consumed what was under it.
 *
 * <p>The settle step and the development grounding run build the same fixtures from here, so what the sandbox
 * settles and what a real level settles are the same arrangement. Fixtures never enter a recipe: they are the
 * conditions of the question, not part of the answer.
 */
public final class Fixtures {
    private static final BlockState FLOOR = Blocks.BEDROCK.defaultBlockState();

    /** The two horizontal axes a feed uses when the arrangement itself does not name one. */
    private static final BlockPos SIDE = new BlockPos(0, 0, 1);
    private static final BlockPos ACROSS = new BlockPos(1, 0, 0);
    private static final List<BlockPos> FEED_DIRECTIONS =
            List.of(SIDE, new BlockPos(0, 0, -1), ACROSS, new BlockPos(-1, 0, 0), new BlockPos(0, 1, 0));

    private Fixtures() {
    }

    /**
     * The fixtures one arrangement needs, keyed by offset from the source and holding no position the content
     * already occupies.
     *
     * @param content        the arrangement's own content, keyed by offset from the source at {@link BlockPos#ZERO}
     * @param neighborOffset where the recipe's neighbor sits, which is the direction a flow is fed away from
     */
    public static Map<BlockPos, Placement> around(Map<BlockPos, Placement> content, BlockPos neighborOffset) {
        Map<BlockPos, Placement> fixtures = new LinkedHashMap<>();
        content.forEach((offset, placement) -> feed(fixtures, content, offset, placement, away(offset, neighborOffset)));

        Map<BlockPos, Placement> standing = new LinkedHashMap<>(content);
        standing.putAll(fixtures);
        standing.keySet().forEach(offset -> {
            BlockPos below = offset.below();
            if (!standing.containsKey(below)) {
                fixtures.putIfAbsent(below, Placement.ofBlock(FLOOR));
            }
        });
        fixtures.keySet().removeAll(content.keySet());
        return fixtures;
    }

    /** Where a flow at this offset is fed from first: outwards along the row the recipe scene draws. */
    private static BlockPos away(BlockPos offset, BlockPos neighborOffset) {
        boolean horizontal = neighborOffset.getY() == 0 && !neighborOffset.equals(BlockPos.ZERO);
        if (offset.equals(BlockPos.ZERO)) {
            return horizontal ? BlockPos.ZERO.subtract(neighborOffset) : SIDE;
        }
        return offset.equals(neighborOffset) && horizontal ? neighborOffset : ACROSS;
    }

    private static void feed(Map<BlockPos, Placement> fixtures, Map<BlockPos, Placement> content,
                             BlockPos offset, Placement placement, BlockPos preferred) {
        if (!placement.isFlowing()) {
            return;
        }
        FluidState feed = FluidInteractionRecipe.stillForm(placement.effectiveFluid()).defaultFluidState();
        if (!feed.isSource() || !FluidBlocks.hasBlock(feed.getType())) {
            return;
        }
        for (BlockPos direction : Stream.concat(Stream.of(preferred), FEED_DIRECTIONS.stream()).distinct().toList()) {
            BlockPos at = offset.offset(direction);
            if (!content.containsKey(at) && !fixtures.containsKey(at)) {
                fixtures.put(at, Placement.ofFluid(feed));
                return;
            }
        }
    }
}
