package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Works out what a level does with one arrangement by building it in the sandbox with real level semantics and
 * letting it run to rest.
 *
 * <p>The probe tiers are candidate generators: each one calls a single rule by hand and asks whether that rule
 * writes anything, which says an arrangement is worth looking at and which interaction or fluid owns it. What the
 * arrangement then produces is never only that rule's writes, because a level runs every channel — the
 * placement's {@code onPlace}, the neighbour updates it sends, the interaction registry the updated liquid blocks
 * run, and the fluids' own scheduled ticks, in that order and with everything each of those sets off. Settling
 * here runs all of them, so the ordering between channels comes out of the simulation instead of being modelled
 * by hand.
 *
 * <p>An arrangement answers for the rule that proposed it and for no other. That holds only while the settled
 * level agrees with what the proposing rule wrote by hand: at every position that rule wrote, the settled state
 * is either the same block state or, where the rule wrote a fluid, the same still fluid whatever its level.
 * Anything else there is another channel's outcome, which belongs to whichever rule owns it and to that rule's
 * own recipe, so the arrangement is dropped for the tier that proposed it. What a level adds beyond those
 * positions — a result block changing something of its own as it is placed — is part of the answer.
 *
 * <p>The arrangement is placed the way {@code GroundCheck} places it in a real level: {@link Fixtures} first,
 * then the content, with the source last, because a level runs the hooks of the block being placed before it
 * tells that block's neighbours anything, and the fluid placed last is the one every tier probes as its source.
 *
 * <p>The answer is the diff over the arrangement's own content positions together with the positions the
 * proposing rule wrote in. Fixtures and wherever the fluids flowed to are not part of the question a recipe asks,
 * and a position is only a result when its final state is something other than air, other than what was put
 * there, and other than a state of a fluid the arrangement itself poured — a spreading fluid arriving somewhere
 * is not a transformation.
 */
public final class Settler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How far the virtual game time may run past the placement. Fluid tick delays decide this: vanilla lava's is
     * 30 in the overworld and the slowest fluid on the development classpath 50, so this leaves room for a rule
     * that waits for a scheduled tick, and for what that tick sets off, to have happened.
     */
    public static final int SETTLE_TICKS = 80;

    /** A ceiling on one arrangement's cost: a fluid pouring off the bedrock reschedules itself indefinitely. */
    private static final int MAX_TICK_RUNS = 20_000;

    private final SandboxLevel level;
    private final Map<Object, Rest> cache = new HashMap<>();
    private int settled;
    private int reused;
    private long nanos;

    public Settler(SandboxLevel level) {
        this.level = level;
    }

    /**
     * What the arrangement leaves behind, keyed by offset from the source, or why it says nothing about the rule
     * that proposed it.
     *
     * @param content        the arrangement, keyed by offset from the source, which sits at {@link BlockPos#ZERO}
     *                       and is placed last
     * @param neighborOffset where the recipe's neighbor sits, which decides how a flow is fed
     * @param wrote          what the proposing rule itself wrote when it was called by hand, keyed by the same
     *                       offsets and already filtered the way that tier filters its own writes
     */
    public Outcome settle(Map<BlockPos, Placement> content, BlockPos neighborOffset, Map<BlockPos, BlockState> wrote) {
        Rest rest = rest(content, neighborOffset);
        Preemption preempted = preempted(rest, wrote);
        if (preempted != null) {
            return new Outcome(Map.of(), preempted);
        }
        return new Outcome(diff(rest, content, wrote.keySet()), null);
    }

    /** The arrangement a spread or neighbor tier describes: the source, and one candidate at the probed target. */
    public static Map<BlockPos, Placement> arrangement(FluidState source, BlockPos target, Placement candidate) {
        Map<BlockPos, Placement> content = new LinkedHashMap<>();
        content.put(BlockPos.ZERO, Placement.ofFluid(source));
        content.put(target, candidate);
        return content;
    }

    /**
     * What one settled arrangement says. Either it produced results, which may be empty, or a level pre-empted
     * the rule that proposed it and {@link #preempted()} says what stands where that rule wrote instead.
     */
    public record Outcome(Map<BlockPos, BlockState> results, @Nullable Preemption preempted) {
    }

    /**
     * What a level holds where a rule wrote. The first difference carries the whole observation a recipe can
     * state, while {@link #describe()} names every one of them for the log.
     *
     * @param offset   the first position, keyed from the source, where the settled level differs
     * @param found    what the settled level holds there
     * @param wrote    what the rule itself wrote there
     * @param describe every difference of the arrangement, as one line
     */
    public record Preemption(BlockPos offset, BlockState found, BlockState wrote, String describe) {
        @Override
        public String toString() {
            return describe;
        }
    }

    private Rest rest(Map<BlockPos, Placement> content, BlockPos neighborOffset) {
        Object key = List.of(Map.copyOf(content), neighborOffset);
        Rest known = cache.get(key);
        if (known != null) {
            reused++;
            return known;
        }
        long start = System.nanoTime();
        Rest rest = run(content, neighborOffset);
        nanos += System.nanoTime() - start;
        settled++;
        cache.put(key, rest);
        return rest;
    }

    private Rest run(Map<BlockPos, Placement> content, BlockPos neighborOffset) {
        Map<BlockPos, Placement> fixtures = Fixtures.around(content, neighborOffset);
        Map<BlockPos, Placement> placed = new LinkedHashMap<>(fixtures);
        placed.putAll(content);
        level.reset();
        level.setLive(true);
        try {
            fixtures.forEach((offset, placement) -> level.placeLive(InteractionProber.ORIGIN.offset(offset), placement));
            content.forEach((offset, placement) -> {
                if (!offset.equals(BlockPos.ZERO)) {
                    level.placeLive(InteractionProber.ORIGIN.offset(offset), placement);
                }
            });
            Placement source = content.get(BlockPos.ZERO);
            if (source != null) {
                level.placeLive(InteractionProber.ORIGIN, source);
            }
            level.settle(SETTLE_TICKS, MAX_TICK_RUNS);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("Settling {} threw", describe(content), e);
            return new Rest(Map.of(), placed, Set.of(), false);
        } finally {
            level.setLive(false);
        }
        Map<BlockPos, BlockState> states = new HashMap<>();
        level.contents().forEach((pos, state) -> states.put(pos.subtract(InteractionProber.ORIGIN), state));
        return new Rest(states, placed, poured(content, fixtures), true);
    }

    /** Ordered so that two arrangements producing the same results produce the same recipe, whoever asked. */
    private static final Comparator<BlockPos> OFFSET_ORDER = Comparator
            .<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private static Map<BlockPos, BlockState> diff(Rest rest, Map<BlockPos, Placement> content, Set<BlockPos> wrote) {
        if (!rest.completed()) {
            return Map.of();
        }
        Set<BlockPos> domain = new LinkedHashSet<>(content.keySet());
        domain.addAll(wrote);
        Map<BlockPos, BlockState> results = new TreeMap<>(OFFSET_ORDER);
        for (BlockPos offset : domain) {
            BlockState found = rest.stateAt(offset);
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

    /**
     * What stands where the proposing rule wrote, whenever that is not what the rule wrote, or null when every
     * one of its writes survived. An arrangement that threw says nothing about any rule, so it is never
     * pre-empted.
     */
    private static @Nullable Preemption preempted(Rest rest, Map<BlockPos, BlockState> wrote) {
        if (!rest.completed()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        BlockPos offset = null;
        BlockState found = null;
        for (var entry : wrote.entrySet()) {
            BlockState there = rest.stateAt(entry.getKey());
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

    /** Whether the settled state is the state a rule wrote, a fluid counting as itself whatever its level. */
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
    private static Set<Fluid> poured(Map<BlockPos, Placement> content, Map<BlockPos, Placement> fixtures) {
        Set<Fluid> fluids = new LinkedHashSet<>();
        for (Map<BlockPos, Placement> part : List.of(content, fixtures)) {
            part.values().forEach(placement -> {
                FluidState state = placement.effectiveFluid();
                if (!state.isEmpty()) {
                    fluids.add(FluidInteractionRecipe.stillForm(state));
                }
            });
        }
        return fluids;
    }

    private static String describe(Map<BlockPos, Placement> content) {
        Map<String, String> parts = new LinkedHashMap<>();
        content.forEach((offset, placement) -> parts.put(offset.toShortString(), placement.describe().getString()));
        return parts.toString();
    }

    public int settledCount() {
        return settled;
    }

    public int reusedCount() {
        return reused;
    }

    public long millis() {
        return nanos / 1_000_000;
    }

    /**
     * One settled arrangement, kept as it came to rest so that every tier proposing it can be answered from the
     * same run.
     *
     * @param states    every block the level holds afterwards, keyed by offset from the source; air is absent
     * @param placed    what the arrangement put where, fixtures included, keyed by the same offsets
     * @param poured    every fluid the arrangement itself put somewhere, in still form
     * @param completed false when the arrangement threw, which is no answer about any rule at all
     */
    private record Rest(Map<BlockPos, BlockState> states, Map<BlockPos, Placement> placed, Set<Fluid> poured,
                        boolean completed) {
        BlockState stateAt(BlockPos offset) {
            return states.getOrDefault(offset, Blocks.AIR.defaultBlockState());
        }
    }
}
