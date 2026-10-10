package com.yucareux.tellus.world.data.canopy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TellusCanopyHeightSourceTest {
   @Test
   void keepsNativeTilesForFullDetailAtThirtyMeterScale() {
      assertEquals(13, TellusCanopyHeightSource.levelForResolution(1.0, 1.0));
      assertEquals(13, TellusCanopyHeightSource.levelForResolution(30.0, 30.0));
      assertEquals(12, TellusCanopyHeightSource.levelForResolution(60.0, 60.0));
      assertEquals(11, TellusCanopyHeightSource.levelForResolution(200.0, 200.0));
   }

   @Test
   void mapsGeographicCoordinatesToTheArcGisTileGrid() {
      assertEquals(new TellusCanopyHeightSource.TileKey(13, 3937, 8437), TellusCanopyHeightSource.tileKey(0.0, 0.0, 13));
      assertNull(TellusCanopyHeightSource.tileKey(0.0, 84.0, 13));
      assertNull(TellusCanopyHeightSource.tileKey(0.0, -60.1, 13));
   }

   @Test
   void coarsensLargePreviewAreasToTheirTileBudget() {
      double requestedResolution = 60.0;
      int initialTiles = TellusCanopyHeightSource.areaTileCount(-512, -512, 512, 512, 30.0, requestedResolution);

      double adjustedResolution = TellusCanopyHeightSource.resolutionForAreaTileBudget(
         -512, -512, 512, 512, 30.0, requestedResolution, 32
      );
      int adjustedTiles = TellusCanopyHeightSource.areaTileCount(-512, -512, 512, 512, 30.0, adjustedResolution);

      assertTrue(initialTiles > 32);
      assertTrue(adjustedTiles <= 32);
      assertTrue(adjustedResolution > requestedResolution);
   }

   /**
    * 像素物理尺寸必须随层级逐级翻倍，这是覆盖率统计窗口换算的基础喵。
    */
   @Test
   void resolutionDoublesForEachCoarserLevel() {
      // 原生层级的像素宽度，单位：米喵。
      double nativeResolution = TellusCanopyHeightSource.resolutionMetersAtLevel(13);
      // 原生层级约等于 1/12000 度对应的米数，应当在 9 米上下喵。
      assertTrue(nativeResolution > 9.0 && nativeResolution < 10.0, "原生像素宽度应约 9.3 米，实际=" + nativeResolution);
      // 每降一级，像素宽度必须精确翻倍喵。
      assertEquals(nativeResolution * 2.0, TellusCanopyHeightSource.resolutionMetersAtLevel(12), 1.0E-9);
      // 再降一级再翻倍喵。
      assertEquals(nativeResolution * 4.0, TellusCanopyHeightSource.resolutionMetersAtLevel(11), 1.0E-9);
      // 升到更细的层级时像素宽度减半，这是一种纯数学外推，用于保证换算不会除零喵。
      assertEquals(nativeResolution / 2.0, TellusCanopyHeightSource.resolutionMetersAtLevel(14), 1.0E-9);
   }

   /**
    * 不可用的冠层采样必须把覆盖率记为 0，而不是 NaN，避免污染后续概率计算喵。
    */
   @Test
   void unavailableSampleReportsZeroCoverFraction() {
      // 直接构造一个"不可用"的采样，验证它的覆盖率字段是安全值喵。
      TellusCanopyHeightSource.CanopySample sample = TellusCanopyHeightSource.CanopySample.unavailable();
      // 不可用标记必须为 false喵。
      assertFalse(sample.available(), "不可用采样的 available 必须为 false");
      // 覆盖率必须是 0 而不是 NaN，否则会一路传播进概率计算喵。
      assertEquals(0.0, sample.coverFraction(), 1.0E-9, "不可用采样的覆盖率必须是 0");
      // 有效样本数必须是 0喵。
      assertEquals(0, sample.validSampleCount(), "不可用采样的有效样本数必须是 0");
   }
}
