package com.yucareux.tellus.platform;

import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.TellusNeoForgeNetworking;
import com.yucareux.tellus.network.TellusServerHelloPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class NeoForgeTellusPlatformService implements TellusPlatformService {
   @Override
   public Path gameDir() {
      return FMLPaths.GAMEDIR.get();
   }

   @Override
   public Path configDir() {
      return FMLPaths.CONFIGDIR.get();
   }

   @Override
   public boolean isModLoaded(String modId) {
      return ModList.get().isLoaded(modId);
   }

   @Override
   public void sendWeatherPayload(ServerPlayer player, TellusWeatherPayload payload) {
      TellusNeoForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public void sendGeoTpOpenMapPayload(ServerPlayer player, GeoTpOpenMapPayload payload) {
      TellusNeoForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public void sendManagedTerrainStatusPayload(ServerPlayer player, ManagedTerrainStatusPayload payload) {
      TellusNeoForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public boolean sendServerHelloPayload(ServerPlayer player, TellusServerHelloPayload payload) {
      // 由网络层判断对端是否认识该 payload，发不出去时返回 false，调用方稍后重试
      return TellusNeoForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public String modVersion() {
      // 喵~防御：NeoForge 目标的 ModList API 未经本机实测，任何异常都兜底成 "unknown"，绝不返回 null
      try {
         // 先按 TellusCN 的模组 id 查找
         var container = ModList.get().getModContainerById("telluscn");
         // 查不到时退回上游模组 id
         if (container.isEmpty()) {
            container = ModList.get().getModContainerById("tellus");
         }
         // 取模组版本字符串；仍然取不到就用 unknown
         return container.map(entry -> entry.getModInfo().getVersion().toString()).orElse("unknown");
      } catch (Throwable error) {
         // 版本号只用于展示与日志，取不到不应影响任何功能
         return "unknown";
      }
   }

   @Override
   public void registerDistantHorizonsLifecycle(Runnable onServerStart, Runnable onServerStop, Runnable onPlayerJoin) {
      NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> onServerStart.run());
      NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> onServerStop.run());
      NeoForge.EVENT_BUS.addListener((PlayerLoggedInEvent event) -> onPlayerJoin.run());
   }
}
