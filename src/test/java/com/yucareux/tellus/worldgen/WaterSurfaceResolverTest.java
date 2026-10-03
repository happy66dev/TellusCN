package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterSurfaceResolverTest {
   @Test
   void raisesDeepInlandShoreFloorNearBank() {
      assertEquals(79, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, 10));
      assertEquals(77, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, 20));
      assertEquals(75, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, 24));
      assertEquals(75, WaterSurfaceResolver.rampedInlandShoreFloorForSteps(42, 80, 3));
   }

   @Test
   void keepsExistingShallowFloorAndUnknownDistance() {
      assertEquals(79, WaterSurfaceResolver.rampedInlandShoreFloor(79, 80, 10));
      assertEquals(42, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, Integer.MAX_VALUE));
   }

   @Test
   void rejectsLakeCellsThatWouldRequireExcessiveCut() {
      assertTrue(WaterSurfaceResolver.shouldRejectLakeWaterCell(100, 80, 8));
      assertFalse(WaterSurfaceResolver.shouldRejectLakeWaterCell(88, 80, 8));
      assertFalse(WaterSurfaceResolver.shouldRejectLakeWaterCell(79, 80, 8));
   }

   @Test
   void capsLakeSurfaceToLowestAdjacentBank() {
      assertEquals(80, WaterSurfaceResolver.capInlandLakeWaterSurface(84, 80));
      assertEquals(84, WaterSurfaceResolver.capInlandLakeWaterSurface(84, Integer.MAX_VALUE));
      assertEquals(78, WaterSurfaceResolver.capInlandLakeWaterSurface(78, 80));
   }

   @Test
   void fullChunkLakeSurfaceCanLowerAnEarlierFastLodEstimate() {
      assertEquals(80, WaterSurfaceResolver.lowestStableLakeSurface(84, 80));
      assertEquals(80, WaterSurfaceResolver.lowestStableLakeSurface(80, 84));
   }

   @Test
   void usesLooserDefaultInlandCutBudgets() {
      assertEquals(12, WaterSurfaceResolver.lakeMaxTerrainCut());
      assertEquals(6, WaterSurfaceResolver.riverMaxTerrainCut());
      assertFalse(WaterSurfaceResolver.shouldRejectLakeWaterCell(92, 80, WaterSurfaceResolver.lakeMaxTerrainCut()));
      assertTrue(WaterSurfaceResolver.shouldRejectLakeWaterCell(93, 80, WaterSurfaceResolver.lakeMaxTerrainCut()));
      assertFalse(WaterSurfaceResolver.shouldRejectRiverWaterCell(120, 80, WaterSurfaceResolver.riverMaxTerrainCut()));
      assertTrue(WaterSurfaceResolver.shouldRejectRiverWaterCell(79, 80, WaterSurfaceResolver.riverMaxTerrainCut()));
   }

   @Test
   void groupsNearbyEsaLakeSamplesByStableBasinAndHeightBand() {
      long bodyKey = WaterSurfaceResolver.stableEsaLakeBodyKey(100, 120, 80);

      assertEquals(bodyKey, WaterSurfaceResolver.stableEsaLakeBodyKey(510, 120, 83));
      assertNotEquals(bodyKey, WaterSurfaceResolver.stableEsaLakeBodyKey(512, 120, 80));
      assertNotEquals(bodyKey, WaterSurfaceResolver.stableEsaLakeBodyKey(100, 120, 84));
   }

   @Test
   void quantizesRiverSurfaceIntoLongitudinalSteps() {
      assertEquals(80, WaterSurfaceResolver.steppedRiverSurfaceAt(0.0, 80, 85, 1));
      assertEquals(82, WaterSurfaceResolver.steppedRiverSurfaceAt(0.49, 80, 85, 1));
      assertEquals(85, WaterSurfaceResolver.steppedRiverSurfaceAt(1.0, 80, 85, 1));
      assertEquals(82, WaterSurfaceResolver.steppedRiverSurfaceAt(0.5, 80, 86, 2));
      assertEquals(80, WaterSurfaceResolver.steppedRiverSurfaceAt(0.5, 80, 80, 1));
   }

   @Test
   void keepsBroadLineConnectedWaterOutOfRiverMode() {
      assertFalse(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(32, 20, 1.6, 24, 12, 420, 12, true, false, false));
      assertFalse(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(64, 20, 3.2, 24, 12, 512, 0, false, true, false));
   }

   @Test
   void keepsNarrowLineDominatedWaterInRiverMode() {
      assertTrue(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(32, 2, 16.0, 24, 12, 48, 40, false, false, false));
      assertTrue(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(4, 1, 4.0, 24, 12, 4, 4, false, false, false));
   }

   @Test
   void polygonRiversDoNotUseDirectLineWaterMask() {
      OsmWaterFeature polygonRiver = new OsmWaterFeature(
         1L,
         false,
         false,
         OsmWaterKind.RIVER,
         new double[][]{{0.0, 0.001, 0.001, 0.0, 0.0}},
         new double[][]{{0.0, 0.0, 0.001, 0.001, 0.0}}
      );
      OsmWaterFeature lineRiver = new OsmWaterFeature(
         2L,
         true,
         false,
         OsmWaterKind.RIVER,
         new double[][]{{0.0, 0.001}},
         new double[][]{{0.0, 0.001}}
      );

      assertTrue(polygonRiver.flowingWater());
      assertFalse(WaterSurfaceResolver.isLineWaterGeometry(polygonRiver));
      assertTrue(WaterSurfaceResolver.isLineWaterGeometry(lineRiver));
   }

   @Test
   void rejectsRiverCellsOnlyWhenWaterWouldSitAboveTerrain() {
      assertFalse(WaterSurfaceResolver.shouldRejectRiverWaterCell(90, 85, 4));
      assertFalse(WaterSurfaceResolver.shouldRejectRiverWaterCell(85, 85, 4));
      assertTrue(WaterSurfaceResolver.shouldRejectRiverWaterCell(83, 85, 4));
   }

   @Test
   void riverSurfaceAdjustmentOnlyCapsAboveTerrain() {
      assertEquals(85, WaterSurfaceResolver.adjustRiverWaterSurfaceForTerrain(85, 90, true, 4));
      assertEquals(85, WaterSurfaceResolver.adjustRiverWaterSurfaceForTerrain(85, 90, false, 4));
      assertEquals(90, WaterSurfaceResolver.adjustRiverWaterSurfaceForTerrain(92, 90, true, 4));
   }

   @Test
   void rejectedRiverCellsCapSurfaceToTerrain() {
      assertEquals(90, WaterSurfaceResolver.repairRejectedRiverWaterSurface(92, 90, 4));
      assertEquals(85, WaterSurfaceResolver.repairRejectedRiverWaterSurface(85, 89, 4));
   }

   @Test
   void fallbackOceanDepthIsDeeperThanMinimumWaterClamp() {
      assertTrue(WaterSurfaceResolver.fallbackOceanDepthBlocks(0, 0, 30.0, 1.0) > 1);
      assertEquals(
         WaterSurfaceResolver.fallbackOceanDepthBlocks(128, -64, 30.0, 1.0),
         WaterSurfaceResolver.fallbackOceanDepthBlocks(128, -64, 30.0, 1.0)
      );
   }

   @Test
   void experimentalOceanClampUsesReservedWorldFloorInsteadOfLegacyShellDepth() {
      assertEquals(-2_040, WaterSurfaceResolver.clampOceanTerrainHeight(-3_000, 0, -2_040, true));
      assertEquals(-900, WaterSurfaceResolver.clampOceanTerrainHeight(-900, 0, -2_040, true));
      assertEquals(-3_000, WaterSurfaceResolver.clampOceanTerrainHeight(-3_000, 0, -2_040, false));
   }

   @Test
   void landCoverWaterCandidatesIncludeEsaWaterAndNoDataOcean() {
      assertTrue(
         WaterSurfaceResolver.landCoverMayContainWater(80, TellusLandMaskSource.LandMaskSample.unknown(), Integer.MAX_VALUE, 64)
      );
      assertTrue(WaterSurfaceResolver.landCoverMayContainWater(0, TellusLandMaskSource.LandMaskSample.unknown(), 64, 64));
      assertTrue(WaterSurfaceResolver.landCoverMayContainWater(0, TellusLandMaskSource.LandMaskSample.known(false), 90, 64));
      assertFalse(WaterSurfaceResolver.landCoverMayContainWater(0, TellusLandMaskSource.LandMaskSample.known(true), 90, 64));
      assertFalse(WaterSurfaceResolver.landCoverMayContainWater(10, TellusLandMaskSource.LandMaskSample.known(false), 64, 64));
   }

   @Test
   void coarseOsmWaterDoesNotFallBackToEsaWater() {
      assertFalse(WaterSurfaceResolver.shouldEmitCoarseFallbackWater(true, 80, false));
      assertTrue(WaterSurfaceResolver.shouldEmitCoarseFallbackWater(false, 80, false));
      assertTrue(WaterSurfaceResolver.shouldEmitCoarseFallbackWater(true, 10, true));
   }

   @Test
   void capsRiverWaterSurfaceToTerrainAndBank() {
      assertEquals(90, WaterSurfaceResolver.capInlandRiverWaterSurface(96, 90, Integer.MAX_VALUE));
      assertEquals(86, WaterSurfaceResolver.capInlandRiverWaterSurface(96, 90, 86));
      assertEquals(84, WaterSurfaceResolver.capInlandRiverWaterSurface(84, 90, 86));
   }

   @Test
   void lowestRiverSurfaceUsesLowestRiverOrBankAltitude() {
      assertEquals(84, WaterSurfaceResolver.lowestInlandRiverWaterSurface(84, 90, 100));
      assertEquals(80, WaterSurfaceResolver.lowestInlandRiverWaterSurface(84, 80, 100));
      assertEquals(100, WaterSurfaceResolver.lowestInlandRiverWaterSurface(Integer.MAX_VALUE, Integer.MAX_VALUE, 100));
   }

   @Test
   void directLineRiverWaterSitsOneBlockInsideTerrain() {
      assertEquals(90, WaterSurfaceResolver.directLineRiverWaterSurface(90));
      assertEquals(Integer.MAX_VALUE, WaterSurfaceResolver.directLineRiverWaterSurface(Integer.MAX_VALUE));
      assertEquals(89, WaterSurfaceResolver.directLineRiverTerrainSurface(90));
   }

   @Test
   void narrowLineWaterCellExcludesWideWaterFootprints() {
      boolean[] waterMask = new boolean[9];
      boolean[] lineWaterMask = new boolean[9];
      waterMask[1] = true;
      waterMask[4] = true;
      waterMask[7] = true;
      lineWaterMask[4] = true;

      assertTrue(WaterSurfaceResolver.isNarrowLineWaterCell(4, 3, waterMask, lineWaterMask));

      waterMask[3] = true;

      assertFalse(WaterSurfaceResolver.isNarrowLineWaterCell(4, 3, waterMask, lineWaterMask));
   }

}
