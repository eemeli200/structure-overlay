package dev.xaeroaddons.structureoverlay.data;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable result of one world-save scan.
 *
 * @param byDimension  structure instances per dimension, sorted by id then position
 * @param counts       how many instances were found per structure type (all dimensions)
 * @param elapsedMs    wall-clock scan duration
 * @param reusedFiles  region files answered from the incremental cache instead of being
 *                     re-read (0 on a cold scan)
 */
public record ScanResult(
    Map<ResourceLocation, List<StructureInstance>> byDimension,
    Map<String, Long> counts,
    long elapsedMs,
    int reusedFiles
) {
    public static final ScanResult EMPTY = new ScanResult(Map.of(), Map.of(), 0L, 0);

    public long totalStructures() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }
}
