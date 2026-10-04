package dev.xaeroaddons.structureoverlay.mixin;

import dev.xaeroaddons.structureoverlay.client.ClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;

/**
 * Adds a "Structures" button to Xaero's world map screen.
 *
 * <p>Placed in the free slot of the map's bottom-left button column (between the hop
 * button and the dimension toggle, both of which are 20px tall 20x20 icons). Xaero
 * rebuilds its widget list on every {@code init()} and clears it beforehand, so the
 * button is simply re-added each time - no cleanup needed.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class GuiMapMixin {

    @Shadow(remap = false)
    public abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addButton(T widget);

    @Inject(method = "init()V", at = @At("RETURN"), remap = false)
    private void structureoverlay$addStructuresButton(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        int y = mc.getWindow().getGuiScaledHeight() - 80;
        Button button = Button.builder(
                Component.translatable("structureoverlay.map_button"),
                b -> ClientEvents.openMenu())
            .bounds(0, y, 96, 20)
            .build();
        this.addButton(button);
    }
}
