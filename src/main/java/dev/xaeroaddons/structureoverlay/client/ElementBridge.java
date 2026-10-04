package dev.xaeroaddons.structureoverlay.client;

import dev.xaeroaddons.structureoverlay.element.StructureElementRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;

/**
 * Xaero's World Map renders waypoints, player markers and claims through a single
 * element render handler ({@link WorldMap#mapElementRenderHandler}). That handler is a
 * public field with a public {@code add(...)} method, so this addon plugs its structure
 * markers into the exact same pipeline - no bytecode injection needed for rendering.
 *
 * <p>The handler is (re)built by Xaero during its own initialization, so registration is
 * retried from the client tick until the renderer has landed in the current instance.
 */
public final class ElementBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementBridge.class);

    private static MapElementRenderHandler boundHandler;

    private ElementBridge() {
    }

    public static void ensureRegistered() {
        MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
        if (handler == null || handler == boundHandler) {
            return;
        }
        handler.add(new StructureElementRenderer());
        boundHandler = handler;
        LOGGER.info("Structure overlay: registered markers with Xaero's world map element renderer");
    }
}
