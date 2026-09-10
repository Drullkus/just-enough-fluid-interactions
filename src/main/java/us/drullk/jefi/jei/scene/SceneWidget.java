package us.drullk.jefi.jei.scene;


import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import com.mojang.blaze3d.platform.InputConstants;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * A JEI recipe widget showing one recipe's arrangement before or after the interaction as a 3D scene.
 *
 * <p>The scene follows whatever alternatives the input slots are currently cycling through, and can be orbited
 * by dragging with the left mouse button.
 */
public final class SceneWidget implements IRecipeWidget, IJeiInputHandler {
    private final SceneView view;
    private final FluidInteractionRecipe recipe;
    private final boolean after;
    private final SceneRotation rotation;
    private final @Nullable IRecipeSlotView sourceSlot;
    private final @Nullable IRecipeSlotView neighborSlot;
    private final ScreenPosition position;
    private final ScreenRectangle area;
    private final int width;
    private final int height;

    public SceneWidget(SceneCache cache, FluidInteractionRecipe recipe, boolean after, SceneRotation rotation,
                       @Nullable IRecipeSlotView sourceSlot, @Nullable IRecipeSlotView neighborSlot,
                       int x, int y, int width, int height) {
        this.view = new SceneView(cache, recipe);
        this.recipe = recipe;
        this.after = after;
        this.rotation = rotation;
        this.sourceSlot = sourceSlot;
        this.neighborSlot = neighborSlot;
        this.position = new ScreenPosition(x, y);
        this.area = new ScreenRectangle(x, y, width, height);
        this.width = width;
        this.height = height;
    }

    @Override
    public ScreenPosition getPosition() {
        return position;
    }

    @Override
    public ScreenRectangle getArea() {
        return area;
    }

    @Override
    public void drawWidget(GuiGraphics graphics, double mouseX, double mouseY) {
        rotation.settle();
        SceneCursor.request(this, contains(mouseX, mouseY) || rotation.dragging());
        view.draw(graphics, variant(), width, height, rotation.yaw(), rotation.pitch(), rotation.version(), !rotation.dragging());
    }

    @Override
    public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
        // Claiming the press keeps JEI from acting on a click that only started a drag.
        return isLeftMouse(input.getKey());
    }

    @Override
    public boolean handleMouseDragged(double mouseX, double mouseY, InputConstants.Key mouseKey, double dragX, double dragY) {
        if (!isLeftMouse(mouseKey)) {
            return false;
        }
        rotation.drag(dragX, dragY);
        return true;
    }

    private static boolean isLeftMouse(InputConstants.Key key) {
        return key.getType() == InputConstants.Type.MOUSE && key.getValue() == InputConstants.MOUSE_BUTTON_LEFT;
    }

    /**
     * Only the drag hint: the placements are the category's tooltip, which every viewer asks for, while a widget's
     * tooltip is asked for by the layouts that also route the drag.
     */
    @Override
    public void getTooltip(ITooltipBuilder tooltip, double mouseX, double mouseY) {
        if (contains(mouseX, mouseY)) {
            tooltip.add(Texts.drag().withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= 0 && mouseY >= 0 && mouseX < width && mouseY < height;
    }

    private SceneVariant variant() {
        return SceneView.variant(recipe, sourceSlot, neighborSlot, after);
    }
}
