package us.drullk.jefi.devtest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.FluidInteractionCategory;
import us.drullk.jefi.jei.FluidInteractionsJeiPlugin;
import us.drullk.jefi.jei.ItemlessBlock;
import us.drullk.jefi.jei.probe.EmiTagView;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.scene.SceneArrangement;
import com.mojang.logging.LogUtils;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.emi.emi.jemi.JemiRecipe;
import dev.emi.emi.jemi.JemiStack;
import dev.emi.emi.jemi.JemiUtil;
import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.runtime.EmiTagKey;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private static boolean lockstepShown;
    private static List<EmiRecipe> recipes = List.of();

    /**
     * The test runs as a list of steps. It enters the world. It opens the category. It screenshots the category
     * and the first recipes. It clicks the left scene of the recipe on screen. It screenshots the recipe turned.
     * It shows the recipe with a block without an item and screenshots it. It shows a lockstep recipe and
     * screenshots it. It stops the client.
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
            .after(10, EmiAutoTestSteps::showBlockless)
            .after(20, mc -> grab(mc, "blockless"))
            .after(10, EmiAutoTestSteps::showLockstep)
            .after(20, EmiAutoTestSteps::grabLockstep)
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
        checkTagList(mc);
        checkTags();
        EmiApi.displayRecipeCategory(category);
    }

    /** The tags that EMI can show in place of entries, with their values, are the tags of {@link EmiTagView}. */
    private static void checkTagList(Minecraft mc) {
        EmiTagView view = EmiTagView.load(mc.getResourceManager());
        List<String> counts = new ArrayList<>();
        for (Registry<?> registry : List.of(BuiltInRegistries.ITEM, BuiltInRegistries.FLUID)) {
            String difference = tagListDifference(registry, view, counts);
            if (difference != null) {
                LOGGER.error("{} tag list of {} differs from EMI's own: {}", PREFIX, registry.key().location(), difference);
            }
        }
        LOGGER.info("{} tag list: {}", PREFIX, counts);
    }

    /** The value sets of at least two values on one side only, or null when both sides hold the same sets. */
    private static <T> @Nullable String tagListDifference(Registry<T> registry, EmiTagView view, List<String> counts) {
        Set<Set<T>> ours = registry.getTags()
                .map(pair -> view.values(registry, pair.getSecond()))
                .filter(values -> values.size() >= 2)
                .map(Set::copyOf)
                .collect(Collectors.toSet());
        Set<Set<T>> emi = EmiTags.getTags(registry).stream()
                .map(EmiTagKey::getList)
                .filter(values -> values.size() >= 2)
                .map(Set::copyOf)
                .collect(Collectors.toSet());
        counts.add(registry.key().location() + " " + ours.size() + " of " + emi.size() + " value set(s)");
        Set<Set<T>> onlyOurs = new HashSet<>(ours);
        onlyOurs.removeAll(emi);
        Set<Set<T>> onlyEmi = new HashSet<>(emi);
        onlyEmi.removeAll(ours);
        if (onlyOurs.isEmpty() && onlyEmi.isEmpty()) {
            return null;
        }
        return onlyOurs.size() + " set(s) only here, " + onlyEmi.size() + " only in EMI, for example "
                + onlyOurs.stream().limit(3).map(set -> set.stream().map(value -> String.valueOf(registry.getKey(value))).sorted().toList()).toList()
                + " and " + onlyEmi.stream().limit(3).map(set -> set.stream().map(value -> String.valueOf(registry.getKey(value))).sorted().toList()).toList();
    }

    /** No slot of a lockstep recipe is a tag in EMI. EMI draws a tag as one entry that never cycles. */
    private static void checkTags() {
        int lockstep = 0;
        int tags = 0;
        for (EmiRecipe recipe : recipes) {
            if (!(recipe instanceof JemiRecipe<?> jemi) || !(jemi.recipe instanceof FluidInteractionRecipe found) || !found.isLockstep()) {
                continue;
            }
            lockstep++;
            List<String> shown = slotIngredients(jemi, found).stream().filter(EmiAutoTestSteps::holdsTag)
                    .map(ingredient -> ingredient.getEmiStacks().size() + " stack(s) " + ingredient.getEmiStacks().stream()
                            .map(stack -> stack.getName().getString()).toList())
                    .toList();
            if (!shown.isEmpty()) {
                tags += shown.size();
                LOGGER.error("{} tags: {} shows a tag in {}", PREFIX, recipe.getId(), shown);
            }
        }
        LOGGER.info("{} tags: {} lockstep recipe(s), {} slot(s) that show a tag", PREFIX, lockstep, tags);
    }

    private static boolean holdsTag(EmiIngredient ingredient) {
        return ingredient instanceof TagEmiIngredient
                || ingredient instanceof ListEmiIngredient list && list.getIngredients().stream().anyMatch(TagEmiIngredient.class::isInstance);
    }

    /** The ingredient of every slot of a lockstep recipe. Each output slot holds one stack per row. */
    private static List<EmiIngredient> slotIngredients(JemiRecipe<?> jemi, FluidInteractionRecipe recipe) {
        List<EmiIngredient> ingredients = new ArrayList<>(jemi.inputs);
        ingredients.addAll(jemi.catalysts);
        int size = recipe.rows().size();
        for (int i = 0; i + size <= jemi.outputs.size() && size > 0; i += size) {
            ingredients.add(EmiIngredient.of(jemi.outputs.subList(i, i + size)));
        }
        return ingredients;
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

    /** JEMI wraps the {@link ItemlessBlock} in a stack that finds its recipe. */
    private static void showBlockless(Minecraft mc) {
        EmiRecipe found = null;
        EmiStack output = null;
        for (EmiRecipe recipe : recipes) {
            for (EmiStack stack : recipe.getOutputs()) {
                if (stack instanceof JemiStack<?> jemi && jemi.ingredient instanceof ItemlessBlock block
                        && block.state().is(JeiAutoTestInteractions.ITEMLESS_RESULT)) {
                    found = recipe;
                    output = stack;
                }
            }
        }
        if (found == null) {
            LOGGER.error("{} found no recipe with an itemless block output", PREFIX);
            return;
        }
        List<EmiRecipe> byOutput = EmiApi.getRecipeManager().getRecipesByOutput(output);
        if (!byOutput.contains(found)) {
            LOGGER.error("{} blockless {}: EMI finds {} recipe(s) by the output, not this one", PREFIX, found.getId(), byOutput.size());
        }
        LOGGER.info("{} blockless {}: output {}, EMI finds {} recipe(s) by the output", PREFIX, found.getId(),
                output.getName().getString(), byOutput.size());
        EmiApi.displayRecipe(found);
    }

    /** Each EMI ingredient of a lockstep recipe holds its slot's entries in order, or one entry of every row. */
    private static void showLockstep(Minecraft mc) {
        for (EmiRecipe recipe : recipes) {
            if (!(recipe instanceof JemiRecipe<?> jemi) || !(jemi.recipe instanceof FluidInteractionRecipe lockstep) || !lockstep.isLockstep()) {
                continue;
            }
            List<List<Placement>> slots = slotRows(lockstep);
            List<EmiIngredient> ingredients = slotIngredients(jemi, lockstep);
            int size = lockstep.rows().size();
            List<String> offenders = new ArrayList<>();
            for (EmiIngredient ingredient : ingredients) {
                if (slots.stream().noneMatch(rows -> holds(ingredient, rows))) {
                    offenders.add(ingredient.getEmiStacks().size() + " stack(s) " + ingredient.getEmiStacks().stream()
                            .map(stack -> stack.getName().getString()).toList());
                }
            }
            if (ingredients.size() != slots.size()) {
                offenders.add(ingredients.size() + " ingredient(s) for " + slots.size() + " slot(s)");
            }
            int row = (int) (System.currentTimeMillis() / 1000L % size);
            if (offenders.isEmpty()) {
                LOGGER.info("{} merged within a mod {}: {} row(s), {} slot(s) in row order, clock row {}: {}", PREFIX, recipe.getId(),
                        size, slots.size(), row, slots.stream().map(rows -> rows.get(row % rows.size()).describe().getString()).toList());
            } else {
                LOGGER.error("{} merged within a mod {} is out of step: {}", PREFIX, recipe.getId(), offenders);
            }
            lockstepShown = true;
            EmiApi.displayRecipe(recipe);
            return;
        }
        LOGGER.error("{} found no lockstep recipe", PREFIX);
    }

    private static void grabLockstep(Minecraft mc) {
        if (lockstepShown) {
            grab(mc, "merged_within");
        }
    }

    /** The entry each slot shows per row, in slot order. */
    private static List<List<Placement>> slotRows(FluidInteractionRecipe recipe) {
        List<List<Placement>> slots = new ArrayList<>();
        if (FluidInteractionRecipe.sharesSources(recipe.rows())) {
            slots.add(recipe.rows().getFirst().sourceFluids().stream().map(fluid -> Placement.ofFluid(fluid.defaultFluidState())).toList());
        } else {
            slots.add(recipe.rows().stream().map(row -> Placement.ofFluid(row.sourceFluids().getFirst().defaultFluidState())).toList());
        }
        if (!recipe.neighbors().isEmpty() && FluidInteractionRecipe.sharesNeighbors(recipe.rows())) {
            Map<Object, Placement> entries = new LinkedHashMap<>();
            recipe.neighbors().forEach(neighbor -> entries.putIfAbsent(neighbor.slotEntry(), neighbor));
            slots.add(List.copyOf(entries.values()));
        } else if (!recipe.neighbors().isEmpty()) {
            slots.add(recipe.rows().stream().map(row -> row.neighbors().getFirst()).toList());
        }
        for (BlockPos offset : recipe.conditions().keySet()) {
            slots.add(recipe.rows().stream().map(row -> row.conditions().get(offset)).toList());
        }
        for (BlockPos offset : recipe.results().keySet()) {
            slots.add(recipe.rows().stream().map(row -> Placement.ofBlock(row.results().get(offset))).toList());
        }
        return slots;
    }

    /** Whether an ingredient holds these rows in order, or one entry that every row shows. */
    private static boolean holds(EmiIngredient ingredient, List<Placement> rows) {
        List<EmiStack> stacks = ingredient.getEmiStacks();
        if (stacks.size() == 1) {
            return rows.stream().allMatch(row -> shows(row, stacks.getFirst()));
        }
        if (stacks.size() != rows.size()) {
            return false;
        }
        for (int i = 0; i < stacks.size(); i++) {
            if (!shows(rows.get(i), stacks.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean shows(Placement placement, EmiStack stack) {
        return JemiUtil.getTyped(stack).map(typed -> SceneArrangement.shows(placement, typed.getIngredient())).orElse(false);
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
