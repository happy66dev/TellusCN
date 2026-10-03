package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.UndergroundGenerationDepthPolicy;

/**
 * Defines where cave biomes may exist inside Tellus's fixed surface-relative
 * underground generation band.
 */
public final class TellusCaveBiomeDepthPolicy {
   public static final int MIN_CAVE_BIOME_DEPTH = 8;
   public static final int MIN_DEEP_DARK_DEPTH = 24;
   public static final int NO_STRUCTURE_PROBE_DEPTH = -1;
   private static final int STRUCTURE_PROBE_BOTTOM_CLEARANCE = 16;

   private TellusCaveBiomeDepthPolicy() {
   }

   /**
    * Returns whether a depth is inside the generation band and far enough
    * below the local terrain surface for cave biomes.
    */
   public static boolean isCaveBiomeDepth(int depthBelowSurface, int undergroundDepth) {
      return depthBelowSurface >= MIN_CAVE_BIOME_DEPTH
         && UndergroundGenerationDepthPolicy.containsDepth(depthBelowSurface, undergroundDepth);
   }

   /**
    * Keeps Deep Dark in the deeper portion of the local generation band
    * without tying it to an absolute world Y.
    */
   public static boolean isDeepDarkDepth(int depthBelowSurface, int undergroundDepth) {
      return depthBelowSurface >= MIN_DEEP_DARK_DEPTH
         && UndergroundGenerationDepthPolicy.containsDepth(depthBelowSurface, undergroundDepth);
   }

   /**
    * Chooses a stable Deep Dark depth for vanilla structure biome checks that
    * originate below Tellus's generation band. The probe remains far enough
    * from its protected floor for large structures while matching their
    * eventual local underground placement.
    */
   public static int structureProbeDepth(int undergroundDepth) {
      int generationDepth = UndergroundGenerationDepthPolicy.generationDepth(undergroundDepth);
      int deepestBiomeDepth = generationDepth - 1;
      if (deepestBiomeDepth < MIN_DEEP_DARK_DEPTH) {
         return NO_STRUCTURE_PROBE_DEPTH;
      }

      int preferredDepth = Math.max(MIN_DEEP_DARK_DEPTH, generationDepth - STRUCTURE_PROBE_BOTTOM_CLEARANCE);
      return Math.min(preferredDepth, deepestBiomeDepth);
   }
}
