package com.yucareux.tellus.world.data.cover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TellusLandCoverSourceTest {
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
}
