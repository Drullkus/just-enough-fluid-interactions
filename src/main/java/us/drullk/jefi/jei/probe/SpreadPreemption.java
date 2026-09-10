package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Removes the neighbor alternatives of a {@link SpreadProber} recipe that a registered interaction reaches first.
 *
 * <p>The sandbox delivers no block updates, so the spread probe sees a fluid land on its neighbor whatever a
 * registered interaction would have done in between. A level places the two blocks next to each other first:
 * {@code LiquidBlock.shouldSpreadLiquid} runs {@link FluidInteractionRegistry} for the updated block's fluid type
 * on that same tick, while the spread rule waits for the fluid's scheduled tick. An interaction covering the same
 * two fluids therefore happens instead of the spread rule, and the fluid it consumes never reaches the spread
 * recipe's neighbor slot.
 *
 * <p>{@code shouldSpreadLiquid} looks at the block above the updated position and its four sides, never the one
 * below. A spread rule reaching down is pre-empted only by an interaction registered on the fluid below, which
 * sees the source above it; a rule reaching sideways is pre-empted from either side, since both blocks are
 * updated and each has the other beside it.
 *
 * <p>Only interactions the registry probe exercised without extra surroundings count: one that needs a particular
 * block somewhere else fires only in that arrangement, which the spread recipe does not describe.
 */
public final class SpreadPreemption {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SpreadPreemption() {
    }

    /**
     * The spread recipes that survive, keyed and ordered as they arrived. A recipe that keeps some of its
     * alternatives keeps its id; one whose every alternative is pre-empted is dropped.
     */
    public static Map<FluidType, List<FluidInteractionRecipe>> apply(
            List<FluidInteractionRecipe> registry, Map<FluidType, List<FluidInteractionRecipe>> spread) {
        Map<Form, Map<Form, ResourceLocation>> interactions = index(registry);
        Map<FluidType, List<FluidInteractionRecipe>> result = new LinkedHashMap<>();
        int alternatives = 0;
        int touched = 0;
        int dropped = 0;
        for (var entry : spread.entrySet()) {
            List<FluidInteractionRecipe> kept = new ArrayList<>(entry.getValue().size());
            for (FluidInteractionRecipe recipe : entry.getValue()) {
                FluidInteractionRecipe trimmed = trim(interactions, recipe);
                int lost = recipe.neighbors().size() - (trimmed != null ? trimmed.neighbors().size() : 0);
                if (lost == 0) {
                    kept.add(recipe);
                    continue;
                }
                alternatives += lost;
                touched++;
                if (trimmed == null) {
                    dropped++;
                } else {
                    kept.add(trimmed);
                }
            }
            if (!kept.isEmpty()) {
                result.put(entry.getKey(), kept);
            }
        }
        LOGGER.info("Registry interactions pre-empt {} spread alternative(s) in {} spread recipe(s)", alternatives, touched);
        if (dropped > 0) {
            LOGGER.debug("Dropped {} spread recipe(s) whose every neighbor alternative a registry interaction pre-empts", dropped);
        }
        return result;
    }

    /**
     * The registered interactions as source form to neighbor form, each mapped to the first recipe describing it,
     * which is what the debug line names. Failures never fired and multi-position interactions only fire in their
     * own arrangement, so neither takes part.
     */
    private static Map<Form, Map<Form, ResourceLocation>> index(List<FluidInteractionRecipe> registry) {
        Map<Form, Map<Form, ResourceLocation>> index = new LinkedHashMap<>();
        for (FluidInteractionRecipe recipe : registry) {
            if (recipe.isFailure() || !recipe.conditions().isEmpty()) {
                continue;
            }
            Set<Form> neighbors = new LinkedHashSet<>();
            for (Placement neighbor : recipe.neighbors()) {
                if (neighbor.isFluid()) {
                    neighbors.add(form(neighbor.effectiveFluid()));
                }
            }
            if (neighbors.isEmpty()) {
                continue;
            }
            for (FluidState source : recipe.sources()) {
                Map<Form, ResourceLocation> byNeighbor = index.computeIfAbsent(form(source), k -> new LinkedHashMap<>());
                neighbors.forEach(neighbor -> byNeighbor.putIfAbsent(neighbor, recipe.id()));
            }
        }
        return index;
    }

    /**
     * One spread recipe without its pre-empted alternatives, the recipe itself when none is, or null when every
     * one of them is.
     */
    private static @Nullable FluidInteractionRecipe trim(Map<Form, Map<Form, ResourceLocation>> interactions, FluidInteractionRecipe recipe) {
        Set<Form> sources = new LinkedHashSet<>();
        recipe.sources().forEach(state -> sources.add(form(state)));
        boolean beside = !recipe.neighborOffset().equals(SpreadProber.BELOW_OFFSET);

        List<Placement> kept = new ArrayList<>(recipe.neighbors().size());
        for (Placement neighbor : recipe.neighbors()) {
            ResourceLocation preempting = preemptedBy(interactions, neighbor, sources, beside);
            if (preempting == null) {
                kept.add(neighbor);
                continue;
            }
            LOGGER.debug("Registry interaction {} pre-empts {} as an alternative of spread recipe {}",
                    preempting, neighbor.describe().getString(), recipe.id());
        }
        if (kept.size() == recipe.neighbors().size()) {
            return recipe;
        }
        if (kept.isEmpty()) {
            return null;
        }

        Set<Fluid> keptFluids = new LinkedHashSet<>();
        kept.forEach(placement -> {
            if (placement.isFluid()) {
                keptFluids.add(FluidInteractionRecipe.stillForm(placement.effectiveFluid()));
            }
        });
        List<Placement> inertNeighbors = new ArrayList<>(recipe.inert().neighbors().size());
        for (Placement neighbor : recipe.inert().neighbors()) {
            boolean stale = neighbor.isFluid()
                    && (!keptFluids.contains(FluidInteractionRecipe.stillForm(neighbor.effectiveFluid()))
                    || preemptedBy(interactions, neighbor, sources, beside) != null);
            if (!stale) {
                inertNeighbors.add(neighbor);
            }
        }
        return new FluidInteractionRecipe(recipe.sourceType(), recipe.index(), recipe.id(), recipe.sources(),
                List.copyOf(kept), recipe.neighborOffset(), recipe.conditions(), recipe.results(), recipe.failure(),
                recipe.owner(), new InertForms(recipe.inert().sources(), List.copyOf(inertNeighbors)));
    }

    /**
     * The interaction that consumes this alternative before the spread rule reaches it, or null when none does.
     * The alternative is matched in the exact form the probe verified, because that is the form the interaction's
     * predicate would see; the source fluid is matched in any form the spread recipe holds, which treats a recipe
     * covering both forms as pre-empted when the interaction only names one of them.
     */
    private static @Nullable ResourceLocation preemptedBy(Map<Form, Map<Form, ResourceLocation>> interactions,
                                                          Placement neighbor, Set<Form> sources, boolean beside) {
        if (!neighbor.isFluid()) {
            return null;
        }
        Form form = form(neighbor.effectiveFluid());
        Map<Form, ResourceLocation> asSource = interactions.getOrDefault(form, Map.of());
        for (Form source : sources) {
            ResourceLocation id = asSource.get(source);
            if (id != null) {
                return id;
            }
        }
        if (!beside) {
            return null;
        }
        for (Form source : sources) {
            ResourceLocation id = interactions.getOrDefault(source, Map.<Form, ResourceLocation>of()).get(form);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static Form form(FluidState state) {
        return new Form(FluidInteractionRecipe.stillForm(state), !state.isSource());
    }

    /** One fluid in one of its two forms, the granularity both probes record and interactions distinguish. */
    private record Form(Fluid fluid, boolean flowing) {
    }
}
