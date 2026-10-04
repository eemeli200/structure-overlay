package dev.xaeroaddons.structureoverlay.data;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable result of one world-save scan.
 *
 * @param byDimension structure instances per dimension, sorted by id then position
 * @param counts      how many instances were found per structure type (all dimensions)
 * @param elapsedMs   wall-clock scan duration
 */
public record ScanResult(
    Map<ResourceLocation, List<StructureInstance>> byDimension,
    Map<String, Long> counts,
    long elapsedMs
) {
    public static final ScanResult EMPTY = new ScanResult(Map.of(), Map.of(), 0L);

    public long totalStructures() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }
}
