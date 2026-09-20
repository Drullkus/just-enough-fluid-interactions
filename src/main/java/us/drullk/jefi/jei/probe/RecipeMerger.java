package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Collapses probed recipes that describe the same interaction pattern into one recipe. The JEI slots of that
 * recipe cycle through the alternatives. A mod that registers the same pattern for hundreds of fluids thus shows
 * a few recipes and not hundreds.
 *
 * <p>Two passes run over the recipe list in probe order. The first pass merges recipes of one source type whose
 * neighbor position, conditions, results, source forms and owner match. It unions their neighbor alternatives.
 * The second pass merges recipes of any source type whose neighbor alternatives, neighbor position, conditions,
 * results, source forms and owner match. It unions their source states. Neighbor alternatives compare by block
 * state and still fluid, plus the forms each one matched in. So the exact flowing state a probe recorded never
 * decides a merge. Failure recipes never merge.
 *
 * <p>A merged recipe keeps the id and source type of its first member in probe order. Probe order ranks the
 * source fluid type by namespace, and then by path. The namespace rank is {@code minecraft}, then
 * {@code neoforge}, then everything else alphabetically. Within one type, probe order ranks owner groups the
 * same way, with a null owner last. Within one owner group, it puts successes before failures. Then it ranks by
 * the result block at the source position, again namespace first and null result last. Then it ranks by
 * registration index, and then by probe variant. Recipe ids thus stay unique and stable across runs for an
 * unchanged set of registered interactions. This includes the case of two mods that register on the same fluid
 * type during NeoForge's parallel mod-loading events. JEI's bookmarks need that stability. No value that changes
 * between runs is part of a merge key. The output order follows the probe order of the first member, and not a
 * hash iteration order.
 */
public final class RecipeMerger {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int FORM_SOURCE = 0b01;
    private static final int FORM_FLOWING = 0b10;

    private RecipeMerger() {
    }

    public static List<FluidInteractionRecipe> merge(List<FluidInteractionRecipe> recipes) {
        List<FluidInteractionRecipe> merged = pass(pass(recipes, RecipeMerger::withinType), RecipeMerger::acrossTypes);
        LOGGER.info("Merged {} fluid interaction recipe(s) into {}", recipes.size(), merged.size());
        return merged;
    }

    private static List<FluidInteractionRecipe> pass(List<FluidInteractionRecipe> recipes, Function<FluidInteractionRecipe, Object> key) {
        List<Merge> merges = new ArrayList<>();
        Map<Object, Merge> byKey = new HashMap<>();
        for (FluidInteractionRecipe recipe : recipes) {
            if (recipe.isFailure()) {
                merges.add(new Merge(recipe));
                continue;
            }
            Merge existing = byKey.get(key.apply(recipe));
            if (existing != null) {
                existing.add(recipe);
                continue;
            }
            Merge merge = new Merge(recipe);
            merges.add(merge);
            byKey.put(key.apply(recipe), merge);
        }
        List<FluidInteractionRecipe> result = new ArrayList<>(merges.size());
        for (Merge merge : merges) {
            result.add(merge.build());
        }
        return result;
    }

    private static Object withinType(FluidInteractionRecipe recipe) {
        return new WithinType(recipe.sourceType(), recipe.owner(), recipe.neighborOffset(), recipe.conditions(), recipe.results(), forms(recipe));
    }

    private static Object acrossTypes(FluidInteractionRecipe recipe) {
        return new AcrossTypes(neighborForms(recipe), recipe.owner(), recipe.neighborOffset(), recipe.conditions(), recipe.results(), forms(recipe));
    }

    /** Which forms the source matched in, as a bit mask. A still-only pattern thus never merges with a flowing one. */
    private static int forms(FluidInteractionRecipe recipe) {
        return (recipe.matchesSourceForm() ? FORM_SOURCE : 0) | (recipe.matchesFlowingForm() ? FORM_FLOWING : 0);
    }

    /**
     * The neighbor alternatives as a form-normalized set. There is one entry per distinct block state and still
     * fluid. Each entry holds a bit mask of the forms that alternative matched in. Two recipes therefore describe
     * the same neighbor slot when they accept the same things in the same forms. The flowing state the probe
     * recorded does not change this.
     */
    private static Set<NeighborForms> neighborForms(FluidInteractionRecipe recipe) {
        Map<Placement, Integer> forms = new LinkedHashMap<>();
        for (Placement neighbor : recipe.neighbors()) {
            forms.merge(stillForm(neighbor), neighbor.isFlowing() ? FORM_FLOWING : FORM_SOURCE, (a, b) -> a | b);
        }
        Set<NeighborForms> normalized = new LinkedHashSet<>();
        forms.forEach((placement, mask) -> normalized.add(new NeighborForms(placement, mask)));
        return normalized;
    }

    /** A fluid alternative in its still form. Anything else is already in its own normal form. */
    private static Placement stillForm(Placement placement) {
        if (!placement.isFluid()) {
            return placement;
        }
        return Placement.ofFluid(FluidInteractionRecipe.stillForm(placement.effectiveFluid()).defaultFluidState());
    }

    private record WithinType(FluidType sourceType, @Nullable String owner, BlockPos neighborOffset,
                              Map<BlockPos, Placement> conditions, Map<BlockPos, BlockState> results, int forms) {
    }

    private record AcrossTypes(Set<NeighborForms> neighbors, @Nullable String owner, BlockPos neighborOffset,
                               Map<BlockPos, Placement> conditions, Map<BlockPos, BlockState> results, int forms) {
    }

    /** One neighbor alternative in its still form, with the forms it matched in as a bit mask. */
    private record NeighborForms(Placement neighbor, int forms) {
    }

    /** The members of one merge. The first member supplies everything the merged recipe does not union. */
    private static final class Merge {
        private final FluidInteractionRecipe first;
        private final Set<FluidState> sources = new LinkedHashSet<>();
        private final Set<Placement> neighbors = new LinkedHashSet<>();
        private final Set<FluidState> inertSources = new LinkedHashSet<>();
        private final Set<Placement> inertNeighbors = new LinkedHashSet<>();
        private int members;

        Merge(FluidInteractionRecipe recipe) {
            this.first = recipe;
            add(recipe);
        }

        void add(FluidInteractionRecipe recipe) {
            sources.addAll(recipe.sources());
            neighbors.addAll(recipe.neighbors());
            inertSources.addAll(recipe.inert().sources());
            inertNeighbors.addAll(recipe.inert().neighbors());
            members++;
        }

        /** A form another member matched is not inert, so the union of the members drops it again. */
        FluidInteractionRecipe build() {
            if (members == 1) {
                return first;
            }
            inertSources.removeAll(sources);
            inertNeighbors.removeAll(neighbors);
            return new FluidInteractionRecipe(first.sourceType(), first.id(),
                    List.copyOf(sources), List.copyOf(neighbors), first.neighborOffset(),
                    first.conditions(), first.results(), null, first.owner(),
                    new InertForms(List.copyOf(inertSources), List.copyOf(inertNeighbors)));
        }
    }
}
