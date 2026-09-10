package us.drullk.jefi.jei;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;

import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.world.level.material.Fluid;

/**
 * A yellow "?" drawn over a slot's top-right corner while the fluid it currently displays has an inert other
 * form, so the observational tooltip line ({@link Texts#inertSource} / {@link Texts#inertNeighbor}) is visible
 * without hovering.
 *
 * <p>{@link mezz.jei.api.gui.builder.IRecipeSlotBuilder#setOverlay} is static per slot and cannot follow a slot's
 * cycling, so this reads the slot's currently displayed ingredient every frame the way {@code SceneWidget} does.
 */
public final class InertFormIndicator implements IRecipeWidget {
    private static final String GLYPH = "!";

    private final IRecipeSlotView slot;
    private final FluidInteractionRecipe recipe;
    private final boolean neighbor;
    private final ScreenPosition position;

    private InertFormIndicator(IRecipeSlotView slot, FluidInteractionRecipe recipe, boolean neighbor, int cornerX, int cornerY) {
        this.slot = slot;
        this.recipe = recipe;
        this.neighbor = neighbor;
        this.position = new ScreenPosition(cornerX, cornerY);
    }

    /**
     * Null when none of the source slot's fluids ever have an inert source form recorded, so no per-frame check
     * is worth doing.
     */
    static @Nullable InertFormIndicator forSource(IRecipeSlotView slot, FluidInteractionRecipe recipe, int cornerX, int cornerY) {
        return sourceMayShow(recipe) ? new InertFormIndicator(slot, recipe, false, cornerX, cornerY) : null;
    }

    /**
     * Null when none of the neighbor slot's fluids ever have an inert alternative recorded, so no per-frame check
     * is worth doing.
     */
    static @Nullable InertFormIndicator forNeighbor(IRecipeSlotView slot, FluidInteractionRecipe recipe, int cornerX, int cornerY) {
        return neighborMayShow(recipe) ? new InertFormIndicator(slot, recipe, true, cornerX, cornerY) : null;
    }

    /** Whether any of the recipe's source fluids ever has an inert source form, i.e. whether a source widget could ever draw. */
    public static boolean sourceMayShow(FluidInteractionRecipe recipe) {
        return recipe.sourceFluids().stream().anyMatch(fluid -> recipe.inert().sourceOf(fluid) != null);
    }

    /** Whether any of the recipe's neighbor fluids ever has an inert alternative, i.e. whether a neighbor widget could ever draw. */
    public static boolean neighborMayShow(FluidInteractionRecipe recipe) {
        return recipe.neighbors().stream()
                .filter(Placement::isFluid)
                .map(placement -> FluidInteractionRecipe.stillForm(placement.effectiveFluid()))
                .anyMatch(fluid -> recipe.inert().neighborOf(fluid) != null);
    }

    @Override
    public ScreenPosition getPosition() {
        return position;
    }

    @Override
    public void drawWidget(GuiGraphics graphics, double mouseX, double mouseY) {
        Fluid fluid = FluidInteractionCategory.displayedFluid(slot);
        if (fluid == null || !qualifies(fluid)) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int width = font.width(GLYPH);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
        graphics.drawString(font, GLYPH, width, 0, ChatFormatting.YELLOW.getColor() | 0xFF000000, true);
        graphics.pose().popPose();
    }

    private boolean qualifies(Fluid fluid) {
        return neighbor ? recipe.inert().neighborOf(fluid) != null : recipe.inert().sourceOf(fluid) != null;
    }
}
