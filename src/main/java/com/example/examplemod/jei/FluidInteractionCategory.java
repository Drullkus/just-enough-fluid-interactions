package com.example.examplemod.jei;

import java.util.List;

import com.example.examplemod.jei.probe.FluidInteractionRecipe;
import com.example.examplemod.jei.probe.Placement;
import com.example.examplemod.jei.scene.SceneCache;
import com.example.examplemod.jei.scene.SceneWidget;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
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
 * <p>Top row: source fluid, plus sign, the neighbor (and any extra required blocks), arrow, result. Below it two
 * 3D scenes show the arrangement before and after the interaction. A recipe whose interaction could not be
 * processed shows only the source fluid and an explanation.
 */
public final class FluidInteractionCategory extends AbstractRecipeCategory<FluidInteractionRecipe> {
    public static final int WIDTH = 170;
    public static final int HEIGHT = 100;

    private static final int ROW_Y = 6;
    private static final int SOURCE_X = 8;
    private static final int NEIGHBOR_X = 40;
    private static final int SLOT_STEP = 20;
    private static final int ARROW_WIDTH = 24;
    private static final int SCENE_Y = 28;
    private static final int SCENE_SIZE = 56;
    private static final int BEFORE_X = 12;
    private static final int AFTER_X = WIDTH - BEFORE_X - SCENE_SIZE;

    private final SceneCache scenes;

    public FluidInteractionCategory(IGuiHelper guiHelper, SceneCache scenes) {
        super(FluidInteractionsJeiPlugin.TYPE, Texts.title(), guiHelper.createDrawableItemLike(Items.LAVA_BUCKET), WIDTH, HEIGHT);
        this.scenes = scenes;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        IRecipeSlotBuilder source = slot(builder.addInputSlot(SOURCE_X, ROW_Y)).setSlotName("source");
        if (recipe.isFailure()) {
            stillFluidsOf(recipe.sourceType()).forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
            return;
        }
        recipe.sourceFluids().forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
        source.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.forms(recipe).withStyle(ChatFormatting.GRAY)));

        int x = NEIGHBOR_X;
        if (!recipe.neighbors().isEmpty()) {
            IRecipeSlotBuilder neighbor = slot(builder.addInputSlot(x, ROW_Y)).setSlotName("neighbor");
            addPlacements(neighbor, recipe.neighbors());
            neighbor.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(FluidInteractionRecipe.NEIGHBOR_OFFSET).withStyle(ChatFormatting.GRAY)));
            x += SLOT_STEP;
        }
        for (var condition : recipe.conditions().entrySet()) {
            BlockPos offset = condition.getKey();
            IRecipeSlotBuilder slot = slot(builder.addInputSlot(x, ROW_Y));
            addPlacements(slot, List.of(condition.getValue()));
            slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
            x += SLOT_STEP;
        }

        int resultX = resultX(recipe);
        for (var result : recipe.results().entrySet()) {
            BlockPos offset = result.getKey();
            IRecipeSlotBuilder slot = slot(builder.addOutputSlot(resultX, ROW_Y));
            addPlacements(slot, List.of(Placement.ofBlock(result.getValue())));
            if (!offset.equals(BlockPos.ZERO)) {
                slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
            }
            resultX += SLOT_STEP;
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        if (recipe.isFailure()) {
            builder.addText(recipe.failure(), WIDTH - 16, HEIGHT - SCENE_Y - 8)
                    .setPosition(8, SCENE_Y)
                    .setTextAlignment(HorizontalAlignment.CENTER)
                    .setTextAlignment(VerticalAlignment.CENTER);
            return;
        }

        builder.addRecipePlusSignWidget().setPosition(SOURCE_X + 16, ROW_Y, NEIGHBOR_X - SOURCE_X - 16, 16, HorizontalAlignment.CENTER, VerticalAlignment.CENTER);
        int arrowX = resultX(recipe) - ARROW_WIDTH - 4;
        builder.addRecipeArrowWidget().setPosition(arrowX, ROW_Y, ARROW_WIDTH, 16, HorizontalAlignment.CENTER, VerticalAlignment.CENTER);

        builder.addWidget(new SceneWidget(scenes, recipe, false, BEFORE_X, SCENE_Y, SCENE_SIZE, SCENE_SIZE));
        builder.addRecipeArrowWidget().setPosition(BEFORE_X + SCENE_SIZE, SCENE_Y, AFTER_X - BEFORE_X - SCENE_SIZE, SCENE_SIZE, HorizontalAlignment.CENTER, VerticalAlignment.CENTER);
        builder.addWidget(new SceneWidget(scenes, recipe, true, AFTER_X, SCENE_Y, SCENE_SIZE, SCENE_SIZE));

        int labelY = SCENE_Y + SCENE_SIZE + 3;
        builder.addText(Texts.key("before"), SCENE_SIZE, 10).setPosition(BEFORE_X, labelY).setTextAlignment(HorizontalAlignment.CENTER);
        builder.addText(Texts.key("after"), SCENE_SIZE, 10).setPosition(AFTER_X, labelY).setTextAlignment(HorizontalAlignment.CENTER);
    }

    @Override
    public ResourceLocation getRegistryName(FluidInteractionRecipe recipe) {
        return recipe.id();
    }

    /** X of the first result slot: after the neighbor and condition slots, leaving room for the arrow. */
    private static int resultX(FluidInteractionRecipe recipe) {
        int inputSlots = (recipe.neighbors().isEmpty() ? 0 : 1) + recipe.conditions().size();
        return NEIGHBOR_X + inputSlots * SLOT_STEP + ARROW_WIDTH + 8;
    }

    private static IRecipeSlotBuilder slot(IRecipeSlotBuilder slot) {
        return slot.setStandardSlotBackground().setFluidRenderer(FluidType.BUCKET_VOLUME, false, 16, 16);
    }

    /** Adds each placement as a fluid or item ingredient; blocks without an item are only shown in the scene. */
    private static void addPlacements(IRecipeSlotBuilder slot, List<Placement> placements) {
        for (Placement placement : placements) {
            if (placement.isFluid()) {
                slot.addFluidStack(placement.effectiveFluid().getType(), FluidType.BUCKET_VOLUME);
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
