package dev.xaeroaddons.structureoverlay.client;

import dev.xaeroaddons.structureoverlay.StructureOverlayMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = StructureOverlayMod.MOD_ID, value = Dist.CLIENT)
public final class ModEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModEvents.class);

    private ModEvents() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KeyBindings.OPEN_MENU);
        event.register(KeyBindings.TOGGLE_OVERLAY);
        LOGGER.info("Structure overlay: registered keybindings");
    }
}
