package dev.xaeroaddons.structureoverlay.client;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** The two keybinds this addon registers into the controls screen. */
public final class KeyBindings {
    public static final String CATEGORY = "key.categories.structureoverlay";

    public static final KeyMapping OPEN_MENU =
        new KeyMapping("key.structureoverlay.menu", GLFW.GLFW_KEY_O, CATEGORY);

    public static final KeyMapping TOGGLE_OVERLAY =
        new KeyMapping("key.structureoverlay.toggle", GLFW.GLFW_KEY_P, CATEGORY);

    private KeyBindings() {
    }
}
