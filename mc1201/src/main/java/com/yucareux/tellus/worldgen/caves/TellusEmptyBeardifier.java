package com.yucareux.tellus.worldgen.caves;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

final class TellusEmptyBeardifier {
   private TellusEmptyBeardifier() {
   }

   static DensityFunctions.BeardifierOrMarker instance() {
      return new Beardifier(
         new ObjectArrayList<Beardifier.Rigid>().iterator(),
         new ObjectArrayList<JigsawJunction>().iterator()
      );
   }
}
