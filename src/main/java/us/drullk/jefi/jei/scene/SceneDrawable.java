package us.drullk.jefi.jei.scene;

import java.util.function.Supplier;

import us.drullk.jefi.jei.probe.FluidInteractionRecipe;

import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One recipe phase drawn as a scene that nothing but the category can turn.
 *
 * <p>A drawable is positioned by whoever draws it and is never asked for a tooltip or offered input, so this is
 * what a recipe layout gets where {@link SceneWidget} cannot work; the variant comes from whatever slot view the
 * category was last drawn with, so the picture and the category's tooltip agree, and the angles come from the
 * rotation the category steps on a click.
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
