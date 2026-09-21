package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

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
 * Discovers every fluid interaction. It runs each one inside one {@link SandboxLevel} and turns the findings
 * into recipes.
 *
 * <p>Three tiers generate candidates. {@link RegistryProber} runs every {@link FluidInteractionRegistry} entry.
 * {@link SpreadProber} ticks the fluids that declare spread code of their own. {@link NeighborProber} calls the
 * update hooks of the liquid blocks that declare them. Each tier calls one rule by hand. It keeps what that rule
 * alone writes. {@link Settler} then builds every hit with the semantics of a level and decides the result.
 *
 * <p>The tiers can propose the same physical arrangement. {@link #arrangementKey} keeps the first tier's recipe:
 * the registry, then the neighbor tier, then the spread tier. {@link RecipeMerger} then collapses recipes that
 * describe one pattern. {@link #byOwner} sets the display order.
 */
public final class InteractionProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final SandboxLevel level;
    private final Settler settler;
    private final Candidates candidates;

    public InteractionProber(RegistryAccess access) {
        this.level = new SandboxLevel(access);
        this.settler = new Settler(level);
        this.candidates = new Candidates();
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

        Tier registry = registryTier(types, registered);
        Tier spread = ruleTier(types, new SpreadProber(level, settler, candidates), "fluid spread", "that inherit all of their spread code");
        Tier neighbors = ruleTier(types, new NeighborProber(level, settler, candidates), "fluid neighbors", "whose blocks inherit all of their update code");
        LOGGER.debug("Settled {} arrangement(s) in {} ms; {} block scheduled tick(s) were asked for, which only a server level can run",
                settler.settledCount(), settler.millis(), level.blockTicksRequested());

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

    /** What one tier found, per fluid type, and how many rules it ran. */
    private record Tier(Map<FluidType, List<FluidInteractionRecipe>> recipes, int count, long nanos) {
        long millis() {
            return nanos / 1_000_000;
        }
    }

    private Tier registryTier(List<FluidType> types, Map<FluidType, List<InteractionInformation>> registered) {
        long start = System.nanoTime();
        RegistryProber prober = new RegistryProber(level, settler, candidates);
        Map<FluidType, List<FluidInteractionRecipe>> found = new LinkedHashMap<>();
        int count = 0;
        for (FluidType type : types) {
            List<InteractionInformation> interactions = List.copyOf(registered.getOrDefault(type, List.of()));
            if (!interactions.isEmpty()) {
                count += interactions.size();
                found.put(type, prober.probe(type, interactions));
            }
        }
        LOGGER.debug("Skipped {} candidate run(s) of the fluid interactions whose predicate did not read the neighbor",
                prober.skippedRuns());
        LOGGER.debug("Swept the predicate's own fluid type first in {} sweep(s) of NeoForge's fluid type predicate",
                prober.shortlisted());
        return new Tier(found, count, System.nanoTime() - start);
    }

    private static Tier ruleTier(List<FluidType> types, RuleProber prober, String tier, String inherit) {
        long start = System.nanoTime();
        Map<FluidType, List<FluidInteractionRecipe>> found = new LinkedHashMap<>();
        for (FluidType type : types) {
            List<FluidInteractionRecipe> recipes = prober.probe(type, Candidates.sourceStates(type));
            if (!recipes.isEmpty()) {
                found.put(type, recipes);
            }
        }
        LOGGER.debug("Skipped the {} of {} of {} fluid type(s) {}", tier, prober.skippedTypes(), types.size(), inherit);
        LOGGER.debug("Skipped {} candidate run(s) of the {} whose hook did not read the target", prober.skippedRuns(), tier);
        return new Tier(found, count(found), System.nanoTime() - start);
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
        LOGGER.info("Skipped {} fluid interaction(s) on {} fluid type(s) whose fluids have no block",
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
