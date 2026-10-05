package dev.xaeroaddons.structureoverlay.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StructureCsvTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");

    @Test
    void rendersHeaderAndRowsSortedByDimensionThenId() {
        StructureInstance village = new StructureInstance(OVERWORLD,
            "minecraft:village_plains", 1, 2, new BlockBox(-10, -20, 30, 40));
        StructureInstance mineshaft = new StructureInstance(OVERWORLD,
            "minecraft:mineshaft", 0, 0, null);
        ScanResult result = new ScanResult(
            Map.of(OVERWORLD, List.of(village, mineshaft)),
            Map.of("minecraft:village_plains", 1L, "minecraft:mineshaft", 1L),
            5L, 0);

        String csv = StructureCsv.render(result);
        assertTrue(csv.endsWith("\n"));
        String[] lines = csv.split("\n");
        assertEquals(3, lines.length);
        assertEquals(StructureCsv.HEADER, lines[0]);
        // mineshaft sorts before village_plains; no footprint -> empty box columns
        assertEquals("minecraft:overworld,minecraft:mineshaft,Mineshaft,8,8,,,,", lines[1]);
        // marker centre is the start chunk centre (1*16+8, 2*16+8)
        assertEquals("minecraft:overworld,minecraft:village_plains,Village Plains,24,40,-10,-20,30,40",
            lines[2]);
    }

    @Test
    void emptyResultStillYieldsTheHeader() {
        assertEquals(StructureCsv.HEADER + "\n", StructureCsv.render(ScanResult.EMPTY));
    }

    @Test
    void quotesValuesContainingCommas() {
        StructureInstance odd = new StructureInstance(OVERWORLD, "odd,id", 0, 0, null);
        ScanResult result = new ScanResult(
            Map.of(OVERWORLD, List.of(odd)), Map.of("odd,id", 1L), 0L, 0);

        String csv = StructureCsv.render(result);
        assertTrue(csv.contains(",\"odd,id\","), csv);
        assertTrue(csv.contains(",\"Odd,id\","), csv);
    }
}
