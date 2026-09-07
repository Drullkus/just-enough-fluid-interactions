package com.example.examplemod.jei.scene;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

import com.example.examplemod.jei.probe.FluidInteractionRecipe;
import com.example.examplemod.jei.probe.InteractionProber;
import com.example.examplemod.jei.probe.Placement;
import com.example.examplemod.jei.sandbox.SandboxLevel;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;

import dev.compactmods.gander.core.camera.SceneCamera;
import dev.compactmods.gander.render.geometry.BakedLevel;
import dev.compactmods.gander.render.geometry.BakedLevelSection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.phys.AABB;

/**
 * Lazily bakes the before/after scenes of recipes into GPU buffers with Gander's bakery, and frees them again
 * when JEI's runtime goes away.
 *
 * <p>Everything here runs on the render thread: JEI draws recipes there, and vertex buffers must be created and
 * destroyed there. One sandbox level is reused for every bake; only the baked buffers are kept per recipe.
 */
public final class SceneCache {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Camera distance from the scene center. Negative values place the camera on Gander's default side. */
    private static final float ZOOM = -6.5f;

    public record BakedScene(BakedLevel level, SceneCamera camera, Vector3f center, Map<BlockPos, Placement> placements) {
    }

    private final Map<FluidInteractionRecipe, Optional<BakedScene>> before = new IdentityHashMap<>();
    private final Map<FluidInteractionRecipe, Optional<BakedScene>> after = new IdentityHashMap<>();
    private @Nullable SandboxLevel level;

    /** Returns the baked scene, baking it on first use, or {@code null} when it could not be baked. */
    public @Nullable BakedScene get(FluidInteractionRecipe recipe, boolean afterPhase) {
        Map<FluidInteractionRecipe, Optional<BakedScene>> map = afterPhase ? after : before;
        return map.computeIfAbsent(recipe, r -> bake(r, afterPhase)).orElse(null);
    }

    /** Releases every vertex buffer. Must run on the render thread. */
    public void clear() {
        before.values().forEach(scene -> scene.ifPresent(SceneCache::release));
        after.values().forEach(scene -> scene.ifPresent(SceneCache::release));
        before.clear();
        after.clear();
        level = null;
    }

    private Optional<BakedScene> bake(FluidInteractionRecipe recipe, boolean afterPhase) {
        try {
            RegistryAccess access = registryAccess();
            if (access == null) {
                return Optional.empty();
            }
            if (level == null) {
                level = new SandboxLevel(access);
            }
            Map<BlockPos, Placement> placements = new LinkedHashMap<>();
            recipe.scene(afterPhase).forEach((offset, placement) -> placements.put(InteractionProber.ORIGIN.offset(offset), placement));
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
            SceneCamera camera = new SceneCamera();
            camera.zoom(ZOOM);

            BakedLevel baked;
            try {
                baked = SceneBakery.bake(level, bounds, camera.getLookFrom().add(center));
            } finally {
                level.reset();
            }
            if (LOGGER.isDebugEnabled()) {
                baked.sections().forEach((pos, section) -> LOGGER.debug(
                        "Baked {} scene of {}: section {} block layers {} fluid layers {}; bounds {} center {} camera at {} rotation {}",
                        afterPhase ? "after" : "before", recipe.id(), pos,
                        section.blockBuffers().keySet(), section.fluidBuffers().keySet(),
                        bounds, center, camera.getLookFrom(), camera.rotation()));
            }
            return Optional.of(new BakedScene(baked, camera, center, placements));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Failed to bake the {} scene for fluid interaction recipe {}", afterPhase ? "after" : "before", recipe.id(), e);
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
