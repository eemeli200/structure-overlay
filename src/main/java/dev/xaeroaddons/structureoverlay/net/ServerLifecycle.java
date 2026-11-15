package dev.xaeroaddons.structureoverlay.net;

import dev.xaeroaddons.structureoverlay.StructureOverlayMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Drops the in-memory server scan when a world/server is shut down. */
@EventBusSubscriber(modid = StructureOverlayMod.MOD_ID)
public final class ServerLifecycle {

    private ServerLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerStructureSource.reset();
    }
}
