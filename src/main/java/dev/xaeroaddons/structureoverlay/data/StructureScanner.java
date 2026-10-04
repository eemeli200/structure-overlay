package dev.xaeroaddons.structureoverlay.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans a vanilla world save's region files for structure starts.
 *
 * <p>Runs off-thread. Dimension folders are resolved the same way vanilla's
 * {@code DimensionType.getStorageFolder} does: overworld at the world root, the nether in
 * {@code DIM-1}, the end in {@code DIM1}, custom dimensions under
 * {@code dimensions/<namespace>/<path>}.
 */
public final class StructureScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureScanner.class);

    private StructureScanner() {
    }

    /** Progress callbacks; invoked from the scan thread. */
    public interface Progress {
        void totalFiles(int total);

        void fileDone();
    }

    /** Cancellation probe polled between region files. */
    public interface CancelSignal {
        boolean isCancelled();
    }

    /**
     * @param worldRoot the singleplayer world folder (the one that contains {@code region/})
     */
    public static ScanResult scan(Path worldRoot, Progress progress, CancelSignal cancelled) {
        long start = System.currentTimeMillis();
        Map<ResourceLocation, List<StructureInstance>> byDimension = new HashMap<>();
        Map<ResourceLocation, Set<String>> seenByDimension = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();

        List<RegionFileRef> files = collectRegionFiles(discoverDimensions(worldRoot));
        progress.totalFiles(files.size());

        for (RegionFileRef file : files) {
            if (cancelled.isCancelled()) {
                break;
            }
            scanRegionFile(file, byDimension, seenByDimension, counts);
            progress.fileDone();
        }

        for (Map.Entry<ResourceLocation, List<StructureInstance>> entry : byDimension.entrySet()) {
            List<StructureInstance> sorted = new ArrayList<>(entry.getValue());
            sorted.sort((a, b) -> {
                int byId = a.structureId().compareTo(b.structureId());
                if (byId != 0) {
                    return byId;
                }
                int byX = Integer.compare(a.startChunkX(), b.startChunkX());
                return byX != 0 ? byX : Integer.compare(a.startChunkZ(), b.startChunkZ());
            });
            entry.setValue(List.copyOf(sorted));
        }

        return new ScanResult(byDimension, Map.copyOf(counts), System.currentTimeMillis() - start);
    }

    private static void scanRegionFile(RegionFileRef file,
                                       Map<ResourceLocation, List<StructureInstance>> byDimension,
                                       Map<ResourceLocation, Set<String>> seenByDimension,
                                       Map<String, Long> counts) {
        ResourceLocation dimension = file.dimensionId();
        // A structure start is duplicated into every chunk of its bounding box, and large
        // structures (mineshafts, trail ruins) span several region files - so de-duplication
        // has to happen per dimension, not per file.
        Set<String> seen = seenByDimension.computeIfAbsent(dimension, d -> new HashSet<>());
        List<StructureInstance> bucket = byDimension.computeIfAbsent(dimension, d -> new ArrayList<>());
        try {
            RegionChunkReader.forEachChunk(file.path(), chunkNbt ->
                StructureStarts.extract(chunkNbt, (id, chunkX, chunkZ, bounds) -> {
                    StructureInstance instance = new StructureInstance(dimension, id, chunkX, chunkZ, bounds);
                    if (seen.add(instance.dedupKey())) {
                        bucket.add(instance);
                        counts.merge(id, 1L, Long::sum);
                    }
                }));
        } catch (IOException e) {
            LOGGER.debug("Skipping unreadable region file {}", file.path(), e);
        }
    }

    /** All region folders of all dimensions present in the save. */
    static List<DimensionSource> discoverDimensions(Path worldRoot) {
        List<DimensionSource> dimensions = new ArrayList<>();
        addDimension(dimensions, Level.OVERWORLD.location(), worldRoot);
        addDimension(dimensions, Level.NETHER.location(), worldRoot.resolve("DIM-1"));
        addDimension(dimensions, Level.END.location(), worldRoot.resolve("DIM1"));

        Path customRoot = worldRoot.resolve("dimensions");
        if (Files.isDirectory(customRoot)) {
            try (Stream<Path> walk = Files.walk(customRoot, 4)) {
                walk.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals("region"))
                    .forEach(regionDir -> {
                        Path dimensionRoot = regionDir.getParent();
                        if (dimensionRoot == null || dimensionRoot.equals(customRoot)) {
                            return;
                        }
                        // dimensions/<namespace>/<path...> where path may itself contain '/'
                        String relative = customRoot.relativize(dimensionRoot)
                            .toString().replace(File.separatorChar, '/');
                        int separator = relative.indexOf('/');
                        if (separator <= 0 || separator == relative.length() - 1) {
                            return;
                        }
                        String namespace = relative.substring(0, separator);
                        String path = relative.substring(separator + 1);
                        try {
                            addDimension(dimensions, ResourceLocation.fromNamespaceAndPath(namespace, path), dimensionRoot);
                        } catch (Exception e) {
                            LOGGER.debug("Ignoring dimension folder {}", dimensionRoot, e);
                        }
                    });
            } catch (IOException e) {
                LOGGER.debug("Could not walk custom dimension folders", e);
            }
        }
        return dimensions;
    }

    private static void addDimension(List<DimensionSource> dimensions, ResourceLocation id, Path dimensionRoot) {
        Path regionDir = dimensionRoot.resolve("region").toAbsolutePath().normalize();
        if (RegionChunkReader.isRegionDirectory(regionDir)) {
            dimensions.add(new DimensionSource(id, regionDir));
        }
    }

    private static List<RegionFileRef> collectRegionFiles(List<DimensionSource> dimensions) {
        List<RegionFileRef> files = new ArrayList<>();
        for (DimensionSource dimension : dimensions) {
            try (Stream<Path> list = Files.list(dimension.regionDir())) {
                list.filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .forEach(p -> files.add(new RegionFileRef(dimension.dimensionId(), p)));
            } catch (IOException e) {
                LOGGER.debug("Could not list region files in {}", dimension.regionDir(), e);
            }
        }
        return files;
    }

    record DimensionSource(ResourceLocation dimensionId, Path regionDir) {
    }

    record RegionFileRef(ResourceLocation dimensionId, Path path) {
    }
}
