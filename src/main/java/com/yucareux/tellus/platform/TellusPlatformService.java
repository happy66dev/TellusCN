package com.yucareux.tellus.platform;

import java.nio.file.Path;

public interface TellusPlatformService {
   Path gameDir();

   Path configDir();

   boolean isModLoaded(String modId);
}
