package us.drullk.jefi.jei.scene;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * The orbit angles shared by the scenes of one recipe layout. JEI builds fresh widgets whenever a page changes,
 * so a new instance per layout is what resets the rotation.
 */
public final class SceneRotation {
    private static final float DEGREES_PER_PIXEL = 1.0f;
    private static final float MAX_PITCH = 89.0f;

    private float yaw = SceneRenderer.DEFAULT_YAW;
    private float pitch = SceneRenderer.DEFAULT_PITCH;
    private int version;
    private boolean dragging;

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /** Increments on every change, so a scene can tell whether the angles it last sorted for are still current. */
    public int version() {
        return version;
    }

    public boolean dragging() {
        return dragging;
    }

    public void drag(double dragX, double dragY) {
        yaw = Mth.wrapDegrees(yaw + (float) dragX * DEGREES_PER_PIXEL);
        pitch = Mth.clamp(pitch + (float) dragY * DEGREES_PER_PIXEL, -MAX_PITCH, MAX_PITCH);
        version++;
        dragging = true;
    }

    /**
     * Ends the drag once the mouse button comes back up. JEI only reports drags while the cursor is still over
     * the widget, so the button itself is the reliable end of one.
     */
    public void settle() {
        if (dragging && !leftMouseDown()) {
            dragging = false;
        }
    }

    private static boolean leftMouseDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }
}
