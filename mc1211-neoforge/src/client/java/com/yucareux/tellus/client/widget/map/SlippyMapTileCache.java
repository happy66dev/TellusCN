package com.yucareux.tellus.client.widget.map;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.world.data.source.MapTileImageValidator;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.Minecraft;

public class SlippyMapTileCache implements TellusCacheHandle {
   private static final int CACHE_SIZE = 1024;
   private static final int MAX_TILE_BYTES = 4 * 1024 * 1024;
   private static final int MAX_TILE_DIMENSION = 512;
   private final ExecutorService loadingService = Executors.newFixedThreadPool(
      4, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("tellus-map-load-%d").build()
   );
   private final Queue<InputStream> loadingStreams = new LinkedBlockingQueue<>();
   private final Path cacheRoot = Minecraft.getInstance().gameDirectory.toPath().resolve("tellus/cache/map");
   private volatile boolean shuttingDown;
   private final LoadingCache<SlippyMapTilePos, SlippyMapTile> tileCache = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).removalListener(notification -> {
      SlippyMapTile tile = (SlippyMapTile)notification.getValue();
      if (tile != null) {
         tile.delete();
      }
   }).build(new CacheLoader<SlippyMapTilePos, SlippyMapTile>() {
      public SlippyMapTile load(SlippyMapTilePos key) {
         SlippyMapTile tile = new SlippyMapTile(key);
         long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
         try {
            SlippyMapTileCache.this.loadingService.submit(() -> {
               NativeImage image = SlippyMapTileCache.this.downloadImage(key, generation);
               if (image == null) {
                  return;
               }

               if (SlippyMapTileCache.this.shuttingDown || !TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
                  image.close();
               } else {
                  tile.supplyImage(image);
               }
            });
         } catch (RejectedExecutionException ignored) {
            tile.supplyImage(SlippyMapTileCache.this.createErrorImage());
         }

         return tile;
      }
   });

   public SlippyMapTileCache() {
      TellusCacheRegistry.register(this);
   }

   public SlippyMapTile getTile(SlippyMapTilePos pos) {
      try {
         return (SlippyMapTile)this.tileCache.get(pos);
      } catch (Exception var4) {
         SlippyMapTile tile = new SlippyMapTile(pos);
         tile.supplyImage(this.createErrorImage());
         return tile;
      }
   }

   public void shutdown() {
      this.shuttingDown = true;
      this.loadingService.shutdownNow();
      this.clearCache();
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OSM;
   }

   @Override
   public void clearCache() {
      this.tileCache.invalidateAll();
      this.tileCache.cleanUp();

      while (!this.loadingStreams.isEmpty()) {
         try {
            InputStream poll = this.loadingStreams.poll();
            if (poll != null) {
               poll.close();
            }
         } catch (IOException var3) {
            Tellus.LOGGER.warn("Failed to close loading map stream", var3);
         }
      }
   }

   private NativeImage downloadImage(SlippyMapTilePos pos, long generation) {
      try {
         byte[] data = this.readTileData(pos, generation);
         if (data == null) {
            return null;
         }
         return NativeImage.read(new ByteArrayInputStream(data));
      } catch (IOException error) {
         if (this.isCancelledLoad(error)) {
            return null;
         }
         Tellus.LOGGER.error("Failed to load map tile {}", pos, error);
         return this.createErrorImage();
      }
   }

   private byte[] readTileData(SlippyMapTilePos pos, long generation) throws IOException {
      Path cachePath = this.cacheRoot.resolve(pos.getCacheName());
      if (Files.isRegularFile(cachePath)) {
         if (Files.size(cachePath) <= MAX_TILE_BYTES) {
            try (InputStream input = new BufferedInputStream(Files.newInputStream(cachePath))) {
               byte[] data = MapTileImageValidator.readBounded(input, MAX_TILE_BYTES);
               MapTileImageValidator.validatePng(data, MAX_TILE_DIMENSION, MAX_TILE_DIMENSION);
               return data;
            } catch (IOException invalidCache) {
               Files.deleteIfExists(cachePath);
            }
         } else {
            Files.deleteIfExists(cachePath);
         }
      }

      URI uri = URI.create(String.format("https://tile.openstreetmap.org/%s/%s/%s.png", pos.getZoom(), pos.getX(), pos.getY()));
      URL url = uri.toURL();
      HttpURLConnection connection = (HttpURLConnection)url.openConnection();
      try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Tellus/2.0.0 (Minecraft Mod)");
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
               throw new IOException("OpenStreetMap tile request failed with HTTP " + responseCode + " for " + pos);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_TILE_BYTES) {
               throw new IOException("OpenStreetMap tile response exceeds the safety limit for " + pos);
            }

            InputStream stream = Objects.requireNonNull(connection.getInputStream(), "tileStream");
            this.loadingStreams.add(stream);

            try (InputStream input = new BufferedInputStream(stream)) {
               byte[] data = MapTileImageValidator.readBounded(input, MAX_TILE_BYTES);
               MapTileImageValidator.validatePng(data, MAX_TILE_DIMENSION, MAX_TILE_DIMENSION);
               if (!this.shuttingDown && !Thread.currentThread().isInterrupted() && TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
                  this.cacheData(cachePath, data, generation);
               }
               return data;
            } finally {
               this.loadingStreams.remove(stream);
            }
         } finally {
            connection.disconnect();
      }
   }

   private boolean isCancelledLoad(IOException error) {
      return this.shuttingDown
         || Thread.currentThread().isInterrupted()
         || error instanceof InterruptedIOException
         || error instanceof ClosedByInterruptException;
   }

   private void cacheData(Path cachePath, byte[] data, long generation) {
      try {
         TellusCacheFiles.writeBytesIfCurrent(TellusCacheDomain.OSM, generation, cachePath, data);
      } catch (IOException var9) {
         Tellus.LOGGER.error("Failed to cache map tile", var9);
      }
   }

   private NativeImage createErrorImage() {
      NativeImage result = new NativeImage(256, 256, false);

      for (int x = 0; x < 256; x++) {
         for (int y = 0; y < 256; y++) {
            result.setPixelRGBA(x, y, -16776961);
         }
      }

      return result;
   }
}
