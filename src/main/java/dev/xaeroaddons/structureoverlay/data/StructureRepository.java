package dev.xaeroaddons.structureoverlay.data;

import dev.xaeroaddons.structureoverlay.config.OverlayConfig;
import dev.xaeroaddons.structureoverlay.element.StructureElement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the scan result for the currently loaded world, tracks scan progress and hands
 * out immutable, filtered element lists to the renderer.
 *
 * <p>All publication happens through {@link #invalidate()} / volatile swaps, so the scan
 * thread never writes data the render thread can observe half-finished.
 */
public final class StructureRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureRepository.class);

    public enum Status {
        IDLE,
        SCANNING,
        READY,
        /** Joined a dedicated server - the world save does not exist on disk here. */
        NO_ACCESS
    }

    private static volatile Status status = Status.IDLE;
    private static volatile ScanResult result = ScanResult.EMPTY;
    private static volatile Path worldRoot;

    private static final AtomicInteger scannedFiles = new AtomicInteger();
    private static final AtomicInteger totalFiles = new AtomicInteger();
    private static final AtomicInteger generation = new AtomicInteger();
    private static final AtomicInteger version = new AtomicInteger();

    private static final Object CACHE_LOCK = new Object();
    private static int cachedVersion = -1;
    private static volatile Map<ResourceLocation, List<StructureElement>> renderCache = Map.of();
    private static volatile List<String> structureIds = List.of();

    private StructureRepository() {
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called whenever the client level changes (join, dimension change, leave).
     *
     * <p>Deliberately takes only a path: this class must stay loadable on a dedicated
     * server, so nothing from {@code net.minecraft.client} may be referenced here.
     */
    public static void onSingleplayerWorld(Path root) {
        startScan(root);
    }

    /** No usable world save (logged out, or a dedicated server world we cannot read). */
    public static void onWorldUnavailable(Status reason) {
        generation.incrementAndGet();
        status = reason;
        result = ScanResult.EMPTY;
        worldRoot = null;
        invalidate();
    }

    /** Starts (or restarts) a background scan of the given world folder. */
    public static synchronized void startScan(Path root) {
        if (root == null || !java.nio.file.Files.isDirectory(root)) {
            status = Status.NO_ACCESS;
            invalidate();
            return;
        }
        if (status == Status.SCANNING && root.equals(worldRoot)) {
            return;
        }
        worldRoot = root;
        status = Status.SCANNING;
        scannedFiles.set(0);
        totalFiles.set(0);
        invalidate();

        int myGeneration = generation.incrementAndGet();
        Thread worker = new Thread(() -> runScan(root, myGeneration), "structureoverlay-scan");
        worker.setDaemon(true);
        worker.start();
    }

    /** Re-scan with the same world (menu "Rescan" button). */
    public static void rescan() {
        Path root = worldRoot;
        if (root != null) {
            startScan(root);
        }
    }

    private static void runScan(Path root, int myGeneration) {
        try {
            ScanResult scanned = StructureScanner.scan(
                root,
                new StructureScanner.Progress() {
                    @Override
                    public void totalFiles(int total) {
                        totalFiles.set(total);
                    }

                    @Override
                    public void fileDone() {
                        scannedFiles.incrementAndGet();
                    }
                },
                () -> generation.get() != myGeneration
            );
            if (generation.get() == myGeneration) {
                result = scanned;
                status = Status.READY;
                LOGGER.info("Structure scan finished: {} structures of {} types in {} ms",
                    scanned.totalStructures(), scanned.counts().size(), scanned.elapsedMs());
            }
        } catch (Exception e) {
            LOGGER.error("Structure scan failed", e);
            if (generation.get() == myGeneration) {
                status = Status.IDLE;
            }
        } finally {
            invalidate();
        }
    }

    // ------------------------------------------------------------------ read API

    /** Bumps the change counter; invalidates the filtered render cache. */
    public static void invalidate() {
        version.incrementAndGet();
    }

    /**
     * Markers for one dimension, filtered by the master toggle and the per-type toggles.
     * Empty until a scan has completed. Safe to call from the render thread.
     */
    public static List<StructureElement> renderable(ResourceLocation dimensionId) {
        ensureFresh();
        return renderCache.getOrDefault(dimensionId, List.of());
    }

    private static void ensureFresh() {
        int current = version.get();
        if (current != cachedVersion) {
            synchronized (CACHE_LOCK) {
                if (current != cachedVersion) {
                    rebuildCache();
                    cachedVersion = current;
                }
            }
        }
    }

    private static void rebuildCache() {
        // The type list is needed by the menu even while the overlay is switched off.
        structureIds = List.copyOf(new TreeSet<>(result.counts().keySet()));

        OverlayConfig config = OverlayConfig.get();
        if (!config.enabled) {
            renderCache = Map.of();
            return;
        }
        Map<ResourceLocation, List<StructureElement>> cache = new HashMap<>();
        for (Map.Entry<ResourceLocation, List<StructureInstance>> entry : result.byDimension().entrySet()) {
            List<StructureElement> elements = new ArrayList<>();
            for (StructureInstance instance : entry.getValue()) {
                if (!config.isStructureEnabled(instance.structureId())) {
                    continue;
                }
                elements.add(new StructureElement(
                    instance.dimension(),
                    instance.structureId(),
                    StructureNames.displayName(instance.structureId()),
                    instance.x(),
                    instance.z(),
                    instance.bounds()
                ));
            }
            cache.put(entry.getKey(), List.copyOf(elements));
        }
        renderCache = Map.copyOf(cache);
    }

    /** Every structure type seen in this world, sorted. */
    public static List<String> structureIds() {
        ensureFresh();
        return structureIds;
    }

    public static long countFor(String structureId) {
        return result.counts().getOrDefault(structureId, 0L);
    }

    public static long totalStructures() {
        return result.totalStructures();
    }

    public static int structureTypeCount() {
        return result.counts().size();
    }

    public static Status status() {
        return status;
    }

    /** 0..100 while scanning, or -1 when no scan is running. */
    public static int progressPercent() {
        if (status != Status.SCANNING) {
            return -1;
        }
        int total = totalFiles.get();
        if (total <= 0) {
            return 0;
        }
        return Math.min(100, (int) (scannedFiles.get() * 100L / total));
    }

    public static int dimensionCount() {
        return result.byDimension().size();
    }
}
