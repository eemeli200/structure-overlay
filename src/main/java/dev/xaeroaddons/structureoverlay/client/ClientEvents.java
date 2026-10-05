package dev.xaeroaddons.structureoverlay.client;

import dev.xaeroaddons.structureoverlay.StructureOverlayMod;
import dev.xaeroaddons.structureoverlay.config.OverlayConfig;
import dev.xaeroaddons.structureoverlay.data.StructureRepository;
import dev.xaeroaddons.structureoverlay.gui.StructureMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-side game bus events: keybind polling, scan lifecycle, element registration. */
@EventBusSubscriber(modid = StructureOverlayMod.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private static ClientLevel lastLevel;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            if (mc.level == null) {
                StructureRepository.onWorldUnavailable(StructureRepository.Status.IDLE);
            } else {
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) {
                    StructureRepository.onWorldUnavailable(StructureRepository.Status.NO_ACCESS);
                } else {
                    StructureRepository.onSingleplayerWorld(server.getWorldPath(LevelResource.ROOT));
                }
            }
        }

        ElementBridge.ensureRegistered();

        while (KeyBindings.OPEN_MENU.consumeClick()) {
            if (mc.screen == null) {
                openMenu();
            }
        }
        while (KeyBindings.TOGGLE_OVERLAY.consumeClick()) {
            // never while a screen is open: typing "p" in the menu's search box must not
            // flip the overlay
            if (mc.screen != null) {
                continue;
            }
            OverlayConfig cfg = OverlayConfig.get();
            cfg.enabled = !cfg.enabled;
            cfg.save();
            StructureRepository.invalidate();
        }
    }

    /** Opens the menu, keeping the current screen (e.g. Xaero's map) as the parent to return to. */
    public static void openMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.screen instanceof StructureMenuScreen) {
            return;
        }
        mc.setScreen(new StructureMenuScreen(mc.screen));
    }
}
