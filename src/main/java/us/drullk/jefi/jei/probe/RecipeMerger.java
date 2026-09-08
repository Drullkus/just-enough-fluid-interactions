package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.HashMap;
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
 * Collapses probed recipes that describe the same interaction pattern into one recipe whose JEI slots cycle
 * through the alternatives, so a mod registering the same pattern for hundreds of fluids shows a handful of
 * recipes instead of hundreds.
 *
 * <p>Two passes run over the recipe list in probe order. The first merges recipes of one source type whose
 * conditions, results, source forms and owner match, unioning their neighbor alternatives; the second merges
 * recipes of any source type whose neighbor alternatives, conditions, results, source forms and owner match,
 * unioning their source states. Failure recipes never merge.
 *
 * <p>A merged recipe keeps the id, source type and index of its first member in probe order, which is registry
 * key order of the source fluid type, then registration index, then probe variant. Recipe ids therefore stay
 * unique and stable across runs for an unchanged set of registered interactions, which is what JEI's bookmarks
 * need. Nothing that varies between runs takes part in a merge key, and output order follows first-member probe
 * order rather than any hash iteration order.
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
        return new WithinType(recipe.sourceType(), recipe.owner(), recipe.conditions(), recipe.results(), forms(recipe));
    }

    private static Object acrossTypes(FluidInteractionRecipe recipe) {
        return new AcrossTypes(Set.copyOf(recipe.neighbors()), recipe.owner(), recipe.conditions(), recipe.results(), forms(recipe));
    }

    /** Which forms the source was matched in, as a bit mask, so a still-only pattern never merges with a flowing one. */
    private static int forms(FluidInteractionRecipe recipe) {
        return (recipe.matchesSourceForm() ? FORM_SOURCE : 0) | (recipe.matchesFlowingForm() ? FORM_FLOWING : 0);
    }

    private record WithinType(FluidType sourceType, @Nullable String owner, Map<BlockPos, Placement> conditions,
                              Map<BlockPos, BlockState> results, int forms) {
    }

    private record AcrossTypes(Set<Placement> neighbors, @Nullable String owner, Map<BlockPos, Placement> conditions,
                               Map<BlockPos, BlockState> results, int forms) {
    }

    /** Members of one merge; the first one supplies everything the merged recipe does not union. */
    private static final class Merge {
        private final FluidInteractionRecipe first;
        private final Set<FluidState> sources = new LinkedHashSet<>();
        private final Set<Placement> neighbors = new LinkedHashSet<>();
        private int members;

        Merge(FluidInteractionRecipe recipe) {
            this.first = recipe;
            add(recipe);
        }

        void add(FluidInteractionRecipe recipe) {
            sources.addAll(recipe.sources());
            neighbors.addAll(recipe.neighbors());
            members++;
        }

        FluidInteractionRecipe build() {
            if (members == 1) {
                return first;
            }
            return new FluidInteractionRecipe(first.sourceType(), first.index(), first.id(),
                    List.copyOf(sources), List.copyOf(neighbors),
                    first.conditions(), first.results(), null, first.owner());
        }
    }
}
