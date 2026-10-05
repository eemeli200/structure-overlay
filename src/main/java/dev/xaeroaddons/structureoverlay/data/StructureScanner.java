package dev.xaeroaddons.structureoverlay.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *
 * <p>Scans are incremental: every region file's result is cached together with its
 * modification time and size, and the cache is persisted to
 * {@code <world>/structureoverlay-cache.json}. A re-scan therefore only re-reads region
 * files that actually changed (e.g. freshly generated chunks) instead of the whole save -
 * which matters a lot after a Chunky pre-generation run.
 */
public final class StructureScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureScanner.class);
    private static final Gson GSON = new GsonBuilder().create();
    /** Version tag so a future format change can simply discard an old cache file. */
    private static final int CACHE_VERSION = 1;
    static final String CACHE_FILE_NAME = "structureoverlay-cache.json";

    private static final Object CACHE_LOCK = new Object();
    /** Region files of {@link #cachedWorld}, keyed by path relative to the world root. */
    private static final Map<String, CachedFile> FILE_CACHE = new LinkedHashMap<>();
    private static Path cachedWorld;

    private StructureScanner() {
    }

    /** One region file's extracted starts plus the stamp used to detect changes. */
    private record CachedFile(long mtime, long size, List<StructureInstance> instances) {
    }

    // ------------------------------------------------------------------ cache control

    /**
     * Drops the incremental cache. The next scan re-reads every region file (and
     * overwrites the persisted cache).
     */
    public static void clearCache() {
        synchronized (CACHE_LOCK) {
            FILE_CACHE.clear();
            cachedWorld = null;
        }
    }

    /**
     * Like {@link #clearCache()}, but also deletes the persisted cache file of this
     * world - the next scan is guaranteed to re-read every region file from scratch
     * (menu: shift-clicking *Rescan*).
     */
    public static void clearCache(Path worldRoot) {
        Path cacheFile = worldRoot.toAbsolutePath().normalize().resolve(CACHE_FILE_NAME);
        synchronized (CACHE_LOCK) {
            FILE_CACHE.clear();
            cachedWorld = null;
        }
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException e) {
            LOGGER.debug("Could not delete {}", cacheFile, e);
        }
    }

    /** True when the persisted cache file exists for this world. */
    public static boolean hasPersistedCache(Path worldRoot) {
        return Files.isRegularFile(worldRoot.resolve(CACHE_FILE_NAME));
    }

    private static void ensureCacheFor(Path worldRoot) {
        synchronized (CACHE_LOCK) {
            if (worldRoot.equals(cachedWorld)) {
                return;
            }
            FILE_CACHE.clear();
            cachedWorld = worldRoot;
            Path cacheFile = worldRoot.resolve(CACHE_FILE_NAME);
            if (!Files.isRegularFile(cacheFile)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
                DiskCache disk = GSON.fromJson(reader, DiskCache.class);
                if (disk == null || disk.version != CACHE_VERSION || disk.files == null) {
                    return;
                }
                int loaded = 0;
                for (DiskFile file : disk.files) {
                    if (file == null || file.path == null || file.starts == null) {
                        continue;
                    }
                    List<StructureInstance> instances = new ArrayList<>(file.starts.size());
                    for (DiskStart start : file.starts) {
                        if (start == null || start.id == null || start.dim == null) {
                            continue;
                        }
                        try {
                            ResourceLocation dimension = ResourceLocation.parse(start.dim);
                            BlockBox bounds = start.bb == null || start.bb.length < 4
                                ? null
                                : new BlockBox(start.bb[0], start.bb[1], start.bb[2], start.bb[3]);
                            instances.add(new StructureInstance(dimension, start.id, start.cx, start.cz, bounds));
                        } catch (Exception e) {
                            LOGGER.debug("Ignoring bad cache entry {} in {}", start.id, cacheFile);
                        }
                    }
                    FILE_CACHE.put(file.path, new CachedFile(file.mtime, file.size, List.copyOf(instances)));
                    loaded++;
                }
                LOGGER.debug("Loaded structure scan cache: {} region files from {}", loaded, cacheFile);
            } catch (Exception e) {
                LOGGER.debug("Could not read {}, rescanning from scratch", cacheFile, e);
            }
        }
    }

    private static void saveCache(Path worldRoot) {
        DiskCache disk = new DiskCache();
        disk.version = CACHE_VERSION;
        disk.files = new ArrayList<>();
        synchronized (CACHE_LOCK) {
            for (Map.Entry<String, CachedFile> entry : FILE_CACHE.entrySet()) {
                CachedFile cached = entry.getValue();
                if (cached.mtime() < 0) {
                    continue; // never successfully stamped - a cache entry would be useless
                }
                DiskFile file = new DiskFile();
                file.path = entry.getKey();
                file.mtime = cached.mtime();
                file.size = cached.size();
                file.starts = new ArrayList<>(cached.instances().size());
                for (StructureInstance instance : cached.instances()) {
                    DiskStart start = new DiskStart();
                    start.dim = instance.dimension().toString();
                    start.id = instance.structureId();
                    start.cx = instance.startChunkX();
                    start.cz = instance.startChunkZ();
                    start.bb = instance.bounds() == null ? null : new int[]{
                        instance.bounds().x1(), instance.bounds().z1(),
                        instance.bounds().x2(), instance.bounds().z2()
                    };
                    file.starts.add(start);
                }
                disk.files.add(file);
            }
        }
        Path cacheFile = worldRoot.resolve(CACHE_FILE_NAME);
        Path temp = worldRoot.resolve(CACHE_FILE_NAME + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(disk, writer);
            }
            try {
                Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not write {}", cacheFile, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ scanning

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

        Path root = worldRoot.toAbsolutePath().normalize();
        List<RegionFileRef> files = collectRegionFiles(discoverDimensions(root));
        progress.totalFiles(files.size());

        ensureCacheFor(root);

        int reused = 0;
        boolean cacheDirty = false;
        for (RegionFileRef file : files) {
            if (cancelled.isCancelled()) {
                break;
            }
            String key = relativeKey(root, file.path());
            long[] stamp = stamp(file.path());
            List<StructureInstance> instances = null;
            if (stamp[0] >= 0) {
                synchronized (CACHE_LOCK) {
                    CachedFile hit = FILE_CACHE.get(key);
                    if (hit != null && hit.mtime() == stamp[0] && hit.size() == stamp[1]) {
                        instances = hit.instances();
                    }
                }
            }
            if (instances != null) {
                reused++;
            } else {
                instances = readRegionFile(file);
                synchronized (CACHE_LOCK) {
                    FILE_CACHE.put(key, new CachedFile(stamp[0], stamp[1], List.copyOf(instances)));
                }
                cacheDirty = true;
            }
            merge(instances, byDimension, seenByDimension, counts);
            progress.fileDone();
        }

        if (cacheDirty) {
            saveCache(root);
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

        return new ScanResult(byDimension, Map.copyOf(counts), System.currentTimeMillis() - start, reused);
    }

    /** Extracts every structure start stored in one region file (de-duplicated inside it). */
    private static List<StructureInstance> readRegionFile(RegionFileRef file) {
        Map<String, StructureInstance> instances = new LinkedHashMap<>();
        try {
            RegionChunkReader.forEachChunk(file.path(), chunkNbt ->
                StructureStarts.extract(chunkNbt, (id, chunkX, chunkZ, bounds) -> {
                    StructureInstance instance = new StructureInstance(file.dimensionId(), id, chunkX, chunkZ, bounds);
                    // the same start is stored in every chunk of its bounding box, so it
                    // repeats many times inside one region file as well
                    instances.putIfAbsent(instance.dedupKey(), instance);
                }));
        } catch (IOException e) {
            LOGGER.debug("Skipping unreadable region file {}", file.path(), e);
        }
        return List.copyOf(instances.values());
    }

    /** Folds file results into the per-dimension buckets, de-duplicating across files. */
    private static void merge(List<StructureInstance> instances,
                              Map<ResourceLocation, List<StructureInstance>> byDimension,
                              Map<ResourceLocation, Set<String>> seenByDimension,
                              Map<String, Long> counts) {
        for (StructureInstance instance : instances) {
            Set<String> seen = seenByDimension.computeIfAbsent(instance.dimension(), d -> new HashSet<>());
            List<StructureInstance> bucket = byDimension.computeIfAbsent(instance.dimension(), d -> new ArrayList<>());
            if (seen.add(instance.dedupKey())) {
                bucket.add(instance);
                counts.merge(instance.structureId(), 1L, Long::sum);
            }
        }
    }

    /** {@code [mtimeMillis, sizeBytes]}, or {@code [-1, -1]} when the file cannot be read. */
    private static long[] stamp(Path path) {
        try {
            return new long[]{Files.getLastModifiedTime(path).toMillis(), Files.size(path)};
        } catch (IOException e) {
            return new long[]{-1L, -1L};
        }
    }

    private static String relativeKey(Path worldRoot, Path file) {
        try {
            return worldRoot.relativize(file.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    // ------------------------------------------------------------------ layout

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

    // ------------------------------------------------------------------ disk format

    record DimensionSource(ResourceLocation dimensionId, Path regionDir) {
    }

    record RegionFileRef(ResourceLocation dimensionId, Path path) {
    }

    /** Root of {@code structureoverlay-cache.json}. */
    static final class DiskCache {
        int version;
        List<DiskFile> files;
    }

    static final class DiskFile {
        String path;
        long mtime;
        long size;
        List<DiskStart> starts;
    }

    static final class DiskStart {
        String dim;
        String id;
        int cx;
        int cz;
        /** {@code [x1, z1, x2, z2]}, or absent when the start carried no piece boxes. */
        int[] bb;
    }
}
