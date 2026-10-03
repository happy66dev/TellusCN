package com.yucareux.tellus.platform;

import java.nio.file.Path;
import java.util.Objects;
import java.util.ServiceLoader;

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

   private static TellusPlatformService loadService() {
      return ServiceLoader.load(TellusPlatformService.class)
         .findFirst()
         .orElseThrow(() -> new IllegalStateException("No Tellus platform service was registered"));
   }
}
