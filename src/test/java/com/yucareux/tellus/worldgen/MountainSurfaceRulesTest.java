package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountainSurfaceRulesTest {
   @Test
   void rawTreeCoverKeepsVegetatedSurfaceClassAtVisualEdges() {
      assertEquals(
         MountainSurfaceRules.ESA_TREE_COVER,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_TREE_COVER, MountainSurfaceRules.ESA_BARE)
      );
      assertEquals(
         MountainSurfaceRules.ESA_TREE_COVER,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_TREE_COVER, MountainSurfaceRules.ESA_SNOW_ICE)
      );
   }

   @Test
   void builtAndWaterLikeTerrainKeepTerrainClass() {
      assertEquals(
         MountainSurfaceRules.ESA_BUILT,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_BUILT, MountainSurfaceRules.ESA_TREE_COVER)
      );
      assertEquals(
         MountainSurfaceRules.ESA_WATER,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_WATER, MountainSurfaceRules.ESA_TREE_COVER)
      );
   }

   @Test
   void highRuggedBareMountainsDefaultToStoneWithoutSnowInfluence() {
      int stoneBacked = 0;
      int sampledMountains = 0;

      for (int x = 0; x < 512; x += 64) {
         for (int z = 0; z < 512; z += 64) {
            MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
               MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 245, 4, -1, false, 0.0F, x, z
            );
            if (surface.isMountain()) {
               sampledMountains++;
               if (surface.palette() == MountainSurfaceRules.ApproximatePalette.STONE) {
                  stoneBacked++;
               }
            }
         }
      }

      assertEquals(64, sampledMountains);
      assertEquals(64, stoneBacked);
   }

   @Test
   void exposedMountainRockUsesStone() {
      MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 180, 3, 0, false, 0.0F, 16, 32
      );

      assertEquals(MountainSurfaceRules.ApproximatePalette.STONE, surface.palette());
   }

   @Test
   void snowRequiresExistingSnowSource() {
      MountainSurfaceRules.ApproximateSurface dryHighBowl = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 260, 1, 2, false, 0.0F, 96, 128
      );
      MountainSurfaceRules.ApproximateSurface snowDataBowl = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 260, 1, 2, true, 0.0F, 96, 128
      );

      assertNotEquals(MountainSurfaceRules.ApproximatePalette.SNOW, dryHighBowl.palette());
      assertEquals(MountainSurfaceRules.ApproximatePalette.SNOW, snowDataBowl.palette());
   }

   @Test
   void steepSnowDataExposesRockOrSnowStreaks() {
      MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_SNOW_ICE, MountainSurfaceRules.ESA_SNOW_ICE, 250, 6, 0, false, 0.0F, 192, 224
      );

      assertNotEquals(MountainSurfaceRules.ApproximatePalette.SNOW, surface.palette());
      assertTrue(surface.isMountain() || surface.palette() == MountainSurfaceRules.ApproximatePalette.SNOW_STREAK);
   }

   @Test
   void ruggedNoDataHighlandFallsBackToMountainPalette() {
      MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_NO_DATA, MountainSurfaceRules.ESA_NO_DATA, 135, 3, 0, false, 0.0F, 32, 48
      );

      assertTrue(MountainSurfaceRules.qualifiesForMountainPalette(MountainSurfaceRules.ESA_NO_DATA, 135, 3, 0));
      assertFalse(MountainSurfaceRules.qualifiesForMountainPalette(MountainSurfaceRules.ESA_NO_DATA, 105, 2, 0));
      assertTrue(surface.isMountain());
   }

}
