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
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.Config;
import us.drullk.jefi.jei.sandbox.SandboxLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * A discovery tier that calls one rule of a fluid by hand.
 *
 * <p>The source fluid sits at {@link SandboxLevel#ORIGIN} and one candidate sits at one target position. The
 * tier calls the rule's hook in the quiet sandbox and reads what the hook wrote. A write that is not air and not
 * a state of a fluid in the arrangement is a hit. The hook call is a filter and not an answer. {@link Settler}
 * builds every hit with the semantics of a level. What the level settles on is the recipe. The writes of the
 * hook are also the measure. The settled level can hold something different where the hook wrote. A different
 * rule then owns that outcome, and the tier drops the arrangement.
 *
 * <p>The tier probes only a fluid whose own classes declare the hook ({@link #declaresHook}). The config value
 * {@code forceProbe} adds fluids whose rule lives outside their classes, such as a mixin.
 *
 * <p>Per recipe, the tier records the forms it tried at the same arrangement that produced nothing
 * ({@link InertForms}). These are the other source form of the recipe's fluid, and the other form of a neighbor
 * fluid.
 */
public abstract class RuleProber {
    /** The target directly below the source, where vanilla lava changes water into stone. */
    public static final BlockPos BELOW_OFFSET = new BlockPos(0, -1, 0);
    /** The target directly above the source. Only the neighbor tier probes it. */
    public static final BlockPos ABOVE_OFFSET = new BlockPos(0, 1, 0);

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Keeps the source in place: a fluid spreads sideways only when it can not fall. Never part of a recipe. */
    private static final BlockState FLOOR = Blocks.BEDROCK.defaultBlockState();

    protected final Logger logger;
    private final SandboxLevel level;
    private final Settler settler;
    private final List<Placement> fluidCandidates;
    private final Set<String> forced;
    /** The tier's name in log lines, such as "fluid spread". */
    private final String tier;
    /** The rule's name in log lines, such as "the fluid spread of". */
    private final String rule;
    private final String idPrefix;
    private final Map<Class<?>, Set<String>> declaredMethods = new HashMap<>();
    /** The settled result of every arrangement of the type in progress. The inert check reads it. */
    private final Map<Arrangement, Map<BlockPos, BlockState>> settled = new HashMap<>();
    /** The sweeps of the type in progress, one per source form and target. */
    private final Map<Target, Sweep> sweeps = new HashMap<>();
    private static final Placement NOTHING = Placement.ofBlock(AIR);
    private final Comparator<Map.Entry<Outcome, Group>> withinOwner = Comparator
            .<Map.Entry<Outcome, Group>>comparingInt(entry -> targets().indexOf(entry.getKey().target()))
            .thenComparing(entry -> primaryResultKey(entry.getKey()), Comparator.nullsLast(RecipeIds.LOCATION_ORDER))
            .thenComparing(entry -> describe(entry.getKey().results()));
    private int runs;
    private int skippedRuns;
    private int skippedTypes;
    private int dropped;

    protected RuleProber(Logger logger, SandboxLevel level, Settler settler, List<Placement> fluidCandidates,
                         String tier, String rule, String idPrefix) {
        this.logger = logger;
        this.level = level;
        this.settler = settler;
        this.fluidCandidates = List.copyOf(fluidCandidates);
        this.forced = Set.copyOf(Config.FORCE_PROBE.get());
        this.tier = tier;
        this.rule = rule;
        this.idPrefix = idPrefix;
    }

    /** The target positions, in probe order. The order numbers the recipes. */
    abstract List<BlockPos> targets();

    /** Whether the own classes of this source state declare the hook. */
    abstract boolean declaresHook(FluidState state);

    /** The candidates offered to this source. Every fluid candidate is always among them. */
    abstract List<Placement> candidates(FluidState source, List<FluidState> probed);

    abstract @Nullable String owner(FluidState source);

    /** Calls the hook on the source. The sandbox is quiet and records the writes. */
    abstract void callHook(SandboxLevel level, FluidState source, BlockPos target, Placement candidate);

    /** The fluids whose own states are not a result. A hook that writes one of them changes nothing a recipe shows. */
    abstract Set<Fluid> ownFluids(FluidState source, Placement candidate);

    /** The offset whose result block orders the outcomes of one target. */
    abstract BlockPos primaryResultOffset(BlockPos target);

    /**
     * Whether the hook learns the candidate only through reads of the level. Then an earlier run at the target
     * can answer for a later candidate ({@link RunMemo}). A hook that receives the candidate as an argument
     * needs a run of its own for every candidate.
     */
    boolean readsTargetThroughLevel(FluidState source) {
        return true;
    }

    /** Runs for every recipe the tier produces. */
    void onRecipe(FluidInteractionRecipe recipe) {
    }

    protected List<Placement> fluidCandidates() {
        return fluidCandidates;
    }

    /** How many fluid types were not probed because no fluid of theirs declares the hook. */
    public int skippedTypes() {
        return skippedTypes;
    }

    /** The candidate runs an earlier run at the same target answered for. */
    int skippedRuns() {
        return skippedRuns;
    }

    /** Whether the config names this fluid. The config makes every tier probe the fluid. */
    protected boolean forced(Fluid still) {
        return forced.contains(String.valueOf(BuiltInRegistries.FLUID.getKey(still)));
    }

    /**
     * The names from {@code hooks} that the own classes of {@code start} declare. The walk stops at the first
     * class {@code isBase} accepts. That class declares the behavior the fluid inherits.
     */
    protected Set<String> declared(Class<?> start, Set<String> hooks, Predicate<Class<?>> isBase) {
        return declaredMethods.computeIfAbsent(start, key -> {
            Set<String> names = new LinkedHashSet<>();
            for (Class<?> type = key; type != null && !isBase.test(type); type = type.getSuperclass()) {
                try {
                    for (Method method : type.getDeclaredMethods()) {
                        if (hooks.contains(method.getName())) {
                            names.add(method.getName());
                        }
                    }
                } catch (RuntimeException | LinkageError e) {
                    logger.debug("Reading the methods of {} failed; probing its {} anyway", type.getName(), tier, e);
                    return hooks;
                }
            }
            return names;
        });
    }

    /**
     * Every recipe the rule of one fluid type produces. Owner groups rank in {@link RecipeIds#OWNER_ORDER},
     * then the target position in probe order, then the result block. A rule that changes nothing gives nothing.
     * There are no failure recipes here.
     */
    public List<FluidInteractionRecipe> probe(FluidType type, List<FluidState> sources) {
        List<FluidState> probed = probable(sources);
        if (probed.isEmpty()) {
            skippedTypes++;
            return List.of();
        }
        long start = System.nanoTime();
        int runsBefore = runs;
        int droppedBefore = dropped;
        settled.clear();
        sweeps.clear();

        Map<Outcome, Group> outcomes = new LinkedHashMap<>();
        for (BlockPos target : targets()) {
            for (FluidState source : probed) {
                String owner = owner(source);
                Sweep sweep = sweep(source, target);
                List<Placement> candidates = candidates(source, probed);
                for (int i = 0; i < candidates.size(); i++) {
                    Placement candidate = candidates.get(i);
                    Map<BlockPos, BlockState> wrote = run(sweep, candidate);
                    if (wrote.isEmpty()) {
                        if (sweep.answersRest()) {
                            skippedRuns += candidates.size() - i - 1;
                            break;
                        }
                        continue;
                    }
                    Map<BlockPos, BlockState> results = settled.computeIfAbsent(new Arrangement(source, target, candidate),
                            key -> settle(source, target, candidate, wrote));
                    if (!results.isEmpty()) {
                        Group group = outcomes.computeIfAbsent(new Outcome(owner, target, results), k -> new Group());
                        group.sources.add(source);
                        group.neighbors.add(candidate);
                    }
                }
            }
        }
        outcomes.forEach((outcome, group) -> group.inert = inert(probed, outcome.target(), group));
        logProbed(type, start, runs - runsBefore, probed.size(), dropped - droppedBefore, outcomes.isEmpty());
        return order(type, outcomes);
    }

    private void logProbed(FluidType type, long start, int runs, int sources, int dropped, boolean nothing) {
        logger.debug("Probed {} of {} in {} ms over {} candidate run(s) of {} source state(s)",
                tier, RecipeIds.keyOf(type), (System.nanoTime() - start) / 1_000_000, runs, sources);
        if (nothing && dropped > 0) {
            logger.info("A level settles every one of the {} arrangement(s) {} {} writes in without a result",
                    dropped, rule, RecipeIds.keyOf(type));
        }
    }

    /** The source states to probe. Both forms of a fluid stand or fall together, so one form can be inert. */
    private List<FluidState> probable(List<FluidState> sources) {
        Set<Fluid> probable = new LinkedHashSet<>();
        for (FluidState state : sources) {
            Fluid still = FluidInteractionRecipe.stillForm(state);
            if (forced(still) || declaresHook(state)) {
                probable.add(still);
            }
        }
        return sources.stream().filter(state -> probable.contains(FluidInteractionRecipe.stillForm(state))).toList();
    }

    /**
     * The forms the tier also tried at one outcome and that wrote nothing. These are source states of the same
     * fluid that left every neighbor of the outcome as it was. They are also the other form of a neighbor fluid
     * that every source state of the outcome left as it was.
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

    /** The candidate that holds the same fluid in its other form, or null when there is no other form. */
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

    /** What a level leaves behind at one arrangement, settled once per type and read from then on. */
    private Map<BlockPos, BlockState> settled(FluidState source, BlockPos target, Placement candidate) {
        return settled.computeIfAbsent(new Arrangement(source, target, candidate), key -> {
            Map<BlockPos, BlockState> wrote = run(sweep(source, target), candidate);
            return wrote.isEmpty() ? Map.of() : settle(source, target, candidate, wrote);
        });
    }

    private Sweep sweep(FluidState source, BlockPos target) {
        return sweeps.computeIfAbsent(new Target(source, target), key -> new Sweep(source, target));
    }

    /**
     * What a level leaves behind at one arrangement the hook writes in. The hook call of {@link #run} decides
     * whether the arrangement is worth building. The answer comes from {@link Settler}. So one mechanism decides
     * the outcome of an alternative and whether a form is inert. The settled level of an arrangement can hold
     * something different where the hook wrote. That arrangement belongs to a different rule, so the answer for
     * it is empty.
     */
    private Map<BlockPos, BlockState> settle(FluidState source, BlockPos target, Placement candidate, Map<BlockPos, BlockState> wrote) {
        Settler.Outcome outcome = settler.settle(content(source, target, candidate), target, wrote);
        if (outcome.preempted() != null) {
            dropped++;
            logger.debug("A level pre-empts {} {} with {} at {}: {}", rule, source.getFluidType().getDescriptionId(),
                    candidate.describe().getString(), target.toShortString(), outcome.preempted());
            return Map.of();
        }
        if (outcome.results().isEmpty()) {
            dropped++;
            logger.debug("A level settles {} {} with {} at {} without a result", rule,
                    source.getFluidType().getDescriptionId(), candidate.describe().getString(), target.toShortString());
        }
        return outcome.results();
    }

    /** The arrangement the tier describes: the source, and one candidate at the probed target. */
    static Map<BlockPos, Placement> content(FluidState source, BlockPos target, Placement candidate) {
        Map<BlockPos, Placement> content = new LinkedHashMap<>();
        content.put(BlockPos.ZERO, Placement.ofFluid(source));
        content.put(target, candidate);
        return content;
    }

    /**
     * Places one arrangement and calls the hook, unless an earlier run at the target already answers for this
     * candidate ({@link RunMemo}). Returns the writes that change a position, keyed by offset from the source.
     * A write of air, of what the tier placed, or of an own fluid's state is not a change.
     *
     * <p>A source form whose own classes declare no hook is probed because its other form does. Its code is all
     * inherited, and inherited code writes only the fluid's own states. So that form changes nothing at any
     * arrangement, and it is inert without a run.
     */
    private Map<BlockPos, BlockState> run(Sweep sweep, Placement candidate) {
        if (!sweep.runs) {
            return Map.of();
        }
        Map<BlockPos, BlockState> writes = sweep.memo != null ? sweep.memo.lookup(candidate) : null;
        if (writes != null) {
            skippedRuns++;
        } else {
            writes = callHook(sweep, candidate);
            if (writes == null) {
                return Map.of();
            }
            if (sweep.memo != null) {
                sweep.memo.record(candidate, level.watchedReads(), writes);
            }
        }
        if (writes.isEmpty()) {
            return Map.of();
        }
        return filter(writes, sweep, candidate, ownFluids(sweep.source, candidate));
    }

    /** The writes that change a position. The keys are offsets from the source. */
    private static Map<BlockPos, BlockState> filter(Map<BlockPos, BlockState> writes, Sweep sweep,
                                                    @Nullable Placement candidate, Set<Fluid> own) {
        Map<BlockPos, BlockState> results = null;
        for (var entry : writes.entrySet()) {
            Placement placed = candidate != null && entry.getKey().equals(sweep.targetPos) ? candidate : sweep.fixed.get(entry.getKey());
            if (transformed(placed != null ? placed.block() : AIR, entry.getValue(), own)) {
                if (results == null) {
                    results = new LinkedHashMap<>();
                }
                results.put(entry.getKey().subtract(SandboxLevel.ORIGIN), entry.getValue());
            }
        }
        return results != null ? results : Map.of();
    }

    /** Places the scene, calls the hook with the target watched, and returns every write. Null when the hook threw. */
    private @Nullable Map<BlockPos, BlockState> callHook(Sweep sweep, Placement candidate) {
        runs++;
        level.reset();
        sweep.fixed.forEach(level::place);
        level.place(sweep.targetPos, candidate);
        level.beginTracking();
        level.watch(sweep.targetPos);
        try {
            callHook(level, sweep.source, sweep.target, candidate);
        } catch (RuntimeException | LinkageError e) {
            logger.debug("{} with {} threw while probing {}", sweep.source.getFluidType().getDescriptionId(),
                    candidate.describe().getString(), tier, e);
            return null;
        } finally {
            level.endTracking();
        }
        return level.wroteNothing() ? Map.of() : level.writes();
    }

    /**
     * The runs of one source form at one target. All candidates of the sweep use one scene. The source is at
     * the origin. A flowing source has a still source above it. Bedrock is below the source. If the target is
     * below the source, the candidate is there and not the bedrock. The sweep also keeps what the hook reads at
     * the target. Then one run can give the answer for a later candidate.
     */
    private final class Sweep {
        final FluidState source;
        final BlockPos target;
        final BlockPos targetPos;
        /** True when the config forces this source form or when its own classes declare the hook. */
        final boolean runs;
        final Map<BlockPos, Placement> fixed = new LinkedHashMap<>();
        final @Nullable RunMemo<Map<BlockPos, BlockState>> memo;

        Sweep(FluidState source, BlockPos target) {
            this.source = source;
            this.target = target;
            this.targetPos = SandboxLevel.ORIGIN.offset(target);
            this.runs = forced(FluidInteractionRecipe.stillForm(source)) || declaresHook(source);
            this.memo = readsTargetThroughLevel(source) ? new RunMemo<>() : null;
            fixed.put(SandboxLevel.ORIGIN, Placement.ofFluid(source));
            if (!source.isSource()) {
                FluidState feed = FluidInteractionRecipe.stillForm(source).defaultFluidState();
                if (feed.isSource()) {
                    fixed.put(SandboxLevel.ORIGIN.offset(ABOVE_OFFSET), Placement.ofFluid(feed));
                }
            }
            if (!target.equals(BELOW_OFFSET)) {
                fixed.put(SandboxLevel.ORIGIN.offset(BELOW_OFFSET), Placement.ofBlock(FLOOR));
            }
        }

        /**
         * Tells whether one earlier run gives the answer "nothing" for all candidates that are still to come. A
         * run that does not read the target gives the same writes for all candidates. If those writes change
         * nothing with an empty target, they change nothing with a candidate at the target. A candidate can only
         * make a write equal to what is there.
         */
        boolean answersRest() {
            Map<BlockPos, BlockState> shared = memo != null ? memo.unread() : null;
            return shared != null && filter(shared, this, null, ownFluids(source, NOTHING)).isEmpty();
        }
    }

    private static boolean transformed(BlockState placed, BlockState written, Set<Fluid> own) {
        if (written.isAir() || written.equals(placed)) {
            return false;
        }
        FluidState fluid = written.getFluidState();
        return fluid.isEmpty() || !own.contains(FluidInteractionRecipe.stillForm(fluid));
    }

    private List<FluidInteractionRecipe> order(FluidType type, Map<Outcome, Group> outcomes) {
        Map<String, List<Map.Entry<Outcome, Group>>> byOwner = new LinkedHashMap<>();
        outcomes.entrySet().forEach(entry -> byOwner.computeIfAbsent(entry.getKey().owner(), k -> new ArrayList<>()).add(entry));
        List<String> owners = new ArrayList<>(byOwner.keySet());
        owners.sort(RecipeIds.OWNER_ORDER);

        List<FluidInteractionRecipe> ordered = new ArrayList<>();
        for (String owner : owners) {
            List<Map.Entry<Outcome, Group>> group = byOwner.get(owner);
            group.sort(withinOwner);
            ordered.addAll(numbered(type, owner, group));
        }
        return ordered;
    }

    /** The recipes of one owner group. {@code n} counts the targets and {@code variant} the outcomes at one. */
    private List<FluidInteractionRecipe> numbered(FluidType type, @Nullable String owner, List<Map.Entry<Outcome, Group>> group) {
        ResourceLocation typeKey = RecipeIds.keyOf(type);
        String ownerSegment = RecipeIds.ownerSegment(owner);
        List<FluidInteractionRecipe> recipes = new ArrayList<>(group.size());
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
            FluidInteractionRecipe recipe = new FluidInteractionRecipe(type,
                    RecipeIds.id(idPrefix, typeKey, ownerSegment, n, variant++),
                    List.copyOf(entry.getValue().sources), List.copyOf(entry.getValue().neighbors),
                    outcome.target(), Map.of(), outcome.results(), null, owner, entry.getValue().inert);
            onRecipe(recipe);
            recipes.add(recipe);
        }
        return recipes;
    }

    private @Nullable ResourceLocation primaryResultKey(Outcome outcome) {
        return RecipeIds.blockKey(outcome.results().get(primaryResultOffset(outcome.target())));
    }

    /** A stable text of a result set, so two outcomes with the same primary result still order the same. */
    private static String describe(Map<BlockPos, BlockState> results) {
        List<String> parts = new ArrayList<>(results.size());
        results.forEach((pos, state) -> parts.add(pos.toShortString() + "=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())));
        parts.sort(Comparator.naturalOrder());
        return String.join(",", parts);
    }

    private record Arrangement(FluidState source, BlockPos target, Placement candidate) {
    }

    /** One source form at one target: the runs that share a memo. */
    private record Target(FluidState source, BlockPos target) {
    }

    private record Outcome(@Nullable String owner, BlockPos target, Map<BlockPos, BlockState> results) {
    }

    /** Several source states and several candidates can reach one outcome, so both stay unique. */
    private static final class Group {
        final Set<FluidState> sources = new LinkedHashSet<>();
        final Set<Placement> neighbors = new LinkedHashSet<>();
        InertForms inert = InertForms.NONE;
    }
}
