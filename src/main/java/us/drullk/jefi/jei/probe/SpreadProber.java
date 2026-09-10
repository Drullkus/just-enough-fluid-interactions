package us.drullk.jefi.jei.probe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
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
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Discovers the hardening rules a fluid implements in its own spread code instead of registering with
 * {@link FluidInteractionRegistry}, by ticking the fluid in a {@link SandboxLevel} and watching what it writes.
 *
 * <p>Vanilla's stone is the reason this exists: lava flowing down onto water becomes stone inside
 * {@code LavaFluid.spreadTo}, which no registry entry describes. Any fluid overriding {@code spreadTo},
 * {@code canSpreadTo} or its liquid block is equally invisible to {@link InteractionProber}.
 *
 * <p>Looking below the source is the convention rather than a workaround: {@link FluidInteractionRegistry}'s own
 * javadoc states that it tests every direction except down and that any fluid causing a change in the down
 * interaction must handle it in {@code FlowingFluid#spreadTo}. NeoForge closed the request to move vanilla's
 * stone into the registry (issue 1880) as intended behavior.
 *
 * <p>Only a fluid that declares spread code of its own below {@code FlowingFluid} and NeoForge's
 * {@code BaseFlowingFluid} is ticked: what those two classes do with a spreading fluid is place it, and placing
 * the fluid is never a result. Vanilla's fluids qualify by implementing {@code beforeDestroyingBlock} themselves,
 * as does every fluid built on {@code FlowingFluid} directly, and a mixin into one of those classes shows up as a
 * declaration too. A mixin into {@code FlowingFluid} itself does not, which is what {@code forceSpreadProbe} is
 * for.
 *
 * <p>The source fluid sits at {@link InteractionProber#ORIGIN} and one candidate at a time sits at a target
 * position — directly below the source, or beside it at {@link FluidInteractionRecipe#NEIGHBOR_OFFSET}. Ticking
 * spreads the fluid, so most writes are the fluid arriving somewhere: a write only counts as a result when it
 * changes a position to a state that is neither air nor a state of the source fluid itself.
 */
public final class SpreadProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The target directly below the source, where vanilla lava turns water into stone. */
    public static final BlockPos BELOW_OFFSET = new BlockPos(0, -1, 0);

    private static final BlockPos ABOVE_OFFSET = new BlockPos(0, 1, 0);
    private static final List<BlockPos> TARGET_OFFSETS = List.of(BELOW_OFFSET, FluidInteractionRecipe.NEIGHBOR_OFFSET);

    /**
     * Stops the source falling straight through, which is the only way a fluid ever spreads sideways. It is a
     * fixture of the probe rather than a requirement of the rule, so it never becomes part of a recipe.
     */
    private static final BlockState FLOOR = Blocks.BEDROCK.defaultBlockState();

    /** The methods a fluid has to declare before it can write anything but its own states while it spreads. */
    private static final Set<String> SPREAD_METHODS =
            Set.of("tick", "spread", "spreadTo", "canSpreadTo", "getNewLiquid", "beforeDestroyingBlock");

    /**
     * The subset of {@link #SPREAD_METHODS} that decides where a fluid may go at all. A fluid declaring none of
     * them reaches only what vanilla's gate lets it reach, so it is offered just the blocks that gate accepts.
     */
    private static final Set<String> REACH_METHODS = Set.of("tick", "spread", "canSpreadTo");

    /**
     * Where the class walk stops: these declare the spread behaviour a fluid inherits, which is the behaviour the
     * walk asks whether the fluid departs from.
     */
    private static final Set<String> BASE_FLUIDS = Set.of(
            "net.minecraft.world.level.material.Fluid", "net.minecraft.world.level.material.FlowingFluid");
    private static final String NEOFORGE_BASE_FLUID = "net.neoforged.neoforge.fluids.BaseFlowingFluid";

    private final SandboxLevel level;
    private final List<Placement> fluidCandidates;
    /** Every fluid, plus the blocks vanilla's spread gate lets a fluid enter. */
    private final List<Placement> reachableCandidates;
    /** Every fluid and every block, for fluids that decide for themselves where they may spread. */
    private final List<Placement> allCandidates;
    private final Set<String> forced;
    private final Map<Class<?>, Set<String>> declaredMethods = new HashMap<>();
    private int runs;
    private int skippedTypes;

    public SpreadProber(SandboxLevel level, List<Placement> fluidCandidates, List<Placement> blockCandidates) {
        this.level = level;
        this.fluidCandidates = List.copyOf(fluidCandidates);
        List<Placement> reachableBlocks = blockCandidates.stream().filter(SpreadProber::canHoldFluid).toList();
        this.reachableCandidates = concat(fluidCandidates, reachableBlocks);
        this.allCandidates = concat(fluidCandidates, blockCandidates);
        this.forced = Set.copyOf(Config.FORCE_SPREAD_PROBE.get());
        LOGGER.debug("Fluid spread candidates: {} fluid state(s) and {} of {} block state(s) a fluid can enter",
                fluidCandidates.size(), reachableBlocks.size(), blockCandidates.size());
    }

    private static List<Placement> concat(List<Placement> fluids, List<Placement> blocks) {
        List<Placement> all = new ArrayList<>(fluids.size() + blocks.size());
        all.addAll(fluids);
        all.addAll(blocks);
        return List.copyOf(all);
    }

    /**
     * Whether vanilla's spread gate would let a fluid into this state: it holds fluid itself, or it does not block
     * movement, which is what {@code FlowingFluid.canSpreadTo} asks of every block a fluid enters.
     */
    private static boolean canHoldFluid(Placement placement) {
        BlockState state = placement.block();
        return state.getBlock() instanceof LiquidBlockContainer || !state.blocksMotion();
    }

    /** How many fluid types were left unticked because no fluid of theirs runs spread code of its own. */
    public int skippedTypes() {
        return skippedTypes;
    }

    /**
     * Every recipe the spread of one fluid type produces, ordered by owner as {@link InteractionProber#OWNER_ORDER}
     * ranks them, then by the target position in probe order, then by the result block. {@link InteractionProber}
     * folds these into the type's registry-derived recipes owner group by owner group. A fluid that hardens
     * nothing yields nothing; there are no failure recipes here.
     */
    public List<FluidInteractionRecipe> probe(FluidType type, List<FluidState> sources) {
        List<FluidState> probed = probable(sources);
        if (probed.isEmpty()) {
            skippedTypes++;
            return List.of();
        }
        Set<Fluid> unrestricted = unrestricted(probed);
        long start = System.nanoTime();
        int before = runs;

        Map<Outcome, Group> outcomes = new LinkedHashMap<>();
        for (BlockPos target : TARGET_OFFSETS) {
            for (FluidState source : probed) {
                Fluid still = FluidInteractionRecipe.stillForm(source);
                String owner = InteractionOwners.ofFluid(still);
                for (Placement candidate : unrestricted.contains(still) ? allCandidates : reachableCandidates) {
                    Map<BlockPos, BlockState> results = run(source, target, candidate);
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
        LOGGER.debug("Probed fluid spread of {} in {} ms over {} candidate run(s) of {} source state(s)",
                InteractionProber.keyOf(type), (System.nanoTime() - start) / 1_000_000, runs - before, probed.size());
        return order(type, outcomes);
    }

    /**
     * The source states worth ticking. Both forms of a fluid stand or fall together, so an outcome one form
     * produces can still record the other as inert.
     */
    private List<FluidState> probable(List<FluidState> sources) {
        Set<Fluid> probable = new LinkedHashSet<>();
        for (FluidState state : sources) {
            if (runsOwnCode(state.getType(), SPREAD_METHODS)) {
                probable.add(FluidInteractionRecipe.stillForm(state));
            }
        }
        return sources.stream().filter(state -> probable.contains(FluidInteractionRecipe.stillForm(state))).toList();
    }

    /** The fluids among these states that decide for themselves where they may spread. */
    private Set<Fluid> unrestricted(List<FluidState> sources) {
        Set<Fluid> fluids = new LinkedHashSet<>();
        for (FluidState state : sources) {
            if (runsOwnCode(state.getType(), REACH_METHODS)) {
                fluids.add(FluidInteractionRecipe.stillForm(state));
            }
        }
        return fluids;
    }

    /**
     * Whether this fluid runs spread code of its own from the given set. A configured fluid always counts, since
     * a rule living outside the fluid's own classes leaves no trace in its class chain.
     */
    private boolean runsOwnCode(Fluid fluid, Set<String> methods) {
        if (forced.contains(String.valueOf(BuiltInRegistries.FLUID.getKey(fluid)))) {
            return true;
        }
        return !Collections.disjoint(declaredSpreadMethods(fluid.getClass()), methods);
    }

    /** The names from {@link #SPREAD_METHODS} a fluid's own classes declare, below the two base classes. */
    private Set<String> declaredSpreadMethods(Class<?> fluidClass) {
        return declaredMethods.computeIfAbsent(fluidClass, key -> {
            Set<String> names = new LinkedHashSet<>();
            for (Class<?> type = key; type != null && !isBaseFluid(type); type = type.getSuperclass()) {
                try {
                    for (Method method : type.getDeclaredMethods()) {
                        if (SPREAD_METHODS.contains(method.getName())) {
                            names.add(method.getName());
                        }
                    }
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.debug("Reading the methods of {} failed; probing its fluid spread anyway", type.getName(), e);
                    return SPREAD_METHODS;
                }
            }
            return names;
        });
    }

    private static boolean isBaseFluid(Class<?> type) {
        String name = type.getName();
        return BASE_FLUIDS.contains(name) || name.startsWith(NEOFORGE_BASE_FLUID);
    }

    /**
     * The forms one outcome was also tried with that wrote nothing: source states of the same fluid that left
     * every one of the outcome's neighbors as it was, and the other form of a neighbor fluid that every one of
     * the outcome's source states left as it was. Only a finished outcome names the arrangements the question is
     * about, so these runs happen here rather than being remembered from the sweep above.
     */
    private InertForms inert(List<FluidState> sources, BlockPos target, Group group) {
        List<FluidState> inertSources = new ArrayList<>();
        for (FluidState candidate : sources) {
            if (group.sources.contains(candidate) || !sharesFluid(candidate, group.sources)) {
                continue;
            }
            if (group.neighbors.stream().allMatch(neighbor -> run(candidate, target, neighbor).isEmpty())) {
                inertSources.add(candidate);
            }
        }
        Set<Placement> inertNeighbors = new LinkedHashSet<>();
        for (Placement neighbor : group.neighbors) {
            Placement other = otherForm(neighbor);
            if (other == null || group.neighbors.contains(other)) {
                continue;
            }
            if (group.sources.stream().allMatch(source -> run(source, target, other).isEmpty())) {
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
     * Places one arrangement, ticks the source, and returns the writes that are a transformation rather than the
     * fluid spreading, keyed by offset from the source.
     */
    private Map<BlockPos, BlockState> run(FluidState source, BlockPos target, Placement candidate) {
        runs++;
        Map<BlockPos, Placement> scene = new LinkedHashMap<>();
        scene.put(InteractionProber.ORIGIN, Placement.ofFluid(source));
        if (!source.isSource()) {
            FluidState feed = FluidInteractionRecipe.stillForm(source).defaultFluidState();
            if (feed.isSource()) {
                scene.put(InteractionProber.ORIGIN.offset(ABOVE_OFFSET), Placement.ofFluid(feed));
            }
        }
        if (!target.equals(BELOW_OFFSET)) {
            scene.put(InteractionProber.ORIGIN.offset(BELOW_OFFSET), Placement.ofBlock(FLOOR));
        }
        scene.put(InteractionProber.ORIGIN.offset(target), candidate);

        level.reset();
        scene.forEach(level::place);
        level.beginTracking();
        try {
            source.tick(level, InteractionProber.ORIGIN);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("Ticking {} beside {} threw while probing fluid spread",
                    source.getFluidType().getDescriptionId(), candidate.describe().getString(), e);
            return Map.of();
        } finally {
            level.endTracking();
        }

        Fluid still = FluidInteractionRecipe.stillForm(source);
        Map<BlockPos, BlockState> results = new LinkedHashMap<>();
        level.writes().forEach((pos, written) -> {
            Placement placed = scene.get(pos);
            if (transformed(placed != null ? placed.block() : Blocks.AIR.defaultBlockState(), written, still)) {
                results.put(pos.subtract(InteractionProber.ORIGIN), written);
            }
        });
        return results;
    }

    /**
     * Whether a write is a transformation of the position rather than the source fluid arriving there: spreading
     * writes the source's own states and evaporation writes air, so only a foreign block or fluid is a result.
     */
    private static boolean transformed(BlockState placed, BlockState written, Fluid source) {
        if (written.isAir() || written.equals(placed)) {
            return false;
        }
        FluidState fluid = written.getFluidState();
        return fluid.isEmpty() || FluidInteractionRecipe.stillForm(fluid) != source;
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
                FluidInteractionRecipe recipe = new FluidInteractionRecipe(type, -1, recipeId(typeKey, ownerSegment, n, variant++),
                        List.copyOf(entry.getValue().sources), List.copyOf(entry.getValue().neighbors),
                        outcome.target(), Map.of(), outcome.results(), null, owner, entry.getValue().inert);
                logFormDifference(recipe);
                ordered.add(recipe);
            }
        }
        return ordered;
    }

    /**
     * States at info level, once per recipe, that a fluid's spread produced a result in one source form and
     * wrote nothing in the other. Mod authors have no other way to see what the probe saw, so this is not a
     * debug line; it reports the observation and nothing beyond it.
     */
    private static void logFormDifference(FluidInteractionRecipe recipe) {
        BlockState result = recipe.results().get(recipe.neighborOffset());
        for (FluidState inert : recipe.inert().sources()) {
            LOGGER.info("Fluid spread form difference: {} changes {} {} into {} as {}; its {} form spreads over them without changing them",
                    BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(inert)),
                    recipe.neighbors().stream().map(SpreadProber::key).toList(),
                    recipe.neighborOffset().equals(BELOW_OFFSET) ? "below it" : "beside it",
                    result != null ? BuiltInRegistries.BLOCK.getKey(result.getBlock()) : describe(recipe.results()),
                    recipe.matchesSourceForm() ? "a source block" : "a flowing block",
                    inert.isSource() ? "source" : "flowing");
        }
    }

    /** The registry id behind a placement, so a form difference can be grepped for by fluid or block id. */
    private static String key(Placement placement) {
        if (placement.isFluid()) {
            return String.valueOf(BuiltInRegistries.FLUID.getKey(placement.effectiveFluid().getType()));
        }
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(placement.block().getBlock()));
    }

    /**
     * Spread-discovered ids carry their own leading segment, so adding this tier leaves every id the registry
     * tier produces — and every bookmark holding one — untouched.
     */
    private static ResourceLocation recipeId(ResourceLocation typeKey, String ownerSegment, int n, int variant) {
        return ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID,
                "spread/" + typeKey.getNamespace() + "/" + typeKey.getPath() + "/" + ownerSegment + "/" + n + "/" + variant);
    }

    private static final Comparator<Map.Entry<Outcome, Group>> WITHIN_OWNER_ORDER = Comparator
            .<Map.Entry<Outcome, Group>>comparingInt(entry -> TARGET_OFFSETS.indexOf(entry.getKey().target()))
            .thenComparing(entry -> resultKey(entry.getKey()), Comparator.nullsLast(InteractionProber.LOCATION_ORDER))
            .thenComparing(entry -> describe(entry.getKey().results()));

    private static @Nullable ResourceLocation resultKey(Outcome outcome) {
        BlockState state = outcome.results().get(outcome.target());
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
