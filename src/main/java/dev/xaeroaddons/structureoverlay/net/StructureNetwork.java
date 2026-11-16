package dev.xaeroaddons.structureoverlay.net;

import dev.xaeroaddons.structureoverlay.StructureOverlayMod;
import dev.xaeroaddons.structureoverlay.data.StructureRepository;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the structure-sync payloads and routes them to either side.
 *
 * <p>Everything is registered as <em>optional</em>: NeoForge then accepts the connection
 * whether or not the other side has this addon, and both ends ask {@code hasChannel(...)}
 * before sending. A server without the addon therefore never receives a payload it cannot
 * decode, and a client without the addon never receives one it cannot decode either; the
 * overlay simply stays empty on the side that lacks the data.
 */
@EventBusSubscriber(modid = StructureOverlayMod.MOD_ID)
public final class StructureNetwork {
    /**
     * Wire-format version. NeoForge refuses connections whose payload versions differ, so
     * both sides have to ship the same build - they do, this is one jar. Kept at "1"
     * because no released build has used a different payload layout.
     */
    public static final String PROTOCOL_VERSION = "1";

    private StructureNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();

        registrar.playToServer(StructurePayloads.RequestPayload.TYPE,
            StructurePayloads.RequestPayload.STREAM_CODEC, StructureNetwork::onRequest);

        registrar.playToClient(StructurePayloads.HeaderPayload.TYPE,
            StructurePayloads.HeaderPayload.STREAM_CODEC, StructureNetwork::onHeader);
        registrar.playToClient(StructurePayloads.BatchPayload.TYPE,
            StructurePayloads.BatchPayload.STREAM_CODEC, StructureNetwork::onBatch);
        registrar.playToClient(StructurePayloads.CompletePayload.TYPE,
            StructurePayloads.CompletePayload.STREAM_CODEC, StructureNetwork::onComplete);
        registrar.playToClient(StructurePayloads.UnavailablePayload.TYPE,
            StructurePayloads.UnavailablePayload.STREAM_CODEC, StructureNetwork::onUnavailable);
    }

    // ------------------------------------------------------------------ server side

    private static void onRequest(StructurePayloads.RequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ServerStructureSource.request(player, payload.full());
        }
    }

    /** Whether this player's client can receive the structure data. */
    static boolean canSendEntries(ServerPlayer player) {
        return player.connection.hasChannel(StructurePayloads.BatchPayload.TYPE);
    }

    // ------------------------------------------------------------------ client side

    /** Asks the server for structure data; call only when the channel is present. */
    public static void requestFromServer(boolean full) {
        PacketDistributor.sendToServer(new StructurePayloads.RequestPayload(full));
    }

    private static void onHeader(StructurePayloads.HeaderPayload payload, IPayloadContext context) {
        StructureRepository.onRemoteHeader(payload.totalInstances(), payload.totalTypes());
    }

    private static void onBatch(StructurePayloads.BatchPayload payload, IPayloadContext context) {
        StructureRepository.onRemoteBatch(StructurePayloads.toInstances(payload.entries()));
    }

    private static void onComplete(StructurePayloads.CompletePayload payload, IPayloadContext context) {
        StructureRepository.onRemoteComplete(payload.elapsedMs(), payload.reusedFiles());
    }

    private static void onUnavailable(StructurePayloads.UnavailablePayload payload, IPayloadContext context) {
        StructureRepository.onRemoteUnavailable(payload.reason().name());
    }
}
