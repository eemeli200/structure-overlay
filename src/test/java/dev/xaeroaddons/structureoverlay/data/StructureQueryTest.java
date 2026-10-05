package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

class StructureQueryTest {

    private static final List<String> IDS = List.of(
        "minecraft:village_plains",
        "minecraft:ancient_city",
        "minecraft:simple_dungeon",
        "create_mod:rustic_watchtower");

    private static final ToLongFunction<String> COUNTS = id -> switch (id) {
        case "minecraft:village_plains" -> 10L;
        case "minecraft:ancient_city" -> 2L;
        case "minecraft:simple_dungeon" -> 2L;
        default -> 0L;
    };

    @Test
    void blankQueryKeepsEverything() {
        assertEquals(IDS, StructureQuery.filter(IDS, ""));
        assertEquals(IDS, StructureQuery.filter(IDS, "   "));
        assertEquals(IDS, StructureQuery.filter(IDS, null));
    }

    @Test
    void filtersCaseInsensitivelyOverIdAndDisplayName() {
        // raw id
        assertEquals(List.of("minecraft:village_plains"),
            StructureQuery.filter(IDS, "VILLAGE"));
        // display name only: "Simple Dungeon" contains a space, the id an underscore
        assertEquals(List.of("minecraft:simple_dungeon"),
            StructureQuery.filter(IDS, "simple dungeon"));
        // display name only: capitalised word
        assertEquals(List.of("minecraft:ancient_city"),
            StructureQuery.filter(IDS, "Ancient Ci"));
        assertEquals(List.of("create_mod:rustic_watchtower"),
            StructureQuery.filter(IDS, "rustic"));
        assertTrue(StructureQuery.filter(IDS, "nobodyhome").isEmpty());
    }

    @Test
    void preservesInputOrderWhileFiltering() {
        List<String> filtered = StructureQuery.filter(IDS, "minecraft:");
        assertEquals(List.of(
            "minecraft:village_plains",
            "minecraft:ancient_city",
            "minecraft:simple_dungeon"), filtered);
    }

    @Test
    void sortsByDisplayNameIgnoringCase() {
        List<String> sorted = StructureQuery.sort(IDS, StructureQuery.SORT_NAME, COUNTS);
        assertEquals(List.of(
            "minecraft:ancient_city",         // Ancient City
            "create_mod:rustic_watchtower",   // Rustic Watchtower
            "minecraft:simple_dungeon",       // Simple Dungeon
            "minecraft:village_plains"),      // Village Plains
            sorted);
    }

    @Test
    void sortsByCountDescendingWithNamesAsTieBreak() {
        List<String> sorted = StructureQuery.sort(IDS, StructureQuery.SORT_COUNT, COUNTS);
        assertEquals("minecraft:village_plains", sorted.get(0), "10 instances first");
        // 2 instances each -> ancient_city before simple_dungeon by name
        assertEquals("minecraft:ancient_city", sorted.get(1));
        assertEquals("minecraft:simple_dungeon", sorted.get(2));
        assertEquals("create_mod:rustic_watchtower", sorted.get(3), "0 instances last");
    }

    @Test
    void sortsByModNamespaceThenName() {
        List<String> sorted = StructureQuery.sort(IDS, StructureQuery.SORT_MOD, COUNTS);
        assertEquals("create_mod:rustic_watchtower", sorted.get(0), "own mod group comes first");
        assertEquals("minecraft:ancient_city", sorted.get(1));
        assertEquals("minecraft:simple_dungeon", sorted.get(2));
        assertEquals("minecraft:village_plains", sorted.get(3));
    }

    @Test
    void doesNotMutateTheInputList() {
        List<String> copy = List.copyOf(IDS);
        StructureQuery.sort(IDS, StructureQuery.SORT_COUNT, COUNTS);
        assertEquals(copy, IDS);
    }

    @Test
    void extractsNamespace() {
        assertEquals("minecraft", StructureQuery.namespace("minecraft:village_plains"));
        assertEquals("create_mod", StructureQuery.namespace("create_mod:watchtower"));
        assertEquals("", StructureQuery.namespace("no_namespace"));
    }
}
