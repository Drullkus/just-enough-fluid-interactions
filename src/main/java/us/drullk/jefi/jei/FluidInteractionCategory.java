package us.drullk.jefi.jei;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.scene.SceneCache;
import us.drullk.jefi.jei.scene.SceneRotation;
import us.drullk.jefi.jei.scene.SceneWidget;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawablesView;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.placement.HorizontalAlignment;
import mezz.jei.api.gui.placement.VerticalAlignment;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * JEI category for probed fluid interactions.
 *
 * <p>Two 3D scenes show the arrangement before and after the interaction, with one arrow between them. Above the
 * left scene sits the input row (source fluid, the neighbor, and any extra required blocks, separated by plus
 * signs); above the right scene sit the results. A recipe whose interaction could not be processed shows only
 * the source fluid and an explanation.
 */
public final class FluidInteractionCategory extends AbstractRecipeCategory<FluidInteractionRecipe> {
    public static final int WIDTH = 170;
    public static final int HEIGHT = 100;

    private static final int ROW_Y = 6;
    /** Footprint of a slot including its background, which JEI draws one pixel outside the ingredient. */
    private static final int SLOT_SIZE = 18;
    private static final int PLUS_GAP = 15;
    private static final int RESULT_GAP = 2;
    private static final int MIN_MARGIN = 4;

    private static final int SCENE_Y = 26;
    private static final int SCENE_SIZE = 68;
    private static final int BEFORE_X = 5;
    private static final int AFTER_X = WIDTH - BEFORE_X - SCENE_SIZE;

    private final SceneCache scenes;

    public FluidInteractionCategory(IGuiHelper guiHelper, SceneCache scenes) {
        super(FluidInteractionsJeiPlugin.TYPE, Texts.title(), guiHelper.createDrawableItemLike(Items.BUCKET), WIDTH, HEIGHT);
        this.scenes = scenes;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        if (recipe.isFailure()) {
            IRecipeSlotBuilder source = slot(builder.addInputSlot(centered(SLOT_SIZE), ROW_Y)).setSlotName("source");
            stillFluidsOf(recipe.sourceType()).forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
            return;
        }

        int[] inputs = inputSlotX(recipe);
        int next = 0;

        IRecipeSlotBuilder source = slot(builder.addInputSlot(inputs[next++], ROW_Y)).setSlotName("source");
        recipe.sourceFluids().forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
        source.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.forms(recipe).withStyle(ChatFormatting.GRAY)));

        if (!recipe.neighbors().isEmpty()) {
            IRecipeSlotBuilder neighbor = slot(builder.addInputSlot(inputs[next++], ROW_Y)).setSlotName("neighbor");
            addPlacements(neighbor, recipe.neighbors());
            neighbor.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(FluidInteractionRecipe.NEIGHBOR_OFFSET).withStyle(ChatFormatting.GRAY)));
        }
        for (var condition : recipe.conditions().entrySet()) {
            BlockPos offset = condition.getKey();
            IRecipeSlotBuilder slot = slot(builder.addInputSlot(inputs[next++], ROW_Y));
            addPlacements(slot, List.of(condition.getValue()));
            slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
        }

        int resultX = resultSlotX(recipe);
        for (var result : recipe.results().entrySet()) {
            BlockPos offset = result.getKey();
            IRecipeSlotBuilder slot = slot(builder.addOutputSlot(resultX, ROW_Y));
            addPlacements(slot, List.of(Placement.ofBlock(result.getValue())));
            if (!offset.equals(BlockPos.ZERO)) {
                slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
            }
            resultX += SLOT_SIZE + RESULT_GAP;
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        if (recipe.isFailure()) {
            builder.addText(recipe.failure(), WIDTH - 2 * MIN_MARGIN, HEIGHT - SCENE_Y - MIN_MARGIN)
                    .setPosition(MIN_MARGIN, SCENE_Y)
                    .setTextAlignment(HorizontalAlignment.CENTER)
                    .setTextAlignment(VerticalAlignment.CENTER);
            return;
        }

        int[] inputs = inputSlotX(recipe);
        for (int i = 1; i < inputs.length; i++) {
            int gapX = inputs[i - 1] - 1 + SLOT_SIZE;
            builder.addRecipePlusSignWidget().setPosition(gapX, ROW_Y, PLUS_GAP, 16, HorizontalAlignment.CENTER, VerticalAlignment.CENTER);
        }

        SceneRotation rotation = new SceneRotation();
        IRecipeSlotDrawablesView slots = builder.getRecipeSlots();
        IRecipeSlotView source = slots.findSlotByName("source").orElse(null);
        IRecipeSlotView neighbor = slots.findSlotByName("neighbor").orElse(null);

        builder.addWidget(scene(builder, recipe, false, rotation, source, neighbor, BEFORE_X));
        builder.addRecipeArrowWidget()
                .setPosition(BEFORE_X + SCENE_SIZE, SCENE_Y, AFTER_X - BEFORE_X - SCENE_SIZE, SCENE_SIZE, HorizontalAlignment.CENTER, VerticalAlignment.CENTER);
        builder.addWidget(scene(builder, recipe, true, rotation, source, neighbor, AFTER_X));
    }

    @Override
    public ResourceLocation getRegistryName(FluidInteractionRecipe recipe) {
        return recipe.id();
    }

    private SceneWidget scene(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, boolean after, SceneRotation rotation,
                              @Nullable IRecipeSlotView source, @Nullable IRecipeSlotView neighbor, int x) {
        SceneWidget widget = new SceneWidget(scenes, recipe, after, rotation, source, neighbor, x, SCENE_Y, SCENE_SIZE, SCENE_SIZE);
        builder.addInputHandler(widget);
        return widget;
    }

    private static int inputCount(FluidInteractionRecipe recipe) {
        return 1 + (recipe.neighbors().isEmpty() ? 0 : 1) + recipe.conditions().size();
    }

    /** Ingredient x of each input slot: the row is centered over the left scene, or flush left when too wide. */
    private static int[] inputSlotX(FluidInteractionRecipe recipe) {
        int count = inputCount(recipe);
        int width = count * SLOT_SIZE + (count - 1) * PLUS_GAP;
        int left = Math.max(MIN_MARGIN, BEFORE_X + SCENE_SIZE / 2 - width / 2);
        int[] xs = new int[count];
        for (int i = 0; i < count; i++) {
            xs[i] = left + i * (SLOT_SIZE + PLUS_GAP) + 1;
        }
        return xs;
    }

    /** Ingredient x of the first result slot: centered over the right scene, pushed right to clear the input row. */
    private static int resultSlotX(FluidInteractionRecipe recipe) {
        int count = Math.max(1, recipe.results().size());
        int width = count * SLOT_SIZE + (count - 1) * RESULT_GAP;
        int[] inputs = inputSlotX(recipe);
        int rowRight = inputs[inputs.length - 1] - 1 + SLOT_SIZE;
        int left = Math.max(rowRight + MIN_MARGIN, AFTER_X + SCENE_SIZE / 2 - width / 2);
        return Math.min(left, WIDTH - MIN_MARGIN - width) + 1;
    }

    private static int centered(int width) {
        return (WIDTH - width) / 2 + 1;
    }

    private static IRecipeSlotBuilder slot(IRecipeSlotBuilder slot) {
        return slot.setStandardSlotBackground().setFluidRenderer(FluidType.BUCKET_VOLUME, false, 16, 16);
    }

    /**
     * Adds each placement as a fluid or item ingredient; blocks without an item are only shown in the scene.
     * JEI only knows still fluids, so both forms of one fluid share a single cycling entry.
     */
    private static void addPlacements(IRecipeSlotBuilder slot, List<Placement> placements) {
        Set<Fluid> fluids = new LinkedHashSet<>();
        for (Placement placement : placements) {
            if (placement.isFluid()) {
                Fluid fluid = FluidInteractionRecipe.stillForm(placement.effectiveFluid());
                if (fluids.add(fluid)) {
                    slot.addFluidStack(fluid, FluidType.BUCKET_VOLUME);
                }
                continue;
            }
            ItemStack item = placement.asItem();
            if (!item.isEmpty()) {
                slot.addItemStack(item);
            }
        }
    }

    static List<Fluid> stillFluidsOf(FluidType type) {
        return BuiltInRegistries.FLUID.stream()
                .filter(fluid -> fluid != Fluids.EMPTY && fluid.getFluidType() == type && fluid.defaultFluidState().isSource())
                .toList();
    }
}
