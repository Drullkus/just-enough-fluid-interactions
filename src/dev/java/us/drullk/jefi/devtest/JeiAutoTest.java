package us.drullk.jefi.devtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import com.mojang.logging.LogUtils;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluid;
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

    private static int phase;
    private static int timer;
    private static int shot;
    private static List<FluidInteractionRecipe> recipes = List.of();

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
                    LOGGER.info("Smoke test finished, stopping the client");
                    phase = 5;
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
