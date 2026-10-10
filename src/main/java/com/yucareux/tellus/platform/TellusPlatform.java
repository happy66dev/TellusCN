package com.yucareux.tellus.platform;

import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.TellusServerHelloPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import java.nio.file.Path;
import java.util.Objects;
import java.util.ServiceLoader;
import net.minecraft.server.level.ServerPlayer;

public final class TellusPlatform {
   private static final TellusPlatformService SERVICE = loadService();

   private TellusPlatform() {
   }

   public static Path gameDir() {
      String override = System.getProperty("tellus.gameDir");
      if (override != null && !override.isBlank()) {
         return Path.of(override).toAbsolutePath().normalize();
      }
      return SERVICE.gameDir();
   }

   public static Path configDir() {
      String override = System.getProperty("tellus.configDir");
      if (override != null && !override.isBlank()) {
         return Path.of(override).toAbsolutePath().normalize();
      }
      return SERVICE.configDir();
   }

   public static boolean isModLoaded(String modId) {
      return SERVICE.isModLoaded(Objects.requireNonNull(modId, "modId"));
   }

   public static void sendWeatherPayload(ServerPlayer player, TellusWeatherPayload payload) {
      SERVICE.sendWeatherPayload(Objects.requireNonNull(player, "player"), Objects.requireNonNull(payload, "payload"));
   }

   public static void sendGeoTpOpenMapPayload(ServerPlayer player, GeoTpOpenMapPayload payload) {
      SERVICE.sendGeoTpOpenMapPayload(Objects.requireNonNull(player, "player"), Objects.requireNonNull(payload, "payload"));
   }

   public static void sendManagedTerrainStatusPayload(ServerPlayer player, ManagedTerrainStatusPayload payload) {
      SERVICE.sendManagedTerrainStatusPayload(Objects.requireNonNull(player, "player"), Objects.requireNonNull(payload, "payload"));
   }

   /**
    * 向指定玩家下发服务端握手包。
    *
    * 输入：目标玩家与握手包。
    * 输出：真正发出返回 true；对端未声明通道而跳过返回 false，调用方应稍后重试。
    */
   public static boolean sendServerHelloPayload(ServerPlayer player, TellusServerHelloPayload payload) {
      // 逐个做非空校验后转发给平台实现
      return SERVICE.sendServerHelloPayload(Objects.requireNonNull(player, "player"), Objects.requireNonNull(payload, "payload"));
   }

   /**
    * 取当前安装的 TellusCN 模组版本字符串。
    *
    * 输入：无。
    * 输出：版本字符串，保证非 null（取不到时实现类返回 "unknown"）。
    */
   public static String modVersion() {
      // 直接转发给平台实现
      return SERVICE.modVersion();
   }

   public static void registerDistantHorizonsLifecycle(Runnable onServerStart, Runnable onServerStop, Runnable onPlayerJoin) {
      SERVICE.registerDistantHorizonsLifecycle(
         Objects.requireNonNull(onServerStart, "onServerStart"),
         Objects.requireNonNull(onServerStop, "onServerStop"),
         Objects.requireNonNull(onPlayerJoin, "onPlayerJoin")
      );
   }

   private static TellusPlatformService loadService() {
      return ServiceLoader.load(TellusPlatformService.class)
         .findFirst()
         .orElseThrow(() -> new IllegalStateException("No Tellus platform service was registered"));
   }
}
