package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class TellusForgeNetworking {
   private static final String PROTOCOL_VERSION = "1";
   private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
      Tellus.id("main"),
      () -> PROTOCOL_VERSION,
      ignored -> true,
      ignored -> true
   );

   private TellusForgeNetworking() {
   }

   public static void registerMessages() {
      int discriminator = 0;
      CHANNEL.registerMessage(
         discriminator++,
         GeoTpTeleportPayload.class,
         GeoTpTeleportPayload::write,
         GeoTpTeleportPayload::new,
         TellusForgeNetworking::handleGeoTeleport,
         Optional.of(NetworkDirection.PLAY_TO_SERVER)
      );
      CHANNEL.registerMessage(
         discriminator++,
         ManagedTerrainViewPayload.class,
         ManagedTerrainViewPayload::write,
         ManagedTerrainViewPayload::new,
         TellusForgeNetworking::handleManagedTerrainView,
         Optional.of(NetworkDirection.PLAY_TO_SERVER)
      );
      CHANNEL.registerMessage(
         discriminator++,
         GeoTpOpenMapPayload.class,
         GeoTpOpenMapPayload::write,
         GeoTpOpenMapPayload::new,
         TellusForgeNetworking::handleOpenMap,
         Optional.of(NetworkDirection.PLAY_TO_CLIENT)
      );
      CHANNEL.registerMessage(
         discriminator++,
         TellusWeatherPayload.class,
         TellusWeatherPayload::write,
         TellusWeatherPayload::new,
         TellusForgeNetworking::handleWeather,
         Optional.of(NetworkDirection.PLAY_TO_CLIENT)
      );
      CHANNEL.registerMessage(
         discriminator,
         ManagedTerrainStatusPayload.class,
         ManagedTerrainStatusPayload::write,
         ManagedTerrainStatusPayload::new,
         TellusForgeNetworking::handleManagedTerrainStatus,
         Optional.of(NetworkDirection.PLAY_TO_CLIENT)
      );
   }

   /**
    * 向指定玩家发送一个 payload。
    *
    * 输入：目标玩家与 payload。
    * 输出：真正发出返回 true；对端未声明 Tellus 频道而跳过返回 false。
    * 边界条件：连接为 null 或对端没装模组时一律返回 false，由调用方决定是否重试。
    */
   public static boolean sendToPlayer(ServerPlayer player, Object payload) {
      // 取该玩家的底层网络连接，用来判断对端是否声明了 Tellus 频道
      Connection connection = player.connection.connection;
      // 喵~防御：对端没声明频道时盲发会出错，这里直接跳过并告知调用方
      if (!CHANNEL.isRemotePresent(connection)) {
         return false;
      }
      // 频道就绪，真正发出
      CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
      // 告知调用方已发出
      return true;
   }

   public static void sendToServer(Object payload) {
      CHANNEL.sendToServer(payload);
   }

   public static boolean isRemotePresent(Connection connection) {
      return connection != null && CHANNEL.isRemotePresent(connection);
   }

   private static void handleGeoTeleport(GeoTpTeleportPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
      NetworkEvent.Context context = contextSupplier.get();
      context.enqueueWork(() -> Tellus.handleGeoTeleport(payload, context));
      context.setPacketHandled(true);
   }

   private static void handleManagedTerrainView(ManagedTerrainViewPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
      NetworkEvent.Context context = contextSupplier.get();
      context.enqueueWork(() -> Tellus.handleManagedTerrainView(payload, context));
      context.setPacketHandled(true);
   }

   private static void handleOpenMap(GeoTpOpenMapPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
      NetworkEvent.Context context = contextSupplier.get();
      context.enqueueWork(
         () -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT,
            () -> () -> com.yucareux.tellus.TellusClient.handleOpenMapPayload(payload)
         )
      );
      context.setPacketHandled(true);
   }

   private static void handleWeather(TellusWeatherPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
      NetworkEvent.Context context = contextSupplier.get();
      context.enqueueWork(
         () -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT,
            () -> () -> com.yucareux.tellus.TellusClient.handleWeatherPayload(payload)
         )
      );
      context.setPacketHandled(true);
   }

   private static void handleManagedTerrainStatus(
      ManagedTerrainStatusPayload payload,
      Supplier<NetworkEvent.Context> contextSupplier
   ) {
      NetworkEvent.Context context = contextSupplier.get();
      context.enqueueWork(
         () -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT,
            () -> () -> com.yucareux.tellus.TellusClient.handleManagedTerrainStatusPayload(payload)
         )
      );
      context.setPacketHandled(true);
   }
}
