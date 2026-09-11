package us.drullk.jefi.jei.scene;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * The orbit angles shared by the scenes of one recipe layout. JEI builds fresh widgets whenever a page changes,
 * so a new instance per layout is what resets the rotation.
 */
public final class SceneRotation {
    private static final float DEGREES_PER_PIXEL = 1.0f;
    private static final float DEGREES_PER_CLICK = 90.0f;
    private static final float MAX_PITCH = 89.0f;

    /** How far one click of a mouse button turns a scene, zero for every key that does not turn one. */
    public static float stepOf(InputConstants.Key key) {
        if (key.getType() != InputConstants.Type.MOUSE) {
            return 0.0f;
        }
        if (key.getValue() == InputConstants.MOUSE_BUTTON_LEFT) {
            return DEGREES_PER_CLICK;
        }
        return key.getValue() == InputConstants.MOUSE_BUTTON_RIGHT ? -DEGREES_PER_CLICK : 0.0f;
    }

    private float yaw = SceneRenderer.DEFAULT_YAW;
    private float pitch = SceneRenderer.DEFAULT_PITCH;
    private int version;
    private boolean dragging;
    private boolean dragged;

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

    /** Whether the button that is down has moved since it was pressed, so a click can tell itself from a drag. */
    public boolean dragged() {
        return dragged;
    }

    public void press() {
        dragged = false;
    }

    /** Turns the yaw by one click, leaving the pitch where it is. */
    public void step(float degrees) {
        yaw = Mth.wrapDegrees(yaw + degrees);
        version++;
    }

    public void drag(double dragX, double dragY) {
        yaw = Mth.wrapDegrees(yaw + (float) dragX * DEGREES_PER_PIXEL);
        pitch = Mth.clamp(pitch + (float) dragY * DEGREES_PER_PIXEL, -MAX_PITCH, MAX_PITCH);
        version++;
        dragging = true;
        dragged = true;
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
