package dev.xaeroaddons.structureoverlay.element;

import net.minecraft.client.Minecraft;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.element.MapElementReader;
import xaero.map.element.render.ElementRenderLocation;

/** Positions, hit boxes and tooltips for structure markers. */
public final class StructureElementReader
    extends MapElementReader<StructureElement, StructureElementContext, StructureElementRenderer> {

    /** Half of the marker hit box, before zoom-based box scaling. */
    private static final int HIT_BOX = 8;
    /**
     * Culling box in pixels (Xaero multiplies it by the GUI scale). Footprints can
     * extend thousands of pixels past the marker at high zoom, so this has to be very
     * generous - otherwise a structure's box pops out while still on screen.
     */
    private static final int CULL_X = 4096;
    private static final int CULL_Y = 4096;

    @Override
    public boolean isHidden(StructureElement element, StructureElementContext context) {
        return context.dimension == null || !context.dimension.location().equals(element.dimension());
    }

    @Override
    public double getRenderX(StructureElement element, StructureElementContext context, float partialTicks) {
        return element.x();
    }

    @Override
    public double getRenderZ(StructureElement element, StructureElementContext context, float partialTicks) {
        return element.z();
    }

    @Override
    public int getInteractionBoxLeft(StructureElement element, StructureElementContext context, float partialTicks) {
        return -HIT_BOX;
    }

    @Override
    public int getInteractionBoxRight(StructureElement element, StructureElementContext context, float partialTicks) {
        return HIT_BOX;
    }

    @Override
    public int getInteractionBoxTop(StructureElement element, StructureElementContext context, float partialTicks) {
        return -HIT_BOX;
    }

    @Override
    public int getInteractionBoxBottom(StructureElement element, StructureElementContext context, float partialTicks) {
        return HIT_BOX;
    }

    @Override
    public int getRenderBoxLeft(StructureElement element, StructureElementContext context, float partialTicks) {
        return -CULL_X;
    }

    @Override
    public int getRenderBoxRight(StructureElement element, StructureElementContext context, float partialTicks) {
        return CULL_X;
    }

    @Override
    public int getRenderBoxTop(StructureElement element, StructureElementContext context, float partialTicks) {
        return -CULL_Y;
    }

    @Override
    public int getRenderBoxBottom(StructureElement element, StructureElementContext context, float partialTicks) {
        return CULL_Y;
    }

    @Override
    public int getLeftSideLength(StructureElement element, Minecraft mc) {
        return 0;
    }

    @Override
    public String getMenuName(StructureElement element) {
        return element.displayName();
    }

    @Override
    public String getFilterName(StructureElement element) {
        return element.displayName();
    }

    @Override
    public int getMenuTextFillLeftPadding(StructureElement element) {
        return 0;
    }

    @Override
    public int getRightClickTitleBackgroundColor(StructureElement element) {
        return 0;
    }

    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
        return true;
    }

    @Override
    public boolean isInteractable(ElementRenderLocation location, StructureElement element) {
        return true;
    }

    @Override
    public Tooltip getTooltip(StructureElement element, StructureElementContext context, boolean overMenu) {
        return new Tooltip(net.minecraft.network.chat.Component.literal(
            element.displayName() + " [" + (int) element.x() + ", " + (int) element.z() + "]"));
    }
}
