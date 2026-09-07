package com.example.examplemod.jei.scene;

import com.example.examplemod.jei.Texts;
import com.example.examplemod.jei.probe.FluidInteractionRecipe;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.network.chat.Component;

/** A JEI recipe widget showing one recipe's arrangement before or after the interaction as a 3D scene. */
public final class SceneWidget implements IRecipeWidget {
    private static final int BACKDROP = 0x30000000;

    private final SceneCache cache;
    private final FluidInteractionRecipe recipe;
    private final boolean after;
    private final ScreenPosition position;
    private final int width;
    private final int height;

    public SceneWidget(SceneCache cache, FluidInteractionRecipe recipe, boolean after, int x, int y, int width, int height) {
        this.cache = cache;
        this.recipe = recipe;
        this.after = after;
        this.position = new ScreenPosition(x, y);
        this.width = width;
        this.height = height;
    }

    @Override
    public ScreenPosition getPosition() {
        return position;
    }

    @Override
    public void drawWidget(GuiGraphics graphics, double mouseX, double mouseY) {
        graphics.fill(0, 0, width, height, BACKDROP);
        SceneCache.BakedScene scene = cache.get(recipe, after);
        if (scene == null) {
            var font = Minecraft.getInstance().font;
            graphics.drawCenteredString(font, "?", width / 2, (height - font.lineHeight) / 2, 0xFFFFFFFF);
            return;
        }
        SceneRenderer.draw(graphics, scene, width, height);
    }

    @Override
    public void getTooltip(ITooltipBuilder tooltip, double mouseX, double mouseY) {
        if (mouseX < 0 || mouseY < 0 || mouseX >= width || mouseY >= height) {
            return;
        }
        tooltip.add(Texts.key(after ? "after" : "before"));
        recipe.scene(after).forEach((offset, placement) -> tooltip.add(
                Component.literal("  ")
                        .append(placement.describe())
                        .append(" ")
                        .append(Texts.offset(offset))
                        .withStyle(ChatFormatting.GRAY)));
    }
}
