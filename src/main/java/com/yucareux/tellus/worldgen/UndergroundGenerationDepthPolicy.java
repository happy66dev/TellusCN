package com.yucareux.tellus.worldgen;

/**
 * Defines the fixed surface-relative band used by vanilla underground content.
 * The configured underground depth controls terrain thickness only; increasing
 * it must not stretch caves, features, biomes, or structures into deeper rock.
 */
public final class UndergroundGenerationDepthPolicy {
   public static final int MAX_DEPTH_BELOW_SURFACE = 64;

   private UndergroundGenerationDepthPolicy() {
   }

   public static int generationDepth(int undergroundDepth) {
      return Math.min(Math.max(undergroundDepth, 0), MAX_DEPTH_BELOW_SURFACE);
   }

   /**
    * Returns the protected floor below generated underground content. Content
    * may be placed above this Y, but never at or below it.
    */
   public static int generationFloorY(int surfaceY, int undergroundDepth) {
      return surfaceY - generationDepth(undergroundDepth);
   }

   public static int deepestGenerationY(int surfaceY, int undergroundDepth) {
      return generationFloorY(surfaceY, undergroundDepth) + 1;
   }

   public static boolean containsDepth(int depthBelowSurface, int undergroundDepth) {
      return depthBelowSurface >= 0 && depthBelowSurface < generationDepth(undergroundDepth);
   }
}
