package com.yucareux.tellus.world.data.cover;

import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TellusLandCoverSourceTest {
   @Test
   void mapsEveryOvertureLandCoverSubtypeToStableWorldgenClasses() {
      assertEquals(10, TellusLandCoverSource.coverClassForSubtype("forest"));
      assertEquals(20, TellusLandCoverSource.coverClassForSubtype("shrub"));
      assertEquals(30, TellusLandCoverSource.coverClassForSubtype("grass"));
      assertEquals(40, TellusLandCoverSource.coverClassForSubtype("crop"));
      assertEquals(50, TellusLandCoverSource.coverClassForSubtype("urban"));
      assertEquals(60, TellusLandCoverSource.coverClassForSubtype("barren"));
      assertEquals(70, TellusLandCoverSource.coverClassForSubtype("snow"));
      assertEquals(90, TellusLandCoverSource.coverClassForSubtype("wetland"));
      assertEquals(95, TellusLandCoverSource.coverClassForSubtype("mangrove"));
      assertEquals(100, TellusLandCoverSource.coverClassForSubtype("moss"));
      assertEquals(-1, TellusLandCoverSource.coverClassForSubtype("unknown"));
   }

   @Test
   void selectsAvailableZoomFromWorldAndLodResolution() {
      assertEquals(13, TellusLandCoverSource.selectZoom(1.0, 8, 13, 512));
      assertEquals(13, TellusLandCoverSource.selectZoom(10.0, 8, 13, 512));
      assertEquals(12, TellusLandCoverSource.selectZoom(20.0, 8, 13, 512));
      assertEquals(11, TellusLandCoverSource.selectZoom(40.0, 8, 13, 512));
      assertEquals(10, TellusLandCoverSource.selectZoom(80.0, 8, 13, 512));
      assertEquals(9, TellusLandCoverSource.selectZoom(160.0, 8, 13, 512));
      assertEquals(8, TellusLandCoverSource.selectZoom(320.0, 8, 13, 512));
      assertEquals(12, TellusLandCoverSource.selectZoom(1.0, 8, 12, 512));
      assertEquals(8, TellusLandCoverSource.selectZoom(10000.0, 8, 13, 512));
   }

   @Test
   void rasterizesLandCoverWithoutTurningWaterIntoCoarseCoverCells() {
      Tile.Feature forest = polygonFeature(0, 4096, 0, 4096, 0, 0);
      Tile.Feature water = polygonFeature(2048, 4096, 0, 4096, -1, -1);
      Tile.Layer landCover = Tile.Layer.newBuilder()
         .setVersion(2)
         .setName("land_cover")
         .setExtent(4096)
         .addKeys("subtype")
         .addKeys("cartography")
         .addValues(Tile.Value.newBuilder().setStringValue("forest"))
         .addValues(Tile.Value.newBuilder().setStringValue("{\"sort_key\":2}"))
         .addFeatures(forest)
         .build();
      Tile.Layer waterLayer = Tile.Layer.newBuilder()
         .setVersion(2)
         .setName("water")
         .setExtent(4096)
         .addFeatures(water)
         .build();
      byte[] payload = Tile.newBuilder().addLayers(landCover).addLayers(waterLayer).build().toByteArray();

      byte[] raster = TellusLandCoverSource.rasterizeVectorTile(payload, 8);

      assertEquals(10, raster[4 * 8 + 1] & 255);
      assertEquals(10, raster[4 * 8 + 6] & 255);
   }

   @Test
   void findsNearestNonWaterLandClass() {
      int[][] cover = new int[5][5];
      for (int y = 0; y < cover.length; y++) {
         for (int x = 0; x < cover[y].length; x++) {
            cover[y][x] = 80;
         }
      }
      cover[3][3] = 30;
      cover[2][0] = 10;

      int nearest = TellusLandCoverSource.findNearestLandCoverClass(
         2,
         2,
         4,
         (x, y) -> x >= 0 && y >= 0 && y < cover.length && x < cover[y].length ? cover[y][x] : Integer.MIN_VALUE
      );

      assertEquals(30, nearest);
   }

   @Test
   void skipsNoDataWaterAndMangrovesWhenFindingTerrainReference() {
      int[][] cover = new int[][]{
         {80, 80, 80, 80, 80},
         {80, 0, 95, 0, 80},
         {80, 95, 80, 95, 80},
         {80, 0, 95, 40, 80},
         {80, 80, 80, 80, 80}
      };

      int nearest = TellusLandCoverSource.findNearestLandCoverClass(
         2,
         2,
         4,
         (x, y) -> x >= 0 && y >= 0 && y < cover.length && x < cover[y].length ? cover[y][x] : Integer.MIN_VALUE
      );

      assertEquals(40, nearest);
   }

   private static Tile.Feature polygonFeature(int minX, int maxX, int minY, int maxY, int firstTagKey, int firstTagValue) {
      Tile.Feature.Builder feature = Tile.Feature.newBuilder()
         .setType(Tile.GeomType.POLYGON)
         .addGeometry(command(1, 1))
         .addGeometry(zigZag(minX))
         .addGeometry(zigZag(minY))
         .addGeometry(command(2, 3))
         .addGeometry(zigZag(maxX - minX))
         .addGeometry(zigZag(0))
         .addGeometry(zigZag(0))
         .addGeometry(zigZag(maxY - minY))
         .addGeometry(zigZag(minX - maxX))
         .addGeometry(zigZag(0))
         .addGeometry(command(7, 1));
      if (firstTagKey >= 0) {
         feature.addTags(firstTagKey).addTags(firstTagValue).addTags(1).addTags(1);
      }
      return feature.build();
   }

   private static int command(int id, int count) {
      return count << 3 | id;
   }

   private static int zigZag(int value) {
      return value << 1 ^ value >> 31;
   }
}
