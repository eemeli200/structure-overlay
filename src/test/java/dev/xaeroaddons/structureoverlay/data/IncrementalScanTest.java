package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xaeroaddons.structureoverlay.testutil.RegionFileBuilder;
import dev.xaeroaddons.structureoverlay.testutil.RegionFileBuilder.Chunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The incremental scan cache: unchanged region files are answered from the cache,
 * changed/new ones are re-read, and the cache survives a "game restart"
 * (simulated with {@link StructureScanner#clearCache()}).
 */
class IncrementalScanTest {

    private static final StructureScanner.Progress NO_PROGRESS = new StructureScanner.Progress() {
        @Override
        public void totalFiles(int total) {
        }

        @Override
        public void fileDone() {
        }
    };

    @TempDir
    Path worldRoot;

    private ScanResult scan() {
        return StructureScanner.scan(worldRoot, NO_PROGRESS, () -> false);
    }

    private Path writeRegion(String name, String... structureIds) throws Exception {
        Path file = worldRoot.resolve("region/" + name);
        Chunk[] chunks = new Chunk[structureIds.length];
        for (int i = 0; i < structureIds.length; i++) {
            chunks[i] = Chunk.zlib(RegionFileBuilder.chunkWithStructureStart(
                structureIds[i], i * 16, 0, i * 16, 0));
        }
        RegionFileBuilder.write(file, chunks);
        return file;
    }

    @Test
    void reusesUnchangedFilesAndPicksUpChangedOnes() throws Exception {
        Path changed = writeRegion("r.0.0.mca", "minecraft:village_plains");
        writeRegion("r.1.0.mca", "minecraft:mineshaft");

        ScanResult cold = scan();
        assertEquals(0, cold.reusedFiles(), "first scan reads everything");
        assertEquals(2, cold.totalStructures());
        assertTrue(StructureScanner.hasPersistedCache(worldRoot), "cache is persisted");

        ScanResult warm = scan();
        assertEquals(2, warm.reusedFiles(), "nothing changed -> nothing re-read");
        assertEquals(2, warm.totalStructures());

        // grow one region file (freshly generated chunks) and move its mtime so the
        // stamp definitely differs from the cached one
        RegionFileBuilder.write(changed,
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:village_plains", 16, 0, 16, 0)),
            Chunk.zlib(RegionFileBuilder.chunkWithStructureStart("minecraft:ancient_city", 32, 0, 32, 0)));
        Files.setLastModifiedTime(changed, FileTime.from(Instant.now().plusSeconds(60)));

        ScanResult updated = scan();
        assertEquals(1, updated.reusedFiles(), "only the changed file is re-read");
        assertEquals(3, updated.totalStructures());
        assertEquals(1L, updated.counts().get("minecraft:ancient_city"));
        assertEquals(1L, updated.counts().get("minecraft:mineshaft"), "unchanged file kept");

        // and the new result is cached now too
        assertEquals(2, scan().reusedFiles());
    }

    @Test
    void cacheSurvivesRestartAndCanBeForced() throws Exception {
        writeRegion("r.0.0.mca", "minecraft:fortress");

        assertEquals(0, scan().reusedFiles());

        StructureScanner.clearCache(); // simulates leaving and re-joining the world
        ScanResult reloaded = scan();
        assertEquals(1, reloaded.reusedFiles(), "cache was loaded from disk");
        assertEquals(1, reloaded.totalStructures());

        // shift-clicking Rescan drops the in-memory cache *and* the persisted file
        StructureScanner.clearCache(worldRoot);
        ScanResult forced = scan();
        assertEquals(0, forced.reusedFiles(), "forced scan re-reads every file");
        assertEquals(1, forced.totalStructures());
        assertTrue(StructureScanner.hasPersistedCache(worldRoot), "cache re-saved afterwards");
    }

    @Test
    void deletingARegionFileDropsItsStructures() throws Exception {
        Path doomed = writeRegion("r.0.0.mca", "minecraft:village_plains");
        writeRegion("r.1.0.mca", "minecraft:mineshaft");

        assertEquals(2, scan().totalStructures());

        Files.delete(doomed);
        ScanResult after = scan();
        assertEquals(1, after.reusedFiles());
        assertEquals(1, after.totalStructures());
        assertEquals(1L, after.counts().get("minecraft:mineshaft"));
    }
}
