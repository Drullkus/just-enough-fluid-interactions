package us.drullk.jefi.jei.scene;

import java.util.function.Supplier;

import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import com.mojang.blaze3d.platform.InputConstants;

import mezz.jei.api.gui.builder.ITooltipBuilder;
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
 * <p>The scene shows the fluids and blocks that the slots show now. A drag with the left mouse button turns
 * the scene. A click without a drag turns it by one step, for a viewer that sends clicks and not drags.
 */
public final class SceneWidget implements IRecipeWidget, IJeiInputHandler {
    private final SceneView view;
    private final SceneRotation rotation;
    private final Supplier<SceneVariant> variant;
    private final ScreenPosition position;
    private final ScreenRectangle area;
    private final int width;
    private final int height;

    public SceneWidget(SceneCache cache, FluidInteractionRecipe recipe, SceneRotation rotation,
                       Supplier<SceneVariant> variant, int x, int y, int width, int height) {
        this.view = new SceneView(cache, recipe);
        this.rotation = rotation;
        this.variant = variant;
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
        view.draw(graphics, variant.get(), width, height, rotation.yaw(), rotation.pitch(), rotation.version(), !rotation.dragging());
    }

    /**
     * Claiming the press keeps JEI from acting on a click that only started a drag. Claiming the press is also
     * what makes the matching release reach this handler. The release turns the scene, unless a drag already
     * turned it.
     */
    @Override
    public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
        float step = SceneRotation.stepOf(input.getKey());
        if (step == 0.0f) {
            return false;
        }
        if (input.isSimulate()) {
            rotation.press();
            return true;
        }
        if (!rotation.dragged()) {
            rotation.step(step);
        }
        return true;
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
     * Only the rotation hint appears here. The placements are the category's tooltip, and every viewer asks
     * for that. Only the layouts that route input to widgets ask for a widget's own tooltip.
     */
    @Override
    public void getTooltip(ITooltipBuilder tooltip, double mouseX, double mouseY) {
        if (contains(mouseX, mouseY)) {
            tooltip.add(Texts.rotate().withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= 0 && mouseY >= 0 && mouseX < width && mouseY < height;
    }
}
