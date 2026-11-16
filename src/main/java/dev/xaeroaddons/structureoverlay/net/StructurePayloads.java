package dev.xaeroaddons.structureoverlay.net;

import dev.xaeroaddons.structureoverlay.StructureOverlayMod;
import dev.xaeroaddons.structureoverlay.data.BlockBox;
import dev.xaeroaddons.structureoverlay.data.StructureInstance;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Wire format between a server that runs this addon and its clients.
 *
 * <p>One exchange is: client {@link RequestPayload} -> server {@link HeaderPayload} ->
 * any number of {@link BatchPayload}s -> {@link CompletePayload}, or a single
 * {@link UnavailablePayload} when the server cannot provide the data.
 *
 * <p>Every payload is registered as <em>optional</em> (see {@link StructureNetwork}), so a
 * vanilla server, a server without the addon and a client without the addon all keep
 * working; both sides check for the channel before sending anything.
 */
public final class StructurePayloads {
    /**
     * Structure starts per batch. Kept small so a packet stays a few kB and arrives well
     * below Minecraft's custom payload size limit, whatever the world size.
     */
    public static final int BATCH_SIZE = 256;
    /** Upper bound accepted when decoding, so malformed input cannot allocate wildly. */
    private static final int MAX_ENTRIES = 4096;

    private StructurePayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(StructureOverlayMod.MOD_ID, path);
    }

    /** Client -> server: send me the structure data ({@code full} = rescan the save). */
    public record RequestPayload(boolean full) implements CustomPacketPayload {
        public static final Type<RequestPayload> TYPE = new Type<>(id("structures/request"));
        public static final StreamCodec<FriendlyByteBuf, RequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBoolean(payload.full()),
            buf -> new RequestPayload(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: how much is coming, so the menu can show progress. */
    public record HeaderPayload(int totalInstances, int totalTypes) implements CustomPacketPayload {
        public static final Type<HeaderPayload> TYPE = new Type<>(id("structures/header"));
        public static final StreamCodec<FriendlyByteBuf, HeaderPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.totalInstances());
                buf.writeVarInt(payload.totalTypes());
            },
            buf -> new HeaderPayload(buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One structure start as it travels over the network. */
    public record Entry(ResourceLocation dimension, String structureId, int chunkX, int chunkZ, BlockBox bounds) {
        static void write(FriendlyByteBuf buf, Entry entry) {
            ResourceLocation.STREAM_CODEC.encode(buf, entry.dimension());
            buf.writeUtf(entry.structureId());
            buf.writeVarInt(entry.chunkX());
            buf.writeVarInt(entry.chunkZ());
            BlockBox bounds = entry.bounds();
            buf.writeBoolean(bounds != null);
            if (bounds != null) {
                buf.writeVarInt(bounds.x1());
                buf.writeVarInt(bounds.z1());
                buf.writeVarInt(bounds.x2());
                buf.writeVarInt(bounds.z2());
            }
        }

        static Entry read(FriendlyByteBuf buf) {
            ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
            String structureId = buf.readUtf();
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            BlockBox bounds = buf.readBoolean()
                ? new BlockBox(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
                : null;
            return new Entry(dimension, structureId, chunkX, chunkZ, bounds);
        }
    }

    /** Server -> client: a slice of the structure list. */
    public record BatchPayload(List<Entry> entries) implements CustomPacketPayload {
        public static final Type<BatchPayload> TYPE = new Type<>(id("structures/batch"));
        public static final StreamCodec<FriendlyByteBuf, BatchPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.entries().size());
                for (Entry entry : payload.entries()) {
                    Entry.write(buf, entry);
                }
            },
            buf -> {
                int size = buf.readVarInt();
                if (size < 0 || size > MAX_ENTRIES) {
                    throw new DecoderException("Invalid structure batch size: " + size);
                }
                List<Entry> entries = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    entries.add(Entry.read(buf));
                }
                return new BatchPayload(List.copyOf(entries));
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: transfer finished, these totals are authoritative. */
    public record CompletePayload(int totalInstances, int reusedFiles, int elapsedMs)
        implements CustomPacketPayload {
        public static final Type<CompletePayload> TYPE = new Type<>(id("structures/complete"));
        public static final StreamCodec<FriendlyByteBuf, CompletePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.totalInstances());
                buf.writeVarInt(payload.reusedFiles());
                buf.writeVarInt(payload.elapsedMs());
            },
            buf -> new CompletePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: the server cannot provide structure data. */
    public record UnavailablePayload(Reason reason) implements CustomPacketPayload {
        /** Why the server has nothing to send. */
        public enum Reason {
            /** The server has no readable world save (e.g. it runs a proxy setup). */
            NO_SAVE,
            /** The scan threw an exception; the log on the server has the details. */
            SCAN_FAILED,
            /** The server's fair-play config switched structure sharing off. */
            DISABLED
        }

        public static final Type<UnavailablePayload> TYPE = new Type<>(id("structures/unavailable"));
        public static final StreamCodec<FriendlyByteBuf, UnavailablePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeEnum(payload.reason()),
            buf -> new UnavailablePayload(buf.readEnum(Reason.class)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Splits instances into wire entries. */
    public static List<Entry> toEntries(List<StructureInstance> instances) {
        List<Entry> entries = new ArrayList<>(instances.size());
        for (StructureInstance instance : instances) {
            entries.add(new Entry(instance.dimension(), instance.structureId(),
                instance.startChunkX(), instance.startChunkZ(), instance.bounds()));
        }
        return entries;
    }

    /** Turns received entries back into instances. */
    public static List<StructureInstance> toInstances(List<Entry> entries) {
        List<StructureInstance> instances = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            instances.add(new StructureInstance(entry.dimension(), entry.structureId(),
                entry.chunkX(), entry.chunkZ(), entry.bounds()));
        }
        return instances;
    }

    /** Splits {@code values} into consecutive chunks of at most {@code size} elements. */
    public static <T> List<List<T>> partition(List<T> values, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        List<List<T>> batches = new ArrayList<>((values.size() + size - 1) / size);
        for (int from = 0; from < values.size(); from += size) {
            batches.add(List.copyOf(values.subList(from, Math.min(values.size(), from + size))));
        }
        return batches;
    }
}
