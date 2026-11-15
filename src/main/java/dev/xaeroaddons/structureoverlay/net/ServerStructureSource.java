package dev.xaeroaddons.structureoverlay.net;

import dev.xaeroaddons.structureoverlay.data.ScanResult;
import dev.xaeroaddons.structureoverlay.data.StructureInstance;
import dev.xaeroaddons.structureoverlay.data.StructureScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server half of the multiplayer support: scans this server's own world save and streams
 * the result to the clients that asked for it.
 *
 * <p>Scanning is lazy (first player request), off-thread, and cached in memory for the
 * life of the server process; requests that arrive while a scan runs are batched into the
 * same reply. The on-disk incremental cache of {@link StructureScanner} means a re-scan
 * after new terrain was generated only re-reads the region files that changed.
 *
 * <p>Nothing here touches client classes, so this runs on a dedicated server.
 */
public final class ServerStructureSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerStructureSource.class);

    private static final Object LOCK = new Object();
    /** Players whose request is waiting for the running scan to finish. */
    private static final Set<UUID> waiting = new HashSet<>();
    private static volatile ScanResult cached;
    private static volatile Path cachedRoot;
    private static boolean scanning;
    private static int sendSequence;

    private ServerStructureSource() {
    }

    /** Handles a client request: reply from cache, or start/join a scan. */
    public static void request(ServerPlayer player, boolean full) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        if (!Files.isDirectory(root)) {
            sendUnavailable(player, StructurePayloads.UnavailablePayload.Reason.NO_SAVE);
            return;
        }
        if (!full) {
            ScanResult cachedResult = cached;
            Path cachedFrom = cachedRoot;
            if (cachedResult != null && cachedFrom != null && cachedFrom.equals(root)) {
                send(player, cachedResult);
                return;
            }
        }

        boolean startScan;
        synchronized (LOCK) {
            waiting.add(player.getUUID());
            startScan = !scanning;
            if (startScan) {
                scanning = true;
            }
        }
        if (!startScan) {
            return; // the running scan answers every waiter when it finishes
        }

        Thread worker = new Thread(() -> scan(server, root, full), "structureoverlay-server-scan");
        worker.setDaemon(true);
        worker.start();
    }

    private static void scan(MinecraftServer server, Path root, boolean full) {
        ScanResult result = null;
        try {
            if (full) {
                StructureScanner.clearCache(root);
            }
            result = StructureScanner.scan(root, NO_PROGRESS, () -> server.isRunning());
        } catch (Exception e) {
            LOGGER.error("Server-side structure scan of {} failed", root, e);
        }
        ScanResult done = result;
        // Sending must happen on the server thread.
        server.execute(() -> finish(server, root, done));
    }

    private static void finish(MinecraftServer server, Path root, ScanResult result) {
        List<UUID> targets;
        synchronized (LOCK) {
            scanning = false;
            targets = new ArrayList<>(waiting);
            waiting.clear();
            if (result != null) {
                cached = result;
                cachedRoot = root;
            }
        }
        if (result != null) {
            LOGGER.info("Server-side structure scan finished: {} structures of {} types in {} ms ({} region files from cache)",
                result.totalStructures(), result.counts().size(), result.elapsedMs(), result.reusedFiles());
        }
        for (UUID id : targets) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                continue; // left in the meantime
            }
            if (result == null) {
                sendUnavailable(player, StructurePayloads.UnavailablePayload.Reason.SCAN_FAILED);
            } else {
                send(player, result);
            }
        }
    }

    /** Streams a whole scan result to one player: header, batches, completion. */
    private static void send(ServerPlayer player, ScanResult result) {
        if (!StructureNetwork.canSendEntries(player)) {
            return;
        }
        List<StructureInstance> all = new ArrayList<>();
        for (List<StructureInstance> instances : result.byDimension().values()) {
            all.addAll(instances);
        }
        int generation = nextSequence();
        PacketDistributor.sendToPlayer(player,
            new StructurePayloads.HeaderPayload(generation, all.size(), result.counts().size()));
        for (List<StructureInstance> batch : StructurePayloads.partition(all, StructurePayloads.BATCH_SIZE)) {
            PacketDistributor.sendToPlayer(player,
                new StructurePayloads.BatchPayload(generation, StructurePayloads.toEntries(batch)));
        }
        PacketDistributor.sendToPlayer(player,
            new StructurePayloads.CompletePayload(generation, all.size(), result.reusedFiles(),
                (int) Math.min(Integer.MAX_VALUE, result.elapsedMs())));
    }

    private static void sendUnavailable(ServerPlayer player, StructurePayloads.UnavailablePayload.Reason reason) {
        if (!player.connection.hasChannel(StructurePayloads.UnavailablePayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new StructurePayloads.UnavailablePayload(reason));
    }

    private static synchronized int nextSequence() {
        return ++sendSequence;
    }

    /** Forgets the cached scan, e.g. when the server stops. */
    public static void reset() {
        synchronized (LOCK) {
            waiting.clear();
            scanning = false;
            cached = null;
            cachedRoot = null;
        }
    }

    private static final StructureScanner.Progress NO_PROGRESS = new StructureScanner.Progress() {
        @Override
        public void totalFiles(int total) {
        }

        @Override
        public void fileDone() {
        }
    };
}
