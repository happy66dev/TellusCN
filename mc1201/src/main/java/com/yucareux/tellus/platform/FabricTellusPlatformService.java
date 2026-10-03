package com.yucareux.tellus.platform;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

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
}
