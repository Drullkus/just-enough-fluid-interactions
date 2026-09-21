package us.drullk.jefi.jei.probe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;

/**
 * The spread tier: the hardening rules a fluid implements in its own spread code. A tick of the fluid finds
 * them.
 *
 * <p>Vanilla's stone is the reason this tier exists. Lava that flows down onto water becomes stone inside
 * {@code LavaFluid.spreadTo}, and no registry entry describes that. The javadoc of {@link FluidInteractionRegistry}
 * states that the registry tests every direction except down. It states that a fluid must handle the down
 * direction in {@code FlowingFluid#spreadTo}. NeoForge closed the request to move the stone into the registry
 * (issue 1880) as intended behavior. So the target below the source is the canonical one, and the target beside
 * it is the other.
 *
 * <p>The tier ticks only a fluid that declares spread code of its own below {@code FlowingFluid} and NeoForge's
 * {@code BaseFlowingFluid}. Those two classes only place the fluid. The fluid's own states are never a result.
 * Vanilla's fluids qualify because they implement {@code beforeDestroyingBlock} themselves. A mixin into one of
 * the fluid's own classes shows as a declaration too. A mixin into {@code FlowingFluid} itself does not, which
 * is what {@code forceProbe} is for.
 *
 * <p>Vanilla's gate lets a fluid enter only a block that holds fluid or that does not block motion. A fluid that
 * declares none of {@code tick}, {@code spread} and {@code canSpreadTo} keeps that gate. The tier offers such a
 * fluid only those blocks. The tier offers every fluid candidate to every fluid.
 *
 * <p>A fluid whose own spread code is {@code beforeDestroyingBlock} alone spreads exactly as vanilla does, except
 * inside that hook. Vanilla calls the hook with the block it is about to replace, right before it writes its own
 * fluid there. So the tier calls the hook by hand with each candidate, instead of a whole tick. The hook runs for
 * every candidate vanilla can enter, some of which the tick never reaches. The settle step drops those.
 */
public final class SpreadProber extends RuleProber {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<BlockPos> TARGETS = List.of(BELOW_OFFSET, FluidInteractionRecipe.NEIGHBOR_OFFSET);

    /** The methods a fluid must declare before it can write anything but its own states while it spreads. */
    private static final Set<String> SPREAD_METHODS =
            Set.of("tick", "spread", "spreadTo", "canSpreadTo", "getNewLiquid", "beforeDestroyingBlock");

    /** The subset of {@link #SPREAD_METHODS} that decides where a fluid can go at all. */
    private static final Set<String> REACH_METHODS = Set.of("tick", "spread", "canSpreadTo");

    /** The hook vanilla calls before it replaces a block with its fluid. Abstract in {@code FlowingFluid}. */
    private static final Set<String> DESTROY_HOOK = Set.of("beforeDestroyingBlock");

    private static final @Nullable MethodHandle DESTROY_HOOK_HANDLE = findDestroyHook();

    /** Where the class walk stops: these classes declare the spread behavior a fluid inherits. */
    private static final Set<String> BASE_FLUIDS = Set.of(
            "net.minecraft.world.level.material.Fluid", "net.minecraft.world.level.material.FlowingFluid");
    private static final String NEOFORGE_BASE_FLUID = "net.neoforged.neoforge.fluids.BaseFlowingFluid";

    /** Every fluid, then the blocks vanilla's gate admits. */
    private final List<Placement> reachableCandidates;
    /** Every fluid, then every block, for the fluids that decide for themselves where they can go. */
    private final List<Placement> allCandidates;
    /** Every fluid, then the blocks vanilla replaces: the ones it can enter that do not hold a fluid themselves. */
    private final List<Placement> destroyCandidates;
    /** Per source state, whether its own spread code is the destroy hook alone. */
    private final Map<FluidState, Boolean> hookAlone = new HashMap<>();

    public SpreadProber(SandboxLevel level, Settler settler, Candidates candidates) {
        super(LOGGER, level, settler, candidates.fluids, "fluid spread", "the fluid spread of", "spread/");
        List<Placement> reachableBlocks = candidates.blocks.stream().filter(SpreadProber::canHoldFluid).toList();
        this.reachableCandidates = concat(candidates.fluids, reachableBlocks);
        this.allCandidates = concat(candidates.fluids, candidates.blocks);
        this.destroyCandidates = concat(candidates.fluids,
                reachableBlocks.stream().filter(placement -> !(placement.block().getBlock() instanceof LiquidBlockContainer)).toList());
        LOGGER.debug("Fluid spread candidates: {} fluid state(s) and {} of {} block state(s) a fluid can enter",
                candidates.fluids.size(), reachableBlocks.size(), candidates.blocks.size());
    }

    @Override
    List<BlockPos> targets() {
        return TARGETS;
    }

    @Override
    boolean declaresHook(FluidState state) {
        return declaresAny(state, SPREAD_METHODS);
    }

    /** Both forms of a fluid get the same list: every block when one form decides its own reach. */
    @Override
    List<Placement> candidates(FluidState source, List<FluidState> probed) {
        if (hookAlone(source)) {
            return destroyCandidates;
        }
        Fluid still = FluidInteractionRecipe.stillForm(source);
        for (FluidState form : probed) {
            if (FluidInteractionRecipe.stillForm(form) == still && (forced(still) || declaresAny(form, REACH_METHODS))) {
                return allCandidates;
            }
        }
        return reachableCandidates;
    }

    @Override
    @Nullable String owner(FluidState source) {
        return InteractionOwners.ofFluid(FluidInteractionRecipe.stillForm(source));
    }

    @Override
    void callHook(SandboxLevel level, FluidState source, BlockPos target, Placement candidate) {
        if (hookAlone(source) && DESTROY_HOOK_HANDLE != null) {
            callDestroyHook(DESTROY_HOOK_HANDLE, (FlowingFluid) source.getType(), level, SandboxLevel.ORIGIN.offset(target), candidate.block());
        } else {
            source.tick(level, SandboxLevel.ORIGIN);
        }
    }

    private static @Nullable MethodHandle findDestroyHook() {
        try {
            Method hook = FlowingFluid.class.getDeclaredMethod("beforeDestroyingBlock", LevelAccessor.class, BlockPos.class, BlockState.class);
            hook.setAccessible(true);
            return MethodHandles.lookup().unreflect(hook);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("FlowingFluid.beforeDestroyingBlock is not reachable; every fluid gets the whole tick", e);
            return null;
        }
    }

    private static void callDestroyHook(MethodHandle hook, FlowingFluid fluid, SandboxLevel level, BlockPos pos, BlockState state) {
        try {
            hook.invokeExact(fluid, (LevelAccessor) level, pos, state);
        } catch (RuntimeException | LinkageError e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** The destroy hook takes the candidate as an argument, so no run answers for another. */
    @Override
    boolean readsTargetThroughLevel(FluidState source) {
        return !hookAlone(source);
    }

    /**
     * Whether this source state's own spread code is the destroy hook alone, so the tier calls that hook by
     * hand. A forced fluid always gets the whole tick, since its rule can live outside its classes.
     */
    private boolean hookAlone(FluidState source) {
        return hookAlone.computeIfAbsent(source, state -> {
            boolean alone = DESTROY_HOOK_HANDLE != null
                    && state.getType() instanceof FlowingFluid
                    && !forced(FluidInteractionRecipe.stillForm(state))
                    && declared(state.getType().getClass(), SPREAD_METHODS, SpreadProber::isBaseFluid).equals(DESTROY_HOOK);
            if (alone) {
                LOGGER.debug("Probing the fluid spread of {} through beforeDestroyingBlock alone, its only own spread code",
                        BuiltInRegistries.FLUID.getKey(state.getType()));
            }
            return alone;
        });
    }

    /** A spreading fluid writes its own states. Evaporation writes air. Only a different block or fluid is a result. */
    @Override
    Set<Fluid> ownFluids(FluidState source, Placement candidate) {
        return Set.of(FluidInteractionRecipe.stillForm(source));
    }

    @Override
    BlockPos primaryResultOffset(BlockPos target) {
        return target;
    }

    @Override
    void onRecipe(FluidInteractionRecipe recipe) {
        logFormDifference(recipe);
    }

    private boolean declaresAny(FluidState state, Set<String> methods) {
        return !Collections.disjoint(declared(state.getType().getClass(), SPREAD_METHODS, SpreadProber::isBaseFluid), methods);
    }

    private static boolean isBaseFluid(Class<?> type) {
        String name = type.getName();
        return BASE_FLUIDS.contains(name) || name.startsWith(NEOFORGE_BASE_FLUID);
    }

    /** Whether vanilla's gate lets a fluid into this state, which is what {@code FlowingFluid.canSpreadTo} asks. */
    private static boolean canHoldFluid(Placement placement) {
        BlockState state = placement.block();
        return state.getBlock() instanceof LiquidBlockContainer || !state.blocksMotion();
    }

    private static List<Placement> concat(List<Placement> fluids, List<Placement> blocks) {
        List<Placement> all = new ArrayList<>(fluids.size() + blocks.size());
        all.addAll(fluids);
        all.addAll(blocks);
        return List.copyOf(all);
    }

    /**
     * States at info level, once per recipe, that the spread of a fluid gave a result in one source form. In
     * the other form it wrote nothing. A mod author has no other way to see this, so the line is not debug. It
     * reports the observation and nothing more.
     */
    private static void logFormDifference(FluidInteractionRecipe recipe) {
        BlockState result = recipe.results().get(recipe.neighborOffset());
        for (FluidState inert : recipe.inert().sources()) {
            LOGGER.debug("Fluid spread form difference: {} changes {} {} into {} as {}; its {} form spreads over them without changing them",
                    BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(inert)),
                    recipe.neighbors().stream().map(SpreadProber::key).toList(),
                    recipe.neighborOffset().equals(BELOW_OFFSET) ? "below it" : "beside it",
                    result != null ? BuiltInRegistries.BLOCK.getKey(result.getBlock()) : recipe.results(),
                    recipe.matchesSourceForm() ? "a source block" : "a flowing block",
                    inert.isSource() ? "source" : "flowing");
        }
    }

    /** The registry id behind a placement, so a search by fluid or block id finds a form difference. */
    private static String key(Placement placement) {
        if (placement.isFluid()) {
            return String.valueOf(BuiltInRegistries.FLUID.getKey(placement.effectiveFluid().getType()));
        }
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(placement.block().getBlock()));
    }
}
