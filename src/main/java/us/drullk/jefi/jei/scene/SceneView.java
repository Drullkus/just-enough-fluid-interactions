package us.drullk.jefi.jei.scene;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.jei.Texts;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Draws one phase of a recipe into a widget-local rectangle, re-sorting the baked translucency whenever the
 * orbit angles or the shown alternatives changed since the last draw.
 *
 * <p>Runs on the render thread, like everything that touches a baked scene.
 */
public final class SceneView {
    private static final int BACKDROP = 0x30000000;

    /** The alternatives a layout's named slots currently show, or the first ones where a slot is missing. */
    public static SceneVariant variant(FluidInteractionRecipe recipe, @Nullable IRecipeSlotsView slots, boolean after) {
        IRecipeSlotView source = slots == null ? null : slots.findSlotByName("source").orElse(null);
        IRecipeSlotView neighbor = slots == null ? null : slots.findSlotByName("neighbor").orElse(null);
        return variant(recipe, source, neighbor, after);
    }

    public static SceneVariant variant(FluidInteractionRecipe recipe, @Nullable IRecipeSlotView source, @Nullable IRecipeSlotView neighbor, boolean after) {
        return new SceneVariant(
                SceneArrangement.sourceIndex(recipe, displayed(source)),
                SceneArrangement.neighborIndex(recipe, displayed(neighbor)),
                after);
    }

    private static @Nullable ITypedIngredient<?> displayed(@Nullable IRecipeSlotView slot) {
        return slot == null ? null : slot.getDisplayedIngredient().orElse(null);
    }

    /**
     * The placements of one phase. A line is white when it differs from the same offset in the recipe's other
     * phase, gray when that phase places the same thing there, so a long list shows what the interaction actually
     * changed at a glance.
     */
    public static void tooltip(ITooltipBuilder tooltip, FluidInteractionRecipe recipe, SceneVariant variant) {
        tooltip.add(Texts.key(variant.after() ? "after" : "before").withStyle(ChatFormatting.GRAY));
        Map<BlockPos, Placement> otherPhase = SceneArrangement.of(recipe, new SceneVariant(variant.source(), variant.neighbor(), !variant.after()));
        SceneArrangement.of(recipe, variant).forEach((offset, placement) -> {
            boolean unchanged = placement.equals(otherPhase.get(offset));
            tooltip.add(
                    Component.literal("  ")
                            .append(placement.describe())
                            .append(" ")
                            .append(Texts.offset(offset))
                            .withStyle(unchanged ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        });
    }

    private final SceneCache cache;
    private final FluidInteractionRecipe recipe;

    private int sortedVersion = -1;
    private @Nullable SceneVariant sortedVariant;

    SceneView(SceneCache cache, FluidInteractionRecipe recipe) {
        this.cache = cache;
        this.recipe = recipe;
    }

    /**
     * @param version increments whenever the angles change, so a scene can tell whether its sort is still current
     * @param settled whether the angles have stopped moving; re-sorting mid-drag is not worth its cost
     */
    void draw(GuiGraphics graphics, SceneVariant variant, int width, int height, float yaw, float pitch, int version, boolean settled) {
        graphics.fill(0, 0, width, height, BACKDROP);
        SceneCache.BakedScene scene = cache.get(recipe, variant);
        if (scene == null) {
            var font = Minecraft.getInstance().font;
            graphics.drawCenteredString(font, "?", width / 2, (height - font.lineHeight) / 2, 0xFFFFFFFF);
            return;
        }
        if (settled && (version != sortedVersion || !variant.equals(sortedVariant))) {
            SceneBakery.resort(scene.level(), SceneRenderer.sortCamera(scene.center(), yaw, pitch));
            sortedVersion = version;
            sortedVariant = variant;
        }
        SceneRenderer.draw(graphics, scene, width, height, yaw, pitch);
    }
}
