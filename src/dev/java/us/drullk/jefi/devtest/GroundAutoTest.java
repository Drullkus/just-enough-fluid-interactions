package us.drullk.jefi.devtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.probe.Settler;
import com.mojang.logging.LogUtils;

import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Development-only grounding run, enabled by {@code -Djustenoughfluidinteractions.groundtest=true} (see the
 * {@code clientGroundTest} run configuration). It creates the same flat creative world the recipe viewer smoke
 * tests use, waits for JEI to hand out the probed recipes, and then rebuilds every alternative of every one of
 * them in the integrated server's own {@link ServerLevel}. What the level settles on is compared with what the
 * probe settled on in its sandbox.
 *
 * <p>Arrangements are placed in cells on a grid, all of them at once, so one settling period covers a whole
 * batch. Cells are spaced far enough apart that a fluid escaping one falls away from the grid rather than
 * reaching the next, and each cell is emptied before it is reused.
 *
 * <p>A mismatch says the sandbox and a real level disagree at one position, which is the finding this run
 * exists to produce.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
public final class GroundAutoTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("justenoughfluidinteractions.groundtest");
    private static final String WORLD_NAME = "ground_fluid_interactions_test";
    private static final String PREFIX = "Ground test";

    /** Where the grid sits: well above the flat world's surface, so nothing it drops lands back on it. */
    private static final BlockPos GRID_ORIGIN = new BlockPos(8, 80, 8);
    private static final int CELL_SPACING = 8;
    private static final int CELLS_PER_ROW = 16;
    private static final int CELLS_PER_BATCH = CELLS_PER_ROW * CELLS_PER_ROW;
    /** The same budget the sandbox settles an arrangement with, so both sides are given the same time. */
    private static final int SETTLE_TICKS = Settler.SETTLE_TICKS;
    private static final int CLEAR_RADIUS = 3;
    private static final int CLEAR_BELOW = 5;
    private static final int CLEAR_ABOVE = 4;

    /**
     * Recipes whose every alternative is reported whole even when the level and the probe agree. Anything that
     * does not agree is reported whole anyway, so this is only for the arrangements worth reading either way.
     */
    private static final List<String> WATCHED = List.of(
            "neighbor/the_bumblezone/sugar_water/",
            "spread/minecraft/lava/minecraft/",
            "spread/divinerpg/smoldering_tar_fluid_type/",
            "justenoughfluidinteractions/");

    private static final ResourceLocation SUGAR_WATER = ResourceLocation.fromNamespaceAndPath("the_bumblezone", "sugar_water");

    private static int phase;
    private static int timer;

    private static volatile @Nullable List<GroundCheck.Alternative> queue;
    private static volatile boolean finished;

    private static int stage;
    private static int cursor;
    private static int wait;
    private static int grounded;
    private static int skipped;
    private static int mismatches;
    private static int recipeCount;
    private static List<GroundCheck.Alternative> inFlight = List.of();
    private static List<Case> cases = List.of();
    private static Set<ChunkPos> forced = Set.of();
    private static boolean casesInFlight;

    private GroundAutoTest() {
    }

    /** One arrangement built by hand rather than read from a recipe, to answer a question about a level. */
    private record Case(String label, Map<BlockPos, Placement> content, BlockPos offset, BlockPos last) {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (phase) {
            case 0 -> {
                if (AutoTestWorld.atTitleScreen(mc)) {
                    phase = 1;
                    AutoTestWorld.enterWorld(mc, WORLD_NAME, LOGGER, PREFIX);
                }
            }
            case 1 -> {
                if (mc.level != null && mc.player != null && mc.screen == null && FluidInteractionsJeiPlugin.runtime() != null) {
                    phase = 2;
                    timer = 40;
                }
            }
            case 2 -> {
                if (--timer <= 0) {
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (runtime == null) {
                        LOGGER.error("{} lost the JEI runtime before it could read the probed recipes", PREFIX);
                        phase = 4;
                        return;
                    }
                    List<FluidInteractionRecipe> recipes = runtime.getRecipeManager()
                            .createRecipeLookup(FluidInteractionsJeiPlugin.TYPE).get().toList();
                    start(recipes);
                    phase = 3;
                }
            }
            case 3 -> {
                if (finished) {
                    LOGGER.info("{} finished, stopping the client", PREFIX);
                    phase = 4;
                    mc.stop();
                }
            }
            default -> {
            }
        }
    }

    private static void start(List<FluidInteractionRecipe> recipes) {
        List<GroundCheck.Alternative> all = GroundCheck.alternatives(recipes);
        List<GroundCheck.Alternative> placeable = all.stream().filter(GroundCheck::placeable).toList();
        skipped = all.size() - placeable.size();
        recipeCount = (int) placeable.stream().map(GroundCheck.Alternative::recipe).distinct().count();
        LOGGER.info("{} found {} recipe(s) with {} alternative(s), {} of them holding a fluid no level can hold",
                PREFIX, recipes.size(), all.size(), skipped);
        all.stream().filter(alternative -> !GroundCheck.placeable(alternative))
                .forEach(alternative -> LOGGER.debug("{} cannot build {} ({}): the fluid has no block of its own",
                        PREFIX, alternative.recipe().id(), alternative.describe()));
        cases = buildCases();
        queue = placeable;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        List<GroundCheck.Alternative> pending = queue;
        if (!ENABLED || finished || pending == null) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        ServerLevel level = event.getServer().overworld();
        switch (stage) {
            case 0 -> {
                if (!inFlight.isEmpty()) {
                    verifyBatch(level);
                } else if (cursor < pending.size()) {
                    placeBatch(level, pending);
                } else {
                    stage = 1;
                }
            }
            case 1 -> {
                if (casesInFlight) {
                    reportCases(level);
                    stage = 2;
                } else if (cases.isEmpty()) {
                    stage = 2;
                } else {
                    placeCases(level);
                }
            }
            default -> {
                LOGGER.info("Grounded {} recipe alternative(s) of {} recipe(s): {} mismatch(es)",
                        grounded, recipeCount, mismatches);
                release(level);
                finished = true;
            }
        }
    }

    private static void placeBatch(ServerLevel level, List<GroundCheck.Alternative> pending) {
        List<GroundCheck.Alternative> batch = pending.subList(cursor, Math.min(cursor + CELLS_PER_BATCH, pending.size()));
        hold(level, batch.size());
        for (int i = 0; i < batch.size(); i++) {
            GroundCheck.Alternative alternative = batch.get(i);
            BlockPos origin = cell(i);
            GroundCheck.clear(level, origin, CLEAR_RADIUS, CLEAR_BELOW, CLEAR_ABOVE);
            GroundCheck.place(level, origin, GroundCheck.content(alternative), alternative.recipe().neighborOffset(), BlockPos.ZERO);
        }
        inFlight = List.copyOf(batch);
        wait = SETTLE_TICKS;
    }

    private static void verifyBatch(ServerLevel level) {
        for (int i = 0; i < inFlight.size(); i++) {
            GroundCheck.Alternative alternative = inFlight.get(i);
            BlockPos origin = cell(i);
            List<GroundCheck.Mismatch> found = GroundCheck.verify(level, origin, alternative);
            for (GroundCheck.Mismatch mismatch : found) {
                mismatches++;
                LOGGER.error("Grounding mismatch {} ({}): expected {} at {}, found {}",
                        alternative.recipe().id(), alternative.describe(), mismatch.expected(),
                        mismatch.offset().toShortString(), mismatch.found());
            }
            if (!found.isEmpty() || watched(alternative.recipe())) {
                LOGGER.info("Grounding detail {} ({}): {}", alternative.recipe().id(), alternative.describe(),
                        GroundCheck.describe(level, origin, reported(alternative)));
            }
            grounded++;
        }
        cursor += inFlight.size();
        inFlight = List.of();
    }

    private static void placeCases(ServerLevel level) {
        hold(level, cases.size());
        for (int i = 0; i < cases.size(); i++) {
            Case built = cases.get(i);
            BlockPos origin = cell(i);
            GroundCheck.clear(level, origin, CLEAR_RADIUS, CLEAR_BELOW, CLEAR_ABOVE);
            GroundCheck.place(level, origin, built.content(), built.offset(), built.last());
        }
        casesInFlight = true;
        wait = SETTLE_TICKS;
    }

    private static void reportCases(ServerLevel level) {
        for (int i = 0; i < cases.size(); i++) {
            Case built = cases.get(i);
            LOGGER.info("Grounding case {}: {}", built.label(),
                    GroundCheck.describe(level, cell(i), built.content().keySet()));
        }
        casesInFlight = false;
    }

    /** The offsets a reported line covers: everything the recipe places and everything it writes. */
    private static List<BlockPos> reported(GroundCheck.Alternative alternative) {
        Set<BlockPos> offsets = new LinkedHashSet<>(GroundCheck.content(alternative).keySet());
        offsets.addAll(alternative.recipe().results().keySet());
        return List.copyOf(offsets);
    }

    private static boolean watched(FluidInteractionRecipe recipe) {
        if (!recipe.conditions().isEmpty()) {
            return true;
        }
        String path = recipe.id().getPath();
        return WATCHED.stream().anyMatch(path::contains);
    }

    private static BlockPos cell(int index) {
        return GRID_ORIGIN.offset((index % CELLS_PER_ROW) * CELL_SPACING, 0, (index / CELLS_PER_ROW) * CELL_SPACING);
    }

    /** Forces the chunks the next batch needs, so its fluids tick wherever the player happens to be. */
    private static void hold(ServerLevel level, int cells) {
        Set<ChunkPos> needed = new LinkedHashSet<>();
        for (int i = 0; i < cells; i++) {
            BlockPos origin = cell(i);
            for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx += CLEAR_RADIUS) {
                for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz += CLEAR_RADIUS) {
                    needed.add(new ChunkPos(origin.offset(dx, 0, dz)));
                }
            }
        }
        if (needed.equals(forced)) {
            return;
        }
        release(level);
        needed.forEach(pos -> level.setChunkForced(pos.x, pos.z, true));
        forced = needed;
    }

    private static void release(ServerLevel level) {
        forced.forEach(pos -> level.setChunkForced(pos.x, pos.z, false));
        forced = Set.of();
    }

    /**
     * The arrangements a recipe does not describe but a question about one does: the Bumblezone's sugar water
     * and a lava-tagged fluid beside, above and below each other, in both forms of each, and — where they sit
     * side by side — in both orders of placing them, since a level runs the hooks of the block placed last first.
     */
    private static List<Case> buildCases() {
        Fluid sugarWater = sourceOfType(SUGAR_WATER);
        if (sugarWater == null) {
            LOGGER.warn("{} found no fluid of type {}, so its hand-built cases are skipped", PREFIX, SUGAR_WATER);
            return List.of();
        }
        BlockPos beside = FluidInteractionRecipe.NEIGHBOR_OFFSET;
        BlockPos above = new BlockPos(0, 1, 0);
        BlockPos below = new BlockPos(0, -1, 0);
        List<Case> built = new ArrayList<>();
        for (boolean sugarFlowing : List.of(false, true)) {
            for (boolean lavaFlowing : List.of(false, true)) {
                FluidState sugar = form(sugarWater, sugarFlowing);
                FluidState lava = form(Fluids.LAVA, lavaFlowing);
                built.add(built("sugar water at the source, lava poured beside it", sugar, lava, beside, beside));
                built.add(built("sugar water at the source, lava already beside it", sugar, lava, beside, BlockPos.ZERO));
                built.add(built("lava at the source, sugar water poured beside it", lava, sugar, beside, beside));
                built.add(built("lava at the source, sugar water already beside it", lava, sugar, beside, BlockPos.ZERO));
                built.add(built("sugar water at the source, lava poured above it", sugar, lava, above, above));
                built.add(built("sugar water at the source, lava poured below it", sugar, lava, below, below));
            }
        }
        return List.copyOf(built);
    }

    /** The source fluid of one fluid type, or null when nothing registers one. */
    private static @Nullable Fluid sourceOfType(ResourceLocation type) {
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid.defaultFluidState().isSource() && type.equals(NeoForgeRegistries.FLUID_TYPES.getKey(fluid.getFluidType()))) {
                return fluid;
            }
        }
        return null;
    }

    private static Case built(String what, FluidState source, FluidState neighbor, BlockPos offset, BlockPos last) {
        Map<BlockPos, Placement> content = new LinkedHashMap<>();
        content.put(BlockPos.ZERO, Placement.ofFluid(source));
        content.put(offset, Placement.ofFluid(neighbor));
        String label = what + " (" + form(source) + " with " + form(neighbor) + ")";
        return new Case(label, content, offset, last);
    }

    private static String form(FluidState state) {
        return BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(state)) + (state.isSource() ? " source" : " flowing");
    }

    private static FluidState form(Fluid fluid, boolean flowing) {
        if (!flowing || !(fluid instanceof FlowingFluid flow)) {
            return fluid.defaultFluidState();
        }
        return flow.getFlowing().defaultFluidState()
                .trySetValue(FlowingFluid.LEVEL, 7)
                .trySetValue(FlowingFluid.FALLING, false);
    }
}
