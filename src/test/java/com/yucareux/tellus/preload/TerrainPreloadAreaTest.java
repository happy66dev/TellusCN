package com.yucareux.tellus.preload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import org.junit.jupiter.api.Test;

class TerrainPreloadAreaTest {
   @Test
   void centeredAreaUsesChunksPerSide() {
      TerrainPreloadArea area = TerrainPreloadArea.centered(20.0, -103.0, 12, 30.0);

      assertEquals(12, area.chunksPerSide());
      assertEquals(144, area.totalChunks());
      assertEquals(11, area.maxChunkX() - area.minChunkX());
      assertEquals(11, area.maxChunkZ() - area.minChunkZ());
   }

   @Test
   void defaultLimitAllowsLargePreloadAreas() {
      assertEquals(8480, TerrainPreloadArea.maxChunksPerSide());
      assertEquals(5000, TerrainPreloadArea.clampChunksPerSide(5000));

      TerrainPreloadArea area = TerrainPreloadArea.centered(20.0, -103.0, 8480, 1.0);

      assertEquals(8480, area.chunksPerSide());
      assertEquals(71_910_400, area.totalChunks());
      assertEquals(8480 * TerrainPreloadArea.CHUNK_SIZE, area.maxBlockX() - area.minBlockX() + 1);
   }

   @Test
   void oneToOneLargeAreaKeepsItsExactChunkFootprint() {
      TerrainPreloadArea area = TerrainPreloadArea.centered(17.0732, -96.7266, 4092, 1.0);

      assertEquals(4092 * TerrainPreloadArea.CHUNK_SIZE, area.maxBlockX() - area.minBlockX() + 1);
      assertEquals(4092 * TerrainPreloadArea.CHUNK_SIZE, area.maxBlockZ() - area.minBlockZ() + 1);
      double longitudeSpan = area.eastLongitude() - area.westLongitude();
      assertTrue(longitudeSpan > 0.58 && longitudeSpan < 0.60);
   }

   @Test
   void worldScaleChangesMapFootprint() {
      TerrainPreloadArea smallScale = TerrainPreloadArea.centered(20.0, -103.0, 16, 10.0);
      TerrainPreloadArea largeScale = TerrainPreloadArea.centered(20.0, -103.0, 16, 30.0);
      double smallLongitudeSpan = smallScale.eastLongitude() - smallScale.westLongitude();
      double largeLongitudeSpan = largeScale.eastLongitude() - largeScale.westLongitude();

      assertTrue(largeLongitudeSpan > smallLongitudeSpan * 2.5);
      assertTrue(largeLongitudeSpan < smallLongitudeSpan * 3.5);
   }

   @Test
   void settingsOverridesApplyThroughCodec() {
      TerrainPreloadSettingsOverrides overrides = TerrainPreloadSettingsOverrides.from(EarthGeneratorSettings.DEFAULT)
         .withWorldScale(12.0)
         .withEnableRoads(false)
         .withEnableBuildings(false)
         .withEnableWater(false)
         .withAddStructures(false);

      EarthGeneratorSettings settings = overrides.apply(EarthGeneratorSettings.DEFAULT);

      assertEquals(12.0, settings.worldScale(), 0.0001);
      assertEquals(false, settings.enableRoads());
      assertEquals(false, settings.enableBuildings());
      assertTrue(settings.enableWater());
      assertEquals(false, settings.addVillages());
      assertEquals(false, settings.addStrongholds());
   }

   @Test
   void preloadOverridesCanUseSelectedAreaCenterAsSpawnpoint() {
      EarthGeneratorSettings settings = TerrainPreloadSettingsOverrides.from(EarthGeneratorSettings.DEFAULT)
         .apply(EarthGeneratorSettings.DEFAULT, 35.3606, 138.7274);

      assertEquals(35.3606, settings.spawnLatitude(), 0.0001);
      assertEquals(138.7274, settings.spawnLongitude(), 0.0001);
   }
}
