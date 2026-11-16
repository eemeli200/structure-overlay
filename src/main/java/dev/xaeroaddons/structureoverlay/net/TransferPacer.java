package dev.xaeroaddons.structureoverlay.net;

import dev.xaeroaddons.structureoverlay.data.StructureInstance;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Spreads outgoing structure transfers over server ticks.
 *
 * <p>A large world is hundreds of batches. Sending them in the tick that finished the scan
 * would stall that tick and hit the client with a burst, so transfers are queued and only a
 * fixed number of batches leave per tick, round-robin between players: everyone joining at
 * once still makes progress, and no single tick does unbounded work.
 *
 * <p>Pure bookkeeping - no Minecraft classes - so the pacing itself is unit tested.
 */
final class TransferPacer {
    /**
     * Batches per tick across all players. At 256 starts per batch that is at most 1024
     * structures per tick, so a 100k-structure world streams in about 5 seconds.
     */
    static final int MAX_BATCHES_PER_TICK = 4;

    /** One player's pending transfer. */
    static final class Job {
        private final UUID playerId;
        private final List<List<StructureInstance>> batches;
        private final int totalInstances;
        private final int reusedFiles;
        private final int elapsedMs;
        private int index;
        private int ticks;
        /** Pacer tick this job was last counted on, so several batches in one tick count once. */
        private long lastCountedTick = -1;

        Job(UUID playerId, List<List<StructureInstance>> batches,
            int totalInstances, int reusedFiles, int elapsedMs) {
            this.playerId = playerId;
            this.batches = List.copyOf(batches);
            this.totalInstances = totalInstances;
            this.reusedFiles = reusedFiles;
            this.elapsedMs = elapsedMs;
        }

        UUID playerId() {
            return playerId;
        }

        int remainingBatches() {
            return batches.size() - index;
        }

        int batchCount() {
            return batches.size();
        }

        /** Ticks this job has been handed a batch on. */
        int ticksUsed() {
            return ticks;
        }

        int totalInstances() {
            return totalInstances;
        }

        int reusedFiles() {
            return reusedFiles;
        }

        int elapsedMs() {
            return elapsedMs;
        }

        private boolean done() {
            return index >= batches.size();
        }

        private void onPolled(long tick) {
            if (tick != lastCountedTick) {
                lastCountedTick = tick;
                ticks++;
            }
        }

        private List<StructureInstance> nextBatch() {
            return batches.get(index++);
        }
    }

    /** The part of one transfer that is due this tick. */
    record Slice(Job job, List<StructureInstance> batch, boolean last) {
        UUID playerId() {
            return job.playerId();
        }
    }

    private final Deque<Job> queue = new ArrayDeque<>();
    private final int maxBatchesPerTick;
    private long tickId;

    TransferPacer() {
        this(MAX_BATCHES_PER_TICK);
    }

    TransferPacer(int maxBatchesPerTick) {
        this.maxBatchesPerTick = Math.max(1, maxBatchesPerTick);
    }

    /**
     * Queues a transfer, replacing anything still queued for the same player - a rescan
     * invalidates the older stream instead of interleaving the two.
     */
    void enqueue(Job job) {
        cancel(job.playerId());
        if (job.remainingBatches() > 0) {
            queue.addLast(job);
        }
    }

    void cancel(UUID playerId) {
        queue.removeIf(job -> job.playerId().equals(playerId));
    }

    void clear() {
        queue.clear();
    }

    /** How many players are still waiting for their transfer to finish. */
    int queued() {
        return queue.size();
    }

    /** The batches to send now; a job leaves the queue once its last batch is handed out. */
    List<Slice> nextTick() {
        List<Slice> due = new ArrayList<>(Math.min(maxBatchesPerTick, queue.size()));
        long currentTick = ++tickId;
        for (int budget = maxBatchesPerTick; budget > 0 && !queue.isEmpty(); budget--) {
            Job job = queue.pollFirst();
            job.onPolled(currentTick);
            due.add(new Slice(job, job.nextBatch(), job.done()));
            if (!job.done()) {
                queue.addLast(job);
            }
        }
        return due;
    }
}
