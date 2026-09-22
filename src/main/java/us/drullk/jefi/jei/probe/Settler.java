package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Finds what a level does with one arrangement: builds it in the live sandbox and lets it come to rest.
 *
 * <p>A probe tier calls one rule by hand and asks whether that rule writes anything. That says the arrangement
 * is worth a look and which rule owns it. What the arrangement produces is never only the writes of that rule.
 * A level runs every channel in order. First comes the {@code onPlace} of the placement. Then come the neighbor
 * updates it sends. Then comes the interaction registry the updated liquid blocks run. Last come the scheduled
 * ticks of the fluids. Everything each of them sets off also runs. The settle step runs all of them, so the
 * simulation gives the order of the channels.
 *
 * <p>An arrangement answers for the rule that proposed it and for no other rule. That holds only while the
 * settled level agrees with what the rule wrote by hand. At every position the rule wrote, the settled state is
 * the same block state. Where the rule wrote a fluid, the same still fluid at any level is also an agreement.
 * Anything different there is the outcome of a different channel. That outcome belongs to the recipe of that
 * channel, so the tier that proposed the arrangement drops it. What the level adds beyond those positions is
 * part of the answer. An example is a result block that changes a neighbor as a level places it.
 *
 * <p>The settle step places the arrangement the way {@code GroundCheck} places it in a real level:
 * {@link Fixtures} first, then the content, the source last. A level runs the hooks of the placed block before
 * it notifies the neighbors. The fluid placed last is the one every tier probes as its source.
 *
 * <p>The answer is the diff over the content positions and the positions the rule wrote in. Fixtures and the
 * positions the fluids flowed to are not part of the question. A position is a result only when its final state
 * is not air. The state must also be different from what the arrangement placed there. It must also not be a
 * state of a fluid the arrangement itself poured.
 */
public final class Settler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How far the virtual game time can run past the placement. Vanilla lava's tick delay is 30 in the
     * overworld. The slowest fluid on the development classpath has 50. This leaves room for a rule that waits
     * for a scheduled tick, and for what that tick sets off.
     */
    public static final int SETTLE_TICKS = 80;

    /** A ceiling on the cost of one arrangement: a fluid that pours off the bedrock reschedules itself without end. */
    private static final int MAX_TICK_RUNS = 20_000;

    /** Experiment knob: how far past the arrangement a fluid can pour sideways and up. Negative: no walls. */
    private static final int MARGIN = Integer.getInteger("jefi.settleMargin", -1);

    /** Orders results so that two arrangements with the same results produce the same recipe. */
    private static final Comparator<BlockPos> OFFSET_ORDER = Comparator
            .<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private final SandboxLevel level;
    private int settled;
    private long nanos;

    public Settler(SandboxLevel level) {
        this.level = level;
    }

    /**
     * What the arrangement leaves behind, keyed by offset from the source. It can instead say why the
     * arrangement tells nothing about the rule that proposed it.
     *
     * @param content        the arrangement, keyed by offset from the source. The source sits at
     *                       {@link BlockPos#ZERO} and goes in last.
     * @param neighborOffset where the neighbor of the recipe sits. This decides how a fixture feeds a flow.
     * @param wrote          what the rule itself wrote at the hand call, keyed by the same offsets. The tier
     *                       filters it the way it filters its own writes.
     */
    public Outcome settle(Map<BlockPos, Placement> content, BlockPos neighborOffset, Map<BlockPos, BlockState> wrote) {
        long start = System.nanoTime();
        Rest rest = run(content, neighborOffset, wrote.keySet());
        nanos += System.nanoTime() - start;
        settled++;
        if (!rest.completed()) {
            return new Outcome(Map.of(), null);
        }
        Preemption preempted = preempted(wrote);
        if (preempted != null) {
            return new Outcome(Map.of(), preempted);
        }
        return new Outcome(diff(rest, content, wrote.keySet()), null);
    }

    /**
     * What one settled arrangement says. It gives results, which can be empty. Or a level pre-empted the rule
     * that proposed it, and {@link #preempted()} says what stands where that rule wrote.
     */
    public record Outcome(Map<BlockPos, BlockState> results, @Nullable Preemption preempted) {
    }

    /**
     * What a level holds where a rule wrote. The first difference is the observation a recipe states.
     * {@link #describe()} names every difference for the log.
     *
     * @param offset   the first position, keyed from the source, where the settled level differs.
     * @param found    what the settled level holds there.
     * @param wrote    what the rule itself wrote there.
     * @param describe every difference of the arrangement, as one line.
     */
    public record Preemption(BlockPos offset, BlockState found, BlockState wrote, String describe) {
        @Override
        public String toString() {
            return describe;
        }
    }

    public int settledCount() {
        return settled;
    }

    public long millis() {
        return nanos / 1_000_000;
    }

    /**
     * Builds the arrangement in the live sandbox and runs its fluid ticks. The level then holds the answer.
     * The level's floor sits at the lowest position the arrangement holds or the rule wrote. A fluid that
     * pours off the arrangement stops on that floor instead of flooding the level below, which the answer
     * never reads and which cannot reach the arrangement back.
     */
    private Rest run(Map<BlockPos, Placement> content, BlockPos neighborOffset, Set<BlockPos> wrote) {
        Map<BlockPos, Placement> fixtures = Fixtures.around(content, neighborOffset);
        Map<BlockPos, Placement> placed = new LinkedHashMap<>(fixtures);
        placed.putAll(content);
        int lowest = Stream.concat(placed.keySet().stream(), wrote.stream()).mapToInt(BlockPos::getY).min().orElse(0);
        level.reset();
        level.floor(SandboxLevel.ORIGIN.getY() + lowest);
        if (MARGIN >= 0) {
            List<BlockPos> box = Stream.concat(placed.keySet().stream(), wrote.stream()).toList();
            BlockPos min = new BlockPos(box.stream().mapToInt(BlockPos::getX).min().orElse(0) - MARGIN, 0,
                    box.stream().mapToInt(BlockPos::getZ).min().orElse(0) - MARGIN);
            BlockPos max = new BlockPos(box.stream().mapToInt(BlockPos::getX).max().orElse(0) + MARGIN,
                    box.stream().mapToInt(BlockPos::getY).max().orElse(0) + MARGIN,
                    box.stream().mapToInt(BlockPos::getZ).max().orElse(0) + MARGIN);
            level.walls(SandboxLevel.ORIGIN.offset(min), SandboxLevel.ORIGIN.offset(max));
        }
        level.setLive(true);
        try {
            fixtures.forEach((offset, placement) -> level.placeLive(SandboxLevel.ORIGIN.offset(offset), placement));
            content.forEach((offset, placement) -> {
                if (!offset.equals(BlockPos.ZERO)) {
                    level.placeLive(SandboxLevel.ORIGIN.offset(offset), placement);
                }
            });
            Placement source = content.get(BlockPos.ZERO);
            if (source != null) {
                level.placeLive(SandboxLevel.ORIGIN, source);
            }
            level.settle(SETTLE_TICKS, MAX_TICK_RUNS);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("Settling {} threw", describe(content), e);
            return new Rest(placed, Set.of(), false);
        } finally {
            level.setLive(false);
        }
        return new Rest(placed, poured(placed), true);
    }

    private BlockState stateAt(BlockPos offset) {
        return level.getBlockState(SandboxLevel.ORIGIN.offset(offset));
    }

    private Map<BlockPos, BlockState> diff(Rest rest, Map<BlockPos, Placement> content, Set<BlockPos> wrote) {
        Set<BlockPos> domain = new LinkedHashSet<>(content.keySet());
        domain.addAll(wrote);
        Map<BlockPos, BlockState> results = new TreeMap<>(OFFSET_ORDER);
        for (BlockPos offset : domain) {
            BlockState found = stateAt(offset);
            Placement placement = rest.placed().get(offset);
            if (found.isAir() || (placement != null && found.equals(placement.block()))) {
                continue;
            }
            FluidState fluid = found.getFluidState();
            if (!fluid.isEmpty() && rest.poured().contains(FluidInteractionRecipe.stillForm(fluid))) {
                continue;
            }
            results.put(offset, found);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }

    /** What stands where the rule wrote, when that is not what the rule wrote, or null when every write survived. */
    private @Nullable Preemption preempted(Map<BlockPos, BlockState> wrote) {
        List<String> lines = new ArrayList<>();
        BlockPos offset = null;
        BlockState found = null;
        for (var entry : wrote.entrySet()) {
            BlockState there = stateAt(entry.getKey());
            if (survived(entry.getValue(), there)) {
                continue;
            }
            if (offset == null) {
                offset = entry.getKey();
                found = there;
            }
            lines.add(entry.getKey().toShortString() + " holds " + key(there) + " rather than " + key(entry.getValue()));
        }
        if (offset == null) {
            return null;
        }
        return new Preemption(offset, found, wrote.get(offset), String.join(", ", lines));
    }

    /** Whether the settled state is the state a rule wrote. A fluid counts as itself at any level. */
    private static boolean survived(BlockState written, BlockState found) {
        if (written.equals(found)) {
            return true;
        }
        FluidState wroteFluid = written.getFluidState();
        FluidState foundFluid = found.getFluidState();
        return !wroteFluid.isEmpty() && !foundFluid.isEmpty()
                && FluidInteractionRecipe.stillForm(wroteFluid) == FluidInteractionRecipe.stillForm(foundFluid);
    }

    private static String key(BlockState state) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Every fluid the arrangement itself put somewhere, content and fixtures alike, in its still form. */
    private static Set<Fluid> poured(Map<BlockPos, Placement> placed) {
        Set<Fluid> fluids = new LinkedHashSet<>();
        placed.values().forEach(placement -> {
            FluidState state = placement.effectiveFluid();
            if (!state.isEmpty()) {
                fluids.add(FluidInteractionRecipe.stillForm(state));
            }
        });
        return fluids;
    }

    private static String describe(Map<BlockPos, Placement> content) {
        Map<String, String> parts = new LinkedHashMap<>();
        content.forEach((offset, placement) -> parts.put(offset.toShortString(), placement.describe().getString()));
        return parts.toString();
    }

    /**
     * How the settle step built one arrangement. The level itself holds the settled states until the next reset.
     *
     * @param placed    what the arrangement put where, fixtures included, keyed by offset from the source.
     * @param poured    every fluid the arrangement itself put somewhere, in still form.
     * @param completed false when the arrangement threw, which is no answer about any rule.
     */
    private record Rest(Map<BlockPos, Placement> placed, Set<Fluid> poured, boolean completed) {
    }
}
