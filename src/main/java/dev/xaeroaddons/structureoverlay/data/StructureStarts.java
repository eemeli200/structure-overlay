package dev.xaeroaddons.structureoverlay.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Pulls structure starts out of a chunk NBT root.
 *
 * <p>Chunk data stores them at {@code structures -> starts}, keyed by structure id. Each
 * entry is the output of vanilla {@code StructureStart.save(...)}, which writes exactly:
 * {@code id} (string), {@code ChunkX} / {@code ChunkZ} (the start chunk), {@code references}
 * and {@code Children}. The same entry is duplicated into every chunk of the structure's
 * bounding box, so callers must deduplicate on {@code id + ChunkX + ChunkZ}.
 *
 * <p>Every child piece additionally carries its block bounding box as a {@code BB} int
 * array {@code [x1, y1, z1, x2, y2, z2]}; the structure's footprint is the union of its
 * children's boxes. Verified against real chunk data of classic (mineshaft), jigsaw
 * (ancient city, trial chambers) and template (ruined portal) pieces.
 *
 * <p>Field names verified against the Minecraft 1.21.1 client jar.
 */
public final class StructureStarts {
    private StructureStarts() {
    }

    public interface StartSink {
        /** @param bounds union of the piece bounding boxes, or {@code null} if the start
         *               carried no {@code BB} data (e.g. an invalid/generated-broken start) */
        void accept(String structureId, int startChunkX, int startChunkZ, BlockBox bounds);
    }

    public static void extract(CompoundTag chunkNbt, StartSink sink) {
        if (!chunkNbt.contains("structures", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag structures = chunkNbt.getCompound("structures");
        if (!structures.contains("starts", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag starts = structures.getCompound("starts");
        for (String key : starts.getAllKeys()) {
            Tag raw = starts.get(key);
            if (!(raw instanceof CompoundTag start)) {
                continue;
            }
            if (!start.contains("ChunkX", Tag.TAG_INT) || !start.contains("ChunkZ", Tag.TAG_INT)) {
                continue;
            }
            String id = start.getString("id");
            if (id.isEmpty() || "INVALID".equals(id)) {
                id = key;
            }
            if (id.isEmpty()) {
                continue;
            }
            sink.accept(id, start.getInt("ChunkX"), start.getInt("ChunkZ"), pieceBounds(start));
        }
    }

    /** Union of all children's {@code BB} arrays, or {@code null} when there is none. */
    private static BlockBox pieceBounds(CompoundTag start) {
        if (!start.contains("Children", Tag.TAG_LIST)) {
            return null;
        }
        ListTag children = start.getList("Children", Tag.TAG_COMPOUND);
        BlockBox bounds = null;
        for (int i = 0; i < children.size(); i++) {
            int[] bb = children.getCompound(i).getIntArray("BB");
            if (bb.length < 6) {
                continue;
            }
            // BB = [x1, y1, z1, x2, y2, z2]; Y is ignored for the top-down map.
            BlockBox piece = new BlockBox(bb[0], bb[2], bb[3], bb[5]);
            bounds = bounds == null ? piece : BlockBox.union(bounds, piece);
        }
        return bounds;
    }
}
