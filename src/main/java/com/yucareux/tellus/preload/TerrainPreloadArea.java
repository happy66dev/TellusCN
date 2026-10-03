package com.yucareux.tellus.preload;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Objects;

public record TerrainPreloadArea(
   double centerLatitude,
   double centerLongitude,
   int chunksPerSide,
   double worldScale,
   int minChunkX,
   int minChunkZ,
   int maxChunkX,
   int maxChunkZ,
   double northLatitude,
   double southLatitude,
   double westLongitude,
   double eastLongitude
) {
   public static final int CHUNK_SIZE = 16;
   public static final int DEFAULT_CHUNKS_PER_SIDE = 32;
   public static final int MIN_CHUNKS_PER_SIDE = 1;
   /** Covers DH's maximum 4096-chunk radius plus its 128-chunk managed safety ring. */
   public static final int DEFAULT_MAX_CHUNKS_PER_SIDE = 8480;

   public TerrainPreloadArea {
      if (!Double.isFinite(centerLatitude) || !Double.isFinite(centerLongitude)) {
         throw new IllegalArgumentException("Area center must be finite");
      }

      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
         throw new IllegalArgumentException("World scale must be positive");
      }

      int maxChunks = maxChunksPerSide();
      if (chunksPerSide < MIN_CHUNKS_PER_SIDE || chunksPerSide > maxChunks) {
         throw new IllegalArgumentException("Chunks per side must be between " + MIN_CHUNKS_PER_SIDE + " and " + maxChunks);
      }

      if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
         throw new IllegalArgumentException("Invalid chunk bounds");
      }
   }

   public static TerrainPreloadArea centered(double latitude, double longitude, int chunksPerSide, double worldScale) {
      int safeChunks = clampChunksPerSide(chunksPerSide);
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double centerBlockX = longitude * blocksPerDegree;
      double centerBlockZ = EarthProjection.latToBlockZ(latitude, worldScale);
      double sideBlocks = safeChunks * (double)CHUNK_SIZE;
      int minChunkX = Math.floorDiv((int)Math.floor(centerBlockX - sideBlocks * 0.5), CHUNK_SIZE);
      int minChunkZ = Math.floorDiv((int)Math.floor(centerBlockZ - sideBlocks * 0.5), CHUNK_SIZE);
      int maxChunkX = minChunkX + safeChunks - 1;
      int maxChunkZ = minChunkZ + safeChunks - 1;
      return fromChunkBounds(latitude, longitude, safeChunks, worldScale, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
   }

   public static TerrainPreloadArea fromChunkBounds(
      double latitude,
      double longitude,
      int chunksPerSide,
      double worldScale,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ
   ) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      int minBlockX = minChunkX * CHUNK_SIZE;
      int minBlockZ = minChunkZ * CHUNK_SIZE;
      int maxBlockX = (maxChunkX + 1) * CHUNK_SIZE - 1;
      int maxBlockZ = (maxChunkZ + 1) * CHUNK_SIZE - 1;
      double west = minBlockX / blocksPerDegree;
      double east = maxBlockX / blocksPerDegree;
      double latA = EarthProjection.blockZToLat(minBlockZ, worldScale);
      double latB = EarthProjection.blockZToLat(maxBlockZ, worldScale);
      double north = Math.max(latA, latB);
      double south = Math.min(latA, latB);
      return new TerrainPreloadArea(
         EarthProjection.clampLatitude(latitude),
         longitude,
         chunksPerSide,
         worldScale,
         minChunkX,
         minChunkZ,
         maxChunkX,
         maxChunkZ,
         north,
         south,
         west,
         east
      );
   }

   public static int clampChunksPerSide(int chunksPerSide) {
      return Math.max(MIN_CHUNKS_PER_SIDE, Math.min(maxChunksPerSide(), chunksPerSide));
   }

   public static int maxChunksPerSide() {
      return Math.max(MIN_CHUNKS_PER_SIDE, Integer.getInteger("tellus.preload.maxChunksPerSide", DEFAULT_MAX_CHUNKS_PER_SIDE));
   }

   public int totalChunks() {
      return this.chunksPerSide * this.chunksPerSide;
   }

   public int minBlockX() {
      return this.minChunkX * CHUNK_SIZE;
   }

   public int minBlockZ() {
      return this.minChunkZ * CHUNK_SIZE;
   }

   public int maxBlockX() {
      return (this.maxChunkX + 1) * CHUNK_SIZE - 1;
   }

   public int maxBlockZ() {
      return (this.maxChunkZ + 1) * CHUNK_SIZE - 1;
   }

   public boolean containsChunk(int chunkX, int chunkZ) {
      return chunkX >= this.minChunkX && chunkX <= this.maxChunkX && chunkZ >= this.minChunkZ && chunkZ <= this.maxChunkZ;
   }

   public String summary() {
      return String.format(
         "%d x %d chunks, lat %.5f..%.5f, lon %.5f..%.5f",
         this.chunksPerSide,
         this.chunksPerSide,
         this.southLatitude,
         this.northLatitude,
         this.westLongitude,
         this.eastLongitude
      );
   }

   public String stableKey() {
      return Objects.hash(
         Math.round(this.centerLatitude * 100000.0),
         Math.round(this.centerLongitude * 100000.0),
         this.chunksPerSide,
         Math.round(this.worldScale * 1000.0),
         this.minChunkX,
         this.minChunkZ,
         this.maxChunkX,
         this.maxChunkZ
      )
         + "";
   }
}
