package us.drullk.jefi.devtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.InteractionProber;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.probe.SpreadProber;
import com.mojang.logging.LogUtils;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

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
    private static final int RECIPES_PER_SHOT = 2;
    private static final int MAX_SHOTS = 10;
    private static final ResourceLocation LAVA_TYPE = ResourceLocation.withDefaultNamespace("lava");

    private static int phase;
    private static int timer;
    private static int shot;
    private static List<FluidInteractionRecipe> recipes = List.of();
    private static @Nullable FluidInteractionRecipe spreadRecipe;
    private static @Nullable FluidInteractionRecipe formRecipe;

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
                if (mc.screen instanceof AccessibilityOnboardingScreen) {
                    mc.options.onboardAccessibility = false;
                    mc.setScreen(new TitleScreen());
                } else if (mc.screen instanceof TitleScreen) {
                    mc.options.pauseOnLostFocus = false;
                    phase = 1;
                    enterWorld(mc);
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
                    phase = 7;
                    timer = 10;
                }
            }
            case 7 -> {
                if (--timer <= 0) {
                    LOGGER.info("Smoke test finished, stopping the client");
                    phase = 8;
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

    /** One line per run naming the exact ordered id list, so consecutive runs can be compared with one grep. */
    private static void logRecipeIds(List<FluidInteractionRecipe> found) {
        List<String> ids = found.stream().map(recipe -> recipe.id().toString()).sorted().toList();
        LOGGER.info("Smoke test recipe ids {}", sha256Hex(String.join(",", ids)));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void enterWorld(Minecraft mc) {
        deleteExistingWorld(mc);
        LOGGER.info("Smoke test creating world {}", WORLD_NAME);
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(1234L, false, false);
        mc.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, options,
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    /** Runs from the title screen before any level is loaded, so the save directory is never open here. */
    private static void deleteExistingWorld(Minecraft mc) {
        Path path = mc.getLevelSource().getLevelPath(WORLD_NAME);
        if (!Files.isDirectory(path)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Smoke test failed to delete existing world " + WORLD_NAME, e);
        }
    }

    private static void grab(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "jei_fluid_interactions_" + name + ".png", mc.getMainRenderTarget(),
                message -> LOGGER.info("Smoke test screenshot: {}", message.getString()));
    }
}
