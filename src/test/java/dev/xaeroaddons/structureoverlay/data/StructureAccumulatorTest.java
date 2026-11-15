package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** The multiplayer path assembles data in batches; this checks it matches a local scan. */
class StructureAccumulatorTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    private static StructureInstance instance(ResourceLocation dimension, String id, int chunkX, int chunkZ) {
        return new StructureInstance(dimension, id, chunkX, chunkZ, new BlockBox(chunkX * 16, chunkZ * 16,
            chunkX * 16 + 10, chunkZ * 16 + 10));
    }

    @Test
    void assemblesInstancesFromSeveralBatches() {
        StructureAccumulator accumulator = new StructureAccumulator(3);
        accumulator.accept(List.of(
            instance(OVERWORLD, "minecraft:village_plains", 1, 2),
            instance(OVERWORLD, "minecraft:monument", 3, 4)));
        accumulator.accept(List.of(instance(NETHER, "minecraft:fortress", 5, 6)));

        ScanResult result = accumulator.finish(120L, 7);

        assertEquals(3, result.totalStructures());
        assertEquals(7, result.reusedFiles());
        assertEquals(120L, result.elapsedMs());
        assertEquals(2, result.byDimension().size());
        assertEquals(2, result.byDimension().get(OVERWORLD).size());
        assertEquals(1, result.byDimension().get(NETHER).size());
        assertEquals(1L, result.counts().get("minecraft:village_plains"));
    }

    @Test
    void deduplicatesRepeatedStarts() {
        StructureInstance repeated = instance(OVERWORLD, "minecraft:village_plains", 8, 9);
        StructureAccumulator accumulator = new StructureAccumulator(1);
        accumulator.accept(List.of(repeated, repeated));
        // a duplicate that arrives in a later batch must be dropped as well
        accumulator.accept(List.of(instance(OVERWORLD, "minecraft:village_plains", 8, 9)));

        ScanResult result = accumulator.finish(0L, 0);

        assertEquals(1, result.totalStructures());
        assertEquals(1L, result.counts().get("minecraft:village_plains"));
    }

    @Test
    void sortsByIdThenPositionLikeALocalScan() {
        StructureAccumulator accumulator = new StructureAccumulator(4);
        accumulator.accept(List.of(
            instance(OVERWORLD, "minecraft:monument", 5, 1),
            instance(OVERWORLD, "minecraft:village_plains", 9, 3),
            instance(OVERWORLD, "minecraft:village_plains", 9, 1),
            instance(OVERWORLD, "minecraft:village_plains", 2, 7)));

        List<StructureInstance> sorted = accumulator.finish(0L, 0).byDimension().get(OVERWORLD);

        assertEquals(List.of(
            "minecraft:monument 5 1",
            "minecraft:village_plains 2 7",
            "minecraft:village_plains 9 1",
            "minecraft:village_plains 9 3"), sorted.stream()
            .map(i -> i.structureId() + ' ' + i.startChunkX() + ' ' + i.startChunkZ())
            .toList());
    }

    @Test
    void reportsProgressAgainstTheAnnouncedTotal() {
        StructureAccumulator accumulator = new StructureAccumulator(4);
        assertEquals(0, accumulator.percent());
        accumulator.accept(List.of(instance(OVERWORLD, "minecraft:village_plains", 0, 0)));
        assertEquals(25, accumulator.percent());
        assertEquals(1L, accumulator.received());

        // no announced total: progress is unknown rather than a bogus percentage
        assertEquals(-1, new StructureAccumulator(0).percent());
    }

    @Test
    void finishWithoutDataIsAnEmptyResult() {
        ScanResult result = new StructureAccumulator(0).finish(0L, 0);
        assertEquals(0, result.totalStructures());
        assertTrue(result.byDimension().isEmpty());
        assertTrue(result.counts().isEmpty());
    }
}
