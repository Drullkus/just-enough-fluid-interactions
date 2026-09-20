package us.drullk.jefi.devtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.InertFormIndicator;
import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.InteractionProber;
import us.drullk.jefi.jei.probe.NeighborProber;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.probe.SpreadProber;
import com.mojang.logging.LogUtils;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * Development-only smoke test, enabled by {@code -Djustenoughfluidinteractions.jeiautotest=true} (see the {@code clientJeiTest}
 * run configuration). Deletes any existing save and creates a fresh flat creative world, opens this mod's JEI
 * category, screenshots a few pages of recipes into {@code run/screenshots}, and exits the game.
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
    /** The lava-tagged fluids on the dev classpath, in both forms, as the sugar water probe finds them. */
    private static final int SUGAR_WATER_ALTERNATIVES = 8;
    /** The same minus both forms of lava, which a registry interaction of lava's own consumes sideways first. */
    private static final int SUGAR_WATER_BESIDE_ALTERNATIVES = 6;
    /** DivineRPG's lava-tagged tar, the second fluid that reaches sugar water and the second spread recipe to lose it. */
    private static final ResourceLocation TAR_TYPE = ResourceLocation.fromNamespaceAndPath("divinerpg", "smoldering_tar_fluid_type");
    private static final String TAR_SPREAD_IDS = "spread/divinerpg/smoldering_tar_fluid_type/";
    /** Vanilla's stone, which lava's own spread code writes into a water-tagged fluid below it. */
    private static final String LAVA_SPREAD_IDS = "spread/minecraft/lava/";
    /** The Bumblezone's honey, whose result block changes a neighbor of its own when it is placed. */
    private static final String HONEY_IDS = "neighbor/the_bumblezone/honey/";

    private static int phase;
    private static int timer;
    private static int shot;
    private static List<FluidInteractionRecipe> recipes = List.of();
    private static @Nullable FluidInteractionRecipe spreadRecipe;
    private static @Nullable FluidInteractionRecipe formRecipe;
    private static @Nullable FluidInteractionRecipe neighborRecipe;
    private static @Nullable FluidInteractionRecipe cascadeRecipe;
    private static @Nullable FluidInteractionRecipe offsetRecipe;

    private JeiAutoTest() {
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
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                    phase = 2;
                    timer = 40;
                }
            }
            case 2 -> {
                if (--timer <= 0) {
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (runtime == null) {
                        LOGGER.error("JEI runtime disappeared before the smoke test could open its category");
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
                    logRecipeIds(recipes);
                    runtime.getRecipesGui().showTypes(List.of(FluidInteractionsJeiPlugin.TYPE));
                    phase = 3;
                    timer = 30;
                }
            }
            case 3 -> {
                if (--timer <= 0) {
                    grab(mc, shot == 0 ? "category" : "recipes_" + shot);
                    int from = shot * RECIPES_PER_SHOT;
                    shot++;
                    if (shot >= MAX_SHOTS || from >= recipes.size()) {
                        phase = 4;
                        timer = 10;
                        return;
                    }
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (runtime == null) {
                        phase = 4;
                        timer = 10;
                        return;
                    }
                    IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
                    List<FluidInteractionRecipe> page = recipes.subList(from, Math.min(from + RECIPES_PER_SHOT, recipes.size()));
                    runtime.getRecipesGui().showRecipes(category, page, List.of());
                    timer = 25;
                }
            }
            case 4 -> {
                if (--timer <= 0) {
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (spreadRecipe != null && runtime != null) {
                        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
                        runtime.getRecipesGui().showRecipes(category, List.of(spreadRecipe), List.of());
                    }
                    phase = 5;
                    timer = 25;
                }
            }
            case 5 -> {
                if (--timer <= 0) {
                    if (spreadRecipe != null) {
                        grab(mc, "spread");
                    }
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (formRecipe != null && runtime != null) {
                        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
                        runtime.getRecipesGui().showRecipes(category, List.of(formRecipe), List.of());
                    }
                    phase = 6;
                    timer = 25;
                }
            }
            case 6 -> {
                if (--timer <= 0) {
                    if (formRecipe != null) {
                        grab(mc, "form");
                    }
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (neighborRecipe != null && runtime != null) {
                        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
                        runtime.getRecipesGui().showRecipes(category, List.of(neighborRecipe), List.of());
                    }
                    phase = 7;
                    timer = 25;
                }
            }
            case 7 -> {
                if (--timer <= 0) {
                    if (neighborRecipe != null) {
                        grab(mc, "neighbor");
                    }
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (cascadeRecipe != null && runtime != null) {
                        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
                        runtime.getRecipesGui().showRecipes(category, List.of(cascadeRecipe), List.of());
                    }
                    phase = 8;
                    timer = 25;
                }
            }
            case 8 -> {
                if (--timer <= 0) {
                    if (cascadeRecipe != null) {
                        grab(mc, "cascade");
                    }
                    IJeiRuntime runtime = FluidInteractionsJeiPlugin.runtime();
                    if (offsetRecipe != null && runtime != null) {
                        IRecipeCategory<FluidInteractionRecipe> category = runtime.getRecipeManager().getRecipeCategory(FluidInteractionsJeiPlugin.TYPE);
                        runtime.getRecipesGui().showRecipes(category, List.of(offsetRecipe), List.of());
                    }
                    phase = 9;
                    timer = 25;
                }
            }
            case 9 -> {
                if (--timer <= 0) {
                    if (offsetRecipe != null) {
                        grab(mc, "offset");
                    }
                    phase = 10;
                    timer = 10;
                }
            }
            case 10 -> {
                if (--timer <= 0) {
                    LOGGER.info("Smoke test finished, stopping the client");
                    phase = 11;
                    mc.stop();
                }
            }
            default -> {
            }
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
     * The dev interactions registered for two fluid types with the same neighbors and result have to arrive as one
     * recipe holding every source fluid and every neighbor, which only happens if both merge passes ran.
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
     * Vanilla's cobblestone interaction accepts its water neighbor in either form, so the probe has to record the
     * flowing one as an alternative for the scene to draw the neighbor flowing.
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
     * Vanilla's stone comes out of lava's own spread code rather than the interaction registry, so it exists only
     * when the spread probe ran: lava with water directly below it and both water forms cycling in one slot.
     */
    private static void checkSpreadRecipe(List<FluidInteractionRecipe> found) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        List<FluidInteractionRecipe> matches = found.stream()
                .filter(recipe -> recipe.neighborOffset().equals(SpreadProber.BELOW_OFFSET))
                .filter(recipe -> stone.equals(recipe.results().get(SpreadProber.BELOW_OFFSET)))
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
                BuiltInRegistries.BLOCK.getKey(stone.getBlock()), SpreadProber.BELOW_OFFSET.toShortString(),
                stoneRecipe.neighbors().stream().map(placement -> placement.describe().getString()).toList());
    }

    /**
     * Within one fluid type the display order ranks owners, so vanilla's stone — owned by {@code minecraft} and
     * found by the spread probe — has to precede every {@code minecraft:lava} recipe owned by a third party,
     * including the dev-only ones registered here.
     */
    private static void checkOwnerOrder(List<FluidInteractionRecipe> found) {
        if (spreadRecipe == null) {
            LOGGER.error("Smoke test has no stone spread recipe to check the owner order against");
            return;
        }
        int stone = found.indexOf(spreadRecipe);
        List<FluidInteractionRecipe> thirdParty = found.stream()
                .filter(recipe -> LAVA_TYPE.equals(InteractionProber.keyOf(recipe.sourceType())))
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
     * The dev fluid hardens a lava-tagged fluid below it only as a source block, so its spread recipe has to
     * carry the source state alone with the flowing state recorded as inert, and the line the source slot shows
     * has to name that flowing form. Vanilla's stone comes out of both lava forms, so it records nothing inert.
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
     * The dev water-tagged fluid has a registered interaction of its own with a lava neighbor, which a level runs
     * on the block update that placing the lava sends, long before lava's spread tick reaches the fluid below it.
     * Lava's own spread code would turn it to stone like any other water-tagged fluid and never gets the chance,
     * so it belongs in a recipe of its own rather than among the alternatives of vanilla's stone.
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
     * The Bumblezone's sugar water hardens inside its liquid block's {@code neighborChanged}, which a level runs
     * on the tick a lava-tagged fluid is placed against it and which no fluid tick and no registry entry
     * describes. Its source form becomes sugar-infused stone and its flowing form sugar-infused cobblestone, from
     * every lava-tagged neighbor in either form, beside the source and above it but never below it. Because that
     * happens before any spread tick, the fluids whose spread would otherwise reach sugar water never do. Lava
     * itself reaches the sugar water beside it through a registry interaction of its own first, so it stays an
     * alternative of the above-position recipes alone.
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

        for (BlockPos offset : List.of(FluidInteractionRecipe.NEIGHBOR_OFFSET, NeighborProber.ABOVE_OFFSET)) {
            FluidInteractionRecipe stone = hardened(matches, offset, SUGAR_INFUSED_STONE, false);
            FluidInteractionRecipe cobblestone = hardened(matches, offset, SUGAR_INFUSED_COBBLESTONE, true);
            if (offset.equals(NeighborProber.ABOVE_OFFSET)) {
                neighborRecipe = stone;
            }
            for (FluidInteractionRecipe recipe : Stream.of(stone, cobblestone).filter(recipe -> recipe != null).toList()) {
                if (offset.equals(NeighborProber.ABOVE_OFFSET)) {
                    checkBothForms(recipe, Fluids.LAVA::isSame, "minecraft:lava");
                }
                checkBothForms(recipe, fluid -> TAR_TYPE.equals(NeoForgeRegistries.FLUID_TYPES.getKey(fluid.getFluidType())), TAR_TYPE.toString());
            }
        }

        checkNeighborRegistryPreempted(matches);
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
     * A registry interaction registered on lava's own fluid type consumes the lava before sugar water's update
     * hook is told about it, and only where that interaction looks: with lava beside it the sugar water is left
     * alone and the lava becomes obsidian or sugar-infused cobblestone, while with lava above it, which the
     * interaction on the lava block never looks down from, the sugar water hardens from every form the probe
     * verified. So among the recipes that harden the sugar water itself, those whose neighbor sits beside it hold
     * neither form of lava and those whose neighbor sits above it hold both.
     */
    private static void checkNeighborRegistryPreempted(List<FluidInteractionRecipe> matches) {
        List<FluidInteractionRecipe> hardened = matches.stream().filter(recipe -> recipe.resultAtSource() != null).toList();
        for (FluidInteractionRecipe recipe : hardened) {
            boolean beside = recipe.neighborOffset().getY() == 0;
            List<String> lava = lavaForms(recipe);
            int expected = beside ? SUGAR_WATER_BESIDE_ALTERNATIVES : SUGAR_WATER_ALTERNATIVES;
            if (beside && !lava.isEmpty()) {
                LOGGER.error("Smoke test found minecraft:lava still among the alternatives of sugar water recipe {} with its neighbor {}: {}",
                        recipe.id(), Texts.offset(recipe.neighborOffset()).getString(), lava);
            }
            if (!beside && lava.isEmpty()) {
                LOGGER.error("Smoke test expected minecraft:lava among the alternatives of sugar water recipe {} with its neighbor {}, found none",
                        recipe.id(), Texts.offset(recipe.neighborOffset()).getString());
            }
            if (recipe.neighbors().size() != expected) {
                LOGGER.error("Smoke test expected {} alternative(s) of sugar water recipe {}, found {}: {}",
                        expected, recipe.id(), recipe.neighbors().size(),
                        recipe.neighbors().stream().map(placement -> placement.describe().getString()).toList());
            }
        }
        LOGGER.info("Smoke test neighbor registry pre-empted minecraft:lava: {}",
                hardened.stream().map(recipe -> recipe.id() + " " + Texts.offset(recipe.neighborOffset()).getString()
                        + " " + recipe.neighbors().size() + " alternative(s), lava " + lavaForms(recipe)).toList());
    }

    /**
     * Every recipe describes what its own rule does. Sugar water's update hook writes into the sugar water
     * itself, so none of that hook's recipes writes at a lava neighbor: what a level leaves where lava stood
     * beside sugar water comes from a registry interaction on the lava, whose own recipes carry it.
     * Lava and the tar both write stone into a water-tagged fluid below them from their spread code, so a spread
     * recipe of either holding such a fluid among its alternatives writes stone at that fluid and nothing
     * anywhere else; anything else a level reaches from the same arrangement is some other rule's outcome and
     * belongs to that rule's recipe.
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
     * Sugar water hardens on the tick a lava-tagged fluid is placed against it, so that fluid's own spread tick
     * never reaches it: neither form may still be an alternative of a spread recipe.
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
     * The dev fluid's source slot always carries the inert-form indicator (a "?" over its top-right corner)
     * because its only source fluid has an inert flowing form recorded. Its neighbor slot carries none: hardening
     * the lava-tagged target does not depend on that target's own form, so neither of its forms is ever inert.
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
     * The glistering honey crystal a honey source under still water becomes has an {@code onPlace} of its own
     * that turns that water into sugar water, so the level writes at two positions from the one arrangement.
     * Neither the honey's update hook nor any registry entry says so on its own; only settling the arrangement
     * does, and the recipe has to carry both results for the scene to draw what a player would see.
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
     * The dripstone beside the dev fluid is the probed neighbor, so the interaction's own write at that offset
     * waterlogs it in place; the write survives settling because it carries a fluid other than the one the
     * arrangement poured, and it must carry {@link BlockStateProperties#WATERLOGGED} for the scene to draw a
     * block whose model offset and fluid share one cell.
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
