package us.drullk.jefi.devtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.Config;
import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.FluidInteractionCategory;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.InertFormIndicator;
import us.drullk.jefi.jei.ItemlessBlock;
import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.probe.RecipeIds;
import us.drullk.jefi.jei.probe.RuleProber;
import us.drullk.jefi.jei.scene.SceneArrangement;
import us.drullk.jefi.jei.scene.SceneVariant;
import us.drullk.jefi.jei.scene.SceneView;
import us.drullk.jefi.jei.scene.SlotLookup;
import com.mojang.logging.LogUtils;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Development-only smoke test. The system property {@code -Djustenoughfluidinteractions.jeiautotest=true} enables
 * it (see the {@code clientJeiTest} run configuration). It deletes any existing save and creates a fresh flat
 * creative world. It opens this mod's JEI category, screenshots a few pages of recipes into
 * {@code run/screenshots}, and exits the game.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
public final class JeiAutoTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("justenoughfluidinteractions.jeiautotest");
    private static final String WORLD_NAME = "jei_fluid_interactions_test";
    private static final String PREFIX = "Smoke test";
    private static final int RECIPES_PER_SHOT = 2;
    private static final int MAX_SHOTS = 13;
    private static final ResourceLocation LAVA_TYPE = ResourceLocation.withDefaultNamespace("lava");
    /** The Bumblezone's sugar water hardens from its liquid block's update hooks, which only the neighbor tier calls. */
    private static final String SUGAR_WATER_IDS = "neighbor/the_bumblezone/sugar_water/";
    private static final ResourceLocation SUGAR_INFUSED_STONE = ResourceLocation.fromNamespaceAndPath("the_bumblezone", "sugar_infused_stone");
    private static final ResourceLocation SUGAR_INFUSED_COBBLESTONE = ResourceLocation.fromNamespaceAndPath("the_bumblezone", "sugar_infused_cobblestone");
    private static final ResourceLocation SUGAR_WATER = ResourceLocation.fromNamespaceAndPath("the_bumblezone", "sugar_water");
    /** DivineRPG's lava-tagged tar, the second fluid that reaches sugar water and the second spread recipe to lose it. */
    private static final ResourceLocation TAR_TYPE = ResourceLocation.fromNamespaceAndPath("divinerpg", "smoldering_tar_fluid_type");
    private static final String TAR_SPREAD_IDS = "spread/divinerpg/smoldering_tar_fluid_type/";
    /** Vanilla's stone, which lava's own spread code writes into a water-tagged fluid below it. */
    private static final String LAVA_SPREAD_IDS = "spread/minecraft/lava/";
    /** The Bumblezone's honey, whose result block changes a neighbor of its own on placement. */
    private static final String HONEY_IDS = "neighbor/the_bumblezone/honey/";
    /** The same honey as a fluid type, which Biomes O' Plenty registers an interaction on. */
    private static final ResourceLocation HONEY_TYPE = ResourceLocation.fromNamespaceAndPath("the_bumblezone", "honey");
    /** The block a level holds at the position that interaction targets. It is the honey the probe placed there. */
    private static final ResourceLocation HONEY_BLOCK = ResourceLocation.fromNamespaceAndPath("the_bumblezone", "honey_fluid_block");
    private static final String BOP = "biomesoplenty";
    /** The neighbor Biomes O' Plenty's interaction looks for, and the two blocks it writes. */
    private static final ResourceLocation BLOOD = ResourceLocation.fromNamespaceAndPath(BOP, "blood");
    private static final List<ResourceLocation> FLESH = List.of(
            ResourceLocation.fromNamespaceAndPath(BOP, "flesh"),
            ResourceLocation.fromNamespaceAndPath(BOP, "porous_flesh"));

    /** The test also screenshots every recipe whose id contains one of these comma-separated texts. */
    private static final @Nullable String SHOTS_FILTER = normalizeFilter(System.getProperty("justenoughfluidinteractions.shots"));

    private static int shot;
    private static List<FluidInteractionRecipe> recipes = List.of();
    private static List<FluidInteractionRecipe> customShots = List.of();
    private static int customShot;
    private static @Nullable FluidInteractionRecipe spreadRecipe;
    private static @Nullable FluidInteractionRecipe formRecipe;
    private static @Nullable FluidInteractionRecipe neighborRecipe;
    private static @Nullable FluidInteractionRecipe cascadeRecipe;
    private static @Nullable FluidInteractionRecipe offsetRecipe;
    private static @Nullable FluidInteractionRecipe preemptedRecipe;
    private static @Nullable FluidInteractionRecipe blocklessRecipe;
    private static @Nullable FluidInteractionRecipe withinRecipe;

    /** The recipes that get a screenshot of their own, in this order. Each is read when its step runs. */
    private static final List<Shot> SHOTS = List.of(
            new Shot("spread", () -> spreadRecipe),
            new Shot("form", () -> formRecipe),
            new Shot("neighbor", () -> neighborRecipe),
            new Shot("cascade", () -> cascadeRecipe),
            new Shot("offset", () -> offsetRecipe),
            new Shot("preempted", () -> preemptedRecipe),
            new Shot("blockless", () -> blocklessRecipe, true),
            new Shot("merged_within", () -> withinRecipe),
            new Shot("merged_within_later", () -> withinRecipe));

    private static final TickSteps STEPS = steps();

    private JeiAutoTest() {
    }

    /** @param focused JEI opens the recipe from a focus on its first output. */
    private record Shot(String name, Supplier<@Nullable FluidInteractionRecipe> recipe, boolean focused) {
        Shot(String name, Supplier<@Nullable FluidInteractionRecipe> recipe) {
            this(name, recipe, false);
        }
    }

    /**
     * The test runs as a list of steps. It enters the world. It reads and checks the recipes. It screenshots
     * the category and its pages. It screenshots each recipe of {@link #SHOTS}. It stops the client.
     */
    private static TickSteps steps() {
        TickSteps steps = new TickSteps()
                .until(AutoTestWorld::atTitleScreen, mc -> AutoTestWorld.enterWorld(mc, WORLD_NAME, LOGGER, PREFIX))
                .until(JeiAutoTest::inWorld, JeiAutoTest::setGuiScale)
                .after(40, JeiAutoTest::inspectRecipes)
                .after(30, mc -> grab(mc, "category"))
                .repeat(25, JeiAutoTest::nextPage)
                .after(10, mc -> show(SHOTS.getFirst()));
        for (int i = 0; i < SHOTS.size(); i++) {
            Shot shot = SHOTS.get(i);
            Shot next = i + 1 < SHOTS.size() ? SHOTS.get(i + 1) : null;
            steps.after(25, mc -> {
                grab(mc, shot);
                if (next != null) {
                    show(next);
                }
            });
        }
        steps.after(10, JeiAutoTest::showFirstCustomShot)
                .after(25, mc -> {
                })
                .repeat(25, JeiAutoTest::nextCustomShot);
        return steps.after(10, mc -> {
            LOGGER.info("Smoke test finished, stopping the client");
            mc.stop();
        });
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (ENABLED) {
            STEPS.tick(Minecraft.getInstance());
        }
    }

    private static boolean inWorld(Minecraft mc) {
        return mc.level != null && mc.player != null && mc.screen == null && FluidInteractionsJeiPlugin.runtime() != null;
    }

    private static void setGuiScale(Minecraft mc) {
        mc.options.guiScale().set(2);
        mc.resizeDisplay();
    }

    /** Reads the probed recipes from JEI, runs every check on them, and opens the category. */
    private static void inspectRecipes(Minecraft mc) {
        IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
        if (runtime == null) {
            LOGGER.error("JEI runtime disappeared before the smoke test could open its category");
            STEPS.stop();
            mc.stop();
            return;
        }
        recipes = runtime.getRecipeManager().createRecipeLookup(FluidInteractionsJeiPlugin.TYPE).get().toList();
        LOGGER.info("Smoke test found {} fluid interaction recipe(s)", recipes.size());
        if (SHOTS_FILTER != null) {
            List<String> texts = List.of(SHOTS_FILTER.split(","));
            customShots = recipes.stream().filter(recipe -> texts.stream().anyMatch(recipe.id().toString()::contains)).toList();
            LOGGER.info("{} shots: {} recipe(s) match \"{}\"", PREFIX, customShots.size(), SHOTS_FILTER);
        }
        checkAlternatives(recipes);
        checkMerging(recipes);
        checkMergedAcrossMods(recipes);
        checkMergedWithinMod(recipes);
        checkLockstepPairs(recipes);
        checkMirrorRows(recipes);
        checkDirection(recipes);
        checkFlowingNeighbor(recipes);
        checkSpreadRecipe(recipes);
        checkOwnerOrder(recipes);
        checkFormDifference(recipes);
        checkPreempted(recipes);
        checkNeighborRecipe(recipes);
        checkOwnRule(recipes);
        checkIndicator();
        checkCascade(recipes);
        checkOffsetRecipe(recipes);
        checkPreemptedInteraction(recipes);
        checkPreemptedThirdPartyInteraction(recipes);
        checkBlockless(recipes);
        checkWaterlog(recipes);
        logInventory(recipes, List.of("alltheores", "colouredstuff", "create_dragons_plus", "mingle"));
        logRecipeIds(recipes);
        runtime.getRecipesGui().showTypes(List.of(FluidInteractionsJeiPlugin.TYPE));
    }

    /** Screenshots the page on screen, then shows the next page of recipes. False once every page is shot. */
    private static boolean nextPage(Minecraft mc) {
        if (shot > 0) {
            grab(mc, "recipes_" + shot);
        }
        int from = shot * RECIPES_PER_SHOT;
        shot++;
        if (shot >= MAX_SHOTS || from >= recipes.size()) {
            return false;
        }
        show(recipes.subList(from, Math.min(from + RECIPES_PER_SHOT, recipes.size())));
        return true;
    }

    private static void show(Shot shot) {
        FluidInteractionRecipe recipe = shot.recipe().get();
        IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
        if (recipe == null || runtime == null) {
            return;
        }
        IFocus<?> focus = shot.focused() ? outputFocus(runtime, recipe) : null;
        if (focus != null) {
            runtime.getRecipesGui().show(focus);
        } else {
            show(List.of(recipe));
        }
    }

    /** A focus on a recipe's first output, as a click on that slot makes. */
    private static @Nullable IFocus<?> outputFocus(IJeiRuntime runtime, FluidInteractionRecipe recipe) {
        IRecipeSlotView output = outputSlots(runtime, recipe).stream().findFirst().orElse(null);
        ITypedIngredient<?> shown = output == null ? null : output.getDisplayedIngredient().orElse(null);
        return shown == null ? null : runtime.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT, shown);
    }

    private static List<IRecipeSlotView> outputSlots(IJeiRuntime runtime, FluidInteractionRecipe recipe) {
        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
        return runtime.getRecipeManager()
                .createRecipeLayoutDrawable(category, recipe, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .map(IRecipeLayoutDrawable::getRecipeSlotsView)
                .map(view -> view.getSlotViews(RecipeIngredientRole.OUTPUT))
                .orElse(List.of());
    }

    /** The tooltip lines of each output slot, with advanced tooltips on. */
    @SuppressWarnings("removal")
    private static List<List<String>> outputTooltips(IJeiRuntime runtime, FluidInteractionRecipe recipe) {
        var options = Minecraft.getInstance().options;
        boolean advanced = options.advancedItemTooltips;
        options.advancedItemTooltips = true;
        try {
            return outputSlots(runtime, recipe).stream()
                    .map(slot -> slot instanceof IRecipeSlotDrawable drawable
                            ? drawable.getTooltip().stream().map(Component::getString).toList()
                            : List.<String>of())
                    .toList();
        } finally {
            options.advancedItemTooltips = advanced;
        }
    }

    private static void show(List<FluidInteractionRecipe> page) {
        IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
        if (runtime == null) {
            return;
        }
        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
        runtime.getRecipesGui().showRecipes(category, page, List.of());
    }

    private static void grab(Minecraft mc, Shot shot) {
        if (shot.recipe().get() != null) {
            grab(mc, shot.name());
        }
    }

    private static void showFirstCustomShot(Minecraft mc) {
        if (!customShots.isEmpty()) {
            show(List.of(customShots.getFirst()));
        }
    }

    /** Screenshots the shown recipe and shows the next match. False when no match is left. */
    private static boolean nextCustomShot(Minecraft mc) {
        if (customShot < customShots.size()) {
            FluidInteractionRecipe recipe = customShots.get(customShot);
            grab(mc, "shot_" + sanitize(recipe.id().toString()));
            IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
            if (runtime != null) {
                LOGGER.info("{} shot {}: output tooltip(s) {}", PREFIX, recipe.id(), outputTooltips(runtime, recipe));
            }
            customShot++;
        }
        if (customShot >= customShots.size()) {
            return false;
        }
        show(List.of(customShots.get(customShot)));
        return true;
    }

    private static String sanitize(String id) {
        return id.replace(':', '_').replace('/', '_');
    }

    private static @Nullable String normalizeFilter(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Neighbor alternatives cycle in a JEI slot, so a repeated one is a defect rather than a display choice. */
    private static void checkAlternatives(List<FluidInteractionRecipe> found) {
        int offenders = 0;
        for (FluidInteractionRecipe recipe : found) {
            long distinct = recipe.neighbors().stream().distinct().count();
            if (distinct != recipe.neighbors().size()) {
                offenders++;
                LOGGER.error("Smoke test found duplicate neighbor alternatives in {}: {} of {} are distinct",
                        recipe.id(), distinct, recipe.neighbors().size());
            }
            LOGGER.info("Smoke test recipe {} (from {}): {} source state(s), {} neighbor alternative(s) {}",
                    recipe.id(), recipe.owner(), recipe.sources().size(), recipe.neighbors().size(),
                    recipe.neighbors().stream().map(placement -> placement.describe().getString()).toList());
        }
        LOGGER.info("Smoke test checked {} recipe(s) for duplicate neighbor alternatives, {} offender(s)", found.size(), offenders);
    }

    /**
     * The dev interactions registered for two fluid types with the same neighbors and result must arrive as one
     * recipe. That recipe must hold every source fluid and every neighbor. This happens only if both merge
     * passes ran.
     */
    private static void checkMerging(List<FluidInteractionRecipe> found) {
        BlockState result = JeiAutoTestInteractions.MERGE_RESULT.defaultBlockState();
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> result.equals(recipe.resultAtSource()))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected the mergeable dev interactions to collapse into one recipe, found {}", matches.size());
            return;
        }
        FluidInteractionRecipe merged = matches.getFirst();
        List<Fluid> fluids = merged.sourceFluids();
        LOGGER.info("Smoke test merged recipe {} (from {}): source fluid(s) {}, neighbor alternative(s) {}",
                merged.id(), merged.owner(),
                fluids.stream().map(fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString()).toList(),
                merged.neighbors().stream().map(placement -> placement.describe().getString()).toList());
        List<Fluid> expectedFluids = JeiAutoTestInteractions.MERGE_TYPES.stream()
                .flatMap(type -> BuiltInRegistries.FLUID.stream()
                        .filter(fluid -> fluid.getFluidType() == type.value() && fluid.defaultFluidState().isSource()))
                .toList();
        for (Fluid fluid : expectedFluids) {
            if (!fluids.contains(fluid)) {
                LOGGER.error("Smoke test merged recipe {} is missing source fluid {}", merged.id(), BuiltInRegistries.FLUID.getKey(fluid));
            }
        }
        for (Block neighbor : JeiAutoTestInteractions.MERGE_NEIGHBORS) {
            if (merged.neighbors().stream().noneMatch(placement -> placement.block().is(neighbor))) {
                LOGGER.error("Smoke test merged recipe {} is missing neighbor alternative {}", merged.id(), BuiltInRegistries.BLOCK.getKey(neighbor));
            }
        }
    }

    /** The obsidian recipes of fluid neighbors merge across mods. A block neighbor stays apart. */
    private static void checkMergedAcrossMods(List<FluidInteractionRecipe> found) {
        Map<BlockPos, BlockState> obsidian = Map.of(BlockPos.ZERO, Blocks.OBSIDIAN.defaultBlockState());
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> LAVA_TYPE.equals(RecipeIds.keyOf(recipe.sourceType())))
                .filter(recipe -> obsidian.equals(recipe.results()) && recipe.conditions().isEmpty())
                .filter(recipe -> recipe.neighborOffset().equals(FluidInteractionRecipe.NEIGHBOR_OFFSET))
                .filter(recipe -> recipe.matchesSourceForm() && !recipe.matchesFlowingForm())
                .toList();
        List<FluidInteractionRecipe> fluids = matches.stream()
                .filter(recipe -> recipe.neighbors().stream().allMatch(Placement::isFluid))
                .toList();
        List<FluidInteractionRecipe> blocks = matches.stream()
                .filter(recipe -> recipe.neighbors().stream().noneMatch(Placement::isFluid))
                .toList();
        if (fluids.size() != 1 || blocks.size() != 1 || matches.size() != 2) {
            LOGGER.error("Smoke test expected one obsidian recipe of lava beside fluids and one beside blocks, found {} and {} of {}: {}",
                    fluids.size(), blocks.size(), matches.size(), matches.stream().map(recipe -> recipe.id().toString()).toList());
            return;
        }
        FluidInteractionRecipe merged = fluids.getFirst();
        Set<String> namespaces = merged.neighbors().stream()
                .map(placement -> BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(placement.effectiveFluid())).getNamespace())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (namespaces.size() < 2) {
            LOGGER.error("Smoke test expected the obsidian recipe {} to hold the fluids of several mods, found {}", merged.id(), namespaces);
        }
        if (!merged.id().getPath().equals("minecraft/lava/merged/0/0") || merged.owner() != null) {
            LOGGER.error("Smoke test expected the obsidian recipe {} (from {}) to have the id minecraft/lava/merged/0/0 and no owner",
                    merged.id(), merged.owner());
        }
        FluidInteractionRecipe block = blocks.getFirst();
        boolean snow = block.neighbors().stream().anyMatch(placement -> placement.block().is(JeiAutoTestInteractions.OBSIDIAN_BLOCK_NEIGHBOR));
        if (!snow) {
            LOGGER.error("Smoke test expected the obsidian recipe {} beside blocks to hold {}", block.id(),
                    BuiltInRegistries.BLOCK.getKey(JeiAutoTestInteractions.OBSIDIAN_BLOCK_NEIGHBOR));
        }
        LOGGER.info("Smoke test merged across mods {} (from {}): {} neighbor fluid(s) from {}, apart from {} (from {}) beside {}",
                merged.id(), merged.owner(), fluidCount(merged), namespaces,
                block.id(), block.owner(), block.neighbors().stream().map(JeiAutoTest::alternativeKey).toList());
    }

    /** Every slot of a lockstep recipe shows the same row, also under a focus. */
    private static void checkMergedWithinMod(List<FluidInteractionRecipe> found) {
        IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
        if (runtime == null) {
            return;
        }
        List<FluidInteractionRecipe> lockstep = found.stream().filter(FluidInteractionRecipe::isLockstep).toList();
        for (FluidInteractionRecipe recipe : lockstep) {
            List<String> offenders = new ArrayList<>();
            String summary = checkLockstep(runtime, recipe, offenders);
            if (offenders.isEmpty()) {
                LOGGER.info("Smoke test merged within a mod {} (from {}): {}", recipe.id(), recipe.owner(), summary);
            } else {
                LOGGER.error("Smoke test merged within a mod {} (from {}) is out of step: {}", recipe.id(), recipe.owner(), offenders);
            }
        }
        withinRecipe = lockstep.stream()
                .filter(recipe -> PackRun.ACTIVE || AutoTestConfig.LOCKSTEP_MOD.equals(recipe.owner()))
                .findFirst()
                .orElse(null);
        if (PackRun.ACTIVE) {
            LOGGER.info("Smoke test merged within a mod: {} lockstep recipe(s)", lockstep.size());
            return;
        }
        FluidInteractionRecipe recipe = withinRecipe;
        long sources = recipe == null ? 0 : recipe.rows().stream().map(row -> row.sourceFluids().getFirst()).distinct().count();
        long results = recipe == null ? 0 : recipe.rows().stream().map(FluidInteractionRecipe::resultAtSource).distinct().count();
        if (recipe == null || sources < 2 || results < 2) {
            LOGGER.error("Smoke test expected a lockstep recipe of {} with several sources and results, found {} with {} source(s) and {} result(s)",
                    AutoTestConfig.LOCKSTEP_MOD, recipe == null ? "none" : recipe.id(), sources, results);
        }
    }

    /** Rows that show different entries never repeat a pair with one result. A pair and its mirror are one pair. */
    private static void checkLockstepPairs(List<FluidInteractionRecipe> found) {
        List<FluidInteractionRecipe> lockstep = found.stream().filter(FluidInteractionRecipe::isLockstep).toList();
        int repeated = 0;
        int mirrors = 0;
        for (FluidInteractionRecipe recipe : lockstep) {
            mirrors += recipe.mirrors().size();
            Set<String> seen = new LinkedHashSet<>();
            Set<String> repeats = new LinkedHashSet<>();
            Map<List<Object>, FluidInteractionRecipe> shown = new LinkedHashMap<>();
            recipe.rows().forEach(row -> shown.putIfAbsent(List.of(row.sourceFluids(), row.neighborEntries(), describe(row.results())), row));
            for (FluidInteractionRecipe row : shown.values()) {
                Set<String> pairs = new LinkedHashSet<>();
                for (Fluid source : row.sourceFluids()) {
                    for (Object neighbor : row.neighborEntries()) {
                        pairs.add(pairKey(row, source, neighbor));
                    }
                }
                for (String pair : pairs) {
                    if (!seen.add(pair)) {
                        repeats.add(pair);
                    }
                }
            }
            if (!repeats.isEmpty()) {
                repeated += repeats.size();
                LOGGER.error("Smoke test lockstep pairs: {} (from {}) repeats {}", recipe.id(), recipe.owner(), repeats);
            }
        }
        LOGGER.info("Smoke test lockstep pairs: {} lockstep recipe(s), {} repeated pair(s), {} mirror row(s)", lockstep.size(), repeated, mirrors);
    }

    /** One pair of a row as text. A horizontal pair reads the same from either of its two positions. */
    private static String pairKey(FluidInteractionRecipe row, Fluid source, Object neighbor) {
        String plain = pairText(row, source, neighbor, false);
        if (row.neighborOffset().getY() != 0) {
            return plain;
        }
        String turned = pairText(row, source, neighbor, true);
        return plain.compareTo(turned) <= 0 ? plain : turned;
    }

    /** What each position holds and becomes, sorted. Turned means half a circle about the middle of the pair. */
    @SuppressWarnings("deprecation")
    private static String pairText(FluidInteractionRecipe row, Fluid source, Object neighbor, boolean turned) {
        BlockPos offset = row.neighborOffset();
        Map<BlockPos, String> placed = new HashMap<>();
        placed.put(BlockPos.ZERO, entryKey(source));
        placed.put(offset, entryKey(neighbor));
        row.conditions().forEach((pos, placement) -> placed.put(pos, entryKey(placement.slotEntry())));
        Set<BlockPos> positions = new HashSet<>(placed.keySet());
        positions.addAll(row.results().keySet());
        List<String> parts = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockState result = row.results().get(pos);
            BlockPos at = turned ? new BlockPos(offset.getX() - pos.getX(), pos.getY(), offset.getZ() - pos.getZ()) : pos;
            String becomes = result == null ? "kept" : String.valueOf(turned ? result.rotate(Rotation.CLOCKWISE_180) : result);
            parts.add(at.toShortString() + "=" + placed.getOrDefault(pos, "air") + "->" + becomes);
        }
        return parts.stream().sorted().toList().toString();
    }

    /** The dev mirror pair shows one row in its lockstep recipe. The other direction is a mirror of that row. */
    private static void checkMirrorRows(List<FluidInteractionRecipe> found) {
        if (PackRun.ACTIVE) {
            return;
        }
        BlockState result = JeiAutoTestInteractions.MIRROR_RESULT.defaultBlockState();
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.rowsOrSelf().stream().anyMatch(row -> result.equals(row.resultAtSource())))
                .toList();
        FluidInteractionRecipe recipe = matches.size() == 1 ? matches.getFirst() : null;
        long rows = recipe == null ? 0 : recipe.rows().stream().filter(row -> result.equals(row.resultAtSource())).count();
        long mirrors = recipe == null ? 0 : recipe.mirrors().stream().filter(row -> result.equals(row.resultAtSource())).count();
        if (recipe == null || !recipe.isLockstep() || recipe.rows().size() != 2 || rows != 1 || mirrors != 1
                || !JeiAutoTestInteractions.MIRROR_OWNER.equals(recipe.owner())) {
            LOGGER.error("Smoke test expected one lockstep recipe of {} with 2 rows, 1 row and 1 mirror of {}, found {} recipe(s): {}",
                    JeiAutoTestInteractions.MIRROR_OWNER, BuiltInRegistries.BLOCK.getKey(JeiAutoTestInteractions.MIRROR_RESULT), matches.size(),
                    matches.stream().map(match -> match.id() + " (from " + match.owner() + ", " + match.rows().size() + " row(s), "
                            + match.mirrors().size() + " mirror(s))").toList());
            return;
        }
        LOGGER.info("Smoke test mirror rows {} (from {}): rows {}, mirrors {}", recipe.id(), recipe.owner(),
                recipe.rows().stream().map(JeiAutoTest::describeRow).toList(), recipe.mirrors().stream().map(JeiAutoTest::describeRow).toList());
    }

    private static String describeRow(FluidInteractionRecipe row) {
        return row.sources().stream().map(state -> Texts.form(state).getString()).toList() + " + "
                + row.neighbors().stream().map(neighbor -> neighbor.describe().getString()).toList() + " -> " + describe(row.results());
    }

    /** The two-direction Gaia fixture frees the source slot and the neighbor slot. */
    private static void checkDirection(List<FluidInteractionRecipe> found) {
        if (PackRun.ACTIVE) {
            return;
        }
        Set<String> list = JeiAutoTestInteractions.DIRECTION_LIST.stream()
                .map(type -> String.valueOf(BuiltInRegistries.FLUID.getKey(fluidOf(type)))).collect(Collectors.toSet());
        Set<String> partners = JeiAutoTestInteractions.DIRECTION_PARTNERS.keySet().stream()
                .map(type -> String.valueOf(BuiltInRegistries.FLUID.getKey(fluidOf(type)))).collect(Collectors.toSet());
        FluidInteractionRecipe bySource = null;
        FluidInteractionRecipe byNeighbor = null;
        for (FluidInteractionRecipe recipe : found.stream().filter(FluidInteractionRecipe::isLockstep).toList()) {
            Set<String> sources = recipe.rows().stream().flatMap(row -> row.sourceFluids().stream()).map(JeiAutoTest::entryKey).collect(Collectors.toSet());
            Set<String> neighbors = recipe.rows().stream().flatMap(row -> row.neighborEntries().stream()).map(JeiAutoTest::entryKey).collect(Collectors.toSet());
            if (FluidInteractionRecipe.sharesSources(recipe.rows()) && sources.equals(list) && neighbors.containsAll(partners)) {
                bySource = recipe;
            } else if (FluidInteractionRecipe.sharesNeighbors(recipe.rows()) && neighbors.equals(list) && sources.containsAll(partners)) {
                byNeighbor = recipe;
            }
        }
        if (bySource == null || byNeighbor == null) {
            LOGGER.error("Smoke test direction: expected a lockstep recipe with the free source slot {} and one with the free neighbor slot {}, found {} and {}",
                    list, list, bySource == null ? "none" : bySource.id(), byNeighbor == null ? "none" : byNeighbor.id());
            return;
        }
        LOGGER.info("Smoke test direction: {} (from {}) frees the source slot with {} row(s), {} (from {}) frees the neighbor slot with {} row(s)",
                bySource.id(), bySource.owner(), bySource.rows().size(), byNeighbor.id(), byNeighbor.owner(), byNeighbor.rows().size());
    }

    /** The still fluid of a fluid type, or water when the type has none. */
    private static Fluid fluidOf(ResourceLocation type) {
        FluidType fluidType = NeoForgeRegistries.FLUID_TYPES.get(type);
        return BuiltInRegistries.FLUID.stream()
                .filter(fluid -> fluid.getFluidType() == fluidType && fluid.defaultFluidState().isSource())
                .findFirst()
                .orElse(Fluids.WATER);
    }

    /** The registry key of a slot entry: a fluid, an item or a block. */
    private static String entryKey(Object entry) {
        return String.valueOf(switch (entry) {
            case Fluid fluid -> BuiltInRegistries.FLUID.getKey(fluid);
            case Item item -> BuiltInRegistries.ITEM.getKey(item);
            case Block block -> BuiltInRegistries.BLOCK.getKey(block);
            default -> entry;
        });
    }

    /** The rows each linked slot holds, the scene against the slots while they cycle, and a focus on one result. */
    private static String checkLockstep(IJeiRuntime runtime, FluidInteractionRecipe recipe, List<String> offenders) {
        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
        IRecipeLayoutDrawable<FluidInteractionRecipe> layout = runtime.getRecipeManager()
                .createRecipeLayoutDrawable(category, recipe, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .orElse(null);
        if (layout == null) {
            offenders.add("no layout");
            return "";
        }
        List<FluidInteractionRecipe> rows = FluidInteractionCategory.visibleRows(runtime.getJeiHelpers().getIngredientVisibility(), recipe);
        Map<String, Function<FluidInteractionRecipe, Placement>> linked = linkedEntries(recipe);
        IRecipeSlotsView view = layout.getRecipeSlotsView();
        for (var entry : linked.entrySet()) {
            IRecipeSlotView slot = view.findSlotByName(entry.getKey()).orElse(null);
            List<ITypedIngredient<?>> held = slot == null ? List.of() : slot.getAllIngredientsList();
            if (held.size() != rows.size()) {
                offenders.add(entry.getKey() + " holds " + held.size() + " of " + rows.size() + " row(s)");
                continue;
            }
            for (int i = 0; i < held.size(); i++) {
                ITypedIngredient<?> typed = held.get(i);
                if (typed == null || !SceneArrangement.shows(entry.getValue().apply(rows.get(i)), typed.getIngredient())) {
                    offenders.add(entry.getKey() + " entry " + i + " is not row " + i);
                }
            }
        }
        Set<Integer> seen = new LinkedHashSet<>();
        int steps = Math.min(rows.size(), 6) + 1;
        for (int step = 0; step < steps; step++) {
            seen.add(sceneMatchesSlots(recipe, view, offenders, "step " + step));
            for (int tick = 0; tick < 20; tick++) {
                layout.tick();
            }
        }
        IRecipeSlotView result = view.findSlotByName(SlotLookup.result(0)).orElse(null);
        List<ITypedIngredient<?>> results = result == null ? List.of() : result.getAllIngredientsList();
        String focusText = "no focus";
        if (!results.isEmpty() && results.getLast() != null) {
            ITypedIngredient<?> focused = results.getLast();
            IFocusGroup focus = runtime.getJeiHelpers().getFocusFactory()
                    .createFocusGroup(List.of(runtime.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT, focused)));
            IRecipeLayoutDrawable<FluidInteractionRecipe> narrowed = runtime.getRecipeManager()
                    .createRecipeLayoutDrawable(category, recipe, focus).orElse(null);
            Set<Integer> focusRows = new LinkedHashSet<>();
            if (narrowed == null) {
                offenders.add("no layout under the focus");
            } else {
                for (int step = 0; step < 3; step++) {
                    int row = sceneMatchesSlots(recipe, narrowed.getRecipeSlotsView(), offenders, "focus step " + step);
                    focusRows.add(row);
                    Placement shown = Placement.ofBlock(recipe.rows().get(row).results().get(List.copyOf(recipe.results().keySet()).getFirst()));
                    if (!SceneArrangement.shows(shown, focused.getIngredient())) {
                        offenders.add("the focus on " + focused.getIngredient() + " shows row " + row);
                    }
                    for (int tick = 0; tick < 20; tick++) {
                        narrowed.tick();
                    }
                }
            }
            focusText = "the focus on " + resultKey(rows.getLast().results().get(List.copyOf(recipe.results().keySet()).getFirst()))
                    + " shows row(s) " + focusRows;
        }
        long members = recipe.rows().stream().map(FluidInteractionRecipe::id).distinct().count();
        return recipe.rows().size() + " row(s) of " + members + " member(s), " + rows.size() + " shown, linked slot(s) " + linked.keySet()
                + ", " + steps + " cycle step(s) with the scene on row(s) " + seen + ", " + focusText + ", rows "
                + recipe.rows().stream().map(row -> row.sourceFluids().stream().map(fluid -> String.valueOf(BuiltInRegistries.FLUID.getKey(fluid))).toList()
                        + " + " + row.neighbors().stream().map(JeiAutoTest::alternativeKey).distinct().toList()
                        + " -> " + describe(row.results())).toList();
    }

    /** The entry of each linked slot for a row. */
    private static Map<String, Function<FluidInteractionRecipe, Placement>> linkedEntries(FluidInteractionRecipe recipe) {
        Map<String, Function<FluidInteractionRecipe, Placement>> entries = new LinkedHashMap<>();
        if (!FluidInteractionRecipe.sharesSources(recipe.rows())) {
            entries.put(SlotLookup.SOURCE, row -> Placement.ofFluid(row.sourceFluids().getFirst().defaultFluidState()));
        }
        if (!recipe.neighbors().isEmpty() && !FluidInteractionRecipe.sharesNeighbors(recipe.rows())) {
            entries.put(SlotLookup.NEIGHBOR, row -> row.neighbors().getFirst());
        }
        List<BlockPos> conditions = List.copyOf(recipe.conditions().keySet());
        for (int i = 0; i < conditions.size(); i++) {
            BlockPos offset = conditions.get(i);
            entries.put(SlotLookup.condition(i), row -> row.conditions().get(offset));
        }
        List<BlockPos> results = List.copyOf(recipe.results().keySet());
        for (int i = 0; i < results.size(); i++) {
            BlockPos offset = results.get(i);
            entries.put(SlotLookup.result(i), row -> Placement.ofBlock(row.results().get(offset)));
        }
        return entries;
    }

    /** Every slot shows what the scene draws at its offset. Returns the scene's row. */
    private static int sceneMatchesSlots(FluidInteractionRecipe recipe, IRecipeSlotsView view, List<String> offenders, String when) {
        SceneVariant variant = SceneView.variant(recipe, view, false);
        Map<BlockPos, Placement> before = SceneArrangement.of(recipe, variant);
        Map<BlockPos, Placement> after = SceneArrangement.of(recipe, variant.otherPhase());
        Map<String, Placement> expected = new LinkedHashMap<>();
        expected.put(SlotLookup.SOURCE, before.get(BlockPos.ZERO));
        if (!recipe.neighbors().isEmpty()) {
            expected.put(SlotLookup.NEIGHBOR, before.get(recipe.neighborOffset()));
        }
        List<BlockPos> conditions = List.copyOf(recipe.conditions().keySet());
        for (int i = 0; i < conditions.size(); i++) {
            expected.put(SlotLookup.condition(i), before.get(conditions.get(i)));
        }
        List<BlockPos> results = List.copyOf(recipe.results().keySet());
        for (int i = 0; i < results.size(); i++) {
            expected.put(SlotLookup.result(i), after.get(results.get(i)));
        }
        expected.forEach((name, placement) -> {
            IRecipeSlotView slot = view.findSlotByName(name).orElse(null);
            ITypedIngredient<?> shown = slot == null ? null : slot.getDisplayedIngredient().orElse(null);
            if (shown == null || placement == null || !SceneArrangement.shows(placement, shown.getIngredient())) {
                offenders.add(when + ": " + name + " shows " + (shown == null ? null : shown.getIngredient()) + ", the scene "
                        + (placement == null ? null : placement.describe().getString()));
            }
        });
        return variant.row();
    }

    private static long fluidCount(FluidInteractionRecipe recipe) {
        return recipe.neighbors().stream().map(placement -> FluidInteractionRecipe.stillForm(placement.effectiveFluid())).distinct().count();
    }

    /**
     * Vanilla's cobblestone interaction accepts its water neighbor in either form. So the probe must record the
     * flowing form as an alternative. Then the scene can draw the neighbor flowing.
     */
    private static void checkFlowingNeighbor(List<FluidInteractionRecipe> found) {
        BlockState result = Blocks.COBBLESTONE.defaultBlockState();
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> result.equals(recipe.resultAtSource()))
                .filter(recipe -> recipe.sourceFluids().contains(Fluids.LAVA))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one cobblestone recipe from lava, found {}", matches.size());
            return;
        }
        FluidInteractionRecipe cobblestone = matches.getFirst();
        Placement flowing = cobblestone.neighbors().stream()
                .filter(placement -> placement.isFlowing() && FluidInteractionRecipe.stillForm(placement.effectiveFluid()) == Fluids.WATER)
                .findFirst()
                .orElse(null);
        if (flowing == null) {
            LOGGER.error("Smoke test found no flowing water neighbor alternative in {}: {}", cobblestone.id(),
                    cobblestone.neighbors().stream().map(placement -> placement.describe().getString()).toList());
            return;
        }
        LOGGER.info("Smoke test flowing neighbor {} (from {}): {} of neighbor alternative(s) {}",
                cobblestone.id(), cobblestone.owner(), flowing.describe().getString(),
                cobblestone.neighbors().stream().map(placement -> placement.describe().getString()).toList());
    }

    /**
     * Vanilla's stone comes from lava's own spread code, not the interaction registry. So it exists only when
     * the spread probe ran. The recipe holds lava with water directly below it, and both water forms cycle in
     * one slot.
     */
    private static void checkSpreadRecipe(List<FluidInteractionRecipe> found) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.neighborOffset().equals(RuleProber.BELOW_OFFSET))
                .filter(recipe -> stone.equals(recipe.results().get(RuleProber.BELOW_OFFSET)))
                .filter(recipe -> recipe.sourceFluids().contains(Fluids.LAVA))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one stone recipe from lava spreading down onto water, found {}", matches.size());
            return;
        }
        FluidInteractionRecipe stoneRecipe = matches.getFirst();
        spreadRecipe = stoneRecipe;
        if (!stoneRecipe.id().getPath().startsWith("spread/")) {
            LOGGER.error("Smoke test expected the stone recipe to carry a spread id, found {}", stoneRecipe.id());
        }
        boolean still = waterNeighbor(stoneRecipe, false);
        boolean flowing = waterNeighbor(stoneRecipe, true);
        if (!still || !flowing) {
            LOGGER.error("Smoke test expected both water forms as alternatives in {}, found still {} flowing {}",
                    stoneRecipe.id(), still, flowing);
        }
        LOGGER.info("Smoke test spread recipe {} (from {}): {} source state(s), {} at {}, neighbor alternative(s) {}",
                stoneRecipe.id(), stoneRecipe.owner(), stoneRecipe.sources().size(),
                BuiltInRegistries.BLOCK.getKey(stone.getBlock()), RuleProber.BELOW_OFFSET.toShortString(),
                stoneRecipe.neighbors().stream().map(placement -> placement.describe().getString()).toList());
    }

    /**
     * Within one fluid type, the display order ranks owners. Vanilla's stone is owned by {@code minecraft} and
     * found by the spread probe. So it must precede every {@code minecraft:lava} recipe owned by a third party,
     * including the dev-only ones registered here.
     */
    private static void checkOwnerOrder(List<FluidInteractionRecipe> found) {
        if (spreadRecipe == null) {
            LOGGER.error("Smoke test has no stone spread recipe to check the owner order against");
            return;
        }
        int stone = found.indexOf(spreadRecipe);
        List<FluidInteractionRecipe> thirdParty = found.stream()
                .filter(recipe -> LAVA_TYPE.equals(RecipeIds.keyOf(recipe.sourceType())))
                .filter(recipe -> !"minecraft".equals(recipe.owner()) && !"neoforge".equals(recipe.owner()))
                .toList();
        if (thirdParty.isEmpty()) {
            LOGGER.error("Smoke test found no third-party {} recipe to order the stone recipe against", LAVA_TYPE);
            return;
        }
        for (FluidInteractionRecipe recipe : thirdParty) {
            if (found.indexOf(recipe) < stone) {
                LOGGER.error("Smoke test found {} (from {}) at index {} before the stone recipe {} at index {}",
                        recipe.id(), recipe.owner(), found.indexOf(recipe), spreadRecipe.id(), stone);
            }
        }
        LOGGER.info("Smoke test order {} (from {}) at index {} of {}, before {} third-party {} recipe(s) at {}",
                spreadRecipe.id(), spreadRecipe.owner(), stone, found.size(), thirdParty.size(), LAVA_TYPE,
                thirdParty.stream().map(recipe -> found.indexOf(recipe)).toList());
    }

    private static boolean waterNeighbor(FluidInteractionRecipe recipe, boolean flowing) {
        return recipe.neighbors().stream()
                .anyMatch(placement -> placement.isFlowing() == flowing
                        && FluidInteractionRecipe.stillForm(placement.effectiveFluid()) == Fluids.WATER);
    }

    /**
     * The dev fluid hardens a lava-tagged fluid below it only as a source block. So its spread recipe must carry
     * the source state alone, with the flowing state recorded as inert. The line the source slot shows must name
     * that flowing form. Vanilla's stone comes from both lava forms, so it records nothing inert.
     */
    private static void checkFormDifference(List<FluidInteractionRecipe> found) {
        BlockState result = JeiAutoTestFluids.RESULT.defaultBlockState();
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.sourceFluids().contains(JeiAutoTestFluids.sourceFluid()))
                .filter(recipe -> result.equals(recipe.results().get(recipe.neighborOffset())))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one {} recipe from the dev fluid spreading down, found {}",
                    BuiltInRegistries.BLOCK.getKey(result.getBlock()), matches.size());
            return;
        }
        FluidInteractionRecipe recipe = matches.getFirst();
        formRecipe = recipe;
        if (recipe.matchesFlowingForm() || !recipe.matchesSourceForm()) {
            LOGGER.error("Smoke test expected {} to match the source form only, source state(s) {}",
                    recipe.id(), recipe.sources().size());
        }
        List<FluidState> inert = recipe.inert().sources();
        if (inert.size() != 1 || inert.getFirst().isSource()) {
            LOGGER.error("Smoke test expected {} to record one flowing inert source form, found {}", recipe.id(), inert.size());
            return;
        }
        String line = Texts.inertSource(inert.getFirst()).getString();
        String expected = "Flowing " + JeiAutoTestFluids.DISPLAY_NAME.getString();
        if (!line.contains(expected)) {
            LOGGER.error("Smoke test expected the source slot line of {} to name {}, found {}", recipe.id(), expected, line);
        }
        LOGGER.info("Smoke test form difference {} (from {}): {} source state(s), inert {}, neighbor alternative(s) {}, line \"{}\"",
                recipe.id(), recipe.owner(), recipe.sources().size(),
                inert.stream().map(state -> Texts.form(state).getString()).toList(),
                recipe.neighbors().stream().map(placement -> placement.describe().getString()).toList(), line);
        if (spreadRecipe != null && !spreadRecipe.inert().isEmpty()) {
            LOGGER.error("Smoke test expected the stone recipe {} to record no inert form, found source(s) {} neighbor(s) {}",
                    spreadRecipe.id(), spreadRecipe.inert().sources().size(), spreadRecipe.inert().neighbors().size());
        }
    }

    /**
     * The dev water-tagged fluid has a registered interaction of its own with a lava neighbor. A level runs it
     * on the block update that the placed lava sends. That is long before lava's spread tick reaches the fluid
     * below it. Lava's own spread code can turn a water-tagged fluid to stone. Here, that code never gets the
     * chance to run. So this result belongs in a recipe of its own. It is not among the alternatives of
     * vanilla's stone recipe.
     */
    private static void checkPreempted(List<FluidInteractionRecipe> found) {
        Fluid dyed = JeiAutoTestFluids.dyedSourceFluid();
        ResourceLocation dyedKey = BuiltInRegistries.FLUID.getKey(dyed);
        if (!dyed.defaultFluidState().is(FluidTags.WATER)) {
            LOGGER.error("Smoke test expected {} to be water-tagged, so lava reaches it; its data pack tag did not load", dyedKey);
            return;
        }
        if (spreadRecipe == null) {
            LOGGER.error("Smoke test has no stone spread recipe to check pre-emption against");
            return;
        }
        List<String> offenders = Stream.concat(spreadRecipe.neighbors().stream(), spreadRecipe.inert().neighbors().stream())
                .filter(placement -> placement.isFluid() && FluidInteractionRecipe.stillForm(placement.effectiveFluid()) == dyed)
                .map(placement -> placement.describe().getString())
                .toList();
        if (!offenders.isEmpty()) {
            LOGGER.error("Smoke test found {} still among the alternatives of the stone recipe {}: {}", dyedKey, spreadRecipe.id(), offenders);
        }
        List<FluidInteractionRecipe> own = found.stream()
                .filter(recipe -> !recipe.isFailure() && recipe.sourceFluids().contains(dyed))
                .toList();
        for (Block result : List.of(JeiAutoTestInteractions.DYED_SOURCE_RESULT, JeiAutoTestInteractions.DYED_FLOWING_RESULT)) {
            if (own.stream().noneMatch(recipe -> result.defaultBlockState().equals(recipe.resultAtSource()))) {
                LOGGER.error("Smoke test found no {} recipe of {}'s own", BuiltInRegistries.BLOCK.getKey(result), dyedKey);
            }
        }
        LOGGER.info("Smoke test pre-empted {}: {} alternative(s) of the stone recipe {}, own recipe(s) {}",
                dyedKey, offenders.size(), spreadRecipe.id(), own.stream().map(recipe -> recipe.id().toString()).toList());
    }

    /**
     * The Bumblezone's sugar water hardens inside its liquid block's {@code neighborChanged} method. A level
     * runs this method on the tick a lava-tagged fluid lands against it. No fluid tick and no registry entry
     * describes this hardening. From its source form, the sugar water becomes sugar-infused stone. From its
     * flowing form, it becomes sugar-infused cobblestone. Both results come from every lava-tagged neighbor, in
     * either form. This occurs beside the source and above it. It never occurs below the source. This hardening
     * happens before any spread tick. So other fluids never reach the sugar water through their own spread. Lava
     * itself reaches the sugar water beside it first, through a registry interaction of its own. So lava stays
     * an alternative of the above-position recipes alone.
     */
    private static void checkNeighborRecipe(List<FluidInteractionRecipe> found) {
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.id().getPath().startsWith(SUGAR_WATER_IDS))
                .toList();
        if (matches.isEmpty()) {
            LOGGER.error("Smoke test found no neighbor recipe with an id under {}:{}", JustEnoughFluidInteractions.MODID, SUGAR_WATER_IDS);
            return;
        }
        for (FluidInteractionRecipe recipe : matches) {
            LOGGER.info("Smoke test neighbor recipe {} (from {}): {} source state(s) (source {}, flowing {}), neighbor {}, result(s) {}, neighbor alternative(s) {}",
                    recipe.id(), recipe.owner(), recipe.sources().size(), recipe.matchesSourceForm(), recipe.matchesFlowingForm(),
                    Texts.offset(recipe.neighborOffset()).getString(), describe(recipe.results()),
                    recipe.neighbors().stream().map(placement -> placement.describe().getString()).toList());
        }

        for (Map.Entry<ResourceLocation, Boolean> hardening : Map.of(SUGAR_INFUSED_STONE, false, SUGAR_INFUSED_COBBLESTONE, true).entrySet()) {
            FluidInteractionRecipe above = hardened(matches, RuleProber.ABOVE_OFFSET, hardening.getKey(), hardening.getValue());
            FluidInteractionRecipe beside = hardened(matches, FluidInteractionRecipe.NEIGHBOR_OFFSET, hardening.getKey(), hardening.getValue());
            if (above != null && SUGAR_INFUSED_COBBLESTONE.equals(hardening.getKey())) {
                neighborRecipe = above;
                checkVerticalFlow(above);
            }
            if (above != null) {
                checkBothForms(above, Fluids.LAVA::isSame, "minecraft:lava");
            }
            for (FluidInteractionRecipe recipe : Stream.of(above, beside).filter(recipe -> recipe != null).toList()) {
                checkBothForms(recipe, fluid -> TAR_TYPE.equals(NeoForgeRegistries.FLUID_TYPES.getKey(fluid.getFluidType())), TAR_TYPE.toString());
            }
            checkNeighborRegistryPreempted(hardening.getKey(), above, beside);
        }

        checkSugarWaterGone(spreadRecipe, "the stone");
        checkSugarWaterGone(found.stream().filter(recipe -> recipe.id().getPath().startsWith(TAR_SPREAD_IDS)).findFirst().orElse(null), "the tar");
    }

    /** The one recipe writing this result at the source, at this neighbor position, from this source form alone. */
    private static @Nullable FluidInteractionRecipe hardened(List<FluidInteractionRecipe> matches, BlockPos offset,
                                                             ResourceLocation result, boolean flowing) {
        List<FluidInteractionRecipe> found = matches.stream()
                .filter(recipe -> recipe.neighborOffset().equals(offset))
                .filter(recipe -> result.equals(resultKey(recipe.resultAtSource())))
                .filter(recipe -> recipe.matchesFlowingForm() == flowing && recipe.matchesSourceForm() != flowing)
                .toList();
        if (found.size() != 1) {
            LOGGER.error("Smoke test expected one sugar water neighbor recipe writing {} from its {} form with the neighbor {}, found {}",
                    result, flowing ? "flowing" : "source", Texts.offset(offset).getString(), found.size());
            return null;
        }
        return found.getFirst();
    }

    /**
     * The scene of the cobblestone recipe draws its flowing sugar water fed from the south. Lava above it draws
     * as the probe verified each alternative: a still block alone, or a flow fed from the west, because the
     * south is taken. The stone recipe draws a lava source over flowing water fed from the south, although both
     * lava forms spread into the water. Vanilla draws the flowing texture only beside a higher fluid of the
     * same kind.
     */
    private static void checkVerticalFlow(FluidInteractionRecipe recipe) {
        int still = alternative(recipe, Fluids.LAVA, false);
        int flowing = alternative(recipe, Fluids.LAVA, true);
        if (still < 0 || flowing < 0) {
            LOGGER.error("Smoke test found no lava alternative pair in {} for the vertical flow check", recipe.id());
            return;
        }
        String type = String.valueOf(NeoForgeRegistries.FLUID_TYPES.getKey(recipe.sourceType()));
        List<String> offenders = new ArrayList<>();
        Map<BlockPos, Placement> scene = SceneArrangement.of(recipe, new SceneVariant(0, still, false));
        expectFlow(scene, BlockPos.ZERO, true, type, offenders);
        expectFlow(scene, new BlockPos(0, 0, 1), false, type, offenders);
        expectFlow(scene, RuleProber.ABOVE_OFFSET, false, "minecraft:lava", offenders);
        if (scene.size() != 3) {
            offenders.add("expected 3 placements with still lava, found " + scene.size());
        }
        Map<BlockPos, Placement> flow = SceneArrangement.of(recipe, new SceneVariant(0, flowing, false));
        expectFlow(flow, RuleProber.ABOVE_OFFSET, true, "minecraft:lava", offenders);
        expectFlow(flow, RuleProber.ABOVE_OFFSET.offset(-1, 0, 0), false, "minecraft:lava", offenders);
        if (flow.size() != 4) {
            offenders.add("expected 4 placements with flowing lava, found " + flow.size());
        }
        Map<BlockPos, Placement> stone = Map.of();
        if (spreadRecipe != null) {
            int water = alternative(spreadRecipe, Fluids.WATER, true);
            stone = SceneArrangement.of(spreadRecipe, new SceneVariant(0, water, false));
            expectFlow(stone, BlockPos.ZERO, false, "minecraft:lava", offenders);
            expectFlow(stone, RuleProber.BELOW_OFFSET, true, "minecraft:water", offenders);
            expectFlow(stone, RuleProber.BELOW_OFFSET.offset(0, 0, 1), false, "minecraft:water", offenders);
            if (stone.size() != 3) {
                offenders.add("expected 3 placements in the stone scene, found " + stone.size());
            }
        }
        if (offenders.isEmpty()) {
            LOGGER.info("Smoke test vertical flow {} (from {}): still lava {}, flowing lava {}, stone {}", recipe.id(), recipe.owner(),
                    describeScene(scene), describeScene(flow), describeScene(stone));
        } else {
            LOGGER.error("Smoke test vertical flow {} drew a wrong scene: {}", recipe.id(), offenders);
        }
    }

    /** The index of the neighbor alternative that is this fluid in this form, or -1. */
    private static int alternative(FluidInteractionRecipe recipe, Fluid fluid, boolean flowing) {
        List<Placement> neighbors = recipe.neighbors();
        for (int i = 0; i < neighbors.size(); i++) {
            Placement neighbor = neighbors.get(i);
            if (neighbor.isFluid() && neighbor.isFlowing() == flowing && fluid.isSame(FluidInteractionRecipe.stillForm(neighbor.effectiveFluid()))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> describeScene(Map<BlockPos, Placement> scene) {
        return scene.entrySet().stream().map(e -> Texts.offset(e.getKey()).getString() + " " + e.getValue().describe().getString()).toList();
    }

    private static void expectFlow(Map<BlockPos, Placement> scene, BlockPos offset, boolean flowing, String type, List<String> offenders) {
        Placement placement = scene.get(offset);
        String at = Texts.offset(offset).getString();
        if (placement == null || !placement.isFluid()) {
            offenders.add("no fluid " + at);
            return;
        }
        String found = String.valueOf(NeoForgeRegistries.FLUID_TYPES.getKey(placement.effectiveFluid().getFluidType()));
        if (placement.isFlowing() != flowing || !type.equals(found)) {
            offenders.add((flowing ? "expected flowing " : "expected still ") + type + " " + at + ", found " + placement.describe().getString());
        }
    }

    /** Both forms of at least one matching fluid have to cycle in the neighbor slot the probe verified. */
    private static void checkBothForms(FluidInteractionRecipe recipe, Predicate<Fluid> matches, String what) {
        boolean still = recipe.neighbors().stream().anyMatch(placement -> placement.isFluid() && !placement.isFlowing()
                && matches.test(FluidInteractionRecipe.stillForm(placement.effectiveFluid())));
        boolean flowing = recipe.neighbors().stream().anyMatch(placement -> placement.isFluid() && placement.isFlowing()
                && matches.test(FluidInteractionRecipe.stillForm(placement.effectiveFluid())));
        if (!still || !flowing) {
            LOGGER.error("Smoke test expected both forms of {} among the neighbor alternatives of {}, found still {} flowing {}: {}",
                    what, recipe.id(), still, flowing,
                    recipe.neighbors().stream().map(placement -> placement.describe().getString()).toList());
        }
    }

    /**
     * A registry interaction runs on lava's own fluid type. It consumes the lava before sugar water's update
     * hook learns about it. This happens only where that interaction looks. With lava beside the sugar water,
     * the sugar water stays unchanged, and the lava becomes obsidian or sugar-infused cobblestone. The
     * interaction on the lava block never looks downward. So with lava above the sugar water, the sugar water
     * hardens instead, from every form the probe verified. Among the recipes that harden the sugar water itself:
     * the ones with a neighbor beside it hold neither form of lava. The ones with a neighbor above it hold both
     * forms.
     */
    private static void checkNeighborRegistryPreempted(ResourceLocation result, @Nullable FluidInteractionRecipe above,
                                                       @Nullable FluidInteractionRecipe beside) {
        if (above == null || beside == null) {
            LOGGER.error("Smoke test has no pair of sugar water recipes writing {} to compare across neighbor positions", result);
            return;
        }
        Set<String> lava = forms(Fluids.LAVA);
        Set<String> aboveKeys = alternatives(above, placement -> true);
        Set<String> besideKeys = alternatives(beside, placement -> true);
        if (!aboveKeys.containsAll(lava)) {
            LOGGER.error("Smoke test expected both forms of minecraft:lava among the alternatives of sugar water recipe {} with its neighbor {}, found {}",
                    above.id(), Texts.offset(above.neighborOffset()).getString(), lavaForms(above));
        }
        if (besideKeys.stream().anyMatch(lava::contains)) {
            LOGGER.error("Smoke test found minecraft:lava still among the alternatives of sugar water recipe {} with its neighbor {}: {}",
                    beside.id(), Texts.offset(beside.neighborOffset()).getString(), lavaForms(beside));
        }
        Set<String> besideOnly = new LinkedHashSet<>(alternatives(beside, JeiAutoTest::isLavaTagged));
        besideOnly.removeAll(aboveKeys);
        if (!besideOnly.isEmpty()) {
            LOGGER.error("Smoke test found lava-tagged alternative(s) of sugar water recipe {} that recipe {} does not hold: {}",
                    beside.id(), above.id(), besideOnly);
        }
        Set<String> aboveOnly = new LinkedHashSet<>(aboveKeys);
        aboveOnly.removeAll(besideKeys);
        if (!aboveOnly.equals(lava)) {
            LOGGER.error("Smoke test expected the alternatives of sugar water recipe {} to be those of {} plus both forms of minecraft:lava, found {} extra",
                    above.id(), beside.id(), aboveOnly);
        }
        LOGGER.info("Smoke test neighbor registry pre-empted minecraft:lava writing {}: {} {} {} alternative(s), {} {} {} alternative(s), only above {}",
                result, above.id(), Texts.offset(above.neighborOffset()).getString(), aboveKeys.size(),
                beside.id(), Texts.offset(beside.neighborOffset()).getString(), besideKeys.size(), aboveOnly);
    }

    /** The matching neighbor alternatives of one recipe, inert ones included, keyed to compare across recipes. */
    private static Set<String> alternatives(FluidInteractionRecipe recipe, Predicate<Placement> wanted) {
        return Stream.concat(recipe.neighbors().stream(), recipe.inert().neighbors().stream())
                .filter(wanted)
                .map(JeiAutoTest::alternativeKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A neighbor alternative's identity across recipes: its fluid and form, or its block. */
    private static String alternativeKey(Placement placement) {
        if (placement.isFluid()) {
            return BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(placement.effectiveFluid()))
                    + (placement.isFlowing() ? "#flowing" : "#source");
        }
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(placement.block().getBlock()));
    }

    /** Both forms of one fluid, keyed the way {@link #alternativeKey} keys an alternative holding them. */
    private static Set<String> forms(Fluid fluid) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(fluid.defaultFluidState()));
        return Set.of(key + "#source", key + "#flowing");
    }

    private static boolean isLavaTagged(Placement placement) {
        return placement.isFluid() && placement.effectiveFluid().is(FluidTags.LAVA);
    }

    /**
     * Every recipe describes what its own rule does. Sugar water's update hook writes into the sugar water
     * itself. So none of that hook's recipes writes at a lava neighbor. What a level leaves where lava stood
     * beside sugar water comes from a registry interaction on the lava instead. That interaction's own recipes
     * carry this result. Lava and the tar both write stone into a water-tagged fluid below them, from their own
     * spread code. So a spread recipe of either fluid, if it holds such a water-tagged fluid as an alternative,
     * writes stone at that fluid and nothing else. Anything else a level reaches from the same arrangement is
     * another rule's outcome. That outcome belongs to that other rule's recipe.
     */
    private static void checkOwnRule(List<FluidInteractionRecipe> found) {
        List<String> consumed = found.stream()
                .filter(recipe -> recipe.id().getPath().startsWith(SUGAR_WATER_IDS))
                .filter(recipe -> !lavaForms(recipe).isEmpty())
                .filter(recipe -> recipe.results().containsKey(recipe.neighborOffset()))
                .map(recipe -> recipe.id() + " " + describe(recipe.results()))
                .toList();
        if (!consumed.isEmpty()) {
            LOGGER.error("Smoke test found sugar water neighbor recipe(s) writing at their lava neighbor: {}", consumed);
        }

        List<FluidInteractionRecipe> spread = found.stream()
                .filter(recipe -> recipe.id().getPath().startsWith(LAVA_SPREAD_IDS)
                        || recipe.id().getPath().startsWith(TAR_SPREAD_IDS))
                .filter(JeiAutoTest::holdsWaterTagged)
                .toList();
        List<String> foreign = spread.stream()
                .filter(recipe -> recipe.results().size() != 1
                        || !Blocks.STONE.defaultBlockState().equals(recipe.results().get(recipe.neighborOffset())))
                .map(recipe -> recipe.id() + " " + describe(recipe.results()))
                .toList();
        if (!foreign.isEmpty()) {
            LOGGER.error("Smoke test expected every spread recipe of {} and {} holding a water-tagged alternative to write only stone at its target, found {}",
                    LAVA_TYPE, TAR_TYPE, foreign);
        }
        LOGGER.info("Smoke test own rule: {} sugar water recipe(s) write at a lava neighbor, {} spread recipe(s) of {} and {} hold a water-tagged alternative: {}",
                consumed.size(), spread.size(), LAVA_TYPE, TAR_TYPE,
                spread.stream().map(recipe -> recipe.id() + " " + describe(recipe.results())).toList());
    }

    /** Whether a recipe offers water, the dev fluid or sugar water as an alternative, inert ones included. */
    private static boolean holdsWaterTagged(FluidInteractionRecipe recipe) {
        return Stream.concat(recipe.neighbors().stream(), recipe.inert().neighbors().stream())
                .filter(Placement::isFluid)
                .map(placement -> FluidInteractionRecipe.stillForm(placement.effectiveFluid()))
                .anyMatch(fluid -> fluid == Fluids.WATER || fluid == JeiAutoTestFluids.dyedSourceFluid()
                        || SUGAR_WATER.equals(BuiltInRegistries.FLUID.getKey(fluid)));
    }

    /** Both forms of lava among a recipe's alternatives, inert ones included, as the tooltip names them. */
    private static List<String> lavaForms(FluidInteractionRecipe recipe) {
        return Stream.concat(recipe.neighbors().stream(), recipe.inert().neighbors().stream())
                .filter(placement -> placement.isFluid() && Fluids.LAVA.isSame(FluidInteractionRecipe.stillForm(placement.effectiveFluid())))
                .map(placement -> placement.describe().getString())
                .toList();
    }

    /**
     * Sugar water hardens on the tick a lava-tagged fluid lands against it. So that fluid's own spread tick
     * never reaches it. Neither form of it can still be an alternative of a spread recipe.
     */
    private static void checkSugarWaterGone(@Nullable FluidInteractionRecipe recipe, String which) {
        if (recipe == null) {
            LOGGER.error("Smoke test has no {} spread recipe to check sugar water pre-emption against", which);
            return;
        }
        List<String> offenders = Stream.concat(recipe.neighbors().stream(), recipe.inert().neighbors().stream())
                .filter(placement -> placement.isFluid()
                        && SUGAR_WATER.equals(BuiltInRegistries.FLUID.getKey(FluidInteractionRecipe.stillForm(placement.effectiveFluid()))))
                .map(placement -> placement.describe().getString())
                .toList();
        if (!offenders.isEmpty()) {
            LOGGER.error("Smoke test found {} still among the alternatives of {} spread recipe {}: {}", SUGAR_WATER, which, recipe.id(), offenders);
        }
        LOGGER.info("Smoke test neighbor pre-empted {}: {} alternative(s) of {} spread recipe {}", SUGAR_WATER, offenders.size(), which, recipe.id());
    }

    private static List<String> describe(Map<BlockPos, BlockState> results) {
        return results.entrySet().stream()
                .map(entry -> Texts.offset(entry.getKey()).getString() + "=" + resultKey(entry.getValue()))
                .toList();
    }

    private static @Nullable ResourceLocation resultKey(@Nullable BlockState state) {
        return state != null ? BuiltInRegistries.BLOCK.getKey(state.getBlock()) : null;
    }

    /**
     * The dev fluid's source slot always carries the inert-form indicator (a "!" at its top-left corner). Its
     * only source fluid has an inert flowing form recorded. Its neighbor slot carries no indicator. Hardening
     * the lava-tagged target does not depend on that target's own form. So neither of its forms is ever inert.
     */
    private static void checkIndicator() {
        if (formRecipe == null) {
            LOGGER.error("Smoke test has no dev fluid form recipe to check the inert form indicator against");
            return;
        }
        boolean source = InertFormIndicator.sourceMayShow(formRecipe);
        boolean neighbor = InertFormIndicator.neighborMayShow(formRecipe);
        if (!source) {
            LOGGER.error("Smoke test expected the inert form indicator on the source slot of {}, found source {} neighbor {}",
                    formRecipe.id(), source, neighbor);
        }
        LOGGER.info("Smoke test indicator {} (from {}): source slot {}, neighbor slot {}",
                formRecipe.id(), formRecipe.owner(), source, neighbor);
    }

    /**
     * A honey source under still water becomes a glistering honey crystal. That crystal has its own
     * {@code onPlace} method, which turns the water into sugar water. So the level writes at two positions from
     * the one arrangement. Neither the honey's update hook nor any registry entry states this on its own. Only
     * settling the arrangement reveals it. So the recipe must carry both results, for the scene to draw what a
     * player sees.
     */
    private static void checkCascade(List<FluidInteractionRecipe> found) {
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.id().getPath().startsWith(HONEY_IDS))
                .filter(recipe -> recipe.results().size() > 1)
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one honey recipe writing at more than one position, found {}: {}",
                    matches.size(), matches.stream().map(recipe -> recipe.id().toString()).toList());
            return;
        }
        FluidInteractionRecipe recipe = matches.getFirst();
        cascadeRecipe = recipe;
        if (recipe.resultAtSource() == null || recipe.results().get(recipe.neighborOffset()) == null) {
            LOGGER.error("Smoke test expected {} to write at both the source and its neighbor, found result(s) {}",
                    recipe.id(), describe(recipe.results()));
        }
        LOGGER.info("Smoke test cascade {} (from {}): neighbor {}, result(s) {}, neighbor alternative(s) {}",
                recipe.id(), recipe.owner(), Texts.offset(recipe.neighborOffset()).getString(), describe(recipe.results()),
                recipe.neighbors().stream().map(placement -> placement.describe().getString()).toList());
    }

    /** The dev fluid writes waterlogged pointed dripstone where the dripstone block stood. */
    private static void checkOffsetRecipe(List<FluidInteractionRecipe> found) {
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.neighbors().stream().anyMatch(placement -> placement.block().is(JeiAutoTestInteractions.OFFSET_TRIGGER)))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one recipe with a {} condition, found {}",
                    BuiltInRegistries.BLOCK.getKey(JeiAutoTestInteractions.OFFSET_TRIGGER), matches.size());
            return;
        }
        FluidInteractionRecipe recipe = matches.getFirst();
        BlockState written = recipe.results().get(recipe.neighborOffset());
        if (written == null || !written.is(JeiAutoTestInteractions.OFFSET_CONDITION)
                || !written.hasProperty(BlockStateProperties.WATERLOGGED) || !written.getValue(BlockStateProperties.WATERLOGGED)) {
            LOGGER.error("Smoke test expected {} to write a waterlogged {} at {}, found {}",
                    recipe.id(), BuiltInRegistries.BLOCK.getKey(JeiAutoTestInteractions.OFFSET_CONDITION),
                    Texts.offset(recipe.neighborOffset()).getString(), written);
            return;
        }
        offsetRecipe = recipe;
        LOGGER.info("Smoke test offset recipe {}: condition {}[waterlogged={}] at {}", recipe.id(),
                BuiltInRegistries.BLOCK.getKey(written.getBlock()), written.getValue(BlockStateProperties.WATERLOGGED),
                Texts.offset(recipe.neighborOffset()).getString());
    }

    /**
     * An earlier interaction, registered on lava before this dev interaction, always answers first, whatever the
     * arrangement. So this dev interaction produces no recipe of its own. Its failure recipe must name what a
     * level holds there instead. It must not simply state that processing failed.
     */
    private static void checkPreemptedInteraction(List<FluidInteractionRecipe> found) {
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(FluidInteractionRecipe::isFailure)
                .filter(recipe -> LAVA_TYPE.equals(RecipeIds.keyOf(recipe.sourceType())))
                .filter(recipe -> JustEnoughFluidInteractions.MODID.equals(recipe.owner()))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one failure recipe from the pre-empted dev interaction on {}, found {}: {}",
                    LAVA_TYPE, matches.size(), matches.stream().map(recipe -> recipe.id().toString()).toList());
            return;
        }
        FluidInteractionRecipe recipe = matches.getFirst();
        preemptedRecipe = recipe;
        String text = failureText(recipe);
        List<String> expected = List.of(
                Fluids.LAVA.getFluidType().getDescription().getString(),
                Fluids.WATER.getFluidType().getDescription().getString(),
                Blocks.OBSIDIAN.getName().getString(),
                JeiAutoTestInteractions.PREEMPTED_RESULT.getName().getString());
        List<String> missing = expected.stream().filter(name -> !text.contains(name)).toList();
        if (!missing.isEmpty()) {
            LOGGER.error("Smoke test expected the failure text of {} to name {}, found \"{}\"", recipe.id(), missing, text);
        }
        LOGGER.info("Smoke test pre-empted interaction {}: {}", recipe.id(), text);
    }

    /**
     * Biomes O' Plenty registers its blood interaction on every fluid type, including The Bumblezone's honey.
     * The honey's own block class answers the block update that the placed blood sends. So a level holds the
     * honey where that interaction writes flesh. Its failure recipe must name both fluids and both blocks. That
     * text matches the dev fixture's, over an arrangement nothing here set up.
     */
    private static void checkPreemptedThirdPartyInteraction(List<FluidInteractionRecipe> found) {
        String blood = fluidName(BLOOD);
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(FluidInteractionRecipe::isFailure)
                .filter(recipe -> HONEY_TYPE.equals(RecipeIds.keyOf(recipe.sourceType())))
                .filter(recipe -> BOP.equals(recipe.owner()))
                .filter(recipe -> failureText(recipe).contains(blood))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one failure recipe from {}'s {} interaction on {}, found {}: {}",
                    BOP, blood, HONEY_TYPE, matches.size(), matches.stream().map(recipe -> recipe.id().toString()).toList());
            return;
        }
        FluidInteractionRecipe recipe = matches.getFirst();
        String text = failureText(recipe);
        List<String> missing = new ArrayList<>(Stream.of(recipe.sourceType().getDescription().getString(), blockName(HONEY_BLOCK))
                .filter(name -> !text.contains(name))
                .toList());
        if (FLESH.stream().map(JeiAutoTest::blockName).noneMatch(text::contains)) {
            missing.add(FLESH.stream().map(JeiAutoTest::blockName).toList().toString());
        }
        if (!missing.isEmpty()) {
            LOGGER.error("Smoke test expected the failure text of {} to name {}, found \"{}\"", recipe.id(), missing, text);
        }
        LOGGER.info("Smoke test pre-empted third-party interaction {}: {}", recipe.id(), text);
    }

    /** The output slot holds an itemless block that a focus finds and the list omits. */
    private static void checkBlockless(List<FluidInteractionRecipe> found) {
        BlockState result = JeiAutoTestInteractions.ITEMLESS_RESULT.defaultBlockState();
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> result.equals(recipe.resultAtSource()))
                .toList();
        IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
        if (matches.size() != 1 || runtime == null) {
            LOGGER.error("Smoke test expected one {} recipe, found {}", BuiltInRegistries.BLOCK.getKey(result.getBlock()), matches.size());
            return;
        }
        FluidInteractionRecipe recipe = matches.getFirst();
        blocklessRecipe = recipe;
        List<IRecipeSlotView> outputs = outputSlots(runtime, recipe);
        ITypedIngredient<?> shown = outputs.isEmpty() ? null : outputs.getFirst().getDisplayedIngredient().orElse(null);
        if (shown == null || shown.getType() != ItemlessBlock.TYPE) {
            LOGGER.error("Smoke test expected the output slot of {} to hold an itemless block, found {}", recipe.id(),
                    shown == null ? null : shown.getType().getUid());
            return;
        }
        List<String> tooltip = outputTooltips(runtime, recipe).getFirst();
        String name = result.getBlock().getName().getString();
        if (!tooltip.contains(name) || tooltip.stream().noneMatch(line -> line.contains(recipe.id().toString()))) {
            LOGGER.error("Smoke test expected the output tooltip of {} to name {} and the recipe id, found {}", recipe.id(), name, tooltip);
        }
        int listed = runtime.getIngredientManager().getAllIngredients(ItemlessBlock.TYPE).size();
        IFocus<ItemlessBlock> focus = runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, ItemlessBlock.TYPE, new ItemlessBlock(result));
        List<FluidInteractionRecipe> focused = runtime.getRecipeManager().createRecipeLookup(FluidInteractionsJeiPlugin.TYPE)
                .limitFocus(List.of(focus))
                .get()
                .toList();
        if (listed != 0 || !focused.contains(recipe)) {
            LOGGER.error("Smoke test expected no listed itemless block and a focus that finds {}, found {} listed, {} recipe(s) found",
                    recipe.id(), listed, focused.size());
        }
        LOGGER.info("Smoke test blockless {} (from {}): output type {}, {} listed ingredient(s), the focus finds {} recipe(s), tooltip {}",
                recipe.id(), recipe.owner(), shown.getType().getUid(), listed, focused.size(), tooltip);
    }

    /** The dev fluid waterlogs blocks below it; none of that is a recipe. */
    private static void checkWaterlog(List<FluidInteractionRecipe> found) {
        List<String> offenders = new ArrayList<>();
        for (FluidInteractionRecipe recipe : found.stream().flatMap(listed -> listed.rowsOrSelf().stream()).toList()) {
            recipe.results().forEach((offset, result) -> {
                if (placedAt(recipe, offset).stream().anyMatch(placed -> onlyFluidDiffers(placed, result))) {
                    offenders.add(recipe.id() + " " + Texts.offset(offset).getString() + " " + result);
                }
            });
        }
        Fluid brine = JeiAutoTestFluids.sourceFluid();
        String brineIds = "spread/" + RecipeIds.keyOf(brine.getFluidType()).toString().replace(':', '/') + "/";
        List<String> brineRecipes = found.stream()
                .filter(recipe -> recipe.id().getPath().startsWith(brineIds))
                .map(recipe -> recipe.id().toString())
                .toList();
        if (!offenders.isEmpty()) {
            LOGGER.error("Smoke test found {} recipe result(s) that differ from the placed block only in their fluid: {}",
                    offenders.size(), offenders);
        }
        if (brineRecipes.size() != 1) {
            LOGGER.error("Smoke test expected one spread recipe of {}, the {} recipe, found {}: {}",
                    BuiltInRegistries.FLUID.getKey(brine), BuiltInRegistries.BLOCK.getKey(JeiAutoTestFluids.RESULT),
                    brineRecipes.size(), brineRecipes);
        }
        LOGGER.info("Smoke test waterlog: {} offender(s), {} spread recipe(s) of {}: {}", offenders.size(), brineRecipes.size(),
                BuiltInRegistries.FLUID.getKey(brine), brineRecipes);
    }

    /** The block states a recipe places at one offset, over all alternatives. */
    private static List<BlockState> placedAt(FluidInteractionRecipe recipe, BlockPos offset) {
        if (offset.equals(BlockPos.ZERO)) {
            return recipe.sources().stream().map(FluidState::createLegacyBlock).toList();
        }
        if (offset.equals(recipe.neighborOffset())) {
            return recipe.neighbors().stream().map(Placement::block).toList();
        }
        Placement condition = recipe.conditions().get(offset);
        return condition != null ? List.of(condition.block()) : List.of();
    }

    /** Same block; each changed property is WATERLOGGED or alone changes the fluid. */
    private static boolean onlyFluidDiffers(BlockState placed, BlockState result) {
        if (placed.getBlock() != result.getBlock() || placed.equals(result)) {
            return false;
        }
        return placed.getProperties().stream()
                .filter(property -> !placed.getValue(property).equals(result.getValue(property)))
                .filter(property -> property != BlockStateProperties.WATERLOGGED)
                .noneMatch(property -> withValue(placed, result, property).getFluidState().equals(placed.getFluidState()));
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState placed, BlockState result, Property<T> property) {
        return placed.setValue(property, result.getValue(property));
    }

    private static String failureText(FluidInteractionRecipe recipe) {
        Component failure = recipe.failure();
        return failure != null ? failure.getString() : "";
    }

    /** How a placement line names a fluid, so a failure text can be checked against the same wording. */
    private static String fluidName(ResourceLocation key) {
        Fluid fluid = BuiltInRegistries.FLUID.get(key);
        return fluid.defaultFluidState().isEmpty() ? key.toString() : fluid.getFluidType().getDescription().getString();
    }

    private static String blockName(ResourceLocation key) {
        return BuiltInRegistries.BLOCK.get(key).getName().getString();
    }

    /** One line per recipe that writes a block of {@code mergeAcrossMods} or comes from one of these mods. */
    private static void logInventory(List<FluidInteractionRecipe> found, List<String> owners) {
        List<String> blocks = List.copyOf(Config.MERGE_ACROSS_MODS.get());
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.owner() != null && owners.contains(recipe.owner())
                        || recipe.results().values().stream().anyMatch(state -> blocks.contains(String.valueOf(resultKey(state)))))
                .toList();
        for (FluidInteractionRecipe recipe : matches) {
            LOGGER.info("{} inventory {}", PREFIX, describeRecipe(recipe));
        }
        LOGGER.info("{} inventory: {} recipe(s) write {} or come from {}", PREFIX, matches.size(), blocks, owners);
    }

    /** The id, owner and every part of the merge key of one recipe. */
    private static String describeRecipe(FluidInteractionRecipe recipe) {
        String path = recipe.id().getPath();
        String tier = path.startsWith("spread/") ? "spread" : path.startsWith("neighbor/") ? "neighbor" : "registry";
        Set<String> kinds = recipe.neighbors().stream().map(placement -> placement.isFluid() ? "fluid" : "block")
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return recipe.id() + " (from " + recipe.owner() + ", " + tier + "): type " + RecipeIds.keyOf(recipe.sourceType())
                + ", source " + recipe.sourceFluids().stream().map(fluid -> String.valueOf(BuiltInRegistries.FLUID.getKey(fluid))).toList()
                + " source form " + recipe.matchesSourceForm() + " flowing form " + recipe.matchesFlowingForm()
                + ", neighbor " + recipe.neighborOffset().toShortString() + " kinds " + kinds + " "
                + recipe.neighbors().stream().map(JeiAutoTest::alternativeKey).toList()
                + ", conditions " + recipe.conditions().entrySet().stream()
                        .map(entry -> entry.getKey().toShortString() + "=" + entry.getValue().block()).toList()
                + ", results " + recipe.results().entrySet().stream()
                        .map(entry -> entry.getKey().toShortString() + "=" + entry.getValue()).toList()
                + (recipe.isLockstep() ? ", " + recipe.rows().size() + " row(s)" : "");
    }

    /** One line per run naming the exact ordered id list, so consecutive runs can be compared with one grep. */
    private static void logRecipeIds(List<FluidInteractionRecipe> found) {
        List<String> ids = found.stream().map(recipe -> recipe.id().toString()).sorted().toList();
        String joined = String.join(",", ids);
        LOGGER.debug("Smoke test recipe id list {}", joined);
        LOGGER.info("Smoke test recipe ids {}", sha256Hex(joined));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void grab(Minecraft mc, String name) {
        AutoTestWorld.grab(mc, "jei_fluid_interactions_" + name + ".png", LOGGER, PREFIX);
    }
}
