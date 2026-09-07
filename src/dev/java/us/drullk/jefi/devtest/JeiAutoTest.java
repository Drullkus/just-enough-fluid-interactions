package us.drullk.jefi.devtest;

import java.util.List;

import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import com.mojang.logging.LogUtils;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Development-only smoke test, enabled by {@code -Djustenoughfluidinteractions.jeiautotest=true} (see the {@code clientJeiTest}
 * run configuration). Loads or creates a flat creative world, opens this mod's JEI category, screenshots a few
 * pages of recipes into {@code run/screenshots}, and exits the game.
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
                if (mc.screen instanceof TitleScreen) {
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

    private static void enterWorld(Minecraft mc) {
        if (mc.getLevelSource().levelExists(WORLD_NAME)) {
            LOGGER.info("Smoke test loading existing world {}", WORLD_NAME);
            mc.createWorldOpenFlows().openWorld(WORLD_NAME, () -> mc.setScreen(new TitleScreen()));
            return;
        }
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

    private static void grab(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "jei_fluid_interactions_" + name + ".png", mc.getMainRenderTarget(),
                message -> LOGGER.info("Smoke test screenshot: {}", message.getString()));
    }
}
