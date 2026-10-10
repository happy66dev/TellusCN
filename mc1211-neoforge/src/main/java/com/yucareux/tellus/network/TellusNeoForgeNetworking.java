package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.TellusClient;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class TellusNeoForgeNetworking {
   private TellusNeoForgeNetworking() {
   }

   public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("1");
      registrar.playToServer(GeoTpTeleportPayload.TYPE, GeoTpTeleportPayload.CODEC, Tellus::handleGeoTeleport);
      registrar.playToServer(ManagedTerrainViewPayload.TYPE, ManagedTerrainViewPayload.CODEC, Tellus::handleManagedTerrainView);

      if (FMLEnvironment.dist == Dist.CLIENT) {
         registerClientPayloadHandlers(registrar);
      } else {
         registrar.playToClient(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC, (payload, context) -> {});
         registrar.playToClient(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC, (payload, context) -> {});
         registrar.playToClient(ManagedTerrainStatusPayload.TYPE, ManagedTerrainStatusPayload.CODEC, (payload, context) -> {});
      }
   }

   private static void registerClientPayloadHandlers(PayloadRegistrar registrar) {
      registrar.playToClient(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC, TellusClient::handleOpenMapPayload);
      registrar.playToClient(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC, TellusClient::handleWeatherPayload);
      registrar.playToClient(ManagedTerrainStatusPayload.TYPE, ManagedTerrainStatusPayload.CODEC, TellusClient::handleManagedTerrainStatusPayload);
   }

   /**
    * 向指定玩家发送一个 payload。
    *
    * 输入：目标玩家与 payload。
    * 输出：发送成功返回 true；对端未声明该 payload 而被跳过返回 false。
    * 边界条件：底层在客户端不认识该 payload 时会抛异常，这里统一吞掉并返回 false，
    *          因为本方法常在服务端 tick / 网络回调里被调用，绝不能把异常抛出去。
    *
    * 主人注意：这里用 try/catch 做「能不能发」的判定，属于**未经运行期验证的尽力而为实现**。
    *          NeoForge 目标默认不参与构建，猫猫无法在本机实测；如果未来启用该目标，
    *          建议改为调用 NeoForge 官方的「连接是否注册了该 payload」查询 API。
    */
   public static boolean sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
      // 喵~防御：任何发送期异常都当成「发不出去」处理
      try {
         // 交给 NeoForge 的定向分发器
         PacketDistributor.sendToPlayer(player, payload);
         // 没有异常即视为发送成功
         return true;
      } catch (RuntimeException error) {
         // 对端不认识这个 payload 时会走到这里，按「暂不发」处理，调用方稍后重试
         return false;
      }
   }
}
