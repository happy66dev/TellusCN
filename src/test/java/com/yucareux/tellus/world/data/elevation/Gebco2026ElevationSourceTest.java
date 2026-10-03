package com.yucareux.tellus.world.data.elevation;

import com.yucareux.tellus.worldgen.EarthProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Gebco2026ElevationSourceTest {
   private static final double GEBCO_PIXEL_DEGREES = 15.0 / 3600.0;

   @Test
   void normalizesLongitudeIntoPeriodicGeotiffDomain() {
      assertEquals(-180.0, Gebco2026ElevationSource.normalizeLongitude(180.0), 0.0);
      assertEquals(-179.75, Gebco2026ElevationSource.normalizeLongitude(180.25), 0.0);
      assertEquals(179.75, Gebco2026ElevationSource.normalizeLongitude(-180.25), 0.0);
      assertEquals(-90.0, Gebco2026ElevationSource.normalizeLongitude(270.0), 0.0);
      assertEquals(90.0, Gebco2026ElevationSource.normalizeLongitude(-270.0), 0.0);
   }

   @Test
   void wrapsLongitudesBeforeSelectingTile() {
      assertEquals(
         "gebco_2026_n90.0_s0.0_w-180.0_e-90.0_geotiff.tif",
         Gebco2026ElevationSource.tileKeyForLatLon(10.0, 180.0).filename()
      );
      assertEquals(
         "gebco_2026_n90.0_s0.0_w-180.0_e-90.0_geotiff.tif",
         Gebco2026ElevationSource.tileKeyForLatLon(10.0, 540.0).filename()
      );
      assertEquals(
         "gebco_2026_n90.0_s0.0_w90.0_e180.0_geotiff.tif",
         Gebco2026ElevationSource.tileKeyForLatLon(10.0, -180.25).filename()
      );
   }

   @Test
   void selectsExpectedHemisphereTilesAtBoundaries() {
      assertEquals(
         "gebco_2026_n90.0_s0.0_w-90.0_e0.0_geotiff.tif",
         Gebco2026ElevationSource.tileKeyForLatLon(0.0, -90.0).filename()
      );
      assertEquals(
         "gebco_2026_n0.0_s-90.0_w0.0_e90.0_geotiff.tif",
         Gebco2026ElevationSource.tileKeyForLatLon(-0.0001, 0.0).filename()
      );
      assertEquals(
         "gebco_2026_n90.0_s0.0_w90.0_e180.0_geotiff.tif",
         Gebco2026ElevationSource.tileKeyForLatLon(89.999, 179.999).filename()
      );
   }

   @Test
   void rejectsInvalidLatitudes() {
      assertNull(Gebco2026ElevationSource.tileKeyForLatLon(90.1, 0.0));
      assertNull(Gebco2026ElevationSource.tileKeyForLatLon(-90.1, 0.0));
   }

   @Test
   void mapsPixelCenterRegisteredCoordinatesToExactPixelCenters() {
      assertEquals(0.0, Gebco2026ElevationSource.TileFile.pixelCenterCoordinate(0.0, GEBCO_PIXEL_DEGREES * 0.5, GEBCO_PIXEL_DEGREES), 1.0E-9);
      assertEquals(1.0, Gebco2026ElevationSource.TileFile.pixelCenterCoordinate(0.0, GEBCO_PIXEL_DEGREES * 1.5, GEBCO_PIXEL_DEGREES), 1.0E-9);
      assertEquals(21599.0, Gebco2026ElevationSource.TileFile.pixelCenterCoordinate(0.0, GEBCO_PIXEL_DEGREES * 21599.5, GEBCO_PIXEL_DEGREES), 1.0E-6);
   }

   @Test
   void usesSameBlockProjectionAsInlandDemSources() {
      double worldScale = 30.0;
      double latitude = 36.1234;
      double longitude = -122.4567;
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double blockX = longitude * blocksPerDegree;
      double blockZ = EarthProjection.latToBlockZ(latitude, worldScale);
      double projectedLatitude = EarthProjection.blockZToLat(blockZ, worldScale);
      double projectedLongitude = blockX / blocksPerDegree;

      assertEquals(latitude, projectedLatitude, 1.0E-9);
      assertEquals(longitude, projectedLongitude, 1.0E-9);
      assertEquals(
         Gebco2026ElevationSource.tileKeyForLatLon(latitude, longitude).filename(),
         Gebco2026ElevationSource.tileKeyForLatLon(projectedLatitude, projectedLongitude).filename()
      );
   }

   @Test
   void backsOffRemoteRetriesAndCapsTheDelay() {
      assertEquals(30000L, Gebco2026ElevationSource.retryDelayMillis(1));
      assertEquals(60000L, Gebco2026ElevationSource.retryDelayMillis(2));
      assertEquals(900000L, Gebco2026ElevationSource.retryDelayMillis(6));
      assertEquals(900000L, Gebco2026ElevationSource.retryDelayMillis(30));
   }

   @Test
   void startsOnlyOneRemoteProbeAfterCooldown() {
      assertFalse(Gebco2026ElevationSource.shouldStartRemoteProbe(999L, 1000L, false));
      assertFalse(Gebco2026ElevationSource.shouldStartRemoteProbe(1000L, 1000L, true));
      assertTrue(Gebco2026ElevationSource.shouldStartRemoteProbe(1000L, 1000L, false));
   }

   @Test
   void gebcoIsEnabledByDefault() {
      assertTrue(Gebco2026ElevationSource.isEnabled());
   }
}
