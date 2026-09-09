package us.drullk.jefi.jei.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
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

    private final SandboxLevel level;
    private final List<Placement> candidates;

    public SpreadProber(SandboxLevel level, List<Placement> fluidCandidates, List<Placement> blockCandidates) {
        this.level = level;
        this.candidates = new ArrayList<>(fluidCandidates.size() + blockCandidates.size());
        this.candidates.addAll(fluidCandidates);
        this.candidates.addAll(blockCandidates);
    }

    /**
     * Every recipe the spread of one fluid type produces, in display order: owner groups ranked as
     * {@link InteractionProber#OWNER_ORDER} ranks them, then the target position in probe order, then the result
     * block. A fluid that hardens nothing yields nothing; there are no failure recipes here.
     */
    public List<FluidInteractionRecipe> probe(FluidType type, List<FluidState> sources) {
        Map<Outcome, Group> outcomes = new LinkedHashMap<>();
        for (BlockPos target : TARGET_OFFSETS) {
            for (FluidState source : sources) {
                String owner = InteractionOwners.ofFluid(FluidInteractionRecipe.stillForm(source));
                for (Placement candidate : candidates) {
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
        return order(type, outcomes);
    }

    /**
     * Places one arrangement, ticks the source, and returns the writes that are a transformation rather than the
     * fluid spreading, keyed by offset from the source.
     */
    private Map<BlockPos, BlockState> run(FluidState source, BlockPos target, Placement candidate) {
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
                ordered.add(new FluidInteractionRecipe(type, -1, recipeId(typeKey, ownerSegment, n, variant++),
                        List.copyOf(entry.getValue().sources), List.copyOf(entry.getValue().neighbors),
                        outcome.target(), Map.of(), outcome.results(), null, owner));
            }
        }
        return ordered;
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
    }
}
