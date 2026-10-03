package com.yucareux.tellus.world.data.elevation;

import com.yucareux.tellus.world.data.elevation.TellusElevationSource.DemUsage;
import java.util.Arrays;
import java.util.Objects;

record TellusElevationProvenance(
   int width,
   int height,
   int providerMask,
   byte[] primaryProviders,
   byte[] blendedFlags,
   byte[] mapterhornAvailableFlags
) {
   TellusElevationProvenance {
      if (width <= 0 || height <= 0) {
         throw new IllegalArgumentException("Invalid provenance dimensions");
      }

      int sampleCount = width * height;
      Objects.requireNonNull(primaryProviders, "primaryProviders");
      Objects.requireNonNull(blendedFlags, "blendedFlags");
      Objects.requireNonNull(mapterhornAvailableFlags, "mapterhornAvailableFlags");
      if (primaryProviders.length != sampleCount) {
         throw new IllegalArgumentException("Invalid primary provider buffer");
      }

      if (blendedFlags.length != bitSetLength(sampleCount)) {
         throw new IllegalArgumentException("Invalid provenance blend buffer");
      }

      if (mapterhornAvailableFlags.length != bitSetLength(sampleCount)) {
         throw new IllegalArgumentException("Invalid Mapterhorn availability buffer");
      }

      primaryProviders = Arrays.copyOf(primaryProviders, primaryProviders.length);
      blendedFlags = Arrays.copyOf(blendedFlags, blendedFlags.length);
      mapterhornAvailableFlags = Arrays.copyOf(mapterhornAvailableFlags, mapterhornAvailableFlags.length);
   }

   DemUsage primaryProvider(int x, int y) {
      int ordinal = this.primaryProviders[this.sampleIndex(x, y)] & 0xFF;
      DemUsage[] usages = DemUsage.values();
      if (ordinal < 0 || ordinal >= usages.length) {
         throw new IllegalStateException("Invalid DEM provenance provider ordinal " + ordinal);
      } else {
         return usages[ordinal];
      }
   }

   boolean isBlended(int x, int y) {
      int index = this.sampleIndex(x, y);
      return (this.blendedFlags[index >> 3] & 1 << (index & 7)) != 0;
   }

   boolean mapterhornAvailable(int x, int y) {
      int index = this.sampleIndex(x, y);
      return (this.mapterhornAvailableFlags[index >> 3] & 1 << (index & 7)) != 0;
   }

   private int sampleIndex(int x, int y) {
      if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
         throw new IndexOutOfBoundsException("Invalid provenance sample " + x + "," + y);
      } else {
         return x + y * this.width;
      }
   }

   static int bitSetLength(int sampleCount) {
      return (sampleCount + 7) >> 3;
   }
}
