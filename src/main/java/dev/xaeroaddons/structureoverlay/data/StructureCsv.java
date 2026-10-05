package dev.xaeroaddons.structureoverlay.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders a scan result as CSV so the discovered structures can be used outside the game
 * (spreadsheets, world planners, bug reports).
 *
 * <p>Columns: dimension, structure id, display name, start chunk centre in block
 * coordinates, and the footprint rectangle when the chunk data carried one.
 */
public final class StructureCsv {
    public static final String HEADER =
        "dimension,structure_id,name,start_x,start_z,min_x,min_z,max_x,max_z";

    private StructureCsv() {
    }

    /** Full CSV document (header + one row per structure, sorted by dimension and id). */
    public static String render(ScanResult result) {
        List<String> rows = new ArrayList<>();
        rows.add(HEADER);
        List<StructureInstance> all = new ArrayList<>();
        for (List<StructureInstance> instances : result.byDimension().values()) {
            all.addAll(instances);
        }
        all.sort(Comparator
            .comparing((StructureInstance i) -> i.dimension().toString())
            .thenComparing(StructureInstance::structureId)
            .thenComparingInt(StructureInstance::startChunkX)
            .thenComparingInt(StructureInstance::startChunkZ));

        for (StructureInstance instance : all) {
            rows.add(row(instance));
        }
        return String.join("\n", rows) + "\n";
    }

    private static String row(StructureInstance instance) {
        BlockBox box = instance.bounds();
        return String.join(",",
            escape(instance.dimension().toString()),
            escape(instance.structureId()),
            escape(StructureNames.displayName(instance.structureId())),
            Long.toString(Math.round(instance.x())),
            Long.toString(Math.round(instance.z())),
            box == null ? "" : Integer.toString(box.x1()),
            box == null ? "" : Integer.toString(box.z1()),
            box == null ? "" : Integer.toString(box.x2()),
            box == null ? "" : Integer.toString(box.z2())
        );
    }

    /** RFC 4180 quoting for the (rare) values containing a comma or quote. */
    private static String escape(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
