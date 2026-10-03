package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UndergroundStructureProtectionTest {
   @Test
   void reservesOneBedrockAndFourStoneLayersBelowTheStructure() {
      assertEquals(1, UndergroundStructureProtection.BEDROCK_THICKNESS);
      assertEquals(4, UndergroundStructureProtection.STONE_THICKNESS);
      assertEquals(5, UndergroundStructureProtection.TOTAL_THICKNESS);
      assertEquals(-45, UndergroundStructureProtection.protectionBottomY(-40));
      assertEquals(-59, UndergroundStructureProtection.minimumStructureY(-64));
   }

   @Test
   void extendsOnlyWhenStructureProtectionCrossesActualTerrainFloor() {
      assertFalse(UndergroundStructureProtection.needsTerrainExtension(40, 100, 128));
      assertTrue(UndergroundStructureProtection.needsTerrainExtension(30, 100, 64));
      assertFalse(UndergroundStructureProtection.needsTerrainExtension(41, 100, 64));
   }

   @Test
   void makesBottomAndOutermostSidesBedrockButLeavesTopOpen() {
      assertTrue(UndergroundStructureProtection.isOuterBedrockSkin(5, -20, 5, 0, -20, 0, 10, 10));
      assertTrue(UndergroundStructureProtection.isOuterBedrockSkin(0, -10, 5, 0, -20, 0, 10, 10));
      assertFalse(UndergroundStructureProtection.isOuterBedrockSkin(1, -10, 1, 0, -20, 0, 10, 10));
   }
}
