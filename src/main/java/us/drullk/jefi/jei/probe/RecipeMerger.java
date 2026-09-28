package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.Config;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.LiquidBlock;
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
 * The second pass merges recipes of any source type whose neighbor alternatives, neighbor position,
 * conditions, results, source forms and owner match. It unions their source states. In both passes, a recipe
 * whose every result block is in {@code mergeAcrossMods} matches on its neighbor kinds, fluid or block, and not
 * on its owner. A merge of several owners has no owner and a {@code merged} id. Neighbor alternatives compare by block state and still fluid, plus the forms each one matched in. So
 * the exact flowing state a probe recorded never decides a merge. Failure recipes never merge.
 *
 * <p>A fluid result compares by its still form. Members whose results then differ in state become rows.
 *
 * <p>A third pass, {@link LockstepMerger}, merges the recipes of each mod in {@code mergeWithinMods} into rows.
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
    private static final int KIND_FLUID = 0b01;
    private static final int KIND_BLOCK = 0b10;
    private static final String MERGED = "merged";

    /** Orders the keys of merges with several owners by values that no installed mod changes. */
    private static final Comparator<Key> ACROSS_MODS_ORDER = Comparator.comparing(RecipeMerger::resultKeys)
            .thenComparingInt(key -> ((AcrossMods) key.owner()).neighborKinds())
            .thenComparingInt(Key::forms)
            .thenComparing(Key::neighborOffset)
            .thenComparing(RecipeMerger::conditionKeys)
            .thenComparing(RecipeMerger::resultStates)
            .thenComparing(RecipeMerger::neighborKeys);

    private RecipeMerger() {
    }

    /** Merges with the config's {@code mergeAcrossMods} and {@code mergeWithinMods}. */
    public static List<FluidInteractionRecipe> merge(List<FluidInteractionRecipe> recipes) {
        return merge(recipes, blockIds(Config.MERGE_ACROSS_MODS.get()), Set.copyOf(Config.MERGE_WITHIN_MODS.get()));
    }

    /**
     * @param acrossMods the result blocks whose recipes merge without their owner.
     * @param withinMods the mod ids whose recipes merge into rows.
     */
    public static List<FluidInteractionRecipe> merge(List<FluidInteractionRecipe> recipes, Set<ResourceLocation> acrossMods,
                                                     Set<String> withinMods) {
        List<FluidInteractionRecipe> merged = pass(pass(recipes, recipe -> withinType(recipe, acrossMods)), recipe -> acrossTypes(recipe, acrossMods));
        merged = LockstepMerger.merge(merged, withinMods);
        LOGGER.info("Merged {} fluid interaction recipe(s) into {}", recipes.size(), merged.size());
        return merged;
    }

    private static Set<ResourceLocation> blockIds(List<? extends String> ids) {
        Set<ResourceLocation> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) {
                blocks.add(key);
            }
        }
        return blocks;
    }

    private static List<FluidInteractionRecipe> pass(List<FluidInteractionRecipe> recipes, Function<FluidInteractionRecipe, Key> key) {
        List<Merge> merges = new ArrayList<>();
        Map<Key, Merge> byKey = new HashMap<>();
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
        Map<Merge, ResourceLocation> ids = mergedIds(byKey);
        List<FluidInteractionRecipe> result = new ArrayList<>(merges.size());
        for (Merge merge : merges) {
            result.addAll(merge.build(ids.get(merge)));
        }
        return result;
    }

    /**
     * The {@code merged} ids of the merges with several owners, in {@link #ACROSS_MODS_ORDER}. Within one type,
     * {@code n} counts the result blocks. Across types, the first result block names the merge. {@code variant}
     * counts the rest of the key.
     */
    private static Map<Merge, ResourceLocation> mergedIds(Map<Key, Merge> byKey) {
        Map<List<Object>, List<Map.Entry<Key, Merge>>> groups = new HashMap<>();
        byKey.forEach((key, merge) -> {
            if (key.owner() instanceof AcrossMods && merge.owners.size() > 1) {
                Object scope = key instanceof WithinType within ? within.sourceType() : firstResult(key);
                groups.computeIfAbsent(List.of(tier(merge.first.id()), scope), k -> new ArrayList<>()).add(Map.entry(key, merge));
            }
        });
        Map<Merge, ResourceLocation> ids = new HashMap<>();
        groups.forEach((scope, entries) -> {
            entries.sort(Map.Entry.comparingByKey(ACROSS_MODS_ORDER));
            String tier = (String) scope.get(0);
            int n = -1;
            int variant = 0;
            String results = null;
            for (Map.Entry<Key, Merge> entry : entries) {
                String next = resultKeys(entry.getKey());
                if (entry.getKey() instanceof WithinType within) {
                    if (!next.equals(results)) {
                        results = next;
                        n++;
                        variant = 0;
                    }
                    ids.put(entry.getValue(), RecipeIds.id(tier, RecipeIds.keyOf(within.sourceType()), MERGED, n, variant++));
                } else {
                    ids.put(entry.getValue(), RecipeIds.mergedId(tier, (ResourceLocation) scope.get(1), variant++));
                }
            }
        });
        return ids;
    }

    /** The block key of the first result in offset order. */
    private static ResourceLocation firstResult(Key key) {
        return key.results().entrySet().stream()
                .min(Map.Entry.comparingByKey())
                .map(entry -> BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()))
                .orElseThrow();
    }

    /** The tier segment of an id: empty, {@code spread/} or {@code neighbor/}. */
    private static String tier(ResourceLocation id) {
        String path = id.getPath();
        return path.startsWith("spread/") ? "spread/" : path.startsWith("neighbor/") ? "neighbor/" : "";
    }

    private static String resultKeys(Key key) {
        return offsetKeys(key.results().entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), String.valueOf(BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()))))
                .toList());
    }

    private static String resultStates(Key key) {
        return offsetKeys(key.results().entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue().toString())).toList());
    }

    private static String conditionKeys(Key key) {
        return offsetKeys(key.conditions().entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().block() + "/" + entry.getValue().effectiveFluid()))
                .toList());
    }

    private static String neighborKeys(Key key) {
        return key instanceof AcrossTypes across
                ? across.neighbors().stream().map(neighbor -> neighbor.neighbor().block() + "/" + neighbor.neighbor().effectiveFluid()
                        + "/" + neighbor.forms()).sorted().toList().toString()
                : "";
    }

    private static String offsetKeys(List<Map.Entry<BlockPos, String>> entries) {
        return entries.stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().toShortString() + "=" + entry.getValue())
                .toList()
                .toString();
    }

    private static Key withinType(FluidInteractionRecipe recipe, Set<ResourceLocation> acrossMods) {
        return new WithinType(recipe.sourceType(), ownerKey(recipe, acrossMods), recipe.neighborOffset(), recipe.conditions(), stillResults(recipe), forms(recipe));
    }

    /** The results with each liquid block in its source form. A fluid result thus matches at any level. */
    private static Map<BlockPos, BlockState> stillResults(FluidInteractionRecipe recipe) {
        Map<BlockPos, BlockState> results = new HashMap<>();
        recipe.results().forEach((offset, state) -> results.put(offset, state.getBlock() instanceof LiquidBlock
                ? FluidInteractionRecipe.stillForm(state.getFluidState()).defaultFluidState().createLegacyBlock()
                : state));
        return results;
    }

    /** The owner, or the neighbor kinds when every result block merges across mods. */
    private static @Nullable Object ownerKey(FluidInteractionRecipe recipe, Set<ResourceLocation> acrossMods) {
        if (!recipe.results().isEmpty() && recipe.results().values().stream()
                .allMatch(state -> acrossMods.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock())))) {
            return new AcrossMods(neighborKinds(recipe));
        }
        return recipe.owner();
    }

    /** Whether the neighbor alternatives hold fluids, blocks or both, as a bit mask. */
    static int neighborKinds(FluidInteractionRecipe recipe) {
        int kinds = 0;
        for (Placement neighbor : recipe.neighbors()) {
            kinds |= neighbor.isFluid() ? KIND_FLUID : KIND_BLOCK;
        }
        return kinds;
    }

    private static Key acrossTypes(FluidInteractionRecipe recipe, Set<ResourceLocation> acrossMods) {
        return new AcrossTypes(neighborForms(recipe), ownerKey(recipe, acrossMods), recipe.neighborOffset(), recipe.conditions(), stillResults(recipe), forms(recipe));
    }

    /** Which forms the source matched in, as a bit mask. A still-only pattern thus never merges with a flowing one. */
    static int forms(FluidInteractionRecipe recipe) {
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

    /** The parts every merge key holds. */
    private sealed interface Key permits WithinType, AcrossTypes {
        @Nullable Object owner();

        BlockPos neighborOffset();

        Map<BlockPos, Placement> conditions();

        Map<BlockPos, BlockState> results();

        int forms();
    }

    private record WithinType(FluidType sourceType, @Nullable Object owner, BlockPos neighborOffset,
                              Map<BlockPos, Placement> conditions, Map<BlockPos, BlockState> results, int forms) implements Key {
    }

    private record AcrossTypes(Set<NeighborForms> neighbors, @Nullable Object owner, BlockPos neighborOffset,
                               Map<BlockPos, Placement> conditions, Map<BlockPos, BlockState> results, int forms) implements Key {
    }

    /** The owner part of a key that matches recipes of every mod. */
    private record AcrossMods(int neighborKinds) {
    }

    /** One neighbor alternative in its still form, with the forms it matched in as a bit mask. */
    private record NeighborForms(Placement neighbor, int forms) {
    }

    /**
     * The members of one merge. The first member supplies everything the merged recipe does not union. Members
     * whose results differ in state, or that hold rows, become rows.
     */
    private static final class Merge {
        private final FluidInteractionRecipe first;
        private final List<FluidInteractionRecipe> all = new ArrayList<>();
        private final Set<FluidState> sources = new LinkedHashSet<>();
        private final Set<Placement> neighbors = new LinkedHashSet<>();
        private final Set<FluidState> inertSources = new LinkedHashSet<>();
        private final Set<Placement> inertNeighbors = new LinkedHashSet<>();
        private final Set<@Nullable String> owners = new HashSet<>();
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
            owners.add(recipe.owner());
            all.add(recipe);
            members++;
        }

        /**
         * A form another member matched is not inert, so the union of the members drops it again.
         *
         * @param mergedId the id of a merge with several owners, which then has no owner.
         */
        List<FluidInteractionRecipe> build(@Nullable ResourceLocation mergedId) {
            if (members == 1) {
                return List.of(first);
            }
            ResourceLocation id = mergedId != null ? mergedId : first.id();
            String owner = mergedId != null ? null : first.owner();
            if (all.stream().anyMatch(FluidInteractionRecipe::isLockstep) || all.stream().map(FluidInteractionRecipe::results).distinct().count() > 1) {
                LockstepMerger.Rows rows = LockstepMerger.rows(all);
                return rows.shown().size() > LockstepMerger.MAX_ROWS ? all : List.of(LockstepMerger.build(all, rows, id, owner));
            }
            inertSources.removeAll(sources);
            inertNeighbors.removeAll(neighbors);
            return List.of(new FluidInteractionRecipe(first.sourceType(), id,
                    List.copyOf(sources), List.copyOf(neighbors), first.neighborOffset(),
                    first.conditions(), first.results(), null, owner,
                    new InertForms(List.copyOf(inertSources), List.copyOf(inertNeighbors)), List.of(),
                    all.stream().flatMap(member -> member.mirrors().stream()).toList()));
        }
    }
}
