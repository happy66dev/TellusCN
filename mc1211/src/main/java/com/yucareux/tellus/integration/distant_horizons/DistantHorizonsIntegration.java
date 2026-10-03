package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public final class DistantHorizonsIntegration {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final String VOXY_MOD_ID = "voxy";
   private static final int DEFAULT_FOLIAGE_COLOR = 0x48B518;
   private static final int BIRCH_FOLIAGE_COLOR = 0x80A755;
   private static final int SPRUCE_FOLIAGE_COLOR = 0x619961;
   private static final int OAK_LEAVES_TEXTURE_COLOR = 0x909090;
   private static final int BIRCH_LEAVES_TEXTURE_COLOR = 0x838182;
   private static final int SPRUCE_LEAVES_TEXTURE_COLOR = 0x7E7E7E;
   private static final int JUNGLE_LEAVES_TEXTURE_COLOR = 0x9D9A90;
   private static final int ACACIA_LEAVES_TEXTURE_COLOR = 0x959595;
   private static final int DARK_OAK_LEAVES_TEXTURE_COLOR = 0x979797;
   private static final int MANGROVE_LEAVES_TEXTURE_COLOR = 0x828181;
   private static final int CHERRY_LEAVES_TEXTURE_COLOR = 0xE5ADC2;
   private static final int PALE_OAK_LEAVES_TEXTURE_COLOR = 0x757A73;
   private static final int AZALEA_LEAVES_TEXTURE_COLOR = 0x5A732C;
   private static final int FLOWERING_AZALEA_LEAVES_TEXTURE_COLOR = 0x646F3D;
   private static final Block PALE_OAK_LEAVES_BLOCK = blockByField("PALE_OAK_LEAVES");
   private static final Map<String, EarthChunkGenerator> TELLUS_GENERATORS_BY_DIMENSION = new ConcurrentHashMap<>();

   private DistantHorizonsIntegration() {
   }

   private static boolean checkApiVersion() {
      int apiMajor = DhApi.getApiMajorVersion();
      int apiMinor = DhApi.getApiMinorVersion();
      int apiPatch = DhApi.getApiPatchVersion();
      if (apiMajor < 7) {
         LOGGER.warn(
            "Detected Distant Horizons {}, but API {}.{}.{} is too old - won't enable integration with Tellus",
            new Object[]{DhApi.getModVersion(), apiMajor, apiMinor, apiPatch}
         );
         return false;
      } else {
         LOGGER.info(
            "Detected Distant Horizons {} (API {}.{}.{}), enabling integration with Tellus", new Object[]{DhApi.getModVersion(), apiMajor, apiMinor, apiPatch}
         );
         return true;
      }
   }

   public static void bootstrap() {
      if (checkApiVersion()) {
         DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> param) {
               DistantHorizonsIntegration.onLevelLoad(param.value.levelWrapper);
            }
         });
         DhApiEventRegister.on(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
            public void onLevelUnload(DhApiEventParam<DhApiLevelUnloadEvent.EventParam> param) {
               DistantHorizonsIntegration.onLevelUnload(param.value.levelWrapper);
            }
         });
         registerLeafColorOverrideEvents();
      }
   }

   private static void onLevelLoad(IDhApiLevelWrapper levelWrapper) {
      if (levelWrapper.getWrappedMcObject() instanceof ServerLevel level && level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator) {
         EarthGeneratorSettings settings = generator.settings();
         if (settings.voxyChunkPregenEnabled() && FabricLoader.getInstance().isModLoaded(VOXY_MOD_ID)) {
            LOGGER.info("Voxy pregen enabled; skipping Tellus Distant Horizons integration override");
            return;
         }

         EDhApiDistantGeneratorMode dhGeneratorMode = currentDistantGeneratorMode();
         if (dhGeneratorMode == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY) {
            LOGGER.info("Distant Horizons generator mode set to pre-existing only; skipping Tellus generator override");
            return;
         }

         TELLUS_GENERATORS_BY_DIMENSION.put(levelWrapper.getDimensionName(), generator);
         if (shouldUseChunkLodGenerator(settings, dhGeneratorMode)) {
            if (settings.distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED) {
               LOGGER.info("Distant Horizons render mode set to detailed; using chunk-based generator");
            } else {
               LOGGER.info("Distant Horizons generator mode set to internal server; using chunk-based generator");
            }

            TellusChunkLodGenerator chunkGenerator = new TellusChunkLodGenerator(level);
            DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, chunkGenerator);
            if (!result.success) {
               LOGGER.warn("Failed to register Tellus chunk LOD generator: {}", result.message);
            }

            return;
         }

         TellusLodGenerator lodGenerator = new TellusLodGenerator(levelWrapper, generator);
         DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, lodGenerator);
         if (!result.success) {
            LOGGER.warn("Failed to register Tellus LOD generator: {}", result.message);
         }
      }
   }

   private static void onLevelUnload(IDhApiLevelWrapper levelWrapper) {
      if (levelWrapper.getWrappedMcObject() instanceof ServerLevel level && level.getChunkSource().getGenerator() instanceof EarthChunkGenerator) {
         TELLUS_GENERATORS_BY_DIMENSION.remove(levelWrapper.getDimensionName());
      }
   }

   private static void registerLeafColorOverrideEvents() {
      DhApiEventRegister.on(DhApiBlockStateWrapperCreatedEvent.class, new DhApiBlockStateWrapperCreatedEvent() {
         public void blockStateWrapperCreated(DhApiEventParam<DhApiBlockStateWrapperCreatedEvent.EventParam> event) {
            IDhApiBlockStateWrapper wrapper = event.value.getBlockStateWrapper();
            if (wrapper.getWrappedMcObject() instanceof BlockState blockState && isCorrectedLodLeafBlock(blockState.getBlock())) {
               event.value.setAllowApiColorOverride(true);
            }
         }
      });
      DhApiEventRegister.on(DhApiBlockColorOverrideEvent.class, new DhApiBlockColorOverrideEvent() {
         public void onBlockColorOverridden(DhApiEventParam<DhApiBlockColorOverrideEvent.EventParam> event) {
            DistantHorizonsIntegration.overrideLeafColor(event.value);
         }
      });
   }

   private static void overrideLeafColor(DhApiBlockColorOverrideEvent.EventParam event) {
      IDhApiLevelWrapper levelWrapper = event.getLevelWrapper();
      if (levelWrapper == null || !(event.getBlockStateWrapper().getWrappedMcObject() instanceof BlockState blockState)) {
         return;
      }

      EarthChunkGenerator generator = TELLUS_GENERATORS_BY_DIMENSION.get(levelWrapper.getDimensionName());
      if (generator == null) {
         return;
      }

      int color = correctedLeafColor(blockState.getBlock(), generator, event.getBlockPosX(), event.getBlockPosZ());
      if (color >= 0) {
         event.setColor(event.getAlpha(), red(color), green(color), blue(color));
      }
   }

   private static int correctedLeafColor(Block block, EarthChunkGenerator generator, int blockX, int blockZ) {
      int textureColor = leafTextureColor(block);
      if (textureColor < 0) {
         return -1;
      } else if (isUntintedLeafBlock(block)) {
         return textureColor;
      }

      if (block == Blocks.BIRCH_LEAVES) {
         return multiplyRgb(textureColor, BIRCH_FOLIAGE_COLOR);
      } else if (block == Blocks.SPRUCE_LEAVES) {
         return multiplyRgb(textureColor, SPRUCE_FOLIAGE_COLOR);
      }

      return multiplyRgb(textureColor, tellusFoliageColor(generator, blockX, blockZ));
   }

   private static int tellusFoliageColor(EarthChunkGenerator generator, int blockX, int blockZ) {
      try {
         if (generator.getBiomeSource() instanceof EarthBiomeSource earthBiomeSource) {
            Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(blockX, blockZ);
            return biome.value().getFoliageColor();
         }
      } catch (RuntimeException error) {
         return DEFAULT_FOLIAGE_COLOR;
      }

      return DEFAULT_FOLIAGE_COLOR;
   }

   private static int leafTextureColor(Block block) {
      if (block == Blocks.OAK_LEAVES) {
         return OAK_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.BIRCH_LEAVES) {
         return BIRCH_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.SPRUCE_LEAVES) {
         return SPRUCE_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.JUNGLE_LEAVES) {
         return JUNGLE_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.ACACIA_LEAVES) {
         return ACACIA_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.DARK_OAK_LEAVES) {
         return DARK_OAK_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.MANGROVE_LEAVES) {
         return MANGROVE_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.CHERRY_LEAVES) {
         return CHERRY_LEAVES_TEXTURE_COLOR;
      } else if (block == PALE_OAK_LEAVES_BLOCK) {
         return PALE_OAK_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.AZALEA_LEAVES) {
         return AZALEA_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.FLOWERING_AZALEA_LEAVES) {
         return FLOWERING_AZALEA_LEAVES_TEXTURE_COLOR;
      }

      return -1;
   }

   private static boolean isUntintedLeafBlock(Block block) {
      return block == Blocks.CHERRY_LEAVES
         || block == PALE_OAK_LEAVES_BLOCK
         || block == Blocks.AZALEA_LEAVES
         || block == Blocks.FLOWERING_AZALEA_LEAVES;
   }

   private static boolean isCorrectedLodLeafBlock(Block block) {
      return block == Blocks.OAK_LEAVES
         || block == Blocks.BIRCH_LEAVES
         || block == Blocks.SPRUCE_LEAVES
         || block == Blocks.JUNGLE_LEAVES
         || block == Blocks.ACACIA_LEAVES
         || block == Blocks.DARK_OAK_LEAVES
         || block == Blocks.MANGROVE_LEAVES
         || block == Blocks.CHERRY_LEAVES
         || block == PALE_OAK_LEAVES_BLOCK
         || block == Blocks.AZALEA_LEAVES
         || block == Blocks.FLOWERING_AZALEA_LEAVES;
   }

   private static int multiplyRgb(int textureColor, int tintColor) {
      int red = multiplyColorChannel(red(textureColor), red(tintColor));
      int green = multiplyColorChannel(green(textureColor), green(tintColor));
      int blue = multiplyColorChannel(blue(textureColor), blue(tintColor));
      return red << 16 | green << 8 | blue;
   }

   private static int multiplyColorChannel(int textureChannel, int tintChannel) {
      return (textureChannel * tintChannel + 127) / 255;
   }

   private static int red(int color) {
      return color >> 16 & 0xFF;
   }

   private static int green(int color) {
      return color >> 8 & 0xFF;
   }

   private static int blue(int color) {
      return color & 0xFF;
   }

   private static Block blockByField(String fieldName) {
      try {
         Object value = Blocks.class.getField(fieldName).get(null);
         return value instanceof Block block ? block : null;
      } catch (IllegalAccessException | NoSuchFieldException error) {
         return null;
      }
   }

   private static boolean shouldUseChunkLodGenerator(EarthGeneratorSettings settings, EDhApiDistantGeneratorMode dhGeneratorMode) {
      return settings.distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED
         || dhGeneratorMode == EDhApiDistantGeneratorMode.INTERNAL_SERVER;
   }

   private static EDhApiDistantGeneratorMode currentDistantGeneratorMode() {
      try {
         return DhApi.Delayed.configs == null ? null : DhApi.Delayed.configs.worldGenerator().distantGeneratorMode().getValue();
      } catch (RuntimeException error) {
         LOGGER.debug("Failed to read Distant Horizons generator mode", error);
         return null;
      }
   }
}
