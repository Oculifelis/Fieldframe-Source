package dev.fieldframe.frameworks;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Frameworks' only packet: the Foundry and arc alloys and the tuning values (server data) for clients, so JEI shows them. */
final class FwNetwork {
    private static final String VERSION = "2";
    // Optional: a client without Frameworks' channel simply gets no alloy page and default numbers.
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(Frameworks.MOD_ID, "main"),
            () -> VERSION, NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    private FwNetwork() {
    }

    static void register() {
        CHANNEL.messageBuilder(DataSync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DataSync::encode).decoder(DataSync::decode).consumerMainThread(DataSync::handle).add();
    }

    /** Sent on join and after every data reload. */
    static void onDatapackSync(OnDatapackSyncEvent e) {
        if (e.getPlayer() != null) {
            send(e.getPlayer());
        } else {
            e.getPlayerList().getPlayers().forEach(FwNetwork::send);
        }
    }

    private static void send(ServerPlayer player) {
        if (player.connection == null || !CHANNEL.isRemotePresent(player.connection.connection)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DataSync(FoundryAlloys.toJson(), FwTuning.toJson(), ArcAlloys.toJson()));
    }

    record DataSync(String json, String tuning, String arc) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(json, 1 << 20);
            buf.writeUtf(tuning, 1 << 16);
            buf.writeUtf(arc, 1 << 20);
        }

        static DataSync decode(FriendlyByteBuf buf) {
            return new DataSync(buf.readUtf(1 << 20), buf.readUtf(1 << 16), buf.readUtf(1 << 20));
        }

        static void handle(DataSync m, Supplier<NetworkEvent.Context> ctx) {
            FoundryAlloys.acceptSync(m.json());
            FwTuning.acceptSync(m.tuning());
            ArcAlloys.acceptSync(m.arc());
            ctx.get().setPacketHandled(true);
        }
    }
}
