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
 * Anything that never fires, throws, or writes no block becomes a failure recipe. Recipes describing the same
 * pattern are collapsed by {@link RecipeMerger} once every interaction has been probed.
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
    private static final Comparator<String> OWNER_ORDER = Comparator.nullsLast(NAMESPACE_ORDER);

    private static final Comparator<ProbedInteraction> WITHIN_OWNER_ORDER = Comparator
            .<ProbedInteraction>comparingInt(interaction -> interaction.isFailure() ? 1 : 0)
            .thenComparing(interaction -> resultKey(interaction.resultAtSource()), Comparator.nullsLast(LOCATION_ORDER))
            .thenComparingInt(ProbedInteraction::registrationIndex);

    private final SandboxLevel level;
    /** Neighbor fluids for tier one: each fluid in source form, followed by its flowing form when it has one. */
    private final List<Placement> fluidCandidates;
    private final List<Placement> blockCandidates;
    /** Candidates for the multi-position search, where fluids only appear in source form. */
    private final List<Placement> allCandidates;

    public InteractionProber(RegistryAccess access) {
        this.level = new SandboxLevel(access);
        this.fluidCandidates = new ArrayList<>();
        List<Placement> stillCandidates = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            FluidState still = fluid.defaultFluidState();
            if (fluid == Fluids.EMPTY || !still.isSource()) {
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
     */
    public List<FluidInteractionRecipe> probeAll() {
        List<Map.Entry<FluidType, List<InteractionInformation>>> entries = new ArrayList<>(RegisteredInteractions.get().entrySet());
        entries.sort(Comparator.comparing(e -> keyOf(e.getKey()), LOCATION_ORDER));

        List<FluidInteractionRecipe> recipes = new ArrayList<>();
        long start = System.nanoTime();
        int interactionCount = 0;
        for (var entry : entries) {
            List<InteractionInformation> interactions = List.copyOf(entry.getValue());
            interactionCount += interactions.size();
            List<ProbedInteraction> probed = new ArrayList<>(interactions.size());
            for (int i = 0; i < interactions.size(); i++) {
                probed.add(probe(entry.getKey(), i, interactions.get(i)));
            }
            recipes.addAll(order(keyOf(entry.getKey()), probed));
        }
        List<FluidInteractionRecipe> merged = RecipeMerger.merge(recipes);
        LOGGER.info("Probed {} fluid interaction(s) into {} JEI recipe(s) in {} ms",
                interactionCount, merged.size(), (System.nanoTime() - start) / 1_000_000);
        return merged;
    }

    private ProbedInteraction probe(FluidType type, int index, InteractionInformation interaction) {
        ResourceLocation key = keyOf(type);
        String owner = InteractionOwners.of(type, interaction);
        List<FluidState> sourceStates = sourceStates(type);
        if (sourceStates.isEmpty()) {
            LOGGER.info("Fluid interaction {}#{} (from {}) has no source fluid state to probe", key, index, InteractionOwners.describe(owner));
            return new ProbedInteraction(index, owner,
                    List.of(FluidInteractionRecipe.failed(type, index, PENDING_ID, unable(type, owner), owner)));
        }

        Map<GroupKey, Group> groups = new LinkedHashMap<>();
        boolean wroteFromPredicate = false;
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
            for (Hit hit : hits) {
                wroteFromPredicate |= hit.wroteFromPredicate();
                Map<BlockPos, Placement> conditions = new LinkedHashMap<>();
                hit.requirements().forEach((pos, placement) -> {
                    if (!pos.equals(NEIGHBOR)) {
                        conditions.put(pos.subtract(ORIGIN), placement);
                    }
                });
                Map<BlockPos, BlockState> results = new LinkedHashMap<>();
                hit.writes().forEach((pos, state) -> results.put(pos.subtract(ORIGIN), state));
                Group group = groups.computeIfAbsent(new GroupKey(conditions, results), k -> new Group());
                group.sources.add(source);
                Placement neighbor = hit.requirements().get(NEIGHBOR);
                if (neighbor != null) {
                    group.neighbors.add(neighbor);
                }
            }
        }

        if (wroteFromPredicate) {
            LOGGER.debug("Fluid interaction {}#{} (from {}) wrote blocks from its predicate", key, index, InteractionOwners.describe(owner));
        }

        if (groups.isEmpty()) {
            LOGGER.info("No probe of fluid interaction {}#{} (from {}) succeeded", key, index, InteractionOwners.describe(owner));
            return new ProbedInteraction(index, owner,
                    List.of(FluidInteractionRecipe.failed(type, index, PENDING_ID, unable(type, owner), owner)));
        }

        List<FluidInteractionRecipe> recipes = new ArrayList<>(groups.size());
        for (var entry : groups.entrySet()) {
            Group group = entry.getValue();
            recipes.add(new FluidInteractionRecipe(
                    type, index, PENDING_ID,
                    List.copyOf(group.sources), List.copyOf(group.neighbors),
                    entry.getKey().conditions(), entry.getKey().results(), null, owner));
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
                recipe.conditions(), recipe.results(), recipe.failure(), recipe.owner());
    }

    private static @Nullable ResourceLocation resultKey(@Nullable BlockState state) {
        return state != null ? BuiltInRegistries.BLOCK.getKey(state.getBlock()) : null;
    }

    /**
     * Mod ids are already restricted to characters legal in a {@link ResourceLocation} path, but anything else is
     * turned into {@code _} so a hostile or unexpected owner id can never split the id into extra path segments.
     */
    private static String ownerSegment(@Nullable String owner) {
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
     * Places the arrangement, runs the predicate, and if it passes runs the action. Blocks written by the
     * predicate count as results too: some interactions do all their work there and register an empty action.
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

    /** Still fluids of the type, each in source form and, when flowing exists, a full-height flowing form. */
    private static List<FluidState> sourceStates(FluidType type) {
        List<FluidState> states = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid.getFluidType() != type || fluid == Fluids.EMPTY) {
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
