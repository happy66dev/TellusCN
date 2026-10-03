package com.yucareux.tellus.worldgen.caves;

import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;

final class TellusEmptyBeardifier {
   private TellusEmptyBeardifier() {
   }

   static DensityFunctions.BeardifierOrMarker instance() {
      return Beardifier.EMPTY;
   }
}
