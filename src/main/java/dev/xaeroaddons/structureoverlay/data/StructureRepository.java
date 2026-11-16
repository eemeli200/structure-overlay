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
        /** Connected to a server that runs this addon: it scans and streams the data. */
        REMOTE,
        /** No structure data available - logged out, or a server without the addon. */
        NO_ACCESS
    }

    private static volatile Status status = Status.IDLE;
    private static volatile ScanResult result = ScanResult.EMPTY;
    private static volatile Path worldRoot;
    /** Set while structure data is arriving from a server. */
    private static volatile StructureAccumulator remote;
    /** True when the current state came from a server rather than a local scan. */
    private static volatile boolean remoteSourced;
    /** Why the server sent nothing, e.g. {@code DISABLED} when its fair-play config is off. */
    private static volatile String unavailableReason;

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

    /** No usable world save (logged out, or a server that does not provide the data). */
    public static void onWorldUnavailable(Status reason) {
        generation.incrementAndGet();
        status = reason;
        result = ScanResult.EMPTY;
        worldRoot = null;
        remote = null;
        remoteSourced = false;
        unavailableReason = null;
        invalidate();
    }

    // ------------------------------------------------------------------ server-provided data
    //
    // On a server that runs this addon the save lives on the server, so the data arrives in
    // pieces instead of from a local scan. Everything below is called from the client main
    // thread by the payload handlers, in the order header -> batches -> complete.

    /** A request has been sent; nothing has arrived yet. */
    public static void onRemoteRequested() {
        generation.incrementAndGet();
        status = Status.REMOTE;
        result = ScanResult.EMPTY;
        worldRoot = null;
        remote = null;
        remoteSourced = true;
        scannedFiles.set(0);
        totalFiles.set(0);
        invalidate();
    }

    /** The server announced the size of the incoming list. */
    public static void onRemoteHeader(int totalInstances, int totalTypes) {
        remote = new StructureAccumulator(totalInstances);
        status = Status.REMOTE;
        remoteSourced = true;
        totalFiles.set(Math.max(0, totalInstances));
        scannedFiles.set(0);
        invalidate();
    }

    /** One received batch of structure starts. */
    public static void onRemoteBatch(List<StructureInstance> instances) {
        StructureAccumulator accumulator = remote;
        if (accumulator == null) {
            return; // no header yet, or a late batch after completion
        }
        accumulator.accept(instances);
        scannedFiles.set((int) Math.min(Integer.MAX_VALUE, accumulator.received()));
        invalidate();
    }

    /** The server finished the transfer; publish what was assembled. */
    public static void onRemoteComplete(long elapsedMs, int reusedFiles) {
        StructureAccumulator accumulator = remote;
        if (accumulator == null) {
            return;
        }
        result = accumulator.finish(elapsedMs, reusedFiles);
        remote = null;
        remoteSourced = true;
        status = Status.READY;
        LOGGER.info("Received {} structures of {} types from the server ({} ms on the server)",
            result.totalStructures(), result.counts().size(), elapsedMs);
        invalidate();
    }

    /** The server cannot provide structure data (no save, or its scan failed). */
    public static void onRemoteUnavailable(String reason) {
        LOGGER.info("Server provided no structure data ({})", reason);
        onWorldUnavailable(Status.NO_ACCESS);
        unavailableReason = reason; // after the reset above, which clears it
    }

    /** Why the server had nothing to send, or {@code null} when it did. */
    public static String unavailableReason() {
        return unavailableReason;
    }

    /** True when the current data came from a server rather than from a local save. */
    public static boolean remoteSourced() {
        return remoteSourced;
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
        remote = null;
        remoteSourced = false;
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

    /**
     * Re-scan that ignores the incremental cache entirely: the persisted cache file is
     * deleted and every region file is re-read (menu: shift-clicking "Rescan").
     */
    public static void forceFullRescan() {
        Path root = worldRoot;
        if (root != null) {
            StructureScanner.clearCache(root);
        }
        rescan();
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
                LOGGER.info("Structure scan finished: {} structures of {} types in {} ms ({} region files from cache)",
                    scanned.totalStructures(), scanned.counts().size(), scanned.elapsedMs(),
                    scanned.reusedFiles());
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

    /** The raw scan result (used by the menu's CSV export). */
    public static ScanResult currentResult() {
        ensureFresh();
        return result;
    }

    /**
     * The structure closest to a point among the currently visible markers of a
     * dimension, or {@code null} when nothing is shown there.
     */
    public static StructureElement nearest(ResourceLocation dimensionId, double x, double z) {
        StructureElement closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (StructureElement element : renderable(dimensionId)) {
            double dx = element.x() - x;
            double dz = element.z() - z;
            double distance = dx * dx + dz * dz;
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = element;
            }
        }
        return closest;
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

    /**
     * 0..100 while a local scan or a server transfer is running, or -1 when neither is.
     * Also -1 while a server transfer has not announced its size yet.
     */
    public static int progressPercent() {
        if (status == Status.SCANNING) {
            int total = totalFiles.get();
            if (total <= 0) {
                return 0;
            }
            return Math.min(100, (int) (scannedFiles.get() * 100L / total));
        }
        if (status == Status.REMOTE) {
            int total = totalFiles.get();
            if (total <= 0) {
                return -1;
            }
            return Math.min(100, (int) (scannedFiles.get() * 100L / total));
        }
        return -1;
    }

    public static int dimensionCount() {
        return result.byDimension().size();
    }
}
