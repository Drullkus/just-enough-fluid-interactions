package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.ModList;

/**
 * Merges the recipes of one mod with one {@link Shape} into one recipe of rows. A row shows one entry per linked
 * slot. A source or neighbor list that every row shares is one free slot.
 *
 * <p>A group without a free slot splits by a list its members share.
 *
 * <p>A group of more than {@link #MAX_ROWS} rows splits by neighbor entries, then by source fluids.
 *
 * <p>A row that shows an earlier row with the source and the neighbor swapped is a mirror. The recipe keeps the
 * mirror apart from its rows.
 *
 * <p>When EMI is loaded, a group whose linked slot holds a tag stays apart ({@link EmiTagView}).
 */
final class LockstepMerger {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** JEI shows at most this many entries of one slot. */
    static final int MAX_ROWS = 100;

    private LockstepMerger() {
    }

    static List<FluidInteractionRecipe> merge(List<FluidInteractionRecipe> recipes, Set<String> withinMods) {
        if (withinMods.isEmpty()) {
            return recipes;
        }
        EmiTagView emiTags = ModList.get().isLoaded("emi") ? EmiTagView.load(Minecraft.getInstance().getResourceManager()) : null;
        List<List<FluidInteractionRecipe>> groups = new ArrayList<>();
        Map<Shape, List<FluidInteractionRecipe>> byShape = new HashMap<>();
        for (FluidInteractionRecipe recipe : recipes) {
            if (recipe.isFailure() || recipe.owner() == null || !withinMods.contains(recipe.owner())) {
                groups.add(List.of(recipe));
                continue;
            }
            List<FluidInteractionRecipe> group = byShape.get(Shape.of(recipe));
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
                byShape.put(Shape.of(recipe), group);
            }
            group.add(recipe);
        }
        List<FluidInteractionRecipe> merged = new ArrayList<>(groups.size());
        int members = 0;
        int lockstep = 0;
        for (List<FluidInteractionRecipe> whole : groups) {
            for (List<FluidInteractionRecipe> group : parts(whole)) {
                if (group.size() == 1) {
                    merged.add(group.getFirst());
                    continue;
                }
                Rows rows = rows(group);
                if (rows.shown().size() > MAX_ROWS) {
                    LOGGER.debug("Kept {} recipe(s) of {} from {} apart: {} rows", group.size(), group.getFirst().owner(),
                            group.getFirst().id(), rows.shown().size());
                    merged.addAll(group);
                    continue;
                }
                TagKey<?> tag = emiTags != null ? filledTag(emiTags, group.getFirst(), rows.shown()) : null;
                if (tag != null) {
                    LOGGER.debug("Kept {} recipe(s) of {} from {} apart: EMI shows the tag {} in place of their entries",
                            group.size(), group.getFirst().owner(), group.getFirst().id(), tag.location());
                    merged.addAll(group);
                    continue;
                }
                merged.add(build(group, rows, group.getFirst().id(), group.getFirst().owner()));
                members += group.size();
                lockstep++;
            }
        }
        LOGGER.debug("Merged {} recipe(s) of {} into {} lockstep recipe(s)", members, withinMods, lockstep);
        return merged;
    }

    private static List<List<FluidInteractionRecipe>> parts(List<FluidInteractionRecipe> group) {
        List<List<FluidInteractionRecipe>> parts = new ArrayList<>();
        byList(group).forEach(part -> parts.addAll(bySize(part)));
        return parts;
    }

    /** Without a free slot, one part per list that several members share. */
    private static List<List<FluidInteractionRecipe>> byList(List<FluidInteractionRecipe> group) {
        if (group.size() == 1) {
            return List.of(group);
        }
        List<FluidInteractionRecipe> rows = rows(group).shown();
        if (FluidInteractionRecipe.sharesSources(rows) || FluidInteractionRecipe.sharesNeighbors(rows)) {
            return List.of(group);
        }
        Map<List<Object>, Integer> holders = new HashMap<>();
        for (FluidInteractionRecipe member : group) {
            for (boolean source : new boolean[] {true, false}) {
                List<Object> key = listKey(member, source);
                if (key != null) {
                    holders.merge(key, 1, Integer::sum);
                }
            }
        }
        Map<List<Object>, List<FluidInteractionRecipe>> parts = new LinkedHashMap<>();
        for (FluidInteractionRecipe member : group) {
            List<Object> key = listKey(member, true);
            if (key == null || holders.get(key) < 2) {
                key = listKey(member, false);
            }
            if (key == null || holders.get(key) < 2) {
                key = List.of();
            }
            parts.computeIfAbsent(key, k -> new ArrayList<>()).add(member);
        }
        if (parts.size() > 1) {
            LOGGER.debug("Split {} recipe(s) of {} from {} into {} part(s) by the lists they share: {}", group.size(),
                    group.getFirst().owner(), group.getFirst().id(), parts.size(),
                    parts.values().stream().map(part -> part.getFirst().id() + " (" + part.size() + ")").toList());
        }
        return List.copyOf(parts.values());
    }

    /** The slot and its shared list of two or more entries, or null. */
    private static @Nullable List<Object> listKey(FluidInteractionRecipe member, boolean source) {
        Set<Set<Object>> lists = new HashSet<>();
        member.rowsOrSelf().forEach(row -> lists.add(Set.copyOf(entries(row, source))));
        Set<Object> list = lists.iterator().next();
        return lists.size() == 1 && list.size() > 1 ? List.of(source, list) : null;
    }

    /**
     * The group itself when its rows fit. Otherwise its members split by neighbor entries, then by source
     * fluids, in member order.
     */
    private static List<List<FluidInteractionRecipe>> bySize(List<FluidInteractionRecipe> group) {
        if (group.size() == 1 || rows(group).shown().size() <= MAX_ROWS) {
            return List.of(group);
        }
        List<List<FluidInteractionRecipe>> parts = new ArrayList<>();
        for (List<FluidInteractionRecipe> byNeighbors : split(group, FluidInteractionRecipe::neighborEntries)) {
            if (byNeighbors.size() == 1 || rows(byNeighbors).shown().size() <= MAX_ROWS) {
                parts.add(byNeighbors);
            } else {
                parts.addAll(split(byNeighbors, FluidInteractionRecipe::sourceFluids));
            }
        }
        return parts;
    }

    private static List<List<FluidInteractionRecipe>> split(List<FluidInteractionRecipe> group,
                                                            Function<FluidInteractionRecipe, List<?>> entries) {
        Map<Set<?>, List<FluidInteractionRecipe>> parts = new LinkedHashMap<>();
        group.forEach(member -> parts.computeIfAbsent(Set.copyOf(entries.apply(member)), key -> new ArrayList<>()).add(member));
        return List.copyOf(parts.values());
    }

    /**
     * The shown rows and the mirrors of a group.
     *
     * @param shown   the rows the slots show.
     * @param mirrors the members' own mirrors, then each row that an earlier shown row mirrors.
     */
    record Rows(List<FluidInteractionRecipe> shown, List<FluidInteractionRecipe> mirrors) {
    }

    /**
     * The members' rows in member order, without exact repeats and without mirrors. A source or neighbor list
     * that pairs with every other entry of the rows becomes one free slot.
     */
    static Rows rows(List<FluidInteractionRecipe> group) {
        Map<List<Object>, FluidInteractionRecipe> rows = new LinkedHashMap<>();
        for (FluidInteractionRecipe member : group.stream().flatMap(recipe -> recipe.rowsOrSelf().stream()).toList()) {
            Map<Object, List<Placement>> neighbors = new LinkedHashMap<>();
            member.neighbors().forEach(neighbor -> neighbors.computeIfAbsent(neighbor.slotEntry(), key -> new ArrayList<>()).add(neighbor));
            List<List<Placement>> entries = neighbors.isEmpty() ? List.of(List.of()) : List.copyOf(neighbors.values());
            for (Fluid fluid : member.sourceFluids()) {
                List<FluidState> sources = member.sources().stream().filter(state -> FluidInteractionRecipe.stillForm(state) == fluid).toList();
                for (List<Placement> neighbor : entries) {
                    FluidInteractionRecipe row = new FluidInteractionRecipe(member.sourceType(), member.id(), sources,
                            List.copyOf(neighbor), member.neighborOffset(), member.conditions(), member.results(), null,
                            member.owner(), member.inert());
                    rows.putIfAbsent(exact(row, true, true), row);
                }
            }
        }
        List<FluidInteractionRecipe> shown = new ArrayList<>();
        List<FluidInteractionRecipe> mirrors = new ArrayList<>();
        group.forEach(member -> mirrors.addAll(member.mirrors()));
        Set<List<Object>> kept = new HashSet<>();
        for (FluidInteractionRecipe row : rows.values()) {
            List<Object> mirrored = mirrored(row);
            if (mirrored != null && kept.contains(mirrored)) {
                mirrors.add(row);
            } else {
                kept.add(content(row));
                shown.add(row);
            }
        }
        return new Rows(free(free(List.copyOf(shown), true), false), List.copyOf(mirrors));
    }

    /** A row's offset, forms and results, with the order of the forms left out. */
    private static List<Object> content(FluidInteractionRecipe row) {
        return List.of(row.neighborOffset(), Set.copyOf(row.sources()), Set.copyOf(row.neighbors()), row.conditions(), row.results());
    }

    /**
     * The {@link #content} of a row turned half a circle about the point between the source and the neighbor.
     * Null for a vertical neighbor and for a neighbor that is not only a fluid.
     */
    @SuppressWarnings("deprecation")
    private static @Nullable List<Object> mirrored(FluidInteractionRecipe row) {
        BlockPos offset = row.neighborOffset();
        if (offset.getY() != 0 || row.neighbors().isEmpty() || !row.neighbors().stream().allMatch(LockstepMerger::isOnlyFluid)) {
            return null;
        }
        Set<FluidState> sources = row.neighbors().stream().map(Placement::effectiveFluid).collect(Collectors.toSet());
        Set<Placement> neighbors = row.sources().stream().map(Placement::ofFluid).collect(Collectors.toSet());
        Map<BlockPos, Placement> conditions = new HashMap<>();
        row.conditions().forEach((pos, placement) -> conditions.put(turned(offset, pos),
                new Placement(placement.block().rotate(Rotation.CLOCKWISE_180), placement.fluid())));
        Map<BlockPos, BlockState> results = new HashMap<>();
        row.results().forEach((pos, state) -> results.put(turned(offset, pos), state.rotate(Rotation.CLOCKWISE_180)));
        return List.of(offset, sources, neighbors, conditions, results);
    }

    private static boolean isOnlyFluid(Placement placement) {
        return placement.fluid() != null && placement.equals(Placement.ofFluid(placement.fluid()));
    }

    /** The position half a circle around the point between the source and the neighbor. */
    private static BlockPos turned(BlockPos offset, BlockPos pos) {
        return new BlockPos(offset.getX() - pos.getX(), pos.getY(), offset.getZ() - pos.getZ());
    }

    /** Joins rows that differ only in the source, or only in the neighbor, when every such group holds every value. */
    private static List<FluidInteractionRecipe> free(List<FluidInteractionRecipe> rows, boolean source) {
        List<Object> all = rows.stream().flatMap(row -> entries(row, source).stream()).distinct().toList();
        if (all.size() < 2) {
            return rows;
        }
        Map<List<Object>, List<FluidInteractionRecipe>> groups = new LinkedHashMap<>();
        for (FluidInteractionRecipe row : rows) {
            groups.computeIfAbsent(exact(row, !source, source), k -> new ArrayList<>()).add(row);
        }
        for (List<FluidInteractionRecipe> joined : groups.values()) {
            Set<Object> held = new HashSet<>();
            joined.forEach(row -> held.addAll(entries(row, source)));
            if (held.size() != all.size()) {
                return rows;
            }
        }
        List<FluidInteractionRecipe> free = new ArrayList<>(groups.size());
        for (List<FluidInteractionRecipe> joined : groups.values()) {
            FluidInteractionRecipe first = joined.getFirst();
            List<FluidState> sources = source
                    ? joined.stream().flatMap(row -> row.sources().stream())
                            .sorted(Comparator.comparingInt(state -> all.indexOf(FluidInteractionRecipe.stillForm(state)))).toList()
                    : first.sources();
            List<Placement> neighbors = source
                    ? first.neighbors()
                    : joined.stream().flatMap(row -> row.neighbors().stream())
                            .sorted(Comparator.comparingInt(neighbor -> all.indexOf(neighbor.slotEntry()))).toList();
            free.add(new FluidInteractionRecipe(first.sourceType(), first.id(), sources, neighbors, first.neighborOffset(),
                    first.conditions(), first.results(), null, first.owner(), first.inert()));
        }
        return free;
    }

    private static List<Object> entries(FluidInteractionRecipe row, boolean source) {
        return source ? List.copyOf(row.sourceFluids()) : row.neighborEntries();
    }

    /** A row's placements and results, with the source or neighbor left out when not kept. */
    private static List<Object> exact(FluidInteractionRecipe row, boolean source, boolean neighbor) {
        return Arrays.asList(source ? row.sources() : null, neighbor ? row.neighbors() : null, row.conditions(), row.results());
    }

    /**
     * A merge of several members into rows. The union of the sources and neighbors of the shown rows is the
     * recipe's. A merge that shows only one row is that row.
     */
    static FluidInteractionRecipe build(List<FluidInteractionRecipe> group, Rows rows, ResourceLocation id, @Nullable String owner) {
        FluidInteractionRecipe first = group.getFirst();
        int carried = group.stream().mapToInt(member -> member.mirrors().size()).sum();
        if (rows.mirrors().size() > carried) {
            LOGGER.debug("Dropped {} row(s) of {} from {} that show another row with the source and the neighbor swapped: {}",
                    rows.mirrors().size() - carried, owner, id, rows.mirrors().subList(carried, rows.mirrors().size()).stream()
                            .map(LockstepMerger::describe).toList());
        }
        if (rows.shown().size() == 1) {
            FluidInteractionRecipe row = rows.shown().getFirst();
            return new FluidInteractionRecipe(first.sourceType(), id, row.sources(), row.neighbors(), row.neighborOffset(),
                    row.conditions(), row.results(), null, owner, row.inert(), List.of(), rows.mirrors());
        }
        Set<FluidState> sources = new LinkedHashSet<>();
        Set<Placement> neighbors = new LinkedHashSet<>();
        Set<FluidState> inertSources = new LinkedHashSet<>();
        Set<Placement> inertNeighbors = new LinkedHashSet<>();
        for (FluidInteractionRecipe member : group) {
            sources.addAll(member.sources());
            neighbors.addAll(member.neighbors());
            inertSources.addAll(member.inert().sources());
            inertNeighbors.addAll(member.inert().neighbors());
        }
        sources.retainAll(rows.shown().stream().flatMap(row -> row.sources().stream()).collect(Collectors.toSet()));
        neighbors.retainAll(rows.shown().stream().flatMap(row -> row.neighbors().stream()).collect(Collectors.toSet()));
        inertSources.removeAll(sources);
        inertNeighbors.removeAll(neighbors);
        return new FluidInteractionRecipe(first.sourceType(), id, List.copyOf(sources), List.copyOf(neighbors),
                first.neighborOffset(), first.conditions(), first.results(), null, owner,
                new InertForms(List.copyOf(inertSources), List.copyOf(inertNeighbors)), rows.shown(), rows.mirrors());
    }

    private static String describe(FluidInteractionRecipe row) {
        return row.sourceFluids().stream().map(BuiltInRegistries.FLUID::getKey).toList() + " beside "
                + row.neighbors().stream().map(neighbor -> BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(neighbor.effectiveFluid())))
                        .distinct().toList();
    }

    /**
     * A tag of at least two values that EMI can show and whose every value one linked slot holds, or null. The
     * tag can cover part of the slot. EMI then shows the tag and the other entries, so the entry count changes.
     */
    private static @Nullable TagKey<?> filledTag(EmiTagView emiTags, FluidInteractionRecipe first, List<FluidInteractionRecipe> rows) {
        List<Function<FluidInteractionRecipe, Object>> slots = new ArrayList<>();
        if (!FluidInteractionRecipe.sharesSources(rows)) {
            slots.add(row -> row.sourceFluids().getFirst());
        }
        if (!first.neighbors().isEmpty() && !FluidInteractionRecipe.sharesNeighbors(rows)) {
            slots.add(row -> row.neighbors().getFirst().slotEntry());
        }
        for (BlockPos offset : first.conditions().keySet()) {
            slots.add(row -> row.conditions().get(offset).slotEntry());
        }
        for (BlockPos offset : first.results().keySet()) {
            slots.add(row -> Placement.ofBlock(row.results().get(offset)).slotEntry());
        }
        for (Function<FluidInteractionRecipe, Object> slot : slots) {
            Set<Object> entries = new HashSet<>();
            rows.forEach(row -> entries.add(slot.apply(row)));
            TagKey<?> tag = filledTag(emiTags, entries);
            if (tag != null) {
                return tag;
            }
        }
        return null;
    }

    /** EMI reads a slot as a tag only when every entry is an item, or every entry is a fluid. */
    private static @Nullable TagKey<?> filledTag(EmiTagView emiTags, Set<Object> entries) {
        if (entries.size() < 2) {
            return null;
        }
        if (entries.stream().allMatch(Item.class::isInstance)) {
            return filledTag(emiTags, BuiltInRegistries.ITEM, entries);
        }
        if (entries.stream().allMatch(Fluid.class::isInstance)) {
            return filledTag(emiTags, BuiltInRegistries.FLUID, entries);
        }
        return null;
    }

    private static <T> @Nullable TagKey<T> filledTag(EmiTagView emiTags, Registry<T> registry, Set<Object> entries) {
        return registry.getTags()
                .filter(pair -> fills(emiTags.values(registry, pair.getSecond()), entries))
                .map(pair -> pair.getFirst())
                .findFirst()
                .orElse(null);
    }

    private static <T> boolean fills(List<T> values, Set<Object> entries) {
        return values.size() >= 2 && entries.containsAll(values);
    }

    /** What the recipes of one merge share. Fluids and blocks can differ. */
    private record Shape(@Nullable String owner, String tier, BlockPos neighborOffset, Set<BlockPos> conditions,
                         Set<BlockPos> results, int forms, int neighborKinds) {
        static Shape of(FluidInteractionRecipe recipe) {
            return new Shape(recipe.owner(), tier(recipe.id()), recipe.neighborOffset(), Set.copyOf(recipe.conditions().keySet()),
                    Set.copyOf(recipe.results().keySet()), RecipeMerger.forms(recipe), RecipeMerger.neighborKinds(recipe));
        }

        private static String tier(ResourceLocation id) {
            String path = id.getPath();
            return path.startsWith("spread/") ? "spread" : path.startsWith("neighbor/") ? "neighbor" : "registry";
        }
    }
}
