package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
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
      registrar.playToClient(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC);
      registrar.playToClient(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC);
      registrar.playToClient(ManagedTerrainStatusPayload.TYPE, ManagedTerrainStatusPayload.CODEC);
   }

   /**
    * 把 payload 定向发给某个玩家。
    *
    * 输入：目标玩家与待发 payload。
    * 输出：真正发出返回 true；对端不认识该 payload 时返回 false，由调用方决定是否重试。
    * 边界条件：任何发送期运行时异常都按「发不出去」处理，绝不让异常冒泡打断服务端 tick。
    *
    * 主人注意：本目标默认不参与构建，以下实现**未经运行期验证**。另外本类目前只注册了 5 个既有
    *          payload，两个握手 payload 尚未注册；若未来启用该目标，需要先补齐注册再依赖本方法。
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
