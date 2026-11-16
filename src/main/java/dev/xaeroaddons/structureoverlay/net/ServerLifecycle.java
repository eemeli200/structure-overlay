package dev.xaeroaddons.structureoverlay.net;

import dev.xaeroaddons.structureoverlay.StructureOverlayMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Advances in-flight uploads every tick and drops server state on shutdown. */
@EventBusSubscriber(modid = StructureOverlayMod.MOD_ID)
public final class ServerLifecycle {

    private ServerLifecycle() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerStructureSource.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerStructureSource.reset();
    }
}
