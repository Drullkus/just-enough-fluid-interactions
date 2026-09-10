package us.drullk.jefi.devtest;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import com.mojang.logging.LogUtils;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * The steps of the EMI smoke test. EMI is on the {@code clientEmiTest} run's classpath alone, so every reference
 * to it lives in this class, which no other run ever loads.
 */
final class EmiAutoTestSteps {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD_NAME = "emi_fluid_interactions_test";
    private static final String PREFIX = "EMI test";
    private static final int MAX_SHOTS = 4;

    private static int phase;
    private static int timer;
    private static int shot;
    private static List<EmiRecipe> recipes = List.of();

    private EmiAutoTestSteps() {
    }

    static void tick(Minecraft mc) {
        switch (phase) {
            case 0 -> {
                if (AutoTestWorld.atTitleScreen(mc)) {
                    phase = 1;
                    AutoTestWorld.enterWorld(mc, WORLD_NAME, LOGGER, PREFIX);
                }
            }
            case 1 -> {
                if (mc.level != null && mc.player != null && mc.screen == null && category() != null) {
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                    phase = 2;
                    timer = 40;
                }
            }
            case 2 -> {
                if (--timer <= 0) {
                    EmiRecipeCategory category = category();
                    if (category == null) {
                        LOGGER.error("{} found no EMI category for {}", PREFIX, FluidInteractionsJeiPlugin.TYPE.getUid());
                        phase = 5;
                        return;
                    }
                    recipes = order(EmiApi.getRecipeManager().getRecipes(category));
                    LOGGER.info("{} found {} recipe(s) in category {}", PREFIX, recipes.size(), category.getId());
                    EmiApi.displayRecipeCategory(category);
                    phase = 3;
                    timer = 30;
                }
            }
            case 3 -> {
                if (--timer <= 0) {
                    if (shot == 0) {
                        LOGGER.info("{} showing the category page", PREFIX);
                        grab(mc, "category");
                    } else {
                        EmiRecipe shown = recipes.get(shot - 1);
                        LOGGER.info("{} showing recipe {}", PREFIX, shown.getId());
                        grab(mc, "recipe_" + shot);
                    }
                    shot++;
                    if (shot >= MAX_SHOTS || shot > recipes.size()) {
                        phase = 4;
                        timer = 10;
                        return;
                    }
                    EmiApi.displayRecipe(recipes.get(shot - 1));
                    timer = 25;
                }
            }
            case 4 -> {
                if (--timer <= 0) {
                    LOGGER.info("{} finished, stopping the client", PREFIX);
                    phase = 5;
                    mc.stop();
                }
            }
            default -> {
            }
        }
    }

    /** Vanilla's lava over water is the recipe with the most to draw, so it leads the screenshots. */
    private static List<EmiRecipe> order(List<EmiRecipe> found) {
        return found.stream()
                .sorted((a, b) -> Boolean.compare(isSpread(b), isSpread(a)))
                .toList();
    }

    /** EMI keeps this mod's recipe id inside a synthetic id of its own, so the marker is matched anywhere in it. */
    private static boolean isSpread(EmiRecipe recipe) {
        ResourceLocation id = recipe.getId();
        return id != null && id.getPath().contains("spread/");
    }

    private static @Nullable EmiRecipeCategory category() {
        ResourceLocation uid = FluidInteractionsJeiPlugin.TYPE.getUid();
        return EmiApi.getRecipeManager().getCategories().stream()
                .filter(category -> uid.equals(category.getId()))
                .findFirst()
                .orElse(null);
    }

    private static void grab(Minecraft mc, String name) {
        AutoTestWorld.grab(mc, "emi_fluid_interactions_" + name + ".png", LOGGER, PREFIX);
    }
}
