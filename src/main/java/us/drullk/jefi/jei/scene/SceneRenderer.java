package us.drullk.jefi.jei.scene;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.math.Axis;

import dev.compactmods.gander.render.geometry.BakedLevelSection;
import dev.compactmods.gander.render.toolkit.BlockRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Draws a baked scene into a rectangle of the current GUI through an orthographic camera orbiting the source
 * position.
 *
 * <p>This deliberately uses Gander's bakery output and section renderer directly instead of its screen pipeline:
 * that pipeline allocates a window-sized render target plus an eight-layer translucency chain per widget, which
 * is far too heavy for a JEI page that shows several recipes at once. Here the geometry is drawn straight into
 * the main framebuffer through a viewport, with depth cleared only inside the widget's scissor rectangle.
 */
public final class SceneRenderer {
    /** Azimuth that puts the camera on the neighbor's side of the source, off-axis enough to see the whole row. */
    public static final float DEFAULT_YAW = 225.0f;
    public static final float DEFAULT_PITCH = 30.0f;

    private static final float VIEW_DISTANCE = 50.0f;
    private static final float NEAR = 1.0f;
    private static final float FAR = 100.0f;
    /** Translucency is sorted from far away so the ordering matches a parallel projection. */
    private static final float SORT_DISTANCE = 1000.0f;

    private static final List<RenderType> OPAQUE_LAYERS = List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());

    private SceneRenderer() {
    }

    /**
     * <p>The widget leaves no depth behind: its depth values are cleared again after it draws, so anything JEI
     * draws later at this position wins the depth test regardless of how close the scene geometry came to camera.
     *
     * @param graphics the GUI graphics, whose pose must already be translated to the widget's top-left corner
     * @param width    widget width in GUI units
     * @param height   widget height in GUI units
     */
    public static void draw(GuiGraphics graphics, SceneCache.BakedScene scene, int width, int height, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        Matrix4f pose = graphics.pose().last().pose();
        double scale = window.getGuiScale();

        // The pose is translated to the widget's top-left corner; GuiGraphics' scissor wants absolute GUI coordinates.
        int guiX = Math.round(pose.m30());
        int guiY = Math.round(pose.m31());
        int viewportX = (int) Math.floor(guiX * scale);
        int viewportWidth = (int) Math.round(width * scale);
        int viewportHeight = (int) Math.round(height * scale);
        int viewportY = window.getHeight() - (int) Math.floor(guiY * scale) - viewportHeight;

        graphics.flush();
        graphics.enableScissor(guiX, guiY, guiX + width, guiY + height);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        FogRenderer.setupNoFog();

        float halfHeight = scene.radius();
        float halfWidth = halfHeight * width / height;
        Matrix4f projection = new Matrix4f().setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, NEAR, FAR);
        PoseStack view = new PoseStack();
        view.translate(0.0f, 0.0f, -VIEW_DISTANCE);
        view.mulPose(Axis.XP.rotationDegrees(pitch));
        view.mulPose(Axis.YP.rotationDegrees(yaw));

        // The chunk shader adds (renderOrigin - cameraPosition) to every vertex, which here centers the scene.
        Vector3f cameraPosition = new Vector3f();
        Vector3f renderOrigin = new Vector3f(scene.center()).negate();

        ProfilerFiller profiler = mc.getProfiler();
        for (BakedLevelSection section : scene.level().sections().values()) {
            for (RenderType layer : OPAQUE_LAYERS) {
                drawLayer(profiler, section.blockBuffers(), layer, view, cameraPosition, renderOrigin, projection);
                drawLayer(profiler, section.fluidBuffers(), layer, view, cameraPosition, renderOrigin, projection);
            }
        }
        for (BakedLevelSection section : scene.level().sections().values()) {
            drawLayer(profiler, section.fluidBuffers(), RenderType.translucent(), view, cameraPosition, renderOrigin, projection);
            drawLayer(profiler, section.blockBuffers(), RenderType.translucent(), view, cameraPosition, renderOrigin, projection);
        }

        RenderSystem.viewport(0, 0, window.getWidth(), window.getHeight());
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        graphics.disableScissor();
    }

    /** Where to sort translucent quads from, in level coordinates, for the given orbit angles. */
    public static Vector3f sortCamera(Vector3f center, float yaw, float pitch) {
        float yawRadians = yaw * Mth.DEG_TO_RAD;
        float pitchRadians = pitch * Mth.DEG_TO_RAD;
        float horizontal = Mth.cos(pitchRadians);
        return new Vector3f(
                -horizontal * Mth.sin(yawRadians),
                Mth.sin(pitchRadians),
                horizontal * Mth.cos(yawRadians))
                .mul(SORT_DISTANCE)
                .add(center);
    }

    private static void drawLayer(ProfilerFiller profiler, Map<RenderType, VertexBuffer> buffers, RenderType layer, PoseStack view,
                                  Vector3f cameraPosition, Vector3f renderOrigin, Matrix4f projection) {
        if (!buffers.containsKey(layer)) {
            return;
        }
        // Gander's renderer pops and pushes a profiler section of its own, so give it one to consume.
        profiler.push("fluid_interaction_scene");
        BlockRenderer.renderSectionLayer(buffers, Function.identity(), layer, view, cameraPosition, renderOrigin, projection);
    }
}
