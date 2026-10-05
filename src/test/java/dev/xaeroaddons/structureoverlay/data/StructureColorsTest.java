package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StructureColorsTest {

    @Test
    void hashColorIsStableOpaqueAndSpreadsOverHues() {
        int village = StructureColors.hashColor("minecraft:village_plains");
        assertEquals(village, StructureColors.hashColor("minecraft:village_plains"));
        assertEquals(0xFF000000, village & 0xFF000000, "markers are opaque");

        assertNotEquals(village, StructureColors.hashColor("minecraft:ancient_city"));
        assertNotEquals(StructureColors.hashColor("minecraft:fortress"),
            StructureColors.hashColor("minecraft:stronghold"));
    }

    @Test
    void overrideWinsOverTheHash() {
        Map<String, String> overrides = Map.of("minecraft:village_plains", "#123AB0");
        assertEquals(0xFF123AB0, StructureColors.colorFor("minecraft:village_plains", overrides));
        // untouched ids keep their hashed colour
        assertEquals(StructureColors.hashColor("minecraft:ancient_city"),
            StructureColors.colorFor("minecraft:ancient_city", overrides));
        assertEquals(StructureColors.hashColor("minecraft:fortress"),
            StructureColors.colorFor("minecraft:fortress", null));
    }

    @Test
    void invalidOverrideFallsBackToTheHash() {
        Map<String, String> overrides = Map.of("minecraft:village_plains", "not a colour");
        assertEquals(StructureColors.hashColor("minecraft:village_plains"),
            StructureColors.colorFor("minecraft:village_plains", overrides));
        Map<String, String> tooShort = Map.of("minecraft:village_plains", "#12345");
        assertEquals(StructureColors.hashColor("minecraft:village_plains"),
            StructureColors.colorFor("minecraft:village_plains", tooShort));
    }

    @Test
    void parsesHexInBothFormats() {
        assertEquals(0x123AB0, StructureColors.parseHex("#123AB0"));
        assertEquals(0x123AB0, StructureColors.parseHex("123ab0"));
        assertEquals(0x000000, StructureColors.parseHex("  #000000 "));
        assertNull(StructureColors.parseHex("#12345"));
        assertNull(StructureColors.parseHex("#GGGGGG"));
        assertNull(StructureColors.parseHex(""));
        assertNull(StructureColors.parseHex(null));
    }

    @Test
    void toHexAndParseRoundTrip() {
        for (int id = 0; id < 50; id++) {
            String structureId = "minecraft:structure_" + id;
            String hex = StructureColors.toHex(StructureColors.hashColor(structureId));
            assertTrue(hex.matches("#[0-9A-F]{6}"), hex);
            assertEquals(StructureColors.hashColor(structureId) & 0xFFFFFF,
                StructureColors.parseHex(hex));
        }
    }

    @Test
    void randomHexProducesAUsableColour() {
        for (int i = 0; i < 100; i++) {
            String hex = StructureColors.randomHex();
            assertNotNull(StructureColors.parseHex(hex), hex);
            assertTrue(hex.matches("#[0-9A-F]{6}"), hex);
        }
    }
}
