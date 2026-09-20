package us.drullk.jefi.jei.scene;

import java.util.function.Supplier;

import us.drullk.jefi.jei.probe.FluidInteractionRecipe;

import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One recipe phase drawn as a scene that nothing but the category can turn.
 *
 * <p>A drawable is positioned by whoever draws it. JEI never asks a drawable for a tooltip or offers it input.
 * So a recipe layout uses a drawable where {@link SceneWidget} cannot work. The variant comes from whatever
 * slot view the category was last drawn with. This keeps the picture and the category's tooltip in agreement.
 * The angles come from the rotation that the category steps on a click.
 */
public final class SceneDrawable implements IDrawable {
    private final SceneView view;
    private final Supplier<SceneVariant> variant;
    private final SceneRotation rotation;
    private final int width;
    private final int height;

    public SceneDrawable(SceneCache cache, FluidInteractionRecipe recipe, Supplier<SceneVariant> variant, SceneRotation rotation, int width, int height) {
        this.view = new SceneView(cache, recipe);
        this.variant = variant;
        this.rotation = rotation;
        this.width = width;
        this.height = height;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
        graphics.pose().pushPose();
        graphics.pose().translate(xOffset, yOffset, 0.0f);
        view.draw(graphics, variant.get(), width, height, rotation.yaw(), rotation.pitch(), rotation.version(), true);
        graphics.pose().popPose();
    }
}
