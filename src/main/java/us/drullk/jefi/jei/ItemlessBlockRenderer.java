package us.drullk.jefi.jei;

import java.util.ArrayList;
import java.util.List;

import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import mezz.jei.api.ingredients.IIngredientRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Draws the block model with the face shade of a level. */
final class ItemlessBlockRenderer implements IIngredientRenderer<ItemlessBlock> {
    private static final int SIZE = 16;
    private static final long MODEL_SEED = 42L;
    private static final int[] FULL_LIGHT = {LightTexture.FULL_BRIGHT, LightTexture.FULL_BRIGHT, LightTexture.FULL_BRIGHT, LightTexture.FULL_BRIGHT};

    @Override
    public void render(GuiGraphics graphics, ItemlessBlock ingredient) {
        BlockState state = ingredient.state();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        if (state.getRenderShape() != RenderShape.MODEL) {
            graphics.blit(0, 0, 0, SIZE, SIZE, dispatcher.getBlockModelShaper().getParticleIcon(state));
            return;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(SIZE / 2.0F, SIZE / 2.0F, 150.0F);
        pose.scale(SIZE, -SIZE, SIZE);
        pose.mulPose(Axis.XP.rotationDegrees(30.0F));
        pose.mulPose(Axis.YP.rotationDegrees(225.0F));
        pose.scale(0.625F, 0.625F, 0.625F);
        pose.translate(-0.5F, -0.5F, -0.5F);
        renderShadedModel(graphics, dispatcher, state, pose);
        graphics.flush();
        pose.popPose();
    }

    /** The block's own render types add no light of their own. */
    private static void renderShadedModel(GuiGraphics graphics, BlockRenderDispatcher dispatcher, BlockState state, PoseStack pose) {
        BakedModel model = dispatcher.getBlockModel(state);
        BlockColors blockColors = Minecraft.getInstance().getBlockColors();
        PoseStack.Pose last = pose.last();
        for (RenderType renderType : model.getRenderTypes(state, RandomSource.create(MODEL_SEED), ModelData.EMPTY)) {
            VertexConsumer consumer = graphics.bufferSource().getBuffer(renderType);
            for (Direction direction : Direction.values()) {
                RandomSource random = RandomSource.create(MODEL_SEED);
                renderQuads(consumer, last, blockColors, state, model.getQuads(state, direction, random, ModelData.EMPTY, renderType));
            }
            RandomSource random = RandomSource.create(MODEL_SEED);
            renderQuads(consumer, last, blockColors, state, model.getQuads(state, null, random, ModelData.EMPTY, renderType));
        }
    }

    private static void renderQuads(VertexConsumer consumer, PoseStack.Pose pose, BlockColors blockColors, BlockState state, List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            float brightness = SandboxLevel.directionalShade(quad.getDirection(), quad.isShade());
            float red = 1.0F;
            float green = 1.0F;
            float blue = 1.0F;
            if (quad.isTinted()) {
                int color = blockColors.getColor(state, null, null, quad.getTintIndex());
                red = (float) (color >> 16 & 0xFF) / 255.0F;
                green = (float) (color >> 8 & 0xFF) / 255.0F;
                blue = (float) (color & 0xFF) / 255.0F;
            }
            consumer.putBulkData(pose, quad, new float[] {brightness, brightness, brightness, brightness}, red, green, blue, 1.0F,
                    FULL_LIGHT, OverlayTexture.NO_OVERLAY, true);
        }
    }

    @SuppressWarnings("removal")
    @Override
    public List<Component> getTooltip(ItemlessBlock ingredient, TooltipFlag flag) {
        List<Component> tooltip = new ArrayList<>(2);
        tooltip.add(ingredient.name());
        if (flag.isAdvanced()) {
            tooltip.add(Component.literal(ingredient.key().toString()).withStyle(ChatFormatting.DARK_GRAY));
        }
        return tooltip;
    }
}
