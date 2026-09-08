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
 *     <li>every registered still fluid as the neighbor;</li>
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

    private final SandboxLevel level;
    private final List<Placement> fluidCandidates;
    private final List<Placement> blockCandidates;
    private final List<Placement> allCandidates;

    public InteractionProber(RegistryAccess access) {
        this.level = new SandboxLevel(access);
        this.fluidCandidates = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            FluidState state = fluid.defaultFluidState();
            if (fluid != Fluids.EMPTY && state.isSource()) {
                fluidCandidates.add(Placement.ofFluid(state));
            }
        }
        this.blockCandidates = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!state.isAir() && !(block instanceof LiquidBlock) && state.getFluidState().isEmpty()) {
                blockCandidates.add(Placement.ofBlock(state));
            }
        }
        this.allCandidates = new ArrayList<>(fluidCandidates.size() + blockCandidates.size());
        allCandidates.addAll(fluidCandidates);
        allCandidates.addAll(blockCandidates);
    }

    /** Probes every registered interaction, in registry-key order of the source fluid type. */
    public List<FluidInteractionRecipe> probeAll() {
        List<Map.Entry<FluidType, List<InteractionInformation>>> entries = new ArrayList<>(RegisteredInteractions.get().entrySet());
        entries.sort(Comparator.comparing(e -> keyOf(e.getKey()).toString()));

        List<FluidInteractionRecipe> recipes = new ArrayList<>();
        long start = System.nanoTime();
        for (var entry : entries) {
            List<InteractionInformation> interactions = List.copyOf(entry.getValue());
            for (int i = 0; i < interactions.size(); i++) {
                recipes.addAll(probe(entry.getKey(), i, interactions.get(i)));
            }
        }
        List<FluidInteractionRecipe> merged = RecipeMerger.merge(recipes);
        LOGGER.info("Probed {} fluid interaction(s) into {} JEI recipe(s) in {} ms",
                entries.stream().mapToInt(e -> e.getValue().size()).sum(), merged.size(), (System.nanoTime() - start) / 1_000_000);
        return merged;
    }

    private List<FluidInteractionRecipe> probe(FluidType type, int index, InteractionInformation interaction) {
        ResourceLocation key = keyOf(type);
        String owner = InteractionOwners.of(type, interaction);
        List<FluidState> sourceStates = sourceStates(type);
        if (sourceStates.isEmpty()) {
            LOGGER.info("Fluid interaction {}#{} (from {}) has no source fluid state to probe", key, index, InteractionOwners.describe(owner));
            return List.of(FluidInteractionRecipe.failed(type, index, recipeId(key, index, 0), unable(type, owner), owner));
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
            return List.of(FluidInteractionRecipe.failed(type, index, recipeId(key, index, 0), unable(type, owner), owner));
        }

        List<FluidInteractionRecipe> recipes = new ArrayList<>(groups.size());
        int variant = 0;
        for (var entry : groups.entrySet()) {
            Group group = entry.getValue();
            recipes.add(new FluidInteractionRecipe(
                    type, index, recipeId(key, index, variant++),
                    List.copyOf(group.sources), List.copyOf(group.neighbors),
                    entry.getKey().conditions(), entry.getKey().results(), null, owner));
        }
        return recipes;
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
            if (fluid instanceof FlowingFluid flowing) {
                FluidState flow = flowing.getFlowing(7, false);
                if (!flow.isEmpty() && !flow.isSource()) {
                    states.add(flow);
                }
            }
        }
        return states;
    }

    public static ResourceLocation keyOf(FluidType type) {
        ResourceLocation key = NeoForgeRegistries.FLUID_TYPES.getKey(type);
        return key != null ? key : ResourceLocation.fromNamespaceAndPath("unknown", "unregistered");
    }

    private static ResourceLocation recipeId(ResourceLocation typeKey, int index, int variant) {
        return ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID,
                typeKey.getNamespace() + "/" + typeKey.getPath() + "/" + index + "/" + variant);
    }

    private static Component unable(FluidType type, @Nullable String owner) {
        if (owner == null) {
            return Component.translatable("jei." + JustEnoughFluidInteractions.MODID + ".fluid_interactions.unable", type.getDescription());
        }
        return Component.translatable("jei." + JustEnoughFluidInteractions.MODID + ".fluid_interactions.unable_from", type.getDescription(), owner);
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
}
