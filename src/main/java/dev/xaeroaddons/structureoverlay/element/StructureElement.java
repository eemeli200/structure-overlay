package dev.xaeroaddons.structureoverlay.element;

import dev.xaeroaddons.structureoverlay.data.BlockBox;
import net.minecraft.resources.ResourceLocation;

/**
 * One structure marker handed to Xaero's element render pipeline.
 *
 * @param dimension   dimension the marker belongs to
 * @param structureId registry id of the structure
 * @param displayName pre-resolved, human readable name
 * @param x           block X of the marker
 * @param z           block Z of the marker
 * @param bounds      block footprint to outline/fill, or {@code null} when the chunk
 *                    data carried no piece boxes
 */
public record StructureElement(
    ResourceLocation dimension,
    String structureId,
    String displayName,
    double x,
    double z,
    BlockBox bounds
) {
}
