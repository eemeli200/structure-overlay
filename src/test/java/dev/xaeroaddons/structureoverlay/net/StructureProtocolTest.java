package dev.xaeroaddons.structureoverlay.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xaeroaddons.structureoverlay.data.BlockBox;
import dev.xaeroaddons.structureoverlay.data.StructureInstance;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** The wire format has to survive a round trip and the batching has to lose nothing. */
class StructureProtocolTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation CUSTOM =
        ResourceLocation.fromNamespaceAndPath("somemod", "deep_dark_dimension");

    private static List<StructureInstance> instances(int count) {
        List<StructureInstance> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new StructureInstance(i % 2 == 0 ? OVERWORLD : CUSTOM, "minecraft:village_plains",
                i - 500, -i, i % 3 == 0 ? new BlockBox(-i, -i, i, i) : null));
        }
        return list;
    }

    @Test
    void partitionKeepsEveryElementInOrder() {
        List<StructureInstance> all = instances(517);
        List<List<StructureInstance>> batches = StructurePayloads.partition(all, StructurePayloads.BATCH_SIZE);

        assertEquals(3, batches.size());
        assertEquals(256, batches.get(0).size());
        assertEquals(256, batches.get(1).size());
        assertEquals(5, batches.get(2).size());
        List<StructureInstance> rejoined = new ArrayList<>();
        batches.forEach(rejoined::addAll);
        assertEquals(all, rejoined);
    }

    @Test
    void partitionHandlesEmptyAndExactMultiples() {
        assertTrue(StructurePayloads.partition(List.of(), 8).isEmpty());

        List<List<StructureInstance>> exact = StructurePayloads.partition(instances(16), 8);
        assertEquals(2, exact.size());
        assertThrows(IllegalArgumentException.class, () -> StructurePayloads.partition(instances(4), 0));
    }

    @Test
    void batchPayloadRoundTrips() {
        StructurePayloads.BatchPayload payload = new StructurePayloads.BatchPayload(7,
            StructurePayloads.toEntries(instances(5)));

        StructurePayloads.BatchPayload decoded = roundTrip(payload, StructurePayloads.BatchPayload.STREAM_CODEC);

        assertEquals(7, decoded.generation());
        assertEquals(payload.entries(), decoded.entries());
        assertEquals(StructurePayloads.toInstances(payload.entries()),
            StructurePayloads.toInstances(decoded.entries()));
    }

    @Test
    void batchPayloadKeepsMissingBoundsMissing() {
        List<StructurePayloads.Entry> entries = List.of(
            new StructurePayloads.Entry(OVERWORLD, "minecraft:monument", 3, -4, null),
            new StructurePayloads.Entry(CUSTOM, "somemod:thing", -17, 12, new BlockBox(10, 20, -30, -40)));

        StructurePayloads.BatchPayload decoded = roundTrip(new StructurePayloads.BatchPayload(1, entries),
            StructurePayloads.BatchPayload.STREAM_CODEC);

        assertNull(decoded.entries().get(0).bounds());
        assertEquals(new BlockBox(-30, -40, 10, 20), decoded.entries().get(1).bounds(),
            "bounds are normalised, so encoding must not change their meaning");
    }

    @Test
    void controlPayloadsRoundTrip() {
        StructurePayloads.HeaderPayload header = roundTrip(new StructurePayloads.HeaderPayload(42, 1234, 9),
            StructurePayloads.HeaderPayload.STREAM_CODEC);
        assertEquals(42, header.generation());
        assertEquals(1234, header.totalInstances());
        assertEquals(9, header.totalTypes());

        StructurePayloads.CompletePayload complete = roundTrip(
            new StructurePayloads.CompletePayload(42, 1234, 17, 5000),
            StructurePayloads.CompletePayload.STREAM_CODEC);
        assertEquals(1234, complete.totalInstances());
        assertEquals(17, complete.reusedFiles());
        assertEquals(5000, complete.elapsedMs());

        StructurePayloads.UnavailablePayload unavailable = roundTrip(
            new StructurePayloads.UnavailablePayload(StructurePayloads.UnavailablePayload.Reason.SCAN_FAILED),
            StructurePayloads.UnavailablePayload.STREAM_CODEC);
        assertEquals(StructurePayloads.UnavailablePayload.Reason.SCAN_FAILED, unavailable.reason());

        assertTrue(roundTrip(new StructurePayloads.RequestPayload(true),
            StructurePayloads.RequestPayload.STREAM_CODEC).full());
        assertFalse(roundTrip(new StructurePayloads.RequestPayload(false),
            StructurePayloads.RequestPayload.STREAM_CODEC).full());
    }

    @Test
    void payloadTypesUseTheModNamespace() {
        assertEquals("structureoverlay", StructurePayloads.RequestPayload.TYPE.id().getNamespace());
        assertEquals("structureoverlay", StructurePayloads.BatchPayload.TYPE.id().getNamespace());
        assertEquals("structureoverlay", StructurePayloads.HeaderPayload.TYPE.id().getNamespace());
        assertEquals("structureoverlay", StructurePayloads.CompletePayload.TYPE.id().getNamespace());
        assertEquals("structureoverlay", StructurePayloads.UnavailablePayload.TYPE.id().getNamespace());
    }

    private static <T> T roundTrip(T payload, net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, payload);
        return codec.decode(buf);
    }
}
