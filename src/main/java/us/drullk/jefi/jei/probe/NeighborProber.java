package us.drullk.jefi.jei.probe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.Config;
import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Discovers the hardening rules a fluid implements in its liquid block's update hooks rather than in
 * {@link FluidInteractionRegistry} or in the fluid's own spread code.
 *
 * <p>This is the channel a level uses first. Placing a block calls {@code onPlace} on it and
 * {@code neighborChanged} on everything around it, all on the tick of the placement;
 * {@code LiquidBlock.shouldSpreadLiquid} runs the interaction registry from exactly there, which is why a rule
 * written into a {@code LiquidBlock} subclass happens before the fluid's own scheduled spread tick ever runs.
 * The sandbox delivers no block updates, so neither {@link InteractionProber} nor {@link SpreadProber} can see
 * such a rule: the hooks have to be called directly.
 *
 * <p>Only a block declaring {@code neighborChanged}, {@code onPlace} or {@code updateShape} below
 * {@link LiquidBlock} is probed. What {@code LiquidBlock} itself does in those hooks is run the interaction
 * registry, which is {@link InteractionProber}'s tier, so a fluid whose block is a plain {@code LiquidBlock} has
 * nothing here. A rule living outside the block's own classes leaves no trace in its class chain, which is what
 * {@code forceProbe} is for.
 *
 * <p>The source fluid sits at {@link InteractionProber#ORIGIN} and one candidate fluid at a time sits directly
 * below it, beside it at {@link FluidInteractionRecipe#NEIGHBOR_OFFSET}, or directly above it. All three are
 * probed because these hooks, unlike the registry and unlike spread, are subject to no convention about which
 * direction they may look in.
 *
 * <p>Calling the hooks is a filter, not an answer: it says which arrangements are worth building and which block
 * owns the rule. What the arrangement produces comes from {@link Settler}, which builds it with a level's own
 * semantics and lets every channel run, the registry among them. It is also the measure the answer is held
 * against: a recipe here describes what the block's own hooks do, so an arrangement whose settled level holds
 * anything but what those hooks wrote was pre-empted by another channel and belongs to the rule that channel
 * runs.
 */
public final class NeighborProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The candidate position directly above the source, which only this tier ever reaches. */
    public static final BlockPos ABOVE_OFFSET = new BlockPos(0, 1, 0);

    private static final List<BlockPos> TARGET_OFFSETS =
            List.of(SpreadProber.BELOW_OFFSET, FluidInteractionRecipe.NEIGHBOR_OFFSET, ABOVE_OFFSET);

    /** Keeps a source that would otherwise fall out of the arrangement the hooks are asked about. */
    private static final BlockState FLOOR = Blocks.BEDROCK.defaultBlockState();

    /** The hooks a level calls on the tick a neighbor is placed, and therefore the ones this tier calls. */
    private static final Set<String> UPDATE_METHODS = Set.of("neighborChanged", "onPlace", "updateShape");

    /**
     * Where the class walk stops: {@link LiquidBlock} is the interaction registry's own channel and
     * {@code Block} is the inert default, so declarations at or above either are not a rule of the fluid's own.
     */
    private static final Set<String> BASE_BLOCKS = Set.of(
            "net.minecraft.world.level.block.LiquidBlock",
            "net.minecraft.world.level.block.Block",
            "net.minecraft.world.level.block.state.BlockBehaviour");

    private final SandboxLevel level;
    private final Settler settler;
    private final List<Placement> fluidCandidates;
    private final Set<String> forced;
    private final Map<Class<?>, Set<String>> declaredMethods = new HashMap<>();
    private int runs;
    private int skippedTypes;
    private int dropped;

    public NeighborProber(SandboxLevel level, Settler settler, List<Placement> fluidCandidates) {
        this.level = level;
        this.settler = settler;
        this.fluidCandidates = List.copyOf(fluidCandidates);
        this.forced = Set.copyOf(Config.FORCE_PROBE.get());
    }

    /** How many fluid types were left unprobed because no block of theirs declares an update hook of its own. */
    public int skippedTypes() {
        return skippedTypes;
    }

    /**
     * Every recipe one fluid type's block update hooks produce, ordered by owner as
     * {@link InteractionProber#OWNER_ORDER} ranks them, then by the candidate position in probe order, then by
     * the result block. A block that hardens nothing yields nothing; there are no failure recipes here.
     */
    public List<FluidInteractionRecipe> probe(FluidType type, List<FluidState> sources) {
        List<FluidState> probed = probable(sources);
        if (probed.isEmpty()) {
            skippedTypes++;
            return List.of();
        }
        long start = System.nanoTime();
        int before = runs;
        int lost = dropped;

        Map<Outcome, Group> outcomes = new LinkedHashMap<>();
        for (BlockPos target : TARGET_OFFSETS) {
            for (FluidState source : probed) {
                String owner = InteractionOwners.ofBlock(source.createLegacyBlock().getBlock());
                for (Placement candidate : fluidCandidates) {
                    Map<BlockPos, BlockState> results = settled(source, target, candidate);
                    if (results.isEmpty()) {
                        continue;
                    }
                    Group group = outcomes.computeIfAbsent(new Outcome(owner, target, results), k -> new Group());
                    group.sources.add(source);
                    group.neighbors.add(candidate);
                }
            }
        }
        outcomes.forEach((outcome, group) -> group.inert = inert(probed, outcome.target(), group));
        LOGGER.debug("Probed fluid neighbors of {} in {} ms over {} candidate run(s) of {} source state(s)",
                InteractionProber.keyOf(type), (System.nanoTime() - start) / 1_000_000, runs - before, probed.size());
        if (outcomes.isEmpty() && dropped > lost) {
            LOGGER.info("A level settles every one of the {} arrangement(s) the update hooks of {} write in without "
                    + "a result", dropped - lost, InteractionProber.keyOf(type));
        }
        return order(type, outcomes);
    }

    /**
     * The source states worth probing. Both forms of a fluid stand or fall together, so an outcome one form
     * produces can still record the other as inert.
     */
    private List<FluidState> probable(List<FluidState> sources) {
        Set<Fluid> probable = new LinkedHashSet<>();
        for (FluidState state : sources) {
            if (runsOwnCode(state)) {
                probable.add(FluidInteractionRecipe.stillForm(state));
            }
        }
        return sources.stream().filter(state -> probable.contains(FluidInteractionRecipe.stillForm(state))).toList();
    }

    /** Whether this state's block declares an update hook of its own; a configured fluid always counts. */
    private boolean runsOwnCode(FluidState state) {
        Fluid still = FluidInteractionRecipe.stillForm(state);
        if (forced.contains(String.valueOf(BuiltInRegistries.FLUID.getKey(still)))) {
            return true;
        }
        return !declaredUpdateMethods(state.createLegacyBlock().getBlock().getClass()).isEmpty();
    }

    /** The names from {@link #UPDATE_METHODS} a block's own classes declare, below the base classes. */
    private Set<String> declaredUpdateMethods(Class<?> blockClass) {
        return declaredMethods.computeIfAbsent(blockClass, key -> {
            Set<String> names = new LinkedHashSet<>();
            for (Class<?> type = key; type != null && !BASE_BLOCKS.contains(type.getName()); type = type.getSuperclass()) {
                try {
                    for (Method method : type.getDeclaredMethods()) {
                        if (UPDATE_METHODS.contains(method.getName())) {
                            names.add(method.getName());
                        }
                    }
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.debug("Reading the methods of {} failed; probing its fluid neighbors anyway", type.getName(), e);
                    return UPDATE_METHODS;
                }
            }
            return names;
        });
    }

    /**
     * The forms one outcome was also tried with that wrote nothing: source states of the same fluid that left
     * every one of the outcome's candidates as it was, and the other form of a candidate fluid that every one of
     * the outcome's source states left as it was.
     */
    private InertForms inert(List<FluidState> sources, BlockPos target, Group group) {
        List<FluidState> inertSources = new ArrayList<>();
        for (FluidState candidate : sources) {
            if (group.sources.contains(candidate) || !sharesFluid(candidate, group.sources)) {
                continue;
            }
            if (group.neighbors.stream().allMatch(neighbor -> settled(candidate, target, neighbor).isEmpty())) {
                inertSources.add(candidate);
            }
        }
        Set<Placement> inertNeighbors = new LinkedHashSet<>();
        for (Placement neighbor : group.neighbors) {
            Placement other = otherForm(neighbor);
            if (other == null || group.neighbors.contains(other)) {
                continue;
            }
            if (group.sources.stream().allMatch(source -> settled(source, target, other).isEmpty())) {
                inertNeighbors.add(other);
            }
        }
        return new InertForms(List.copyOf(inertSources), List.copyOf(inertNeighbors));
    }

    private static boolean sharesFluid(FluidState state, Set<FluidState> states) {
        Fluid still = FluidInteractionRecipe.stillForm(state);
        return states.stream().anyMatch(other -> FluidInteractionRecipe.stillForm(other) == still);
    }

    /** The candidate holding the same fluid as this one in its other form, or null when there is no such form. */
    private @Nullable Placement otherForm(Placement placement) {
        if (!placement.isFluid()) {
            return null;
        }
        Fluid still = FluidInteractionRecipe.stillForm(placement.effectiveFluid());
        for (Placement candidate : fluidCandidates) {
            if (candidate.isFluid()
                    && FluidInteractionRecipe.stillForm(candidate.effectiveFluid()) == still
                    && candidate.isFlowing() != placement.isFlowing()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * What a level leaves behind at one arrangement the source's update hooks write into. The direct hook calls
     * of {@link #run} decide whether the arrangement is worth building at all and what those hooks wrote there;
     * the answer always comes from {@link Settler}, so an alternative's outcome and whether a form is inert are
     * decided the same way. Nothing is left of an arrangement whose settled level holds something other than what
     * the hooks wrote: that outcome is another rule's, and so is the recipe describing it.
     */
    private Map<BlockPos, BlockState> settled(FluidState source, BlockPos target, Placement candidate) {
        Map<BlockPos, BlockState> wrote = run(source, target, candidate);
        if (wrote.isEmpty()) {
            return Map.of();
        }
        Settler.Outcome outcome = settler.settle(Settler.arrangement(source, target, candidate), target, wrote);
        if (outcome.preempted() != null) {
            dropped++;
            LOGGER.debug("A level pre-empts the update hooks of {} with {} at {}: {}",
                    source.getFluidType().getDescriptionId(), candidate.describe().getString(), target.toShortString(),
                    outcome.preempted());
            return Map.of();
        }
        if (outcome.results().isEmpty()) {
            dropped++;
            LOGGER.debug("A level settles {} with {} at {} without a result",
                    source.getFluidType().getDescriptionId(), candidate.describe().getString(), target.toShortString());
        }
        return outcome.results();
    }

    /**
     * Places one arrangement, calls the update hooks a level would call on the tick the candidate is placed, and
     * returns the writes that are a transformation rather than either fluid, keyed by offset from the source.
     *
     * <p>The source is told its neighbor changed and then that it was itself placed, which is the pair of hooks
     * {@code LiquidBlock} routes to the same rule. The candidate's hooks are never called: a plain
     * {@code LiquidBlock} would run the interaction registry, which {@link InteractionProber} already covers, and
     * any other block is probed as a source in its own turn, so a rule of its own is found there once.
     */
    private Map<BlockPos, BlockState> run(FluidState source, BlockPos target, Placement candidate) {
        runs++;
        BlockState sourceState = source.createLegacyBlock();
        if (sourceState.isAir()) {
            return Map.of();
        }
        Map<BlockPos, Placement> scene = new LinkedHashMap<>();
        scene.put(InteractionProber.ORIGIN, Placement.ofFluid(source));
        if (!source.isSource()) {
            FluidState feed = FluidInteractionRecipe.stillForm(source).defaultFluidState();
            if (feed.isSource()) {
                scene.put(InteractionProber.ORIGIN.offset(ABOVE_OFFSET), Placement.ofFluid(feed));
            }
        }
        if (!target.equals(SpreadProber.BELOW_OFFSET)) {
            scene.put(InteractionProber.ORIGIN.offset(SpreadProber.BELOW_OFFSET), Placement.ofBlock(FLOOR));
        }
        BlockPos candidatePos = InteractionProber.ORIGIN.offset(target);
        scene.put(candidatePos, candidate);

        level.reset();
        scene.forEach(level::place);
        level.beginTracking();
        try {
            BlockState air = Blocks.AIR.defaultBlockState();
            sourceState.handleNeighborChanged(level, InteractionProber.ORIGIN, candidate.block().getBlock(), candidatePos, false);
            sourceState.onPlace(level, InteractionProber.ORIGIN, air, false);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("{} beside {} threw while probing fluid neighbors",
                    source.getFluidType().getDescriptionId(), candidate.describe().getString(), e);
            return Map.of();
        } finally {
            level.endTracking();
        }

        Fluid still = FluidInteractionRecipe.stillForm(source);
        Fluid candidateStill = candidate.isFluid() ? FluidInteractionRecipe.stillForm(candidate.effectiveFluid()) : null;
        Map<BlockPos, BlockState> results = new LinkedHashMap<>();
        level.writes().forEach((pos, written) -> {
            Placement placed = scene.get(pos);
            BlockState was = placed != null ? placed.block() : Blocks.AIR.defaultBlockState();
            if (transformed(was, written, still, candidateStill)) {
                results.put(pos.subtract(InteractionProber.ORIGIN), written);
            }
        });
        return results;
    }

    /**
     * Whether a write is a transformation of the position rather than one of the two fluids in the arrangement
     * settling there: a hook that rewrites either fluid has changed nothing a recipe could show.
     */
    private static boolean transformed(BlockState placed, BlockState written, Fluid source, @Nullable Fluid candidate) {
        if (written.isAir() || written.equals(placed)) {
            return false;
        }
        FluidState fluid = written.getFluidState();
        if (fluid.isEmpty()) {
            return true;
        }
        Fluid still = FluidInteractionRecipe.stillForm(fluid);
        return still != source && still != candidate;
    }

    private List<FluidInteractionRecipe> order(FluidType type, Map<Outcome, Group> outcomes) {
        Map<String, List<Map.Entry<Outcome, Group>>> byOwner = new LinkedHashMap<>();
        outcomes.entrySet().forEach(entry -> byOwner.computeIfAbsent(entry.getKey().owner(), k -> new ArrayList<>()).add(entry));
        List<String> owners = new ArrayList<>(byOwner.keySet());
        owners.sort(InteractionProber.OWNER_ORDER);

        ResourceLocation typeKey = InteractionProber.keyOf(type);
        List<FluidInteractionRecipe> ordered = new ArrayList<>();
        for (String owner : owners) {
            List<Map.Entry<Outcome, Group>> group = byOwner.get(owner);
            group.sort(WITHIN_OWNER_ORDER);
            String ownerSegment = InteractionProber.ownerSegment(owner);
            int n = -1;
            int variant = 0;
            BlockPos target = null;
            for (var entry : group) {
                Outcome outcome = entry.getKey();
                if (!outcome.target().equals(target)) {
                    target = outcome.target();
                    n++;
                    variant = 0;
                }
                ordered.add(new FluidInteractionRecipe(type, -1, recipeId(typeKey, ownerSegment, n, variant++),
                        List.copyOf(entry.getValue().sources), List.copyOf(entry.getValue().neighbors),
                        outcome.target(), Map.of(), outcome.results(), null, owner, entry.getValue().inert));
            }
        }
        return ordered;
    }

    /**
     * Neighbor-discovered ids carry their own leading segment, so adding this tier leaves every id the other two
     * produce — and every bookmark holding one — untouched.
     */
    private static ResourceLocation recipeId(ResourceLocation typeKey, String ownerSegment, int n, int variant) {
        return ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID,
                "neighbor/" + typeKey.getNamespace() + "/" + typeKey.getPath() + "/" + ownerSegment + "/" + n + "/" + variant);
    }

    private static final Comparator<Map.Entry<Outcome, Group>> WITHIN_OWNER_ORDER = Comparator
            .<Map.Entry<Outcome, Group>>comparingInt(entry -> TARGET_OFFSETS.indexOf(entry.getKey().target()))
            .thenComparing(entry -> resultKey(entry.getKey()), Comparator.nullsLast(InteractionProber.LOCATION_ORDER))
            .thenComparing(entry -> describe(entry.getKey().results()));

    private static @Nullable ResourceLocation resultKey(Outcome outcome) {
        BlockState state = outcome.results().get(BlockPos.ZERO);
        return state != null ? BuiltInRegistries.BLOCK.getKey(state.getBlock()) : null;
    }

    /** A stable textual form of a result set, so two outcomes with the same primary result still order the same. */
    private static String describe(Map<BlockPos, BlockState> results) {
        List<String> parts = new ArrayList<>(results.size());
        results.forEach((pos, state) -> parts.add(pos.toShortString() + "=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())));
        parts.sort(Comparator.naturalOrder());
        return String.join(",", parts);
    }

    private record Outcome(@Nullable String owner, BlockPos target, Map<BlockPos, BlockState> results) {
    }

    /** One outcome can be reached by several source states and several candidates, so both stay unique. */
    private static final class Group {
        final Set<FluidState> sources = new LinkedHashSet<>();
        final Set<Placement> neighbors = new LinkedHashSet<>();
        InertForms inert = InertForms.NONE;
    }
}
