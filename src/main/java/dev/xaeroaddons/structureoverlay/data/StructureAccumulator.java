package dev.xaeroaddons.structureoverlay.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Assembles structure instances that arrive in several pieces into exactly the shape a
 * local world-save scan produces.
 *
 * <p>Used by the multiplayer path, where the server streams its scan results in batches
 * that may be split across dimensions and repeat an instance (the same start is stored in
 * every chunk of its bounding box, so a re-scan can legitimately send it twice).
 *
 * <p>Free of client classes, file access and FML so it stays unit testable. The netcode
 * calls it from the client main thread only, so it is not thread safe by design.
 */
public final class StructureAccumulator {
    private final Map<ResourceLocation, List<StructureInstance>> byDimension = new HashMap<>();
    private final Map<ResourceLocation, Set<String>> seenByDimension = new HashMap<>();
    private final Map<String, Long> counts = new HashMap<>();
    private final int expected;
    private long received;

    /**
     * @param expected how many instances the sender announced, or 0 when it did not say
     */
    public StructureAccumulator(int expected) {
        this.expected = Math.max(0, expected);
    }

    /** Folds one batch in, de-duplicating against everything received so far. */
    public void accept(List<StructureInstance> instances) {
        for (StructureInstance instance : instances) {
            List<StructureInstance> bucket = byDimension.computeIfAbsent(instance.dimension(), d -> new ArrayList<>());
            Set<String> seen = seenByDimension.computeIfAbsent(instance.dimension(), d -> new HashSet<>());
            if (seen.add(instance.dedupKey())) {
                bucket.add(instance);
                counts.merge(instance.structureId(), 1L, Long::sum);
                received++;
            }
        }
    }

    public long received() {
        return received;
    }

    public int expected() {
        return expected;
    }

    /** 0..100, or -1 when the sender announced no total to measure against. */
    public int percent() {
        if (expected <= 0) {
            return -1;
        }
        return Math.min(100, (int) (received * 100L / expected));
    }

    /**
     * Freezes the accumulated data into a {@link ScanResult}, sorted the same way a local
     * scan sorts it (structure id, then chunk X, then chunk Z).
     */
    public ScanResult finish(long elapsedMs, int reusedFiles) {
        Map<ResourceLocation, List<StructureInstance>> sorted = new HashMap<>();
        for (Map.Entry<ResourceLocation, List<StructureInstance>> entry : byDimension.entrySet()) {
            List<StructureInstance> list = new ArrayList<>(entry.getValue());
            list.sort((a, b) -> {
                int byId = a.structureId().compareTo(b.structureId());
                if (byId != 0) {
                    return byId;
                }
                int byX = Integer.compare(a.startChunkX(), b.startChunkX());
                return byX != 0 ? byX : Integer.compare(a.startChunkZ(), b.startChunkZ());
            });
            sorted.put(entry.getKey(), List.copyOf(list));
        }
        return new ScanResult(Map.copyOf(sorted), Map.copyOf(counts), elapsedMs, reusedFiles);
    }
}
