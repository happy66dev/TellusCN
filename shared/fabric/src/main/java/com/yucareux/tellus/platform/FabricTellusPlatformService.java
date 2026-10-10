package com.yucareux.tellus.platform;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.TellusProtocol;
import com.yucareux.tellus.network.TellusServerHelloPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

public final class FabricTellusPlatformService implements TellusPlatformService {
   @Override
   public Path gameDir() {
      return FabricLoader.getInstance().getGameDir();
   }

   @Override
   public Path configDir() {
      return FabricLoader.getInstance().getConfigDir();
   }

   @Override
   public boolean isModLoaded(String modId) {
      return FabricLoader.getInstance().isModLoaded(modId);
   }

   @Override
   public void sendWeatherPayload(ServerPlayer player, TellusWeatherPayload payload) {
      // 只在客户端确实声明了该通道时才发，避免给原版 / 未装模组的客户端发无效包
      if (canSend(player, TellusProtocol.CHANNEL_REALTIME_WEATHER)) {
         ServerPlayNetworking.send(player, payload);
      }
   }

   @Override
   public void sendGeoTpOpenMapPayload(ServerPlayer player, GeoTpOpenMapPayload payload) {
      // 只在客户端确实声明了该通道时才发，避免给原版 / 未装模组的客户端发无效包
      if (canSend(player, TellusProtocol.CHANNEL_GEOTP_OPEN_MAP)) {
         ServerPlayNetworking.send(player, payload);
      }
   }

   @Override
   public void sendManagedTerrainStatusPayload(ServerPlayer player, ManagedTerrainStatusPayload payload) {
      // 只在客户端确实声明了该通道时才发，避免给原版 / 未装模组的客户端发无效包
      if (canSend(player, TellusProtocol.CHANNEL_MANAGED_TERRAIN_STATUS)) {
         ServerPlayNetworking.send(player, payload);
      }
   }

   @Override
   public boolean sendServerHelloPayload(ServerPlayer player, TellusServerHelloPayload payload) {
      // 喵~防御：客户端在刚进入游戏时可能还没来得及声明通道（Fabric 的注册包与 JOIN 事件存在竞争），
      // 此时返回 false，由调用方稍后重试，而不是盲发后让对端静默丢弃
      if (!canSend(player, TellusProtocol.CHANNEL_SERVER_HELLO)) {
         return false;
      }
      // 通道已就绪，真正发出握手包
      ServerPlayNetworking.send(player, payload);
      // 告知调用方已经发出，不必再重试
      return true;
   }

   @Override
   public String modVersion() {
      // 先按 TellusCN 的模组 id 查找；查不到时再退回上游 id，最后兜底 "unknown"，保证绝不返回 null
      return FabricLoader.getInstance()
         .getModContainer("telluscn")
         .or(() -> FabricLoader.getInstance().getModContainer("tellus"))
         .map(container -> container.getMetadata().getVersion().getFriendlyString())
         .orElse("unknown");
   }

   /**
    * 判断指定玩家是否已经声明了某个网络通道。
    *
    * 输入：目标玩家与通道名（不含命名空间，例如 server_hello）。
    * 输出：客户端声明过该通道返回 true，否则 false。
    * 边界条件：底层调用抛任何异常时一律返回 false（保守地不发），
    *          因为本方法常在服务端 tick / 网络回调里被调用，绝不能把异常抛出去。
    *
    * 说明：这里刻意使用「通道名」重载（而不是 payload 的 TYPE 重载），
    *       因为该重载在 MC 1.20.1 / 1.21.1 / 26.2 三个版本里签名一致，
    *       可以让同一份 shared/fabric 源码在三个目标上都编译通过。
    */
   private static boolean canSend(ServerPlayer player, String channelPath) {
      // 喵~防御：任何底层异常都当成「不能发」，绝不向外抛
      try {
         // 用 Tellus.id 拼出带命名空间的通道标识，交给 Fabric API 查询
         return ServerPlayNetworking.canSend(player, Tellus.id(channelPath));
      } catch (Throwable error) {
         return false;
      }
   }

   @Override
   public void registerDistantHorizonsLifecycle(Runnable onServerStart, Runnable onServerStop, Runnable onPlayerJoin) {
      ServerLifecycleEvents.SERVER_STARTING.register(server -> onServerStart.run());
      ServerLifecycleEvents.SERVER_STOPPING.register(server -> onServerStop.run());
      ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerJoin.run());
   }
}
