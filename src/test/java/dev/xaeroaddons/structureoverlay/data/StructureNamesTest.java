package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StructureNamesTest {

    @Test
    void humanizesVanillaIds() {
        assertEquals("Village Plains", StructureNames.displayName("minecraft:village_plains"));
        assertEquals("Simple Dungeon", StructureNames.displayName("minecraft:simple_dungeon"));
        assertEquals("Ancient City", StructureNames.displayName("minecraft:ancient_city"));
        assertEquals("Stronghold", StructureNames.displayName("minecraft:stronghold"));
    }

    @Test
    void keepsForeignNamespacesVisible() {
        assertEquals("Sky Castle", StructureNames.displayName("myaddo:sky_castle"));
        assertEquals("myaddo:sky_castle", StructureNames.shortName("myaddo:sky_castle"));
        assertEquals("village_plains", StructureNames.shortName("minecraft:village_plains"));
    }

    @Test
    void handlesOddIds() {
        assertEquals("Trail Ruins", StructureNames.displayName("minecraft:trail_ruins"));
        assertEquals("Weird Id", StructureNames.displayName("weird__id"));
        assertEquals("Minecraft Villager outpost", StructureNames.displayName("Minecraft Villager outpost"));
    }
}
