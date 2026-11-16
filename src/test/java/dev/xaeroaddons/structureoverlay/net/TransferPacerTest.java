package dev.xaeroaddons.structureoverlay.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xaeroaddons.structureoverlay.data.StructureInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** A huge world must be streamed over many ticks, never in one. */
class TransferPacerTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static StructureInstance instance(int chunkX) {
        return new StructureInstance(OVERWORLD, "minecraft:village_plains", chunkX, 0, null);
    }

    /** @param batches one list of instances per batch */
    private static TransferPacer.Job job(UUID player, int batches, int perBatch) {
        List<List<StructureInstance>> slices = new ArrayList<>();
        for (int b = 0; b < batches; b++) {
            List<StructureInstance> instances = new ArrayList<>();
            for (int i = 0; i < perBatch; i++) {
                instances.add(instance(b * perBatch + i));
            }
            slices.add(instances);
        }
        return new TransferPacer.Job(player, slices, batches * perBatch, 0, 10);
    }

    @Test
    void sendsAtMostTheBudgetPerTick() {
        TransferPacer pacer = new TransferPacer(2);
        pacer.enqueue(job(ALICE, 5, 3));

        assertEquals(2, pacer.nextTick().size(), "only the per-tick budget goes out");
        assertEquals(2, pacer.nextTick().size());
        List<TransferPacer.Slice> last = pacer.nextTick();
        assertEquals(1, last.size());
        assertTrue(last.get(0).last(), "the final batch is flagged so the caller completes");
        assertEquals(5, last.get(0).job().batchCount());
        assertEquals(3, last.get(0).job().ticksUsed(), "five batches at two per tick spans three ticks");
        assertEquals(0, pacer.queued());
        assertTrue(pacer.nextTick().isEmpty());
    }

    @Test
    void theDefaultBudgetFitsThreeBatchesInOneTick() {
        TransferPacer pacer = new TransferPacer();
        pacer.enqueue(job(ALICE, 3, 256));

        // 3 batches at 4/tick: one tick, and the client still gets them in order
        List<TransferPacer.Slice> first = pacer.nextTick();
        assertEquals(3, first.size());
        assertTrue(first.get(2).last());
        assertEquals(768, first.get(0).job().totalInstances());
        assertEquals(0, pacer.queued());
    }

    @Test
    void roundRobinsBetweenPlayers() {
        TransferPacer pacer = new TransferPacer(2);
        pacer.enqueue(job(ALICE, 3, 1));
        pacer.enqueue(job(BOB, 3, 1));

        List<TransferPacer.Slice> first = pacer.nextTick();
        assertEquals(List.of(ALICE, BOB), first.stream().map(TransferPacer.Slice::playerId).toList());
        assertEquals(1, first.get(0).job().ticksUsed(), "both players are served in their first tick");

        List<TransferPacer.Slice> second = pacer.nextTick();
        assertEquals(List.of(ALICE, BOB), second.stream().map(TransferPacer.Slice::playerId).toList());

        List<TransferPacer.Slice> third = pacer.nextTick();
        assertEquals(List.of(ALICE, BOB), third.stream().map(TransferPacer.Slice::playerId).toList());
        assertTrue(third.stream().allMatch(TransferPacer.Slice::last));
        assertEquals(0, pacer.queued());
    }

    @Test
    void aRescanReplacesTheQueuedUploadForThatPlayer() {
        TransferPacer pacer = new TransferPacer(1);
        pacer.enqueue(job(ALICE, 4, 1));
        assertEquals(1, pacer.nextTick().size()); // one old batch already went out

        pacer.enqueue(job(ALICE, 2, 1)); // rescan

        List<TransferPacer.Slice> slices = new ArrayList<>();
        List<TransferPacer.Slice> tick;
        while (!(tick = pacer.nextTick()).isEmpty()) {
            slices.addAll(tick);
        }
        assertEquals(2, slices.size(), "the replaced upload is dropped, not interleaved");
        assertTrue(slices.get(1).last());
    }

    @Test
    void cancelStopsAnUpload() {
        TransferPacer pacer = new TransferPacer(1);
        pacer.enqueue(job(ALICE, 3, 1));
        pacer.cancel(ALICE);
        assertEquals(0, pacer.queued());
        assertTrue(pacer.nextTick().isEmpty());
    }

    @Test
    void anEmptyUploadIsNotQueued() {
        TransferPacer pacer = new TransferPacer();
        pacer.enqueue(job(ALICE, 0, 0));
        assertEquals(0, pacer.queued());
        assertTrue(pacer.nextTick().isEmpty());
    }
}
