package com.example.examplemod.jei.scene;

import java.util.Map;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;

import dev.compactmods.gander.render.geometry.BakedLevel;
import dev.compactmods.gander.render.geometry.BakedLevelSection;
import dev.compactmods.gander.render.vertex.FluidVertexConsumer;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Bakes a small region of a level into vertex buffers, producing Gander's {@link BakedLevel} so the rest of
 * Gander's toolkit can draw it.
 *
 * <p>This stands in for Gander's own {@code LevelBakery} for two reasons. First, that bakery takes the fluid
 * vertex buffers from the <em>block</em> buffer pack, so a fluid and a block on the same render layer (lava next
 * to stone, for instance) interleave in one byte stream and only the first block's worth of vertices survives.
 * Second, it re-bakes the whole region once per level section, which doubles translucent geometry. Here every
 * position is visited once, block and fluid geometry get their own buffers, and the CPU-side buffers are freed as
 * soon as the meshes are uploaded. The camera is fixed, so translucency is sorted once at upload time and the
 * baked section carries no builders for re-sorting.
 */
public final class SceneBakery {
    private static final int INITIAL_BUFFER_BYTES = 64 * 1024;

    private SceneBakery() {
    }

    /**
     * @param level          the level to read blocks and fluids from
     * @param bounds         inclusive block bounds of the region to bake
     * @param cameraPosition camera position in level coordinates, used to sort translucent quads
     */
    public static BakedLevel bake(Level level, AABB bounds, Vector3f cameraPosition) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.createNewThreadLocalInstance();
        PoseStack pose = new PoseStack();

        try (Builders blocks = new Builders(); Builders fluids = new Builders()) {
            ModelBlockRenderer.enableCaching();
            try {
                for (BlockPos pos : BlockPos.betweenClosed(
                        Mth.floor(bounds.minX), Mth.floor(bounds.minY), Mth.floor(bounds.minZ),
                        Mth.floor(bounds.maxX), Mth.floor(bounds.maxY), Mth.floor(bounds.maxZ))) {
                    bakePosition(level, pos.immutable(), dispatcher, modelRenderer, random, pose, blocks, fluids);
                }
            } finally {
                ModelBlockRenderer.clearCache();
            }

            VertexSorting sorting = VertexSorting.byDistance(cameraPosition.x, cameraPosition.y, cameraPosition.z);
            Map<RenderType, VertexBuffer> blockBuffers = blocks.upload(sorting);
            Map<RenderType, VertexBuffer> fluidBuffers = fluids.upload(sorting);

            BakedLevelSection section = new BakedLevelSection(null, null, blockBuffers, fluidBuffers, Map.of(), Map.of(), bounds);
            SectionPos sectionPos = SectionPos.of(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ));
            return new BakedLevel(level, bounds, Map.of(sectionPos, section));
        }
    }

    private static void bakePosition(Level level, BlockPos pos, BlockRenderDispatcher dispatcher, ModelBlockRenderer modelRenderer,
                                     RandomSource random, PoseStack pose, Builders blocks, Builders fluids) {
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = level.getFluidState(pos);
        pose.pushPose();
        pose.translate(pos.getX(), pos.getY(), pos.getZ());
        try {
            if (state.getRenderShape() == RenderShape.MODEL) {
                BakedModel model = dispatcher.getBlockModel(state);
                ModelData modelData = model.getModelData(level, pos, state, level.getModelData(pos));
                long seed = state.getSeed(pos);
                random.setSeed(seed);
                for (RenderType type : model.getRenderTypes(state, random, modelData)) {
                    modelRenderer.tesselateBlock(level, model, state, pos, pose, blocks.builder(type), true, random, seed,
                            OverlayTexture.NO_OVERLAY, modelData, type);
                }
            }
            if (!fluidState.isEmpty()) {
                RenderType type = ItemBlockRenderTypes.getRenderLayer(fluidState);
                dispatcher.getLiquidBlockRenderer().tesselate(level, pos, new FluidVertexConsumer(fluids.builder(type), pose, pos), state, fluidState);
            }
        } finally {
            pose.popPose();
        }
    }

    /** One byte buffer and vertex builder per render type, released on close. */
    private static final class Builders implements AutoCloseable {
        private final Map<RenderType, ByteBufferBuilder> memory = new Reference2ObjectArrayMap<>();
        private final Map<RenderType, BufferBuilder> builders = new Reference2ObjectArrayMap<>();

        BufferBuilder builder(RenderType type) {
            return builders.computeIfAbsent(type, t -> {
                ByteBufferBuilder bytes = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
                memory.put(t, bytes);
                return new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            });
        }

        Map<RenderType, VertexBuffer> upload(VertexSorting sorting) {
            Map<RenderType, VertexBuffer> uploaded = new Reference2ObjectArrayMap<>();
            builders.forEach((type, builder) -> {
                MeshData mesh = builder.build();
                if (mesh == null) {
                    return;
                }
                // The sorted index buffer lives in the scratch builder's memory until the upload copies it.
                try (ByteBufferBuilder scratch = new ByteBufferBuilder(INITIAL_BUFFER_BYTES)) {
                    if (type.sortOnUpload()) {
                        mesh.sortQuads(scratch, sorting);
                    }
                    VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    buffer.bind();
                    buffer.upload(mesh);
                    VertexBuffer.unbind();
                    uploaded.put(type, buffer);
                }
            });
            return uploaded;
        }

        @Override
        public void close() {
            memory.values().forEach(ByteBufferBuilder::close);
            memory.clear();
            builders.clear();
        }
    }
}
