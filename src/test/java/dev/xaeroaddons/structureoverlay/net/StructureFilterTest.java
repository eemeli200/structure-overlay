package dev.xaeroaddons.structureoverlay.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xaeroaddons.structureoverlay.data.BlockBox;
import dev.xaeroaddons.structureoverlay.data.StructureInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** What a server is allowed to tell a player, and how precisely. */
class StructureFilterTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static final BlockBox FOOTPRINT = new BlockBox(-20, -20, 20, 20);

    private static ServerConfig.Policy policy(int maxDistance, int snapTo, boolean footprints,
                                              int maxStructures, Set<String> disabled) {
        return new ServerConfig.Policy(true, maxDistance, snapTo, footprints, true, 0,
            maxStructures, disabled);
    }

    private static StructureInstance at(ResourceLocation dimension, int chunkX, int chunkZ) {
        return new StructureInstance(dimension, "minecraft:village_plains", chunkX, chunkZ, FOOTPRINT);
    }

    @Test
    void distanceLimitKeepsOnlyNearbyStartsOfThePlayersDimension() {
        List<StructureInstance> all = List.of(
            at(OVERWORLD, 10, 10),      // ~236 blocks away
            at(OVERWORLD, 400, 400),    // ~9,000 blocks away
            at(NETHER, 10, 10));        // nearby, but another dimension

        List<StructureInstance> kept = StructureFilter.apply(all, policy(1500, 0, true, 0, Set.of()),
            OVERWORLD, 8, 8);

        assertEquals(1, kept.size());
        assertEquals(10, kept.get(0).startChunkX());
        assertEquals(OVERWORLD, kept.get(0).dimension());
    }

    @Test
    void unlimitedDistanceKeepsEveryDimension() {
        List<StructureInstance> all = List.of(
            at(OVERWORLD, 10, 10), at(NETHER, 5000, 5000));

        List<StructureInstance> kept = StructureFilter.apply(all, policy(0, 0, true, 0, Set.of()),
            OVERWORLD, 0, 0);

        assertEquals(2, kept.size());
    }

    @Test
    void snappingCoarsensThePositionAndDropsTheFootprint() {
        // chunk (5,5) -> block (88,88); a 32-block grid puts it on block 64 -> chunk 4
        List<StructureInstance> kept = StructureFilter.apply(List.of(at(OVERWORLD, 5, 5)),
            policy(0, 32, true, 0, Set.of()), OVERWORLD, 0, 0);

        assertEquals(1, kept.size());
        assertEquals(4, kept.get(0).startChunkX());
        assertEquals(4, kept.get(0).startChunkZ());
        assertNull(kept.get(0).bounds(), "a coarse position must not carry an exact footprint");
    }

    @Test
    void footprintsCanBeSwitchedOffWithoutMovingTheMarker() {
        List<StructureInstance> kept = StructureFilter.apply(List.of(at(OVERWORLD, 5, 5)),
            policy(0, 0, false, 0, Set.of()), OVERWORLD, 0, 0);

        assertEquals(1, kept.size());
        assertEquals(5, kept.get(0).startChunkX());
        assertFalse(kept.get(0).hasBounds());
    }

    @Test
    void blockedTypesAreNeverSent() {
        List<StructureInstance> all = List.of(
            new StructureInstance(OVERWORLD, "minecraft:ancient_city", 10, 10, FOOTPRINT),
            new StructureInstance(OVERWORLD, "minecraft:village_plains", 10, 10, FOOTPRINT));

        List<StructureInstance> kept = StructureFilter.apply(all,
            policy(0, 0, true, 0, Set.of("minecraft:ancient_city")), OVERWORLD, 0, 0);

        assertEquals(1, kept.size());
        assertEquals("minecraft:village_plains", kept.get(0).structureId());
    }

    @Test
    void capKeepsTheNearestStarts() {
        List<StructureInstance> all = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            all.add(new StructureInstance(OVERWORLD, "minecraft:village_plains", i * 100, 0, FOOTPRINT));
        }

        List<StructureInstance> kept = StructureFilter.apply(all, policy(100_000, 0, true, 2, Set.of()),
            OVERWORLD, 0, 0);

        assertEquals(2, kept.size());
        assertEquals(100, kept.get(0).startChunkX());
        assertEquals(200, kept.get(1).startChunkX());
    }

    @Test
    void capKeepsInputOrderWhenThereIsNoDistanceLimit() {
        List<StructureInstance> all = List.of(
            at(OVERWORLD, 900, 0), at(OVERWORLD, 1, 0), at(OVERWORLD, 500, 0));

        List<StructureInstance> kept = StructureFilter.apply(all, policy(0, 0, true, 2, Set.of()),
            OVERWORLD, 0, 0);

        assertEquals(2, kept.size());
        assertEquals(900, kept.get(0).startChunkX());
        assertTrue(kept.get(1).startChunkX() == 1);
    }
}
