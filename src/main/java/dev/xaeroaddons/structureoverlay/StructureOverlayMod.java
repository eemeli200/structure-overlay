package dev.xaeroaddons.structureoverlay;

import net.neoforged.fml.common.Mod;

/**
 * Mod entry point. All behaviour is registered through {@code @EventBusSubscriber}
 * classes so no client-only class is ever loaded on a dedicated server.
 */
@Mod(StructureOverlayMod.MOD_ID)
public final class StructureOverlayMod {
    public static final String MOD_ID = "structureoverlay";

    public StructureOverlayMod() {
    }
}
