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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Discovers what every registered {@link FluidInteractionRegistry} entry does by running it inside a
 * {@link SandboxLevel}.
 *
 * <p>Interactions are opaque predicate/action pairs, so each one is exercised in tiers:
 * <ol>
 *     <li>every registered fluid as the neighbor, in source form and, where one exists, in flowing form;</li>
 *     <li>every registered block's default state as the neighbor;</li>
 *     <li>a greedy multi-position search for predicates that inspect more than the neighbor. The sandbox
 *     records which positions a predicate reads; a candidate is kept at a position when it makes the predicate
 *     read somewhere new, which is what short-circuit evaluation reveals once an earlier clause passes.</li>
 * </ol>
 * Anything that never fires, throws, or writes no block becomes a failure recipe. {@link SpreadProber} then adds
 * what the fluids do on their own and {@link NeighborProber} what their blocks do in their update hooks.
 *
 * <p>All three tiers only generate candidates. What each arrangement actually produces comes from
 * {@link Settler}, which builds it in the sandbox with a level's own semantics and lets every channel run in the
 * order a level runs them; an arrangement the level settles without a result is dropped, and so is one whose
 * settled level holds something other than what the proposing rule wrote, because that outcome belongs to
 * whichever channel reached the arrangement first and to that channel's own recipe. Recipes describing the same
 * pattern are collapsed by {@link RecipeMerger} once everything has been probed.
 */
public final class InteractionProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where the source fluid sits in the sandbox, well inside the level bounds. */
    public static final BlockPos ORIGIN = new BlockPos(7, 4, 7);
    /** The neighbor position handed to predicates, matching {@link FluidInteractionRecipe#NEIGHBOR_OFFSET}. */
    public static final BlockPos NEIGHBOR = ORIGIN.offset(FluidInteractionRecipe.NEIGHBOR_OFFSET);

    private static final int MAX_FIXED_POSITIONS = 3;
    private static final int MAX_SEARCH_CALLS = 50_000;

    private static final String MINECRAFT = "minecraft";
    private static final String NEOFORGE = "neoforge";
    private static final ResourceLocation PENDING_ID = ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID, "pending");

    /** Namespace-first ranking: {@code minecraft}, then {@code neoforge}, then everything else alphabetically. */
    private static final Comparator<String> NAMESPACE_ORDER =
            Comparator.comparingInt(InteractionProber::namespaceRank).thenComparing(Comparator.naturalOrder());

    /**
     * The comparator behind every ordering rule in this class: {@link #NAMESPACE_ORDER} on the namespace, then the
     * path alphabetically. Never {@link ResourceLocation}'s natural order, which compares path before namespace.
     */
    static final Comparator<ResourceLocation> LOCATION_ORDER =
            Comparator.comparing(ResourceLocation::getNamespace, NAMESPACE_ORDER).thenComparing(ResourceLocation::getPath);

    /** {@link #NAMESPACE_ORDER} applied to an owner mod id; a null (unattributed) owner sorts last. */
    static final Comparator<String> OWNER_ORDER = Comparator.nullsLast(NAMESPACE_ORDER);

    private static final Comparator<ProbedInteraction> WITHIN_OWNER_ORDER = Comparator
            .<ProbedInteraction>comparingInt(interaction -> interaction.isFailure() ? 1 : 0)
            .thenComparing(interaction -> resultKey(interaction.resultAtSource()), Comparator.nullsLast(LOCATION_ORDER))
            .thenComparingInt(ProbedInteraction::registrationIndex);

    private final SandboxLevel level;
    private final Settler settler;
    /** Neighbor fluids for tier one: each fluid in source form, followed by its flowing form when it has one. */
    private final List<Placement> fluidCandidates;
    private final List<Placement> blockCandidates;
    /** Candidates for the multi-position search, where fluids only appear in source form. */
    private final List<Placement> allCandidates;

    public InteractionProber(RegistryAccess access) {
        this.level = new SandboxLevel(access);
        this.settler = new Settler(level);
        this.fluidCandidates = new ArrayList<>();
        List<Placement> stillCandidates = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            FluidState still = fluid.defaultFluidState();
            if (fluid == Fluids.EMPTY || !still.isSource() || !FluidBlocks.hasBlock(fluid)) {
                continue;
            }
            Placement source = Placement.ofFluid(still);
            stillCandidates.add(source);
            fluidCandidates.add(source);
            FluidState flow = flowingForm(fluid);
            if (flow != null) {
                fluidCandidates.add(Placement.ofFluid(flow));
            }
        }
        this.blockCandidates = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!state.isAir() && !(block instanceof LiquidBlock) && state.getFluidState().isEmpty()) {
                blockCandidates.add(Placement.ofBlock(state));
            }
        }
        this.allCandidates = new ArrayList<>(stillCandidates.size() + blockCandidates.size());
        allCandidates.addAll(stillCandidates);
        allCandidates.addAll(blockCandidates);
    }

    /**
     * Probes every registered interaction. Fluid types are ordered by {@link #LOCATION_ORDER}; within one type,
     * interactions are grouped by owner in the same namespace-first order (null owner last) and, within one owner
     * group, ordered with successes before failures, then by the result block at the source position (again
     * {@link #LOCATION_ORDER}, null result last), then by registration index. That order is what JEI displays and
     * what recipe ids encode, so it stays the same across launches even though NeoForge dispatches mod setup — and
     * therefore fluid interaction registration — in parallel.
     *
     * <p>What {@link SpreadProber} and {@link NeighborProber} find for a type is folded into the same owner
     * groups: within one type the owner ranking decides the order, and within one owner every registry-derived
     * recipe precedes every spread-discovered one, which in turn precedes every neighbor-discovered one. Recipe
     * ids are assigned before that regrouping, so they never depend on it.
     */
    public List<FluidInteractionRecipe> probeAll() {
        Map<FluidType, List<InteractionInformation>> registered = RegisteredInteractions.get();
        List<FluidType> types = probable(orderedTypes(registered.keySet()), registered);

        long start = System.nanoTime();
        Map<FluidType, List<FluidInteractionRecipe>> fromRegistry = new LinkedHashMap<>();
        int interactionCount = 0;
        for (FluidType type : types) {
            List<InteractionInformation> interactions = List.copyOf(registered.getOrDefault(type, List.of()));
            if (interactions.isEmpty()) {
                continue;
            }
            interactionCount += interactions.size();
            List<ProbedInteraction> probed = new ArrayList<>(interactions.size());
            for (int i = 0; i < interactions.size(); i++) {
                probed.add(probe(type, i, interactions.get(i)));
            }
            fromRegistry.put(type, order(keyOf(type), probed));
        }
        long registryNanos = System.nanoTime() - start;

        long spreadStart = System.nanoTime();
        SpreadProber spreadProber = new SpreadProber(level, settler, fluidCandidates, blockCandidates);
        Map<FluidType, List<FluidInteractionRecipe>> probedSpread = new LinkedHashMap<>();
        for (FluidType type : types) {
            List<FluidInteractionRecipe> spread = spreadProber.probe(type, sourceStates(type));
            if (!spread.isEmpty()) {
                probedSpread.put(type, spread);
            }
        }
        long spreadNanos = System.nanoTime() - spreadStart;
        LOGGER.debug("Skipped the fluid spread of {} of {} fluid type(s) that inherit all of their spread code",
                spreadProber.skippedTypes(), types.size());

        long neighborStart = System.nanoTime();
        NeighborProber neighborProber = new NeighborProber(level, settler, fluidCandidates);
        Map<FluidType, List<FluidInteractionRecipe>> probedNeighbors = new LinkedHashMap<>();
        for (FluidType type : types) {
            List<FluidInteractionRecipe> found = neighborProber.probe(type, sourceStates(type));
            if (!found.isEmpty()) {
                probedNeighbors.put(type, found);
            }
        }
        long neighborNanos = System.nanoTime() - neighborStart;
        LOGGER.debug("Skipped the fluid neighbors of {} of {} fluid type(s) whose blocks inherit all of their update code",
                neighborProber.skippedTypes(), types.size());

        Set<Object> known = new LinkedHashSet<>();
        fromRegistry.values().forEach(recipes -> recipes.forEach(recipe -> known.add(arrangementKey(recipe))));

        Map<FluidType, List<FluidInteractionRecipe>> fromNeighbors = dedupe(probedNeighbors, known);
        int neighborCount = 0;
        for (List<FluidInteractionRecipe> found : fromNeighbors.values()) {
            neighborCount += found.size();
            found.forEach(recipe -> known.add(arrangementKey(recipe)));
        }

        Map<FluidType, List<FluidInteractionRecipe>> fromSpread = dedupe(probedSpread, known);
        int spreadCount = fromSpread.values().stream().mapToInt(List::size).sum();

        LOGGER.debug("Settled {} arrangement(s) in {} ms, reusing {} already settled; {} block scheduled tick(s) were "
                        + "asked for, which only a server level can run",
                settler.settledCount(), settler.millis(), settler.reusedCount(), level.blockTicksRequested());

        List<FluidInteractionRecipe> recipes = new ArrayList<>();
        for (FluidType type : types) {
            recipes.addAll(byOwner(fromRegistry.getOrDefault(type, List.of()), fromSpread.getOrDefault(type, List.of()),
                    fromNeighbors.getOrDefault(type, List.of())));
        }
        List<FluidInteractionRecipe> merged = RecipeMerger.merge(recipes);
        LOGGER.info("Probed fluid spread of {} fluid type(s) into {} JEI recipe(s) in {} ms",
                types.size(), spreadCount, spreadNanos / 1_000_000);
        LOGGER.info("Probed fluid neighbors of {} fluid type(s) into {} JEI recipe(s) in {} ms",
                types.size(), neighborCount, neighborNanos / 1_000_000);
        LOGGER.info("Probed {} fluid interaction(s) into {} JEI recipe(s) in {} ms",
                interactionCount, merged.size(), (registryNanos + spreadNanos + neighborNanos) / 1_000_000);
        return merged;
    }

    /**
     * One fluid type's recipes from all three tiers as a single list: owner groups ranked by {@link #OWNER_ORDER},
     * and within one owner every registry recipe, then every spread recipe, then every neighbor recipe, each in
     * its own order. All three inputs already carry their ids and are already grouped by owner in that ranking,
     * so this regroups them without changing any recipe, id or numbering.
     */
    private static List<FluidInteractionRecipe> byOwner(List<FluidInteractionRecipe> registry,
                                                        List<FluidInteractionRecipe> spread,
                                                        List<FluidInteractionRecipe> neighbors) {
        List<Map<String, List<FluidInteractionRecipe>>> tiers = new ArrayList<>();
        Set<String> owners = new LinkedHashSet<>();
        for (List<FluidInteractionRecipe> tier : List.of(registry, spread, neighbors)) {
            Map<String, List<FluidInteractionRecipe>> byOwner = new LinkedHashMap<>();
            tier.forEach(recipe -> byOwner.computeIfAbsent(recipe.owner(), k -> new ArrayList<>()).add(recipe));
            tiers.add(byOwner);
            owners.addAll(byOwner.keySet());
        }
        List<String> ordered = new ArrayList<>(owners);
        ordered.sort(OWNER_ORDER);

        List<FluidInteractionRecipe> result = new ArrayList<>(registry.size() + spread.size() + neighbors.size());
        for (String owner : ordered) {
            tiers.forEach(tier -> result.addAll(tier.getOrDefault(owner, List.of())));
        }
        return result;
    }

    /**
     * The types worth probing: the ones a level can hold a fluid of. A type whose every fluid lacks a block can
     * never stand anywhere, so nothing keyed on it can be exercised and none of its interactions is probed.
     */
    private static List<FluidType> probable(List<FluidType> types, Map<FluidType, List<InteractionInformation>> registered) {
        List<FluidType> probable = new ArrayList<>(types.size());
        int skippedTypes = 0;
        int skippedInteractions = 0;
        for (FluidType type : types) {
            if (FluidBlocks.hasBlock(type)) {
                probable.add(type);
                continue;
            }
            int interactions = registered.getOrDefault(type, List.of()).size();
            skippedTypes++;
            skippedInteractions += interactions;
            LOGGER.debug("Skipped {} fluid interaction(s) on {}, no fluid of which has a block", interactions, keyOf(type));
        }
        LOGGER.info("Skipped {} fluid interaction(s) on {} fluid type(s) whose fluids have no block",
                skippedInteractions, skippedTypes);
        return probable;
    }

    /** Every fluid type that either has registered interactions or has a fluid to tick, in {@link #LOCATION_ORDER}. */
    private static List<FluidType> orderedTypes(Set<FluidType> registered) {
        Set<FluidType> types = new LinkedHashSet<>(registered);
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid != Fluids.EMPTY && fluid.defaultFluidState().isSource()) {
                types.add(fluid.getFluidType());
            }
        }
        List<FluidType> ordered = new ArrayList<>(types);
        ordered.sort(Comparator.comparing(InteractionProber::keyOf, LOCATION_ORDER));
        return ordered;
    }

    /** The tier's recipes with everything an earlier tier already describes removed. */
    private static Map<FluidType, List<FluidInteractionRecipe>> dedupe(
            Map<FluidType, List<FluidInteractionRecipe>> tier, Set<Object> known) {
        Map<FluidType, List<FluidInteractionRecipe>> kept = new LinkedHashMap<>();
        for (var entry : tier.entrySet()) {
            List<FluidInteractionRecipe> found = new ArrayList<>(entry.getValue());
            found.removeIf(recipe -> known.contains(arrangementKey(recipe)));
            if (!found.isEmpty()) {
                kept.put(entry.getKey(), found);
            }
        }
        return kept;
    }

    /**
     * The physical arrangement a recipe describes: which fluid is placed last (the source), what stands where,
     * and nothing about who found it. Results are a function of that arrangement now that every tier settles it,
     * so two tiers proposing the same one reach the same answer and the first tier's attribution wins.
     */
    private static Object arrangementKey(FluidInteractionRecipe recipe) {
        return List.of(Set.copyOf(recipe.sources()), Set.copyOf(recipe.neighbors()), recipe.neighborOffset(),
                recipe.conditions());
    }

    private ProbedInteraction probe(FluidType type, int index, InteractionInformation interaction) {
        ResourceLocation key = keyOf(type);
        String owner = InteractionOwners.of(type, interaction);
        List<FluidState> sourceStates = sourceStates(type);
        if (sourceStates.isEmpty()) {
            LOGGER.debug("Fluid interaction {}#{} (from {}) has no source fluid state to probe", key, index, InteractionOwners.describe(owner));
            return new ProbedInteraction(index, owner,
                    List.of(FluidInteractionRecipe.failed(type, index, PENDING_ID, unable(type, owner), owner)));
        }

        Map<GroupKey, Group> groups = new LinkedHashMap<>();
        boolean wroteFromPredicate = false;
        int hitCount = 0;
        int dropped = 0;
        int preempted = 0;
        Settler.Preemption first = null;
        FluidState firstSource = null;
        Placement firstNeighbor = null;
        for (FluidState source : sourceStates) {
            List<Hit> hits = new ArrayList<>();
            for (Placement candidate : fluidCandidates) {
                tryHit(interaction, source, Map.of(NEIGHBOR, candidate)).ifPresent(hits::add);
            }
            for (Placement candidate : blockCandidates) {
                tryHit(interaction, source, Map.of(NEIGHBOR, candidate)).ifPresent(hits::add);
            }
            if (hits.isEmpty()) {
                search(interaction, source).ifPresent(hits::add);
            }
            hitCount += hits.size();
            for (Hit hit : hits) {
                wroteFromPredicate |= hit.wroteFromPredicate();
                Map<BlockPos, Placement> conditions = new LinkedHashMap<>();
                hit.requirements().forEach((pos, placement) -> {
                    if (!pos.equals(NEIGHBOR)) {
                        conditions.put(pos.subtract(ORIGIN), placement);
                    }
                });
                Placement neighbor = hit.requirements().get(NEIGHBOR);
                Map<BlockPos, Placement> content = new LinkedHashMap<>();
                content.put(BlockPos.ZERO, Placement.ofFluid(source));
                if (neighbor != null) {
                    content.put(FluidInteractionRecipe.NEIGHBOR_OFFSET, neighbor);
                }
                conditions.forEach(content::putIfAbsent);
                Map<BlockPos, BlockState> wrote = new LinkedHashMap<>();
                hit.writes().forEach((pos, state) -> wrote.put(pos.subtract(ORIGIN), state));
                Settler.Outcome outcome = settler.settle(content, FluidInteractionRecipe.NEIGHBOR_OFFSET, wrote);
                if (outcome.preempted() != null) {
                    dropped++;
                    preempted++;
                    if (first == null) {
                        first = outcome.preempted();
                        firstSource = source;
                        firstNeighbor = neighbor;
                    }
                    LOGGER.debug("A level pre-empts fluid interaction {}#{} (from {}) with {}: {}",
                            key, index, InteractionOwners.describe(owner),
                            neighbor != null ? neighbor.describe().getString() : "no neighbor", outcome.preempted());
                    continue;
                }
                Map<BlockPos, BlockState> results = outcome.results();
                if (results.isEmpty()) {
                    dropped++;
                    LOGGER.debug("A level settles fluid interaction {}#{} (from {}) with {} without a result",
                            key, index, InteractionOwners.describe(owner),
                            neighbor != null ? neighbor.describe().getString() : "no neighbor");
                    continue;
                }
                Group group = groups.computeIfAbsent(new GroupKey(conditions, results), k -> new Group());
                group.sources.add(source);
                if (neighbor != null) {
                    group.neighbors.add(neighbor);
                }
            }
        }

        if (wroteFromPredicate) {
            LOGGER.debug("Fluid interaction {}#{} (from {}) wrote blocks from its predicate", key, index, InteractionOwners.describe(owner));
        }

        if (groups.isEmpty()) {
            if (first != null) {
                LOGGER.info("A level pre-empts {} of the {} arrangement(s) of fluid interaction {}#{} (from {}): {}",
                        preempted, dropped, key, index, InteractionOwners.describe(owner), first);
            } else if (hitCount > 0) {
                LOGGER.info("A level settles every one of the {} arrangement(s) of fluid interaction {}#{} (from {}) "
                        + "without a result", dropped, key, index, InteractionOwners.describe(owner));
            }
            LOGGER.debug("No probe of fluid interaction {}#{} (from {}) succeeded", key, index, InteractionOwners.describe(owner));
            Component reason = first != null
                    ? Texts.preempted(firstSource, firstNeighbor, first.found(), first.wrote())
                    : unable(type, owner);
            return new ProbedInteraction(index, owner,
                    List.of(FluidInteractionRecipe.failed(type, index, PENDING_ID, reason, owner)));
        }

        List<FluidInteractionRecipe> recipes = new ArrayList<>(groups.size());
        for (var entry : groups.entrySet()) {
            Group group = entry.getValue();
            recipes.add(new FluidInteractionRecipe(
                    type, index, PENDING_ID,
                    List.copyOf(group.sources), List.copyOf(group.neighbors), FluidInteractionRecipe.NEIGHBOR_OFFSET,
                    entry.getKey().conditions(), entry.getKey().results(), null, owner, InertForms.NONE));
        }
        return new ProbedInteraction(index, owner, recipes);
    }

    /**
     * Final probe order within one fluid type: owner groups ranked by {@link #OWNER_ORDER} (null owner last),
     * then within a group successes before failures, then by the result block at the source position
     * ({@link #LOCATION_ORDER}, null result last), then by registration index. An interaction's position in its
     * ordered group is the {@code n} embedded in its recipe ids; variants keep their discovery order.
     */
    private static List<FluidInteractionRecipe> order(ResourceLocation typeKey, List<ProbedInteraction> probed) {
        Map<String, List<ProbedInteraction>> byOwner = new LinkedHashMap<>();
        for (ProbedInteraction interaction : probed) {
            byOwner.computeIfAbsent(interaction.owner(), k -> new ArrayList<>()).add(interaction);
        }
        List<String> owners = new ArrayList<>(byOwner.keySet());
        owners.sort(OWNER_ORDER);

        List<FluidInteractionRecipe> ordered = new ArrayList<>();
        for (String owner : owners) {
            List<ProbedInteraction> group = byOwner.get(owner);
            group.sort(WITHIN_OWNER_ORDER);
            String ownerSegment = ownerSegment(owner);
            int n = 0;
            for (ProbedInteraction interaction : group) {
                int variant = 0;
                for (FluidInteractionRecipe recipe : interaction.recipes()) {
                    ordered.add(withId(recipe, recipeId(typeKey, ownerSegment, n, variant++)));
                }
                n++;
            }
        }
        return ordered;
    }

    private static FluidInteractionRecipe withId(FluidInteractionRecipe recipe, ResourceLocation id) {
        return new FluidInteractionRecipe(recipe.sourceType(), recipe.index(), id, recipe.sources(), recipe.neighbors(),
                recipe.neighborOffset(), recipe.conditions(), recipe.results(), recipe.failure(), recipe.owner(), recipe.inert());
    }

    private static @Nullable ResourceLocation resultKey(@Nullable BlockState state) {
        return state != null ? BuiltInRegistries.BLOCK.getKey(state.getBlock()) : null;
    }

    /**
     * Mod ids are already restricted to characters legal in a {@link ResourceLocation} path, but anything else is
     * turned into {@code _} so a hostile or unexpected owner id can never split the id into extra path segments.
     */
    static String ownerSegment(@Nullable String owner) {
        String raw = owner != null ? owner : "unknown";
        StringBuilder sanitized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean legal = c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            sanitized.append(legal ? c : '_');
        }
        return sanitized.toString();
    }

    /**
     * Places the arrangement, runs the predicate, and if it passes runs the action. An interaction that writes
     * nothing at all never fired; blocks written by the predicate count, since some interactions do all their
     * work there and register an empty action. What those writes were is not the answer, only the sign that this
     * arrangement is one to settle and the measure the settled level is held against.
     */
    private Optional<Hit> tryHit(InteractionInformation interaction, FluidState source, Map<BlockPos, Placement> requirements) {
        Run run = run(interaction, source, requirements);
        if (!run.passed()) {
            return Optional.empty();
        }
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>(run.writes());
        boolean wroteFromPredicate = !writes.isEmpty();
        level.beginTracking();
        try {
            interaction.interaction().interact(level, ORIGIN, NEIGHBOR, source);
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
     * Places the arrangement and evaluates only the predicate, recording the positions it looked at and any
     * blocks it wrote.
     */
    private Run run(InteractionInformation interaction, FluidState source, Map<BlockPos, Placement> requirements) {
        level.reset();
        level.placeFluid(ORIGIN, source);
        requirements.forEach(level::place);
        level.beginTracking();
        boolean passed;
        try {
            passed = interaction.predicate().test(level, ORIGIN, NEIGHBOR, source);
        } catch (RuntimeException | LinkageError e) {
            passed = false;
        } finally {
            level.endTracking();
        }
        return new Run(passed, level.reads(), level.writes());
    }

    /**
     * Greedy multi-position search. Each round picks the first position the predicate has read that is not yet
     * fixed and tries every candidate there. A candidate that satisfies the predicate wins; a candidate that
     * makes the predicate read a new position is kept and the search moves on to that position.
     */
    private Optional<Hit> search(InteractionInformation interaction, FluidState source) {
        Map<BlockPos, Placement> fixed = new LinkedHashMap<>();
        Run base = run(interaction, source, fixed);
        if (base.passed()) {
            return tryHit(interaction, source, fixed);
        }
        Set<BlockPos> seen = new LinkedHashSet<>(base.reads());
        int calls = 0;

        rounds:
        while (fixed.size() < MAX_FIXED_POSITIONS) {
            for (BlockPos position : List.copyOf(seen)) {
                if (position.equals(ORIGIN) || fixed.containsKey(position)) {
                    continue;
                }
                for (Placement candidate : allCandidates) {
                    if (++calls > MAX_SEARCH_CALLS) {
                        return Optional.empty();
                    }
                    fixed.put(position, candidate);
                    Run attempt = run(interaction, source, fixed);
                    if (attempt.passed()) {
                        return tryHit(interaction, source, fixed);
                    }
                    if (!seen.containsAll(attempt.reads())) {
                        seen.addAll(attempt.reads());
                        continue rounds;
                    }
                    fixed.remove(position);
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Still fluids of the type a level can hold, each in source form and, when flowing exists, a full-height
     * flowing form.
     */
    static List<FluidState> sourceStates(FluidType type) {
        List<FluidState> states = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid.getFluidType() != type || fluid == Fluids.EMPTY || !FluidBlocks.hasBlock(fluid)) {
                continue;
            }
            FluidState still = fluid.defaultFluidState();
            if (!still.isSource()) {
                continue;
            }
            states.add(still);
            FluidState flow = flowingForm(fluid);
            if (flow != null) {
                states.add(flow);
            }
        }
        return states;
    }

    /**
     * A full-height flowing state of a still fluid, or null when it has none. Modded fluids may register a flowing
     * fluid without the level properties, so the state is only ever narrowed with {@code trySetValue}.
     */
    private static @Nullable FluidState flowingForm(Fluid fluid) {
        if (!(fluid instanceof FlowingFluid flowing)) {
            return null;
        }
        FluidState flow = flowing.getFlowing().defaultFluidState()
                .trySetValue(FlowingFluid.LEVEL, 7)
                .trySetValue(FlowingFluid.FALLING, false);
        return !flow.isEmpty() && !flow.isSource() ? flow : null;
    }

    public static ResourceLocation keyOf(FluidType type) {
        ResourceLocation key = NeoForgeRegistries.FLUID_TYPES.getKey(type);
        return key != null ? key : ResourceLocation.fromNamespaceAndPath("unknown", "unregistered");
    }

    private static ResourceLocation recipeId(ResourceLocation typeKey, String ownerSegment, int n, int variant) {
        return ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID,
                typeKey.getNamespace() + "/" + typeKey.getPath() + "/" + ownerSegment + "/" + n + "/" + variant);
    }

    private static Component unable(FluidType type, @Nullable String owner) {
        if (owner == null) {
            return Component.translatable("jei." + JustEnoughFluidInteractions.MODID + ".fluid_interactions.unable", type.getDescription());
        }
        return Component.translatable("jei." + JustEnoughFluidInteractions.MODID + ".fluid_interactions.unable_from", type.getDescription(), owner);
    }

    private static int namespaceRank(String namespace) {
        if (MINECRAFT.equals(namespace)) {
            return 0;
        }
        return NEOFORGE.equals(namespace) ? 1 : 2;
    }

    private record Run(boolean passed, List<BlockPos> reads, Map<BlockPos, BlockState> writes) {
    }

    /**
     * One arrangement worth settling: what stands where, and what the interaction itself wrote there, which is
     * what the settled level has to agree with for the outcome to be this interaction's.
     */
    private record Hit(Map<BlockPos, Placement> requirements, Map<BlockPos, BlockState> writes, boolean wroteFromPredicate) {
    }

    private record GroupKey(Map<BlockPos, Placement> conditions, Map<BlockPos, BlockState> results) {
    }

    /** One source state can hit many neighbors and one neighbor many source states, so both stay unique. */
    private static final class Group {
        final Set<FluidState> sources = new LinkedHashSet<>();
        final Set<Placement> neighbors = new LinkedHashSet<>();
    }

    /**
     * One interaction's probe result before it has a final id: every recipe it produced (a single failure recipe,
     * or one recipe per outcome group, in discovery order) plus what step 2's ordering needs to place it.
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
