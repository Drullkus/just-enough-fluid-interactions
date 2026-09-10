package us.drullk.jefi.jei;

import java.util.List;

import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Text wrapped to a fixed rectangle and centered in it, matching what JEI's own text widget draws. */
final class TextDrawable implements IDrawable {
    private static final int COLOR = 0xFF000000;
    private static final int LINE_SPACING = 2;

    private final List<FormattedCharSequence> lines;
    private final int width;
    private final int height;

    TextDrawable(Component text, int width, int height) {
        this.lines = Minecraft.getInstance().font.split(text, width);
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
        Font font = Minecraft.getInstance().font;
        int lineHeight = font.lineHeight + LINE_SPACING;
        int y = yOffset + (height - lines.size() * lineHeight) / 2;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, xOffset + (width - font.width(line)) / 2, y, COLOR, false);
            y += lineHeight;
        }
    }
}
