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
 * <p>Uploads are paced by {@link TransferPacer} from {@link #tick(MinecraftServer)}: a huge
 * world is streamed over many ticks instead of in one, so neither the server tick nor the
 * player's connection is flooded.
 *
 * <p>Nothing here touches client classes, so this runs on a dedicated server.
 */
final class ServerStructureSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerStructureSource.class);

    private static final Object LOCK = new Object();
    /** Players whose request is waiting for the running scan to finish. */
    private static final Set<UUID> waiting = new HashSet<>();
    /** Uploads in flight, advanced once per server tick. */
    private static final TransferPacer PACER = new TransferPacer();

    /** A finished scan, flattened once so each request can filter it for one player. */
    private record Prepared(Path root, ScanResult result, List<StructureInstance> all) {
        static Prepared of(Path root, ScanResult result) {
            List<StructureInstance> all = new ArrayList<>();
            for (List<StructureInstance> instances : result.byDimension().values()) {
                all.addAll(instances);
            }
            return new Prepared(root, result, List.copyOf(all));
        }
    }

    private static volatile Prepared cached;
    private static boolean scanning;
    /**
     * Set while the server shuts down, so a running scan stops early. Deliberately our own
     * flag rather than {@code MinecraftServer#isRunning()}, which reported false here even
     * while the server was up - using it cancelled every scan before it read a single file.
     */
    private static volatile boolean stopping;

    /** When a client last forced a full re-read, for the rescan cooldown. */
    private static long lastForcedScanAt;

    private ServerStructureSource() {
    }

    /** Handles a client request: answer from the cached scan, or start/join one. */
    static void request(ServerPlayer player, boolean full) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerConfig.Policy policy = ServerConfig.get().policy();
        if (!policy.enabled()) {
            sendUnavailable(player, StructurePayloads.UnavailablePayload.Reason.DISABLED);
            return;
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        if (!Files.isDirectory(root)) {
            sendUnavailable(player, StructurePayloads.UnavailablePayload.Reason.NO_SAVE);
            return;
        }
        boolean forceFull = full && mayForceRescan(policy);
        Prepared cachedScan = cached;
        if (!forceFull && cachedScan != null && cachedScan.root().equals(root)) {
            beginUpload(player, cachedScan, policy);
            return;
        }

        boolean startScan;
        synchronized (LOCK) {
            waiting.add(player.getUUID());
            startScan = !scanning;
            if (startScan) {
                scanning = true;
                stopping = false;
                if (forceFull) {
                    lastForcedScanAt = System.currentTimeMillis();
                }
            }
        }
        if (!startScan) {
            // A scan is already running. It answers every waiter, so a "full" request that
            // arrives now is served by it rather than queued as a second scan.
            return;
        }

        Thread worker = new Thread(() -> scan(server, root, forceFull), "structureoverlay-server-scan");
        worker.setDaemon(true);
        worker.start();
    }

    private static void scan(MinecraftServer server, Path root, boolean full) {
        ScanResult result = null;
        try {
            if (full) {
                StructureScanner.clearCache(root);
            }
            result = StructureScanner.scan(root, NO_PROGRESS, () -> stopping);
        } catch (Exception e) {
            LOGGER.error("Server-side structure scan of {} failed", root, e);
        }
        if (stopping) {
            result = null; // shutting down; a partial result is not worth sending
        }
        ScanResult done = result;
        // Sending must happen on the server thread.
        try {
            server.execute(() -> finish(server, root, done));
        } catch (Exception e) {
            LOGGER.debug("Could not deliver the structure scan result, server is stopping", e);
        }
    }

    private static void finish(MinecraftServer server, Path root, ScanResult result) {
        ServerConfig.Policy policy = ServerConfig.get().policy();
        Prepared prepared = result == null ? null : Prepared.of(root, result);
        List<UUID> targets;
        synchronized (LOCK) {
            scanning = false;
            targets = new ArrayList<>(waiting);
            waiting.clear();
            if (prepared != null) {
                cached = prepared;
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
            if (prepared == null) {
                sendUnavailable(player, StructurePayloads.UnavailablePayload.Reason.SCAN_FAILED);
            } else {
                beginUpload(player, prepared, policy);
            }
        }
    }

    /**
     * Starts one player's transfer: the header goes out now, the batches follow one per
     * slice from {@link #tick(MinecraftServer)}.
     */
    private static void beginUpload(ServerPlayer player, Prepared prepared, ServerConfig.Policy policy) {
        if (!StructureNetwork.canSendEntries(player)) {
            return;
        }
        ScanResult result = prepared.result();
        // the fair-play filter runs per request: it depends on where the player stands
        List<StructureInstance> filtered = StructureFilter.apply(prepared.all(), policy,
            player.level().dimension().location(), player.getX(), player.getZ());
        Set<String> types = new HashSet<>();
        for (StructureInstance instance : filtered) {
            types.add(instance.structureId());
        }
        List<List<StructureInstance>> batches =
            StructurePayloads.partition(filtered, StructurePayloads.BATCH_SIZE);
        int totalInstances = filtered.size();
        int elapsedMs = (int) Math.min(Integer.MAX_VALUE, result.elapsedMs());

        // a rescan replaces whatever was still queued for this player
        PACER.cancel(player.getUUID());
        PacketDistributor.sendToPlayer(player,
            new StructurePayloads.HeaderPayload(totalInstances, types.size()));

        if (batches.isEmpty()) {
            PacketDistributor.sendToPlayer(player,
                new StructurePayloads.CompletePayload(0, result.reusedFiles(), elapsedMs));
            return;
        }
        PACER.enqueue(new TransferPacer.Job(player.getUUID(), batches, totalInstances,
            result.reusedFiles(), elapsedMs));
    }

    /** Called every server tick: sends the next slice of each transfer in flight. */
    static void tick(MinecraftServer server) {
        for (TransferPacer.Slice slice : PACER.nextTick()) {
            ServerPlayer player = server.getPlayerList().getPlayer(slice.playerId());
            if (player == null || !StructureNetwork.canSendEntries(player)) {
                PACER.cancel(slice.playerId()); // gone, or no longer able to receive
                continue;
            }
            TransferPacer.Job job = slice.job();
            PacketDistributor.sendToPlayer(player,
                new StructurePayloads.BatchPayload(StructurePayloads.toEntries(slice.batch())));
            if (slice.last()) {
                PacketDistributor.sendToPlayer(player,
                    new StructurePayloads.CompletePayload(job.totalInstances(), job.reusedFiles(),
                        job.elapsedMs()));
                LOGGER.info("Sent {} structures to {} in {} batches over {} ticks",
                    job.totalInstances(), player.getGameProfile().getName(),
                    job.batchCount(), job.ticksUsed());
            }
        }
    }

    private static void sendUnavailable(ServerPlayer player, StructurePayloads.UnavailablePayload.Reason reason) {
        if (!player.connection.hasChannel(StructurePayloads.UnavailablePayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new StructurePayloads.UnavailablePayload(reason));
    }

    /** Forgets the cached scan and any half-sent transfer, e.g. when the server stops. */
    static void reset() {
        synchronized (LOCK) {
            waiting.clear();
            scanning = false;
            stopping = true;
            cached = null;
        }
        PACER.clear();
    }

    /** Whether a client may force a re-scan right now: preset allows it and the cooldown passed. */
    private static boolean mayForceRescan(ServerConfig.Policy policy) {
        if (!policy.allowRescan()) {
            return false;
        }
        int cooldown = policy.rescanCooldownSeconds();
        return cooldown <= 0 || System.currentTimeMillis() - lastForcedScanAt >= cooldown * 1000L;
    }

    /** Re-reads the fair-play config and logs what it resolved to; called when a server starts. */
    static void reloadConfig() {
        ServerConfig.reload();
        ServerConfig config = ServerConfig.get();
        ServerConfig.Policy policy = config.policy();
        if (!policy.enabled()) {
            LOGGER.info("Structure overlay fair-play preset '{}': structure data is disabled", config.preset);
            return;
        }
        LOGGER.info("Structure overlay fair-play preset '{}': distance {}, snap {}, footprints {}, rescan {}, cap {}, blocked types {}",
            config.preset,
            policy.maxDistance() > 0 ? policy.maxDistance() + " blocks" : "unlimited",
            policy.snapTo() > 0 ? policy.snapTo() + " blocks" : "exact",
            policy.footprints() ? "on" : "off",
            policy.allowRescan()
                ? (policy.rescanCooldownSeconds() > 0 ? policy.rescanCooldownSeconds() + " s cooldown" : "allowed")
                : "off",
            policy.maxStructures() > 0 ? policy.maxStructures() : "unlimited",
            policy.disabledTypes().size());
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
