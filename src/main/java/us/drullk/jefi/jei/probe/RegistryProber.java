package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * The registry tier: runs every {@link FluidInteractionRegistry} entry of one fluid type in the sandbox.
 *
 * <p>An interaction is an opaque predicate and action. For each source state, the tier places the fluid at
 * {@link SandboxLevel#ORIGIN}. It then calls the pair with every fluid candidate as the neighbor, in both forms.
 * It then calls the pair with every block candidate as the neighbor. A greedy multi-position search runs only
 * when nothing fires. The sandbox records the positions the predicate reads. The search fixes the first unfixed
 * position with each candidate in turn. A candidate that satisfies the predicate wins. A candidate that makes
 * the predicate read a new position stays, because short-circuit evaluation shows that an earlier clause passed.
 *
 * <p>A hit is the arrangement and the writes of the interaction itself. Writes from the predicate count too,
 * because some mods do the work there and register an empty action. {@link Settler} builds every hit with the
 * semantics of a level. A different rule can pre-empt every arrangement of an interaction. That interaction
 * becomes a failure recipe that states the observation ({@link Texts#preempted}). An interaction that never
 * fires, or whose arrangements settle without a result, becomes an "Unable to process" failure recipe.
 */
final class RegistryProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The neighbor position handed to the predicate and the action. */
    static final BlockPos NEIGHBOR = SandboxLevel.ORIGIN.offset(FluidInteractionRecipe.NEIGHBOR_OFFSET);

    private static final int MAX_FIXED_POSITIONS = 3;
    private static final int MAX_SEARCH_CALLS = 50_000;
    private static final ResourceLocation PENDING_ID = ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID, "pending");

    /** Successes before failures, then the result block at the source, then the registration index. */
    private static final Comparator<ProbedInteraction> WITHIN_OWNER_ORDER = Comparator
            .<ProbedInteraction>comparingInt(interaction -> interaction.isFailure() ? 1 : 0)
            .thenComparing(interaction -> RecipeIds.blockKey(interaction.resultAtSource()), Comparator.nullsLast(RecipeIds.LOCATION_ORDER))
            .thenComparingInt(ProbedInteraction::registrationIndex);

    private final SandboxLevel level;
    private final Settler settler;
    private final Candidates candidates;
    private int skippedRuns;

    RegistryProber(SandboxLevel level, Settler settler, Candidates candidates) {
        this.level = level;
        this.settler = settler;
        this.candidates = candidates;
    }

    /**
     * Every recipe of the registered interactions of one type, with ids. Owner groups rank by
     * {@link RecipeIds#OWNER_ORDER}. In a group, the interactions rank by {@link #WITHIN_OWNER_ORDER}. The
     * position of an interaction in its group is the {@code n} of its ids. Variants keep their discovery order.
     */
    List<FluidInteractionRecipe> probe(FluidType type, List<InteractionInformation> interactions) {
        List<ProbedInteraction> probed = new ArrayList<>(interactions.size());
        for (int i = 0; i < interactions.size(); i++) {
            probed.add(probe(type, i, interactions.get(i)));
        }
        return order(RecipeIds.keyOf(type), probed);
    }

    private ProbedInteraction probe(FluidType type, int index, InteractionInformation interaction) {
        Probe probe = new Probe(type, index, InteractionOwners.of(type, interaction));
        List<FluidState> sourceStates = Candidates.sourceStates(type);
        if (sourceStates.isEmpty()) {
            LOGGER.debug("Fluid interaction {} has no source fluid state to probe", probe);
            return probe.failed(Texts.unable(type, probe.owner));
        }
        for (FluidState source : sourceStates) {
            for (Hit hit : hits(interaction, source)) {
                probe.settle(source, hit);
            }
        }
        return probe.result();
    }

    /** The arrangements one source state fires the interaction in. The search runs only when the sweeps find none. */
    private List<Hit> hits(InteractionInformation interaction, FluidState source) {
        List<Hit> hits = new ArrayList<>();
        RunMemo<Boolean> memo = new RunMemo<>();
        sweep(interaction, source, candidates.fluids, memo, hits);
        sweep(interaction, source, candidates.blocks, memo, hits);
        if (hits.isEmpty()) {
            new Search(interaction, source).run().ifPresent(hits::add);
        }
        return hits;
    }

    /**
     * Tries every candidate beside the source. A candidate an earlier run already answers for is skipped
     * ({@link RunMemo}): the predicate fails for it without a run.
     */
    private void sweep(InteractionInformation interaction, FluidState source, List<Placement> sweep,
                       RunMemo<Boolean> memo, List<Hit> hits) {
        for (Placement candidate : sweep) {
            if (memo.lookup(candidate) != null) {
                skippedRuns++;
                continue;
            }
            tryHit(interaction, source, Map.of(NEIGHBOR, candidate), memo).ifPresent(hits::add);
        }
    }

    /** The candidate runs an earlier run of the same predicate answered for. */
    int skippedRuns() {
        return skippedRuns;
    }

    private static List<FluidInteractionRecipe> order(ResourceLocation typeKey, List<ProbedInteraction> probed) {
        Map<String, List<ProbedInteraction>> byOwner = new LinkedHashMap<>();
        for (ProbedInteraction interaction : probed) {
            byOwner.computeIfAbsent(interaction.owner(), k -> new ArrayList<>()).add(interaction);
        }
        List<String> owners = new ArrayList<>(byOwner.keySet());
        owners.sort(RecipeIds.OWNER_ORDER);

        List<FluidInteractionRecipe> ordered = new ArrayList<>();
        for (String owner : owners) {
            List<ProbedInteraction> group = byOwner.get(owner);
            group.sort(WITHIN_OWNER_ORDER);
            String ownerSegment = RecipeIds.ownerSegment(owner);
            int n = 0;
            for (ProbedInteraction interaction : group) {
                int variant = 0;
                for (FluidInteractionRecipe recipe : interaction.recipes()) {
                    ordered.add(withId(recipe, RecipeIds.id("", typeKey, ownerSegment, n, variant++)));
                }
                n++;
            }
        }
        return ordered;
    }

    private static FluidInteractionRecipe withId(FluidInteractionRecipe recipe, ResourceLocation id) {
        return new FluidInteractionRecipe(recipe.sourceType(), id, recipe.sources(), recipe.neighbors(),
                recipe.neighborOffset(), recipe.conditions(), recipe.results(), recipe.failure(), recipe.owner(), recipe.inert());
    }

    /**
     * Places the arrangement, runs the predicate and, when it passes, the action. An interaction that writes
     * nothing did not fire. What it wrote is not the answer. It is the sign that the arrangement is one to
     * settle. It is also the measure for the settled level.
     */
    private Optional<Hit> tryHit(InteractionInformation interaction, FluidState source, Map<BlockPos, Placement> requirements,
                                 @Nullable RunMemo<Boolean> memo) {
        Run run = run(interaction, source, requirements, false);
        if (!run.passed()) {
            if (memo != null) {
                memo.record(requirements.get(NEIGHBOR), run.watched(), Boolean.FALSE);
            }
            return Optional.empty();
        }
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>(run.writes());
        boolean wroteFromPredicate = !writes.isEmpty();
        level.beginTracking();
        try {
            interaction.interaction().interact(level, SandboxLevel.ORIGIN, NEIGHBOR, source);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("Fluid interaction action threw while probing {}", source.getFluidType().getDescriptionId(), e);
            return Optional.empty();
        } finally {
            level.endTracking();
        }
        writes.putAll(level.writes());
        if (writes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Hit(new LinkedHashMap<>(requirements), writes, wroteFromPredicate));
    }

    /**
     * Places the arrangement and runs only the predicate. The sandbox records what the predicate wrote, and what
     * it read when {@code reads} is set. Only the search reads that list.
     */
    private Run run(InteractionInformation interaction, FluidState source, Map<BlockPos, Placement> requirements, boolean reads) {
        level.reset();
        level.placeFluid(SandboxLevel.ORIGIN, source);
        requirements.forEach(level::place);
        level.beginTracking(reads);
        level.watch(NEIGHBOR);
        boolean passed;
        try {
            passed = interaction.predicate().test(level, SandboxLevel.ORIGIN, NEIGHBOR, source);
        } catch (RuntimeException | LinkageError e) {
            passed = false;
        } finally {
            level.endTracking();
        }
        return new Run(passed, level.watchedReads(), level.reads(), level.writes());
    }

    /**
     * The greedy multi-position search for one source state. Each round takes the first position the predicate
     * read that is not fixed yet. It tries every candidate there.
     */
    private final class Search {
        private final InteractionInformation interaction;
        private final FluidState source;
        private final Map<BlockPos, Placement> fixed = new LinkedHashMap<>();
        private final Set<BlockPos> seen = new LinkedHashSet<>();
        private int calls;

        Search(InteractionInformation interaction, FluidState source) {
            this.interaction = interaction;
            this.source = source;
        }

        Optional<Hit> run() {
            Run base = RegistryProber.this.run(interaction, source, fixed, true);
            if (base.passed()) {
                return tryHit(interaction, source, fixed, null);
            }
            seen.addAll(base.reads());
            rounds:
            while (fixed.size() < MAX_FIXED_POSITIONS) {
                for (BlockPos position : List.copyOf(seen)) {
                    if (position.equals(SandboxLevel.ORIGIN) || fixed.containsKey(position)) {
                        continue;
                    }
                    switch (fix(position)) {
                        case PASSED -> {
                            return tryHit(interaction, source, fixed, null);
                        }
                        case ADVANCED -> {
                            continue rounds;
                        }
                        case EXHAUSTED -> {
                            return Optional.empty();
                        }
                        case NOTHING -> {
                        }
                    }
                }
                return Optional.empty();
            }
            return Optional.empty();
        }

        /** Tries every candidate at one position. A candidate that makes the predicate read a new position stays. */
        private Step fix(BlockPos position) {
            for (Placement candidate : candidates.stillFluidsAndBlocks) {
                if (++calls > MAX_SEARCH_CALLS) {
                    return Step.EXHAUSTED;
                }
                fixed.put(position, candidate);
                Run attempt = RegistryProber.this.run(interaction, source, fixed, true);
                if (attempt.passed()) {
                    return Step.PASSED;
                }
                if (!seen.containsAll(attempt.reads())) {
                    seen.addAll(attempt.reads());
                    return Step.ADVANCED;
                }
                fixed.remove(position);
            }
            return Step.NOTHING;
        }
    }

    private enum Step {
        PASSED, ADVANCED, NOTHING, EXHAUSTED
    }

    /** One interaction under probe: what its hits settled into, and what to say when nothing did. */
    private final class Probe {
        final FluidType type;
        final int index;
        final @Nullable String owner;
        final Map<GroupKey, Group> groups = new LinkedHashMap<>();
        boolean wroteFromPredicate;
        int hits;
        int dropped;
        int preempted;
        @Nullable Settler.Preemption first;
        @Nullable FluidState firstSource;
        @Nullable Placement firstNeighbor;

        Probe(FluidType type, int index, @Nullable String owner) {
            this.type = type;
            this.index = index;
            this.owner = owner;
        }

        /** Settles one hit and files the outcome under its conditions and results, or drops it. */
        void settle(FluidState source, Hit hit) {
            hits++;
            wroteFromPredicate |= hit.wroteFromPredicate();
            Placement neighbor = hit.neighbor();
            String neighborName = neighbor != null ? neighbor.describe().getString() : "no neighbor";
            Settler.Outcome outcome = settler.settle(hit.content(source), FluidInteractionRecipe.NEIGHBOR_OFFSET, hit.wrote());
            if (outcome.preempted() != null) {
                dropped++;
                preempted++;
                if (first == null) {
                    first = outcome.preempted();
                    firstSource = source;
                    firstNeighbor = neighbor;
                }
                LOGGER.debug("A level pre-empts fluid interaction {} with {}: {}", this, neighborName, outcome.preempted());
                return;
            }
            if (outcome.results().isEmpty()) {
                dropped++;
                LOGGER.debug("A level settles fluid interaction {} with {} without a result", this, neighborName);
                return;
            }
            Group group = groups.computeIfAbsent(new GroupKey(hit.conditions(), outcome.results()), k -> new Group());
            group.sources.add(source);
            if (neighbor != null) {
                group.neighbors.add(neighbor);
            }
        }

        ProbedInteraction result() {
            if (wroteFromPredicate) {
                LOGGER.debug("Fluid interaction {} wrote blocks from its predicate", this);
            }
            if (!groups.isEmpty()) {
                return new ProbedInteraction(index, owner, recipes());
            }
            if (first != null) {
                LOGGER.info("A level pre-empts {} of the {} arrangement(s) of fluid interaction {}: {}", preempted, dropped, this, first);
            } else if (hits > 0) {
                LOGGER.info("A level settles every one of the {} arrangement(s) of fluid interaction {} without a result", dropped, this);
            }
            LOGGER.debug("No probe of fluid interaction {} succeeded", this);
            return failed(first != null
                    ? Texts.preempted(firstSource, firstNeighbor, first.found(), first.wrote())
                    : Texts.unable(type, owner));
        }

        ProbedInteraction failed(Component reason) {
            return new ProbedInteraction(index, owner, List.of(FluidInteractionRecipe.failed(type, PENDING_ID, reason, owner)));
        }

        private List<FluidInteractionRecipe> recipes() {
            List<FluidInteractionRecipe> recipes = new ArrayList<>(groups.size());
            groups.forEach((key, group) -> recipes.add(new FluidInteractionRecipe(type, PENDING_ID,
                    List.copyOf(group.sources), List.copyOf(group.neighbors), FluidInteractionRecipe.NEIGHBOR_OFFSET,
                    key.conditions(), key.results(), null, owner, InertForms.NONE)));
            return recipes;
        }

        @Override
        public String toString() {
            return RecipeIds.keyOf(type) + "#" + index + " (from " + InteractionOwners.describe(owner) + ")";
        }
    }

    private record Run(boolean passed, int watched, List<BlockPos> reads, Map<BlockPos, BlockState> writes) {
    }

    /**
     * One arrangement worth settling, in sandbox positions: what stands where, and what the interaction itself
     * wrote there. The settled level must agree with those writes before the outcome is this interaction's.
     */
    private record Hit(Map<BlockPos, Placement> requirements, Map<BlockPos, BlockState> writes, boolean wroteFromPredicate) {
        @Nullable Placement neighbor() {
            return requirements.get(NEIGHBOR);
        }

        /** The requirements other than the neighbor, keyed by offset from the source. */
        Map<BlockPos, Placement> conditions() {
            Map<BlockPos, Placement> conditions = new LinkedHashMap<>();
            requirements.forEach((pos, placement) -> {
                if (!pos.equals(NEIGHBOR)) {
                    conditions.put(pos.subtract(SandboxLevel.ORIGIN), placement);
                }
            });
            return conditions;
        }

        /** The arrangement to settle, keyed by offset from the source: the source, the neighbor, the conditions. */
        Map<BlockPos, Placement> content(FluidState source) {
            Map<BlockPos, Placement> content = new LinkedHashMap<>();
            content.put(BlockPos.ZERO, Placement.ofFluid(source));
            Placement neighbor = neighbor();
            if (neighbor != null) {
                content.put(FluidInteractionRecipe.NEIGHBOR_OFFSET, neighbor);
            }
            conditions().forEach(content::putIfAbsent);
            return content;
        }

        /** The writes keyed by offset from the source. */
        Map<BlockPos, BlockState> wrote() {
            Map<BlockPos, BlockState> wrote = new LinkedHashMap<>();
            writes.forEach((pos, state) -> wrote.put(pos.subtract(SandboxLevel.ORIGIN), state));
            return wrote;
        }
    }

    private record GroupKey(Map<BlockPos, Placement> conditions, Map<BlockPos, BlockState> results) {
    }

    /** One source state can hit many neighbors and one neighbor many source states, so both stay unique. */
    private static final class Group {
        final Set<FluidState> sources = new LinkedHashSet<>();
        final Set<Placement> neighbors = new LinkedHashSet<>();
    }

    /**
     * The result of one interaction before it has an id. This is one failure recipe, or one recipe per outcome
     * group in discovery order. It also holds what {@link #order} needs to place it.
     */
    private record ProbedInteraction(int registrationIndex, @Nullable String owner, List<FluidInteractionRecipe> recipes) {
        boolean isFailure() {
            return recipes.size() == 1 && recipes.getFirst().isFailure();
        }

        @Nullable BlockState resultAtSource() {
            return recipes.getFirst().resultAtSource();
        }
    }
}
