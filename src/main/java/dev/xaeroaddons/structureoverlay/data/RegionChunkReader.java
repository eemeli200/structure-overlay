package dev.xaeroaddons.structureoverlay.data;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Minimal, read-only reader for vanilla Anvil region files ({@code r.X.Z.mca}).
 *
 * <p>Kept deliberately independent of Minecraft's own {@code RegionFileStorage} because
 * that class is package-private to construct in 1.21.1; the on-disk format is stable and
 * trivial: 4 KiB of chunk offsets, 4 KiB of timestamps, then chunk payloads of
 * {@code [length:i32][compression:u8][payload]} with 1=gzip, 2=zlib, 3=uncompressed.
 *
 * <p>Chunks that are missing, corrupt, mid-write or in an unsupported compression
 * scheme are skipped rather than aborting the scan.
 */
public final class RegionChunkReader {
    private static final int HEADER_SIZE = 8192;
    private static final int SECTOR_SIZE = 4096;
    /** Sanity cap for a single chunk payload (32 MiB). */
    private static final int MAX_PAYLOAD = 1 << 25;

    private RegionChunkReader() {
    }

    /** Visitor invoked with the root NBT compound of every readable chunk in the file. */
    public interface ChunkVisitor {
        void visit(CompoundTag chunkNbt);
    }

    /**
     * @return the number of chunks successfully handed to the visitor
     */
    public static int forEachChunk(Path regionFile, ChunkVisitor visitor) throws IOException {
        int visited = 0;
        try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (file.length() < HEADER_SIZE) {
                return 0;
            }
            byte[] offsets = new byte[4096];
            file.readFully(offsets);

            for (int entry = 0; entry < 1024; entry++) {
                int offset = ((offsets[entry * 4] & 0xFF) << 16)
                    | ((offsets[entry * 4 + 1] & 0xFF) << 8)
                    | (offsets[entry * 4 + 2] & 0xFF);
                if (offset == 0) {
                    continue;
                }
                long position = (long) offset * SECTOR_SIZE;
                if (position + 5 > file.length()) {
                    continue;
                }
                file.seek(position);
                int length = file.readInt();
                if (length <= 1 || length > MAX_PAYLOAD) {
                    continue;
                }
                int compression = file.readUnsignedByte();
                int payloadLength = length - 1;
                if (position + 5L + payloadLength > file.length()) {
                    continue;
                }
                byte[] payload = new byte[payloadLength];
                file.readFully(payload);

                CompoundTag nbt = decode(payload, compression);
                if (nbt != null) {
                    visited++;
                    visitor.visit(nbt);
                }
            }
        }
        return visited;
    }

    private static CompoundTag decode(byte[] payload, int compression) {
        try {
            InputStream stream = switch (compression) {
                case 1 -> new GZIPInputStream(new java.io.ByteArrayInputStream(payload));
                case 2 -> new InflaterInputStream(new java.io.ByteArrayInputStream(payload));
                case 3 -> new java.io.ByteArrayInputStream(payload);
                default -> null; // e.g. LZ4 from newer versions - not expected for 1.21.1
            };
            if (stream == null) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
                // cast disambiguates NbtIo.read(DataInput, ...) from read(InputStream, ...)
                DataInput input = in;
                return NbtIo.read(input, NbtAccounter.unlimitedHeap());
            }
        } catch (Exception e) {
            return null; // torn write or corrupt chunk - skip it
        }
    }

    /** Whether the given directory looks like a vanilla {@code region} folder. */
    public static boolean isRegionDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.newDirectoryStream(dir, "*.mca")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

}
