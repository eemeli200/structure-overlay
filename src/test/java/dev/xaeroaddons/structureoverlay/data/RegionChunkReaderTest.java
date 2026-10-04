package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xaeroaddons.structureoverlay.testutil.RegionFileBuilder;
import dev.xaeroaddons.structureoverlay.testutil.RegionFileBuilder.Chunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionChunkReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsZlibCompressedChunkAndExtractsStructureStart() throws Exception {
        Path region = tempDir.resolve("region/r.0.0.mca");
        RegionFileBuilder.write(region, Chunk.zlib(
            RegionFileBuilder.chunkWithStructureStart("minecraft:village_plains", 3, 4, 10, -7)));

        List<CompoundTag> read = readAll(region);
        assertEquals(1, read.size());

        List<String> ids = new ArrayList<>();
        int[] start = new int[2];
        StructureStarts.extract(read.get(0), (id, chunkX, chunkZ, bounds) -> {
            ids.add(id);
            start[0] = chunkX;
            start[1] = chunkZ;
        });
        assertEquals(List.of("minecraft:village_plains"), ids);
        assertEquals(10, start[0]);
        assertEquals(-7, start[1]);
    }

    @Test
    void readsGzipAndUncompressedChunks() throws Exception {
        Path region = tempDir.resolve("region/r.0.0.mca");
        RegionFileBuilder.write(region,
            Chunk.gzip(RegionFileBuilder.chunkWithStructureStart("minecraft:desired_pyramid", 0, 0, 1, 2)),
            new Chunk(RegionFileBuilder.chunkWithStructureStart("minecraft:jigsaw", 1, 0, 1, 2),
                RegionFileBuilder.Compression.NONE));

        assertEquals(2, readAll(region).size());
    }

    @Test
    void skipsCorruptChunkInsteadOfFailing() throws Exception {
        Path region = tempDir.resolve("region/r.0.0.mca");
        RegionFileBuilder.write(region, Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:igloo", 0, 0, 0, 0)));

        byte[] data = Files.readAllBytes(region);
        // find the first chunk payload (starts right after the 8 KiB header) and break it
        for (int i = 8192 + 5; i < Math.min(data.length, 8192 + 100); i++) {
            data[i] = (byte) 0xA5;
        }
        Files.write(region, data);

        // either the corrupt chunk is skipped or - if it survived - no exception is thrown
        readAll(region);
    }

    @Test
    void emptyRegionFileIsHandled() throws Exception {
        Path region = tempDir.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.write(region, new byte[0]);
        assertTrue(readAll(region).isEmpty());
    }

    @Test
    void extractIgnoresChunksWithoutStructureData() {
        CompoundTag chunk = RegionFileBuilder.emptyChunk(0, 0);
        List<String> found = new ArrayList<>();
        StructureStarts.extract(chunk, (id, x, z, bounds) -> found.add(id));
        assertTrue(found.isEmpty());
    }

    private static List<CompoundTag> readAll(Path region) throws Exception {
        List<CompoundTag> out = new ArrayList<>();
        RegionChunkReader.forEachChunk(region, out::add);
        return out;
    }
}
