package us.drullk.jefi.jei.scene;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.InteractionProber;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.sandbox.SandboxLevel;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;

import dev.compactmods.gander.render.geometry.BakedLevel;
import dev.compactmods.gander.render.geometry.BakedLevelSection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.phys.AABB;

/**
 * Lazily bakes the scenes of recipes into GPU buffers with Gander's bakery, and frees them again when JEI's
 * runtime goes away. Every combination of cycling alternatives and phase is baked separately, and all of a
 * recipe's bakes are released together.
 *
 * <p>Everything here runs on the render thread: JEI draws recipes there, and vertex buffers must be created and
 * destroyed there. One sandbox level is reused for every bake; only the baked buffers are kept per recipe.
 */
public final class SceneCache {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Extra room around the scene's bounding sphere so nothing clips the widget edge while it is rotated. */
    private static final float MARGIN = 0.25f;

    public record BakedScene(BakedLevel level, Vector3f center, float radius, Map<BlockPos, Placement> placements) {
    }

    private final Map<FluidInteractionRecipe, Map<SceneVariant, Optional<BakedScene>>> scenes = new IdentityHashMap<>();
    private @Nullable SandboxLevel level;

    /** Returns the baked scene, baking it on first use, or {@code null} when it could not be baked. */
    public @Nullable BakedScene get(FluidInteractionRecipe recipe, SceneVariant variant) {
        return scenes.computeIfAbsent(recipe, r -> new HashMap<>())
                .computeIfAbsent(variant, v -> bake(recipe, v))
                .orElse(null);
    }

    /** Releases every vertex buffer. Must run on the render thread. */
    public void clear() {
        scenes.values().forEach(variants -> variants.values().forEach(scene -> scene.ifPresent(SceneCache::release)));
        scenes.clear();
        level = null;
        SceneCursor.release();
    }

    private Optional<BakedScene> bake(FluidInteractionRecipe recipe, SceneVariant variant) {
        try {
            RegistryAccess access = registryAccess();
            if (access == null) {
                return Optional.empty();
            }
            if (level == null) {
                level = new SandboxLevel(access);
            }
            Map<BlockPos, Placement> placements = new LinkedHashMap<>();
            SceneArrangement.of(recipe, variant).forEach((offset, placement) -> placements.put(InteractionProber.ORIGIN.offset(offset), placement));
            if (placements.isEmpty()) {
                return Optional.empty();
            }

            level.reset();
            placements.forEach(level::place);
            AABB bounds = boundsOf(placements);
            Vector3f center = new Vector3f(
                    (float) (bounds.minX + bounds.maxX + 1) / 2f,
                    (float) (bounds.minY + bounds.maxY + 1) / 2f,
                    (float) (bounds.minZ + bounds.maxZ + 1) / 2f);
            float radius = radiusOf(bounds);
            Vector3f sortCamera = SceneRenderer.sortCamera(center, SceneRenderer.DEFAULT_YAW, SceneRenderer.DEFAULT_PITCH);

            BakedLevel baked;
            try {
                baked = SceneBakery.bake(level, bounds, sortCamera);
            } finally {
                level.reset();
            }
            if (LOGGER.isDebugEnabled()) {
                baked.sections().forEach((pos, section) -> LOGGER.debug(
                        "Baked {} scene of {} (source {}, neighbor {}): section {} block layers {} fluid layers {}; bounds {} center {} radius {}",
                        variant.after() ? "after" : "before", recipe.id(), variant.source(), variant.neighbor(), pos,
                        section.blockBuffers().keySet(), section.fluidBuffers().keySet(), bounds, center, radius));
            }
            return Optional.of(new BakedScene(baked, center, radius, placements));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Failed to bake the {} scene for fluid interaction recipe {}", variant.after() ? "after" : "before", recipe.id(), e);
            return Optional.empty();
        }
    }

    private static AABB boundsOf(Map<BlockPos, Placement> placements) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : placements.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Half the bounding box's diagonal, so the framing holds at every orbit angle. */
    private static float radiusOf(AABB bounds) {
        float x = (float) (bounds.maxX - bounds.minX) + 1f;
        float y = (float) (bounds.maxY - bounds.minY) + 1f;
        float z = (float) (bounds.maxZ - bounds.minZ) + 1f;
        return 0.5f * (float) Math.sqrt(x * x + y * y + z * z) + MARGIN;
    }

    private static void release(BakedScene scene) {
        for (BakedLevelSection section : scene.level().sections().values()) {
            section.blockBuffers().values().forEach(VertexBuffer::close);
            section.fluidBuffers().values().forEach(VertexBuffer::close);
        }
    }

    private static @Nullable RegistryAccess registryAccess() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.registryAccess() : null;
    }
}
