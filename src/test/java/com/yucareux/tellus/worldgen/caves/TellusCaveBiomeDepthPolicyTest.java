package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TellusCaveBiomeDepthPolicyTest {
   @Test
   void keepsCaveBiomesInsideTheSelectedUndergroundShell() {
      int undergroundDepth = 64;

      assertFalse(TellusCaveBiomeDepthPolicy.isCaveBiomeDepth(7, undergroundDepth));
      assertTrue(TellusCaveBiomeDepthPolicy.isCaveBiomeDepth(8, undergroundDepth));
      assertTrue(TellusCaveBiomeDepthPolicy.isCaveBiomeDepth(63, undergroundDepth));
      assertFalse(TellusCaveBiomeDepthPolicy.isCaveBiomeDepth(64, undergroundDepth));
      assertFalse(TellusCaveBiomeDepthPolicy.isCaveBiomeDepth(512, undergroundDepth));
   }

   @Test
   void makesDeepDarkEligibleByLocalSurfaceDepth() {
      int undergroundDepth = 64;

      assertFalse(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(23, undergroundDepth));
      assertTrue(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(24, undergroundDepth));
      assertTrue(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(63, undergroundDepth));
      assertFalse(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(64, undergroundDepth));
   }

   @Test
   void deeperConfiguredShellsDoNotExtendCaveBiomeGeneration() {
      int undergroundDepth = 512;

      assertTrue(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(63, undergroundDepth));
      assertFalse(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(64, undergroundDepth));
      assertFalse(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(400, undergroundDepth));
      assertFalse(TellusCaveBiomeDepthPolicy.isDeepDarkDepth(2_000, undergroundDepth));
   }

   @Test
   void mapsVanillaStructureChecksIntoAUsableDeepDarkDepth() {
      assertEquals(48, TellusCaveBiomeDepthPolicy.structureProbeDepth(64));
      assertEquals(48, TellusCaveBiomeDepthPolicy.structureProbeDepth(128));
      assertEquals(48, TellusCaveBiomeDepthPolicy.structureProbeDepth(512));
      assertEquals(
         TellusCaveBiomeDepthPolicy.NO_STRUCTURE_PROBE_DEPTH,
         TellusCaveBiomeDepthPolicy.structureProbeDepth(TellusCaveBiomeDepthPolicy.MIN_DEEP_DARK_DEPTH)
      );
   }
}
