package dev.xaeroaddons.structureoverlay.data;

import net.minecraft.resources.ResourceLocation;

/**
 * One structure start read from the world save.
 *
 * @param dimension   the dimension the structure lives in
 * @param structureId the structure's registry id, e.g. {@code minecraft:village_plains}
 * @param startChunkX the start chunk X stored by {@code StructureStart.save}
 * @param startChunkZ the start chunk Z stored by {@code StructureStart.save}
 * @param bounds      union of the piece bounding boxes in block coords, or {@code null}
 *                    when the start carried no piece box data
 */
public record StructureInstance(ResourceLocation dimension, String structureId,
                                int startChunkX, int startChunkZ, BlockBox bounds) {
    /** Marker X in block coordinates (centre of the start chunk). */
    public double x() {
        return startChunkX * 16.0 + 8.0;
    }

    /** Marker Z in block coordinates (centre of the start chunk). */
    public double z() {
        return startChunkZ * 16.0 + 8.0;
    }

    /** Dedup key: the same start is stored in every chunk of its bounding box. */
    public String dedupKey() {
        return structureId + ' ' + startChunkX + ' ' + startChunkZ;
    }

    /** True when a footprint was read from the chunk data. */
    public boolean hasBounds() {
        return bounds != null;
    }
}
