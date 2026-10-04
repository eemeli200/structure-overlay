package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xaeroaddons.structureoverlay.testutil.RegionFileBuilder;
import dev.xaeroaddons.structureoverlay.testutil.RegionFileBuilder.Chunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureScannerTest {

    @TempDir
    Path worldRoot;

    private static final StructureScanner.Progress NO_PROGRESS = new StructureScanner.Progress() {
        @Override
        public void totalFiles(int total) {
        }

        @Override
        public void fileDone() {
        }
    };

    @Test
    void scansOverworldNetherAndCustomDimensionsAndDeduplicates() throws Exception {
        RegionFileBuilder.write(worldRoot.resolve("region/r.0.0.mca"),
            // the same start stored in two chunks (as vanilla does for every chunk of the
            // bounding box) must be counted once
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:village_plains", 8, 8, 8, 8)),
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:village_plains", 9, 8, 8, 8)),
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:mineshaft", 1, 1, 1, 1)));

        RegionFileBuilder.write(worldRoot.resolve("DIM-1/region/r.0.0.mca"),
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:fortress", 2, 2, 2, 2)));

        RegionFileBuilder.write(worldRoot.resolve("dimensions/myaddo/skyregion/region/r.0.0.mca"),
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("myaddo:sky_castle", 3, 3, 3, 3)));

        AtomicInteger totalReported = new AtomicInteger();
        ScanResult result = StructureScanner.scan(worldRoot, new StructureScanner.Progress() {
            @Override
            public void totalFiles(int total) {
                totalReported.set(total);
            }

            @Override
            public void fileDone() {
            }
        }, () -> false);

        assertEquals(3, totalReported.get());

        ResourceLocation overworld = ResourceLocation.parse("minecraft:overworld");
        ResourceLocation nether = ResourceLocation.parse("minecraft:the_nether");
        ResourceLocation custom = ResourceLocation.parse("myaddo:skyregion");

        List<StructureInstance> overworldStructures = result.byDimension().get(overworld);
        assertEquals(2, overworldStructures.size(), "duplicate start must be de-duplicated per dimension");
        assertEquals(1, result.byDimension().get(nether).size());
        assertEquals(1, result.byDimension().get(custom).size());

        assertEquals(4L, result.totalStructures());
        // two chunks stored the same village start -> one structure, one count
        assertEquals(1L, result.counts().get("minecraft:village_plains"));
        assertEquals(1L, result.counts().get("myaddo:sky_castle"));

        StructureInstance village = overworldStructures.get(0); // sorted by id: mineshaft < village_plains
        assertEquals("minecraft:mineshaft", village.structureId());
    }

    @Test
    void cancelsEarly() throws Exception {
        for (int i = 0; i < 4; i++) {
            RegionFileBuilder.write(worldRoot.resolve("region/r." + i + ".0.mca"),
                Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:igloo", i * 32, 0, i * 32, 0)));
        }
        AtomicInteger filesDone = new AtomicInteger();
        ScanResult result = StructureScanner.scan(worldRoot, new StructureScanner.Progress() {
            @Override
            public void totalFiles(int total) {
            }

            @Override
            public void fileDone() {
                filesDone.incrementAndGet();
            }
        }, () -> filesDone.get() >= 1);

        assertEquals(1, filesDone.get());
        assertTrue(result.totalStructures() < 4);
    }

    @Test
    void worldWithoutRegionFolderYieldsNothing() {
        ScanResult result = StructureScanner.scan(worldRoot, NO_PROGRESS, () -> false);
        assertTrue(result.byDimension().isEmpty());
        assertEquals(0, result.totalStructures());
    }

    @Test
    void extractsAndUnionsPieceBoundingBoxes() throws Exception {
        RegionFileBuilder.write(worldRoot.resolve("region/r.0.0.mca"),
            // piece 1: x -100..-80, z -60..-40
            // piece 2: same box written min/max reversed -> must be normalised
            // union:   x -100..-70, z -60..-10
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:ancient_city", 0, 0, 1, 1,
                new int[]{-100, -50, -60, -80, -40, -40},
                new int[]{-70, -40, -10, -90, -50, -20})),
            // a start without Children carries no footprint
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:village_plains", 1, 0, 2, 2)));

        ScanResult result = StructureScanner.scan(worldRoot, NO_PROGRESS, () -> false);
        List<StructureInstance> overworld = result.byDimension().get(ResourceLocation.parse("minecraft:overworld"));

        StructureInstance city = find(overworld, "minecraft:ancient_city");
        assertEquals(new BlockBox(-100, -60, -70, -10), city.bounds());
        assertEquals(31, city.bounds().width());
        assertEquals(51, city.bounds().depth());
        assertTrue(city.hasBounds());

        StructureInstance village = find(overworld, "minecraft:village_plains");
        assertNull(village.bounds());
    }

    private static StructureInstance find(List<StructureInstance> instances, String structureId) {
        for (StructureInstance instance : instances) {
            if (instance.structureId().equals(structureId)) {
                return instance;
            }
        }
        throw new AssertionError("missing structure " + structureId);
    }
}
