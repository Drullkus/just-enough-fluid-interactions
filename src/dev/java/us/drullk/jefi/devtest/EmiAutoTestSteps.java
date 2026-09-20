package us.drullk.jefi.devtest;

import java.lang.reflect.Field;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.FluidInteractionCategory;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import com.mojang.logging.LogUtils;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/**
 * The steps of the EMI smoke test. EMI is on the {@code clientEmiTest} run's classpath alone. So every reference
 * to it lives in this class, and no other run loads this class.
 */
final class EmiAutoTestSteps {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD_NAME = "emi_fluid_interactions_test";
    private static final String PREFIX = "EMI test";
    private static final int MAX_SHOTS = 4;

    private static int shot;
    private static List<EmiRecipe> recipes = List.of();

    /**
     * The test runs as a list of steps. It enters the world. It opens the category. It screenshots the category
     * and the first recipes. It clicks the left scene of the recipe on screen. It screenshots the recipe turned.
     * It stops the client.
     */
    private static final TickSteps STEPS = new TickSteps()
            .until(AutoTestWorld::atTitleScreen, mc -> AutoTestWorld.enterWorld(mc, WORLD_NAME, LOGGER, PREFIX))
            .until(mc -> mc.level != null && mc.player != null && mc.screen == null && category() != null, mc -> {
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
            })
            .after(40, EmiAutoTestSteps::openCategory)
            .after(30, mc -> {
                LOGGER.info("{} showing the category page", PREFIX);
                grab(mc, "category");
            })
            .repeat(25, EmiAutoTestSteps::nextRecipe)
            .after(10, EmiAutoTestSteps::rotate)
            .after(20, mc -> grab(mc, "rotated"))
            .after(10, mc -> {
                LOGGER.info("{} finished, stopping the client", PREFIX);
                mc.stop();
            });

    private EmiAutoTestSteps() {
    }

    static void tick(Minecraft mc) {
        STEPS.tick(mc);
    }

    private static void openCategory(Minecraft mc) {
        EmiRecipeCategory category = category();
        if (category == null) {
            LOGGER.error("{} found no EMI category for {}", PREFIX, FluidInteractionsJeiPlugin.TYPE.getUid());
            STEPS.stop();
            return;
        }
        recipes = order(EmiApi.getRecipeManager().getRecipes(category));
        LOGGER.info("{} found {} recipe(s) in category {}", PREFIX, recipes.size(), category.getId());
        EmiApi.displayRecipeCategory(category);
    }

    /** Screenshots the recipe on screen, then shows the next one. Returns false once it screenshots every recipe. */
    private static boolean nextRecipe(Minecraft mc) {
        if (shot > 0) {
            EmiRecipe shown = recipes.get(shot - 1);
            LOGGER.info("{} showing recipe {}", PREFIX, shown.getId());
            grab(mc, "recipe_" + shot);
        }
        shot++;
        if (shot >= MAX_SHOTS || shot > recipes.size()) {
            return false;
        }
        EmiApi.displayRecipe(recipes.get(shot - 1));
        return true;
    }

    /**
     * Clicks the middle of the left scene of the recipe on screen. EMI hands this click to the category, and it
     * is the only rotation EMI's static scenes have.
     */
    private static void rotate(Minecraft mc) {
        Screen screen = mc.screen;
        WidgetGroup group = screen instanceof RecipeScreen ? firstGroup(screen) : null;
        if (group == null) {
            LOGGER.error("{} found no recipe widget group to click on {}", PREFIX, screen);
            return;
        }
        double x = group.x() + FluidInteractionCategory.sceneCenterX(false);
        double y = group.y() + FluidInteractionCategory.sceneCenterY();
        boolean handled = screen.mouseClicked(x, y, 0);
        if (handled) {
            LOGGER.info("{} clicked the left scene at {}, {}", PREFIX, x, y);
        } else {
            LOGGER.error("{} clicked the left scene at {}, {} and nothing took the click", PREFIX, x, y);
        }
    }

    /** EMI keeps the groups of the page it shows to itself. The recipe this test clicks is the first group. */
    private static @Nullable WidgetGroup firstGroup(Screen screen) {
        try {
            Field field = RecipeScreen.class.getDeclaredField("currentPage");
            field.setAccessible(true);
            List<?> groups = (List<?>) field.get(screen);
            return groups.isEmpty() ? null : (WidgetGroup) groups.get(0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("{} could not read the recipe widget groups", PREFIX, e);
            return null;
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
