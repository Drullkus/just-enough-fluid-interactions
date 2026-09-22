package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.Config;
import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Discovers every fluid interaction. It runs each one inside a {@link SandboxLevel} and turns the findings
 * into recipes.
 *
 * <p>Three tiers generate candidates. {@link RegistryProber} runs every {@link FluidInteractionRegistry} entry.
 * {@link SpreadProber} ticks the fluids that declare spread code of their own. {@link NeighborProber} calls the
 * update hooks of the liquid blocks that declare them. Each tier calls one rule by hand. It keeps what that rule
 * alone writes. {@link Settler} then builds every hit with the semantics of a level and decides the result.
 *
 * <p>One fluid type is one unit of work. Its interactions, its runs and its ids depend on no other type. So a
 * tier probes the types on several threads at once, each thread with a sandbox of its own ({@link Worker}). The
 * ids are the same on any number of threads. A pack can hold a hook that is not safe off its own thread. When a
 * tier fails on the pool, the probe continues on one thread. The config can also set one thread from the start.
 *
 * <p>The tiers can propose the same physical arrangement. {@link #arrangementKey} keeps the first tier's recipe:
 * the registry, then the neighbor tier, then the spread tier. {@link RecipeMerger} then collapses recipes that
 * describe one pattern. {@link #byOwner} sets the display order.
 */
public final class InteractionProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The most threads the config can ask for. */
    private static final int MAX_THREADS = 16;
    /** How often the watchdog looks at the workers while it waits for them. */
    private static final long WATCH_MILLIS = 250;
    /**
     * The most threads the default uses. Beyond eight, every thread runs slower under the shared caches and the
     * longest fluid type bounds the tier, so more gain nothing.
     */
    private static final int DEFAULT_MAX_THREADS = 8;

    private final RegistryAccess access;
    private final Candidates candidates;
    /** The threads' own sandboxes and probers. One worker when the probe runs on the calling thread. */
    private List<Worker> workers;

    public InteractionProber(RegistryAccess access) {
        this.access = access;
        this.candidates = new Candidates();
        int threads = threads();
        List<Worker> workers = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            workers.add(new Worker(access, candidates));
        }
        this.workers = workers;
    }

    /**
     * Probes every fluid type in {@link RecipeIds#LOCATION_ORDER}. Inside one type, owner groups rank in
     * {@link RecipeIds#OWNER_ORDER}. Inside one owner, every registry recipe comes before every spread recipe.
     * Every spread recipe comes before every neighbor recipe. The tiers assign the recipe ids before that
     * regrouping. So the ids never depend on it.
     */
    public List<FluidInteractionRecipe> probeAll() {
        Map<FluidType, List<InteractionInformation>> registered = RegisteredInteractions.get();
        List<FluidType> types = probable(orderedTypes(registered.keySet()), registered);
        LOGGER.debug("Probing on {} thread(s)", workers.size());

        Tier registry = registryTier(types, registered);
        counters("registry");
        Tier spread = ruleTier(types, worker -> worker.spread, "fluid spread", "that inherit all of their spread code");
        counters("spread");
        Tier neighbors = ruleTier(types, worker -> worker.neighbors, "fluid neighbors", "whose blocks inherit all of their update code");
        counters("neighbors");
        LOGGER.debug("Settled {} arrangement(s) in {} ms; {} block scheduled tick(s) were asked for, which only a server level can run",
                sum(worker -> worker.settler.settledCount()), sum(worker -> worker.settler.millis()),
                sum(worker -> worker.level.blockTicksRequested()));

        Set<Object> known = new HashSet<>();
        registry.recipes().values().forEach(recipes -> recipes.forEach(recipe -> known.add(arrangementKey(recipe))));
        Map<FluidType, List<FluidInteractionRecipe>> fromNeighbors = dedupe(neighbors.recipes(), known);
        fromNeighbors.values().forEach(recipes -> recipes.forEach(recipe -> known.add(arrangementKey(recipe))));
        Map<FluidType, List<FluidInteractionRecipe>> fromSpread = dedupe(spread.recipes(), known);

        List<FluidInteractionRecipe> recipes = new ArrayList<>();
        for (FluidType type : types) {
            recipes.addAll(byOwner(registry.recipes().getOrDefault(type, List.of()),
                    fromSpread.getOrDefault(type, List.of()), fromNeighbors.getOrDefault(type, List.of())));
        }
        List<FluidInteractionRecipe> merged = RecipeMerger.merge(recipes);
        LOGGER.info("Probed fluid spread of {} fluid type(s) into {} JEI recipe(s) in {} ms",
                types.size(), count(fromSpread), spread.millis());
        LOGGER.info("Probed fluid neighbors of {} fluid type(s) into {} JEI recipe(s) in {} ms",
                types.size(), count(fromNeighbors), neighbors.millis());
        LOGGER.info("Probed {} fluid interaction(s) into {} JEI recipe(s) in {} ms",
                registry.count(), merged.size(), registry.millis() + spread.millis() + neighbors.millis());
        return merged;
    }

    /**
     * The threads the config asks for. Zero means every processor but one, as vanilla sizes its own workers, and
     * at most {@link #DEFAULT_MAX_THREADS}. The last processor stays with the server thread and the rest of the
     * machine.
     */
    private static int threads() {
        int configured = Config.PROBE_THREADS.get();
        if (configured > 0) {
            return Math.min(configured, MAX_THREADS);
        }
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, DEFAULT_MAX_THREADS));
    }

    /** One thread's sandbox, settler and probers. A worker probes one fluid type at a time. */
    private static final class Worker {
        final SandboxLevel level;
        final Settler settler;
        final RegistryProber registry;
        final SpreadProber spread;
        final NeighborProber neighbors;

        Worker(RegistryAccess access, Candidates candidates) {
            level = new SandboxLevel(access);
            settler = new Settler(level);
            registry = new RegistryProber(level, settler, candidates);
            spread = new SpreadProber(level, settler, candidates);
            neighbors = new NeighborProber(level, settler, candidates);
        }
    }

    /** What one tier found, per fluid type, and how many rules it ran. */
    private record Tier(Map<FluidType, List<FluidInteractionRecipe>> recipes, int count, long nanos) {
        long millis() {
            return nanos / 1_000_000;
        }
    }

    private Tier registryTier(List<FluidType> types, Map<FluidType, List<InteractionInformation>> registered) {
        long start = System.nanoTime();
        List<FluidType> withInteractions = types.stream()
                .filter(type -> !registered.getOrDefault(type, List.of()).isEmpty())
                .toList();
        int count = withInteractions.stream().mapToInt(type -> registered.get(type).size()).sum();
        Map<FluidType, List<FluidInteractionRecipe>> found = phase("fluid interactions", withInteractions,
                (worker, type) -> worker.registry.probe(type, List.copyOf(registered.get(type))));
        LOGGER.debug("Skipped {} candidate run(s) of the fluid interactions whose predicate did not read the neighbor",
                sum(worker -> worker.registry.skippedRuns()));
        LOGGER.debug("Swept the predicate's own fluid type first in {} sweep(s) of NeoForge's fluid type predicate",
                sum(worker -> worker.registry.shortlisted()));
        return new Tier(found, count, System.nanoTime() - start);
    }

    private Tier ruleTier(List<FluidType> types, Function<Worker, RuleProber> prober, String tier, String inherit) {
        long start = System.nanoTime();
        Map<FluidType, List<FluidInteractionRecipe>> found = phase(tier, types,
                (worker, type) -> prober.apply(worker).probe(type, candidates.sourceStates(type)));
        found.values().removeIf(List::isEmpty);
        LOGGER.debug("Skipped the {} of {} of {} fluid type(s) {}", tier,
                sum(worker -> prober.apply(worker).skippedTypes()), types.size(), inherit);
        LOGGER.debug("Skipped {} candidate run(s) of the {} whose hook did not read the target",
                sum(worker -> prober.apply(worker).skippedRuns()), tier);
        return new Tier(found, count(found), System.nanoTime() - start);
    }

    /**
     * Runs one tier over the types, one type per task, on every worker at once. The answer holds the types in
     * the order given. A tier that fails on the pool runs again on one fresh worker, because a hook of a mod can
     * be unsafe off its own thread. A hook that waits for the render thread is one case: the render thread
     * waits here, so that hook never returns, and the watchdog in {@link #parallel} reports it as a failure.
     * Every later tier then runs on that worker too.
     */
    private <T> Map<FluidType, T> phase(String tier, List<FluidType> types, BiFunction<Worker, FluidType, T> task) {
        Map<FluidType, T> results = new ConcurrentHashMap<>();
        if (workers.size() > 1) {
            try {
                parallel(types, (worker, type) -> results.put(type, task.apply(worker, type)));
            } catch (RuntimeException | Error e) {
                LOGGER.warn("Probing the {} on {} threads failed. The probe continues on one thread.", tier, workers.size(), e);
                results.clear();
                workers = List.of(new Worker(access, candidates));
            }
        }
        if (workers.size() == 1) {
            Worker worker = workers.getFirst();
            for (FluidType type : types) {
                results.put(type, task.apply(worker, type));
            }
        }
        Map<FluidType, T> ordered = new LinkedHashMap<>();
        for (FluidType type : types) {
            T result = results.get(type);
            if (result != null) {
                ordered.put(type, result);
            }
        }
        return ordered;
    }

    /**
     * Every worker takes the next type until none is left. The first failure stops all of them. A type that a
     * worker holds for longer than the config allows is a failure too: the worker stays behind, a daemon with
     * one sandbox, and the other workers take no further type.
     */
    private void parallel(List<FluidType> types, BiConsumer<Worker, FluidType> task) {
        AtomicInteger next = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>(workers.size());
        List<Progress> progress = new ArrayList<>(workers.size());
        for (Worker worker : workers) {
            Progress current = new Progress();
            progress.add(current);
            Thread thread = new Thread(() -> {
                try {
                    for (int i; (i = next.getAndIncrement()) < types.size() && failure.get() == null; ) {
                        current.start(types.get(i));
                        task.accept(worker, types.get(i));
                        current.finish();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "jefi-probe-" + threads.size());
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        long limit = Config.PROBE_STALL_SECONDS.get() * 1_000_000_000L;
        List<Thread> alive = new ArrayList<>(threads);
        while (!alive.isEmpty()) {
            try {
                alive.getFirst().join(WATCH_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the fluid interactions were probed", e);
            }
            alive.removeIf(thread -> !thread.isAlive());
            List<String> stuck = progress.stream().map(current -> current.stuckFor(limit)).filter(Objects::nonNull).toList();
            if (!stuck.isEmpty()) {
                IllegalStateException stall = new IllegalStateException("No answer for " + stuck + " within "
                        + Config.PROBE_STALL_SECONDS.get() + " s");
                failure.compareAndSet(null, stall);
                throw stall;
            }
        }
        Throwable failed = failure.get();
        if (failed instanceof RuntimeException e) {
            throw e;
        }
        if (failed instanceof Error e) {
            throw e;
        }
        if (failed != null) {
            throw new IllegalStateException(failed);
        }
    }

    /** The type one worker holds and since when. The watchdog reads both from a different thread. */
    private static final class Progress {
        private volatile @Nullable FluidType type;
        private volatile long since;

        void start(FluidType type) {
            this.since = System.nanoTime();
            this.type = type;
        }

        void finish() {
            this.type = null;
        }

        /** The held type's key when the worker has held it longer than {@code limit} nanoseconds, else null. */
        @Nullable String stuckFor(long limit) {
            FluidType held = type;
            return held != null && System.nanoTime() - since > limit ? RecipeIds.keyOf(held).toString() : null;
        }
    }

    private long sum(ToLongFunction<Worker> counter) {
        return workers.stream().mapToLong(counter).sum();
    }

    private void counters(String tier) {
        LOGGER.debug("After {}: settled {} in {} ms, live writes {}, neighbor updates {}, fluid ticks {}", tier,
                sum(worker -> worker.settler.settledCount()), sum(worker -> worker.settler.millis()),
                sum(worker -> worker.level.liveWrites()), sum(worker -> worker.level.liveNeighborUpdates()),
                sum(worker -> worker.level.liveTicks()));
    }

    private static int count(Map<FluidType, List<FluidInteractionRecipe>> found) {
        return found.values().stream().mapToInt(List::size).sum();
    }

    /**
     * The types worth probing: the ones a level can hold a fluid of. A type whose every fluid has no block can
     * stand nowhere. So no tier probes its interactions.
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
            LOGGER.debug("Skipped {} fluid interaction(s) on {}, no fluid of which has a block", interactions, RecipeIds.keyOf(type));
        }
        LOGGER.debug("Skipped {} fluid interaction(s) on {} fluid type(s) whose fluids have no block",
                skippedInteractions, skippedTypes);
        return probable;
    }

    /** Every fluid type with registered interactions or with a still fluid, in {@link RecipeIds#LOCATION_ORDER}. */
    private static List<FluidType> orderedTypes(Set<FluidType> registered) {
        Set<FluidType> types = new LinkedHashSet<>(registered);
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid != Fluids.EMPTY && fluid.defaultFluidState().isSource()) {
                types.add(fluid.getFluidType());
            }
        }
        List<FluidType> ordered = new ArrayList<>(types);
        ordered.sort(Comparator.comparing(RecipeIds::keyOf, RecipeIds.LOCATION_ORDER));
        return ordered;
    }

    /** The recipes of one tier without those whose arrangement an earlier tier already describes. */
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
     * The physical arrangement a recipe describes: the fluid placed last, what stands where, and nothing about
     * the tier that found it. Every tier settles the arrangement. The results are thus a function of it. Two
     * tiers that propose the same arrangement reach the same answer. The first tier's attribution wins.
     */
    private static Object arrangementKey(FluidInteractionRecipe recipe) {
        return List.of(Set.copyOf(recipe.sources()), Set.copyOf(recipe.neighbors()), recipe.neighborOffset(),
                recipe.conditions());
    }

    /**
     * The recipes of one fluid type from all three tiers as one list. Owner groups rank in
     * {@link RecipeIds#OWNER_ORDER}. Inside one owner come every registry recipe, then every spread recipe, then
     * every neighbor recipe, each in its own order. The inputs carry their ids already. The tiers group them by
     * owner in that order already. So this changes no recipe, id or number.
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
        ordered.sort(RecipeIds.OWNER_ORDER);

        List<FluidInteractionRecipe> result = new ArrayList<>(registry.size() + spread.size() + neighbors.size());
        for (String owner : ordered) {
            tiers.forEach(tier -> result.addAll(tier.getOrDefault(owner, List.of())));
        }
        return result;
    }
}
