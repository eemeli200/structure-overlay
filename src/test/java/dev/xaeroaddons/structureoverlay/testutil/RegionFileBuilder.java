package dev.xaeroaddons.structureoverlay.testutil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * Builds synthetic vanilla-format region files for tests.
 *
 * <p>Layout: 4 KiB chunk offset table + 4 KiB timestamp table, then per chunk
 * {@code [length:i32 big endian][compression:u8][payload]} padded to 4 KiB sectors.
 */
public final class RegionFileBuilder {
    private RegionFileBuilder() {
    }

    public enum Compression {
        ZLIB(2),
        GZIP(1),
        NONE(3);

        final int id;

        Compression(int id) {
            this.id = id;
        }
    }

    public record Chunk(CompoundTag nbt, Compression compression) {
        public static Chunk zlib(CompoundTag nbt) {
            return new Chunk(nbt, Compression.ZLIB);
        }

        public static Chunk gzip(CompoundTag nbt) {
            return new Chunk(nbt, Compression.GZIP);
        }
    }

    /**
     * Creates a chunk NBT root containing a single structure start.
     *
     * @param pieceBoxes optional child pieces, each {@code [x1, y1, z1, x2, y2, z2]} and
     *                   written as a {@code Children} entry with a {@code BB} array - the
     *                   exact shape vanilla {@code StructurePiece.save} produces
     */
    public static CompoundTag chunkWithStructureStart(String structureId, int chunkX, int chunkZ,
                                                      int startChunkX, int startChunkZ, int[]... pieceBoxes) {
        CompoundTag start = new CompoundTag();
        start.putString("id", structureId);
        start.putInt("ChunkX", startChunkX);
        start.putInt("ChunkZ", startChunkZ);
        start.putInt("references", 1);
        if (pieceBoxes.length > 0) {
            ListTag children = new ListTag();
            for (int[] box : pieceBoxes) {
                CompoundTag piece = new CompoundTag();
                piece.putString("id", "test:piece");
                piece.putIntArray("BB", box);
                children.add(piece);
            }
            start.put("Children", children);
        }

        CompoundTag starts = new CompoundTag();
        starts.put(structureId, start);

        CompoundTag structures = new CompoundTag();
        structures.put("starts", starts);

        CompoundTag chunk = new CompoundTag();
        chunk.putInt("xPos", chunkX);
        chunk.putInt("zPos", chunkZ);
        chunk.putInt("DataVersion", 3955); // 1.21.1
        chunk.put("structures", structures);
        return chunk;
    }

    /** A chunk without any structure data. */
    public static CompoundTag emptyChunk(int chunkX, int chunkZ) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("xPos", chunkX);
        chunk.putInt("zPos", chunkZ);
        return chunk;
    }

    public static void write(Path file, Chunk... chunks) throws IOException {
        byte[] data = build(List.of(chunks));
        Files.createDirectories(file.getParent());
        Files.write(file, data);
    }

    public static byte[] build(List<Chunk> chunks) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        int sectorCount = 2; // first two sectors are the header
        byte[] offsets = new byte[4096];

        for (Chunk chunk : chunks) {
            byte[] payload = encode(chunk);
            int sectors = (payload.length + 4095) / 4096;
            int chunkX = chunk.nbt().getInt("xPos");
            int chunkZ = chunk.nbt().getInt("zPos");
            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            int entry = localX + localZ * 32;
            offsets[entry * 4] = (byte) (sectorCount >> 16);
            offsets[entry * 4 + 1] = (byte) (sectorCount >> 8);
            offsets[entry * 4 + 2] = (byte) sectorCount;
            offsets[entry * 4 + 3] = (byte) sectors;
            sectorCount += sectors;
            payloads.add(payload);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(8192 + payloads.size() * 4096);
        out.write(offsets);
        out.write(new byte[4096]); // timestamps
        for (byte[] payload : payloads) {
            out.write(payload);
            int padding = (4096 - (payload.length % 4096)) % 4096;
            out.write(new byte[padding]);
        }
        return out.toByteArray();
    }

    private static byte[] encode(Chunk chunk) throws IOException {
        // Note: vanilla's NbtIo.writeCompressed produces GZIP (it writes level.dat), while
        // region file compression type 2 is zlib - so each case is framed explicitly here.
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        switch (chunk.compression()) {
            case ZLIB -> {
                DeflaterOutputStream deflater = new DeflaterOutputStream(raw);
                DataOutputStream data = new DataOutputStream(deflater);
                NbtIo.write(chunk.nbt(), data);
                data.flush();
                deflater.finish();
            }
            case GZIP -> NbtIo.writeCompressed(chunk.nbt(), raw);
            case NONE -> NbtIo.write(chunk.nbt(), new DataOutputStream(raw));
        }
        byte[] body = raw.toByteArray();

        ByteArrayOutputStream framed = new ByteArrayOutputStream(body.length + 5);
        framed.write((body.length + 1) >>> 24);
        framed.write((body.length + 1) >>> 16);
        framed.write((body.length + 1) >>> 8);
        framed.write(body.length + 1);
        framed.write(chunk.compression().id);
        framed.writeBytes(body);
        return framed.toByteArray();
    }
}
