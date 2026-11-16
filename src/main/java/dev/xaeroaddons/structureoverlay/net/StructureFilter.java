package dev.xaeroaddons.structureoverlay.net;

import dev.xaeroaddons.structureoverlay.data.StructureInstance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Applies a {@link ServerConfig.Policy} to a finished scan before it is sent to a player.
 *
 * <p>This is the fair-play half of the server config: it decides which structure starts a
 * player is allowed to know about (distance, type), how precisely they are reported
 * (grid snapping, footprints) and how many of them are sent.
 *
 * <p>Kept free of server classes so the rules can be tested without a running server.
 */
final class StructureFilter {

    private StructureFilter() {
    }

    /**
     * @param all       every structure start of the scan
     * @param policy    the resolved server settings
     * @param dimension the dimension the requesting player is in
     * @param x         the player's block X
     * @param z         the player's block Z
     */
    static List<StructureInstance> apply(List<StructureInstance> all, ServerConfig.Policy policy,
                                         ResourceLocation dimension, double x, double z) {
        long limitSq = policy.limitedByDistance()
            ? (long) policy.maxDistance() * policy.maxDistance()
            : -1L;
        List<StructureInstance> kept = new ArrayList<>(all.size());
        for (StructureInstance instance : all) {
            if (policy.disabledTypes().contains(instance.structureId())) {
                continue;
            }
            if (limitSq >= 0) {
                // a distance limit is measured in the player's own dimension; other
                // dimensions are never sent, so a map cannot reveal them from afar
                if (!instance.dimension().equals(dimension)) {
                    continue;
                }
                if (squaredDistance(instance, dimension, x, z) > limitSq) {
                    continue;
                }
            }
            kept.add(coarsen(instance, policy));
        }
        if (policy.maxStructures() > 0 && kept.size() > policy.maxStructures()) {
            if (limitSq >= 0) {
                kept.sort(Comparator.comparingDouble(
                    (StructureInstance instance) -> squaredDistance(instance, dimension, x, z)));
            }
            return List.copyOf(kept.subList(0, policy.maxStructures()));
        }
        return List.copyOf(kept);
    }

    private static double squaredDistance(StructureInstance instance, ResourceLocation dimension,
                                          double x, double z) {
        if (!instance.dimension().equals(dimension)) {
            return Double.MAX_VALUE;
        }
        double dx = instance.x() - x;
        double dz = instance.z() - z;
        return dx * dx + dz * dz;
    }

    /** Drops footprints when the policy forbids them, and snaps positions to the grid. */
    private static StructureInstance coarsen(StructureInstance instance, ServerConfig.Policy policy) {
        int snap = policy.snapTo();
        if (snap <= 0) {
            if (policy.footprints() || !instance.hasBounds()) {
                return instance;
            }
            return new StructureInstance(instance.dimension(), instance.structureId(),
                instance.startChunkX(), instance.startChunkZ(), null);
        }
        // a snapped marker carries no footprint: the box would give the exact place away
        int snappedX = Math.floorDiv((int) Math.round(instance.x()), snap) * snap;
        int snappedZ = Math.floorDiv((int) Math.round(instance.z()), snap) * snap;
        return new StructureInstance(instance.dimension(), instance.structureId(),
            Math.floorDiv(snappedX, 16), Math.floorDiv(snappedZ, 16), null);
    }
}
