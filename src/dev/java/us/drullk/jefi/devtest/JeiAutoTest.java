package us.drullk.jefi.devtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.InertFormIndicator;
import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.probe.RecipeIds;
import us.drullk.jefi.jei.probe.RuleProber;
import us.drullk.jefi.jei.scene.SceneArrangement;
import us.drullk.jefi.jei.scene.SceneVariant;
import com.mojang.logging.LogUtils;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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

    private static int shot;
    private static List<FluidInteractionRecipe> recipes = List.of();
    private static @Nullable FluidInteractionRecipe spreadRecipe;
    private static @Nullable FluidInteractionRecipe formRecipe;
    private static @Nullable FluidInteractionRecipe neighborRecipe;
    private static @Nullable FluidInteractionRecipe cascadeRecipe;
    private static @Nullable FluidInteractionRecipe offsetRecipe;
    private static @Nullable FluidInteractionRecipe preemptedRecipe;

    /** The recipes that get a screenshot of their own, in this order. Each is read when its step runs. */
    private static final List<Shot> SHOTS = List.of(
            new Shot("spread", () -> spreadRecipe),
            new Shot("form", () -> formRecipe),
            new Shot("neighbor", () -> neighborRecipe),
            new Shot("cascade", () -> cascadeRecipe),
            new Shot("offset", () -> offsetRecipe),
            new Shot("preempted", () -> preemptedRecipe));

    private static final TickSteps STEPS = steps();

    private JeiAutoTest() {
    }

    private record Shot(String name, Supplier<@Nullable FluidInteractionRecipe> recipe) {
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
        checkAlternatives(recipes);
        checkMerging(recipes);
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
        if (recipe != null) {
            show(List.of(recipe));
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

    /**
     * The dripstone beside the dev fluid is the probed neighbor. So the interaction's own write at that offset
     * waterlogs it in place. This write survives settling because it carries a fluid other than the one the
     * arrangement poured. It must carry {@link BlockStateProperties#WATERLOGGED} so the scene can draw a block
     * whose model offset and fluid share one cell.
     */
    private static void checkOffsetRecipe(List<FluidInteractionRecipe> found) {
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.neighbors().stream().anyMatch(placement -> placement.block().is(JeiAutoTestInteractions.OFFSET_CONDITION)))
                .toList();
        if (matches.size() != 1) {
            LOGGER.error("Smoke test expected one recipe with a {} condition, found {}",
                    BuiltInRegistries.BLOCK.getKey(JeiAutoTestInteractions.OFFSET_CONDITION), matches.size());
            return;
        }
        FluidInteractionRecipe recipe = matches.getFirst();
        BlockState written = recipe.results().get(recipe.neighborOffset());
        if (written == null || !written.hasProperty(BlockStateProperties.WATERLOGGED) || !written.getValue(BlockStateProperties.WATERLOGGED)) {
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
