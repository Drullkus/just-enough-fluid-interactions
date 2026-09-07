package us.drullk.jefi.jei.scene;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** The mouse cursor shown while a scene is hovered or being dragged. */
public final class SceneCursor {
    private static long cursor = MemoryUtil.NULL;
    private static @Nullable Object owner;
    private static boolean listening;

    private SceneCursor() {
    }

    /** Claims or gives up the scene cursor. Only the claimant can give it up, so widgets can call this in any order. */
    public static void request(Object widget, boolean wanted) {
        listen();
        if (wanted) {
            if (owner != widget) {
                owner = widget;
                apply(handle());
            }
        } else if (owner == widget) {
            release();
        }
    }

    /** Restores the default arrow. */
    public static void release() {
        if (owner != null) {
            owner = null;
            apply(MemoryUtil.NULL);
        }
    }

    /**
     * The cursor drawn over a scene. A custom rotate icon would be loaded as a {@code GLFWImage} and created
     * here with {@link GLFW#glfwCreateCursor} instead of the standard cursor.
     */
    private static long handle() {
        if (cursor == MemoryUtil.NULL) {
            cursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        }
        return cursor;
    }

    private static void apply(long handle) {
        GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().getWindow(), handle);
    }

    /** Nothing calls back once the recipe screen is gone, so the cursor has to be released from the outside. */
    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Closing event) -> release());
    }
}
