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
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

/**
 * A JEI recipe widget showing one recipe's arrangement before or after the interaction as a 3D scene.
 *
 * <p>The scene follows whatever alternatives the input slots are currently cycling through, and can be orbited
 * by dragging with the left mouse button.
 */
public final class SceneWidget implements IRecipeWidget, IJeiInputHandler {
    private static final int BACKDROP = 0x30000000;

    private final SceneCache cache;
    private final FluidInteractionRecipe recipe;
    private final boolean after;
    private final SceneRotation rotation;
    private final @Nullable IRecipeSlotView sourceSlot;
    private final @Nullable IRecipeSlotView neighborSlot;
    private final ScreenPosition position;
    private final ScreenRectangle area;
    private final int width;
    private final int height;

    private int sortedVersion = -1;
    private @Nullable SceneVariant sortedVariant;

    public SceneWidget(SceneCache cache, FluidInteractionRecipe recipe, boolean after, SceneRotation rotation,
                       @Nullable IRecipeSlotView sourceSlot, @Nullable IRecipeSlotView neighborSlot,
                       int x, int y, int width, int height) {
        this.cache = cache;
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

        graphics.fill(0, 0, width, height, BACKDROP);
        SceneVariant variant = variant();
        SceneCache.BakedScene scene = cache.get(recipe, variant);
        if (scene == null) {
            var font = Minecraft.getInstance().font;
            graphics.drawCenteredString(font, "?", width / 2, (height - font.lineHeight) / 2, 0xFFFFFFFF);
            return;
        }
        if (!rotation.dragging() && (rotation.version() != sortedVersion || !variant.equals(sortedVariant))) {
            SceneBakery.resort(scene.level(), SceneRenderer.sortCamera(scene.center(), rotation.yaw(), rotation.pitch()));
            sortedVersion = rotation.version();
            sortedVariant = variant;
        }
        SceneRenderer.draw(graphics, scene, width, height, rotation.yaw(), rotation.pitch());
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

    @Override
    public void getTooltip(ITooltipBuilder tooltip, double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return;
        }
        tooltip.add(Texts.key(after ? "after" : "before"));
        SceneArrangement.of(recipe, variant()).forEach((offset, placement) -> tooltip.add(
                Component.literal("  ")
                        .append(placement.describe())
                        .append(" ")
                        .append(Texts.offset(offset))
                        .withStyle(ChatFormatting.GRAY)));
        tooltip.add(Texts.drag().withStyle(ChatFormatting.DARK_GRAY));
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= 0 && mouseY >= 0 && mouseX < width && mouseY < height;
    }

    private SceneVariant variant() {
        return new SceneVariant(
                SceneArrangement.sourceIndex(recipe, displayed(sourceSlot)),
                SceneArrangement.neighborIndex(recipe, displayed(neighborSlot)),
                after);
    }

    private static @Nullable ITypedIngredient<?> displayed(@Nullable IRecipeSlotView slot) {
        return slot == null ? null : slot.getDisplayedIngredient().orElse(null);
    }
}
