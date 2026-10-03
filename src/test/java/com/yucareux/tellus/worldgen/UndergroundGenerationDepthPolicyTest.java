package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UndergroundGenerationDepthPolicyTest {
   @Test
   void capsGenerationAtVanillaDepthWhenTheTerrainShellIsDeeper() {
      assertEquals(64, UndergroundGenerationDepthPolicy.generationDepth(64));
      assertEquals(64, UndergroundGenerationDepthPolicy.generationDepth(192));
      assertEquals(936, UndergroundGenerationDepthPolicy.generationFloorY(1_000, 512));
      assertEquals(937, UndergroundGenerationDepthPolicy.deepestGenerationY(1_000, 512));
   }

   @Test
   void treatsTheSixtyFourBlockBoundaryAsProtectedTerrain() {
      assertTrue(UndergroundGenerationDepthPolicy.containsDepth(63, 512));
      assertFalse(UndergroundGenerationDepthPolicy.containsDepth(64, 512));
      assertFalse(UndergroundGenerationDepthPolicy.containsDepth(400, 512));
   }

   @Test
   void stillHonorsAConfigurableShellIfItIsEverShallowerThanVanilla() {
      assertEquals(20, UndergroundGenerationDepthPolicy.generationDepth(20));
      assertTrue(UndergroundGenerationDepthPolicy.containsDepth(19, 20));
      assertFalse(UndergroundGenerationDepthPolicy.containsDepth(20, 20));
   }
}
