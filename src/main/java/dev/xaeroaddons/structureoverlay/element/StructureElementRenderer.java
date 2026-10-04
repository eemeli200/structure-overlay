package dev.xaeroaddons.structureoverlay.element;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.xaeroaddons.structureoverlay.config.OverlayConfig;
import dev.xaeroaddons.structureoverlay.data.BlockBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureManager;
import xaero.map.element.MapElementRenderer;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Draws structure markers on Xaero's world map.
 *
 * <p>Registered into {@code WorldMap.mapElementRenderHandler} (see {@code ElementBridge}),
 * so Xaero handles world-to-screen conversion, zoom, culling and hover detection. Within
 * the render call the pose is already translated to the marker position in screen pixels,
 * so drawing is a plain {@link GuiGraphics} operation relative to the origin.
 */
public final class StructureElementRenderer
    extends MapElementRenderer<StructureElement, StructureElementContext, StructureElementRenderer> {


    public StructureElementRenderer() {
        super(new StructureElementContext(), new StructureElementProvider(), new StructureElementReader());
    }

    // ------------------------------------------------------------------ modern API

    @Override
    public void preRender(ElementRenderInfo renderInfo, MultiBufferSource.BufferSource vanillaBufferSource,
                          MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
        if (renderInfo != null) {
            // tells the provider which dimension to serve and the reader which markers to hide
            this.context.dimension = renderInfo.mapDimension;
        }
    }

    @Override
    public void postRender(ElementRenderInfo renderInfo, MultiBufferSource.BufferSource vanillaBufferSource,
                           MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
    }

    @Override
    public void renderElementShadow(StructureElement element, boolean hovered, float optionalScale,
                                    double partialX, double partialY, ElementRenderInfo renderInfo,
                                    GuiGraphics guiGraphics, MultiBufferSource.BufferSource vanillaBufferSource,
                                    MultiTextureRenderTypeRendererProvider rendererProvider) {
        // Xaero runs this pre-pass for every element before the main pass, so footprint
        // fills drawn here always end up underneath every marker and label.
        if (renderInfo != null && renderInfo.location == ElementRenderLocation.WORLD_MAP) {
            drawBoundsFill(element, partialX, partialY, renderInfo.scale, guiGraphics);
        }
    }

    @Override
    public boolean renderElement(StructureElement element, boolean hovered, double optionalDepth, float optionalScale,
                                 double partialX, double partialY, ElementRenderInfo renderInfo,
                                 GuiGraphics guiGraphics, MultiBufferSource.BufferSource vanillaBufferSource,
                                 MultiTextureRenderTypeRendererProvider rendererProvider) {
        return draw(element, hovered, optionalScale, partialX, partialY,
            renderInfo == null ? 1.0D : renderInfo.scale, guiGraphics, Minecraft.getInstance().font);
    }

    @Override
    public int getOrder() {
        // after waypoints (100-ish) and player markers, before nothing in particular
        return 300;
    }

    @Override
    public boolean shouldBeDimScaled() {
        // Marker coordinates already live in the coordinate space of the dimension being
        // displayed, so Xaero's cross-dimension scaling must not be applied to them.
        return false;
    }

    // ------------------------------------------------------ deprecated adapter API
    //
    // MapElementRenderer forwards its modern methods into these abstract ones (and vice
    // versa in some subclasses), so both directions are implemented explicitly here.

    @Deprecated
    @Override
    public void beforeRender(int location, Minecraft mc, GuiGraphics guiGraphics, double cameraX, double cameraZ,
                             double mouseX, double mouseZ, float brightness, double scale, double screenSizeBasedScale,
                             TextureManager textureManager, Font fontRenderer,
                             MultiBufferSource.BufferSource vanillaBufferSource,
                             MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
        // dimension is captured in preRender(ElementRenderInfo, ...)
    }

    @Deprecated
    @Override
    public void afterRender(int location, Minecraft mc, GuiGraphics guiGraphics, double cameraX, double cameraZ,
                            double mouseX, double mouseZ, float brightness, double scale, double screenSizeBasedScale,
                            TextureManager textureManager, Font fontRenderer,
                            MultiBufferSource.BufferSource vanillaBufferSource,
                            MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
    }

    @Deprecated
    @Override
    public void renderElementPre(int location, StructureElement element, boolean hovered, Minecraft mc,
                                 GuiGraphics guiGraphics, double cameraX, double cameraZ, double mouseX, double mouseZ,
                                 float brightness, double scale, double screenSizeBasedScale,
                                 TextureManager textureManager, Font fontRenderer,
                                 MultiBufferSource.BufferSource vanillaBufferSource,
                                 MultiTextureRenderTypeRendererProvider rendererProvider, float optionalScale,
                                 double partialX, double partialY, boolean cave, float partialTicks) {
        if (location == ElementRenderLocation.WORLD_MAP.getIndex()) {
            drawBoundsFill(element, partialX, partialY, scale, guiGraphics);
        }
    }

    @Deprecated
    @Override
    public boolean renderElement(int location, StructureElement element, boolean hovered, Minecraft mc,
                                 GuiGraphics guiGraphics, double cameraX, double cameraZ, double mouseX, double mouseZ,
                                 float brightness, double scale, double screenSizeBasedScale,
                                 TextureManager textureManager, Font fontRenderer,
                                 MultiBufferSource.BufferSource vanillaBufferSource,
                                 MultiTextureRenderTypeRendererProvider rendererProvider, int elementIndex,
                                 double optionalDepth, float optionalScale, double partialX, double partialY,
                                 boolean cave, float partialTicks) {
        return draw(element, hovered, optionalScale, partialX, partialY, scale, guiGraphics, fontRenderer);
    }

    @Deprecated
    @Override
    public boolean shouldRender(int location, boolean shadow) {
        // The pre-pass (shadow) is what draws the footprint fills, so both passes run.
        return location == ElementRenderLocation.WORLD_MAP.getIndex();
    }

    // ------------------------------------------------------------------ drawing

    private boolean draw(StructureElement element, boolean hovered, float optionalScale,
                         double partialX, double partialY, double zoom, GuiGraphics guiGraphics, Font font) {
        OverlayConfig config = OverlayConfig.get();
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(partialX, partialY, 0.0D);
            // Footprint outline: at this point the pose units are logical screen pixels,
            // so block offsets convert with the map zoom (renderInfo.scale = pixels/block).
            drawBoundsOutline(element, hovered, zoom, guiGraphics);
            float scale = Math.max(0.5F, optionalScale);
            pose.scale(scale, scale, 1.0F);

            int fill = structureColor(element.structureId());
            if (hovered) {
                fill = brighten(fill);
            }

            pose.pushPose();
            try {
                pose.mulPose(Axis.ZP.rotationDegrees(45.0F));
                guiGraphics.fill(-6, -6, 6, 6, 0xE0000000); // outline
                guiGraphics.fill(-5, -5, 5, 5, fill);        // marker body
            } finally {
                pose.popPose();
            }
            guiGraphics.fill(-1, -1, 1, 1, 0xFFFFFFFF); // centre dot

            if (hovered || config.showNames) {
                String label = element.displayName();
                if (hovered) {
                    label += " [" + (int) element.x() + ", " + (int) element.z() + "]";
                }
                int width = font.width(label);
                int labelY = 8;
                guiGraphics.fill(-width / 2 - 3, labelY - 2, width / 2 + 3, labelY + font.lineHeight + 2, 0xB0000000);
                guiGraphics.drawString(font, label, -width / 2, labelY, 0xFFFFFF, true);
            }
        } finally {
            pose.popPose();
        }
        return true;
    }

    /** Translucent footprint fill, drawn in the pre-pass so it sits under all markers. */
    private void drawBoundsFill(StructureElement element, double partialX, double partialY,
                                double zoom, GuiGraphics guiGraphics) {
        OverlayConfig config = OverlayConfig.get();
        BlockBox box = element.bounds();
        if (!config.boundsFill || box == null || zoom <= 0.0D) {
            return;
        }
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(partialX, partialY, 0.0D);
            int[] rect = rectPx(element, box, zoom);
            int rgb = structureColor(element.structureId()) & 0xFFFFFF;
            guiGraphics.fill(rect[0], rect[1], rect[2], rect[3], 0x30000000 | rgb);
        } finally {
            pose.popPose();
        }
    }

    /** Outline of the footprint, drawn right before the marker in the main pass. */
    private void drawBoundsOutline(StructureElement element, boolean hovered, double zoom,
                                   GuiGraphics guiGraphics) {
        OverlayConfig config = OverlayConfig.get();
        BlockBox box = element.bounds();
        if (!config.boundsOutline || box == null || zoom <= 0.0D) {
            return;
        }
        int color = structureColor(element.structureId());
        if (hovered) {
            color = brighten(color);
        }
        int line = 0xE0000000 | (color & 0xFFFFFF);
        int[] rect = rectPx(element, box, zoom);
        int left = rect[0];
        int top = rect[1];
        int right = Math.max(rect[2], left + 1);
        int bottom = Math.max(rect[3], top + 1);
        guiGraphics.fill(left, top, right, top + 1, line);
        guiGraphics.fill(left, bottom - 1, right, bottom, line);
        guiGraphics.fill(left, top, left + 1, bottom, line);
        guiGraphics.fill(right - 1, top, right, bottom, line);
    }

    /** Footprint rectangle in pixels relative to the marker origin (inclusive x2/z2 + 1). */
    private static int[] rectPx(StructureElement element, BlockBox box, double zoom) {
        return new int[]{
            (int) Math.floor((box.x1() - element.x()) * zoom),
            (int) Math.floor((box.z1() - element.z()) * zoom),
            (int) Math.ceil((box.x2() + 1 - element.x()) * zoom),
            (int) Math.ceil((box.z2() + 1 - element.z()) * zoom)
        };
    }

    /** Stable, readable per-structure colour derived from the id. */
    static int structureColor(String structureId) {
        int hue = Math.floorMod(structureId.hashCode(), 360);
        float sector = hue / 60.0F;
        int index = (int) sector;
        float fraction = sector - index;
        float r;
        float g;
        float b;
        switch (index % 6) {
            case 0 -> { r = 1; g = fraction; b = 0; }
            case 1 -> { r = 1 - fraction; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = fraction; }
            case 3 -> { r = 0; g = 1 - fraction; b = 1; }
            case 4 -> { r = fraction; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = 1 - fraction; }
        }
        // lift towards white so markers stay visible on dark map areas
        r = r * 0.8F + 0.2F;
        g = g * 0.8F + 0.2F;
        b = b * 0.8F + 0.2F;
        int rgb = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
        return 0xFF000000 | rgb;
    }

    private static int brighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 60);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 60);
        int b = Math.min(255, (argb & 0xFF) + 60);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
