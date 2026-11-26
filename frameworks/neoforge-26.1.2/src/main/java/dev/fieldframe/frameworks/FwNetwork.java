package dev.fieldframe.frameworks;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Frameworks' only packet: the Foundry and arc alloys and the tuning values (server data) for clients, so JEI shows them. */
final class FwNetwork {
    private FwNetwork() {
    }

    static void register(RegisterPayloadHandlersEvent e) {
        // Optional: a client without Frameworks' channel simply gets no alloy page and default numbers.
        e.registrar("2").optional().playToClient(DataSync.TYPE, DataSync.CODEC, DataSync::handle);
    }

    /** Sent on join and after every data reload. */
    static void onDatapackSync(OnDatapackSyncEvent e) {
        e.getRelevantPlayers().forEach(FwNetwork::send);
    }

    private static void send(ServerPlayer player) {
        if (player.connection.hasChannel(DataSync.TYPE)) {
            PacketDistributor.sendToPlayer(player, new DataSync(FoundryAlloys.toJson(), FwTuning.toJson(), ArcAlloys.toJson()));
        }
    }

    record DataSync(String json, String tuning, String arc) implements CustomPacketPayload {
        static final Type<DataSync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Frameworks.MOD_ID, "data_sync"));
        static final StreamCodec<RegistryFriendlyByteBuf, DataSync> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1 << 20), DataSync::json, ByteBufCodecs.stringUtf8(1 << 16), DataSync::tuning,
                ByteBufCodecs.stringUtf8(1 << 20), DataSync::arc, DataSync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(DataSync m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                FoundryAlloys.acceptSync(m.json());
                FwTuning.acceptSync(m.tuning());
                ArcAlloys.acceptSync(m.arc());
            });
        }
    }
}
