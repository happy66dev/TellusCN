package com.yucareux.tellus.platform;

import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.TellusForgeNetworking;
import com.yucareux.tellus.network.TellusServerHelloPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import java.nio.file.Path;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

public final class ForgeTellusPlatformService implements TellusPlatformService {
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
      TellusForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public void sendGeoTpOpenMapPayload(ServerPlayer player, GeoTpOpenMapPayload payload) {
      TellusForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public void sendManagedTerrainStatusPayload(ServerPlayer player, ManagedTerrainStatusPayload payload) {
      TellusForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public boolean sendServerHelloPayload(ServerPlayer player, TellusServerHelloPayload payload) {
      // 由网络层判断对端是否认识该 payload（isRemotePresent），发不出去时返回 false，调用方稍后重试
      return TellusForgeNetworking.sendToPlayer(player, payload);
   }

   @Override
   public String modVersion() {
      // 喵~防御：Forge 目标的 ModList API 未经本机实测，任何异常都兜底成 "unknown"，绝不返回 null
      try {
         // 先按 TellusCN 的模组 id 查找，查不到时退回上游模组 id
         return ModList.get()
            .getModContainerById("telluscn")
            .or(() -> ModList.get().getModContainerById("tellus"))
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("unknown");
      } catch (Throwable error) {
         // 版本号只用于展示与日志，取不到不应影响任何功能
         return "unknown";
      }
   }

   @Override
   public void registerDistantHorizonsLifecycle(Runnable onServerStart, Runnable onServerStop, Runnable onPlayerJoin) {
      MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> onServerStart.run());
      MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> onServerStop.run());
      MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> onPlayerJoin.run());
   }
}
