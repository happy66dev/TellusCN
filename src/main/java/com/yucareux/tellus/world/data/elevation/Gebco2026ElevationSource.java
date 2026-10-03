package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

public final class Gebco2026ElevationSource implements TellusCacheHandle {
   public static final double NOMINAL_RESOLUTION_METERS = 463.0;
   private static final String DEFAULT_TILE_URL_PATTERN =
      "https://dap.ceda.ac.uk/bodc/gebco/global/gebco_2026/ice_surface_elevation/geotiff/%s?download=1";
   private static final String TILE_URL_PATTERN = System.getProperty("tellus.gebco2026.tileUrlPattern", DEFAULT_TILE_URL_PATTERN);
   private static final boolean ENABLED = booleanProperty("tellus.gebco2026.enabled", true);
   private static final int HTTP_CONNECT_TIMEOUT = intProperty("tellus.gebco2026.connectTimeoutMs", 12000);
   private static final int HTTP_READ_TIMEOUT = intProperty("tellus.gebco2026.readTimeoutMs", 12000);
   private static final boolean REMOTE_ENABLED = booleanProperty("tellus.gebco2026.remoteEnabled", true);
   private static final long REMOTE_INITIAL_RETRY_MS = intProperty("tellus.gebco2026.initialRetryMs", 30000);
   private static final long REMOTE_MAX_RETRY_MS = intProperty("tellus.gebco2026.maxRetryMs", 900000);
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final int MAX_FILE_CACHE = intProperty("tellus.gebco2026.cacheFiles", 8);
   private static final int MAX_STRIP_CACHE = intProperty("tellus.gebco2026.cacheStrips", 32);
   private static final int DEFAULT_NO_DATA = -32767;
   private static final Gebco2026ElevationSource.TileRecord MISSING_TILE = new Gebco2026ElevationSource.TileRecord(null, true);
   private static final Object REMOTE_HEALTH_LOCK = new Object();
   private static volatile int remoteFailureCount = REMOTE_ENABLED ? 0 : 1;
   private static volatile long remoteRetryAfterMs = REMOTE_ENABLED ? 0L : Long.MAX_VALUE;
   private static volatile boolean remoteProbeInFlight;
   private static volatile long remoteOutageId = REMOTE_ENABLED ? 0L : 1L;
   private static final ThreadLocal<Boolean> REMOTE_PROBE_OWNER = new ThreadLocal<>();
   private final Path cacheRoot;
   private final Path localRoot;
   private final LoadingCache<Gebco2026ElevationSource.TileKey, Gebco2026ElevationSource.TileRecord> fileCache;
   private final Object localOutageLock = new Object();
   private final Map<Gebco2026ElevationSource.TileKey, Long> localOutageMisses = new HashMap<>();

   public Gebco2026ElevationSource() {
      this.cacheRoot = FabricLoader.getInstance().getGameDir().resolve("tellus/cache/elevation-gebco2026");
      String localRootProperty = System.getProperty("tellus.gebco2026.localRoot");
      this.localRoot = localRootProperty == null || localRootProperty.isBlank() ? null : Path.of(localRootProperty);
      this.fileCache = CacheBuilder.newBuilder().maximumSize(MAX_FILE_CACHE).build(new CacheLoader<Gebco2026ElevationSource.TileKey, Gebco2026ElevationSource.TileRecord>() {
         public Gebco2026ElevationSource.TileRecord load(Gebco2026ElevationSource.TileKey key) throws Exception {
            return Gebco2026ElevationSource.this.loadTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public static boolean isRemoteUnavailable() {
      return ENABLED && (!REMOTE_ENABLED || remoteFailureCount > 0);
   }

   public static boolean isEnabled() {
      return ENABLED;
   }

   public static long remoteOutageId() {
      return remoteOutageId;
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
      if (!ENABLED || worldScale <= 0.0) {
         return Double.NaN;
      } else {
         return this.sampleLatLon(EarthProjection.blockZToLat(blockZ, worldScale), longitudeForBlock(blockX, worldScale), ReadMode.BLOCKING);
      }
   }

   public double sampleElevationMetersLocalOnly(double blockX, double blockZ, double worldScale) {
      if (!ENABLED || worldScale <= 0.0) {
         return Double.NaN;
      } else {
         return this.sampleLatLon(EarthProjection.blockZToLat(blockZ, worldScale), longitudeForBlock(blockX, worldScale), ReadMode.LOCAL_ONLY);
      }
   }

   public double sampleElevationMetersMemoryOnly(double blockX, double blockZ, double worldScale) {
      if (!ENABLED || worldScale <= 0.0) {
         return Double.NaN;
      } else {
         return this.sampleLatLon(EarthProjection.blockZToLat(blockZ, worldScale), longitudeForBlock(blockX, worldScale), ReadMode.MEMORY_ONLY);
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      if (!ENABLED || worldScale <= 0.0 || radius <= 0) {
         return;
      }

      double blockRadius = Math.max(1, radius) * 256.0;
      Set<Gebco2026ElevationSource.TileKey> keys = new LinkedHashSet<>();
      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            double sampleX = blockX + dx * blockRadius;
            double sampleZ = blockZ + dz * blockRadius;
            double lat = EarthProjection.blockZToLat(sampleZ, worldScale);
            double lon = longitudeForBlock(sampleX, worldScale);
            Gebco2026ElevationSource.TileKey key = tileKeyForLatLon(lat, lon);
            if (key != null && keys.add(key)) {
               this.prefetchTile(key, lat, lon);
            }
         }
      }
   }

   private double sampleLatLon(double lat, double lon, ReadMode mode) {
      double normalizedLon = normalizeLongitude(lon);
      Gebco2026ElevationSource.TileKey key = tileKeyForLatLon(lat, normalizedLon);
      if (key == null) {
         return Double.NaN;
      } else {
         Gebco2026ElevationSource.TileFile tile = this.getTile(key, mode);
         return tile == null ? Double.NaN : tile.sample(lat, normalizedLon, mode);
      }
   }

   private void prefetchTile(Gebco2026ElevationSource.TileKey key, double lat, double lon) {
      try {
         Gebco2026ElevationSource.TileFile tile = this.getTile(key, ReadMode.BLOCKING);
         if (tile != null) {
            tile.prefetch(lat, normalizeLongitude(lon), ReadMode.BLOCKING);
         }
      } catch (RuntimeException error) {
         Tellus.LOGGER.debug("Failed to prefetch GEBCO 2026 tile {}", key, error);
      }
   }

   private Gebco2026ElevationSource.TileFile getTile(Gebco2026ElevationSource.TileKey key, ReadMode mode) {
      if (mode == ReadMode.MEMORY_ONLY) {
         Gebco2026ElevationSource.TileRecord cached = this.fileCache.getIfPresent(key);
         return cached == null || cached.missing() ? null : cached.file();
      } else if (mode == ReadMode.LOCAL_ONLY) {
         Path tileCacheDir = this.cacheRoot.resolve(key.cacheDirectory());
         Path missingMarker = tileCacheDir.resolve(".missing");
         Path localTile = this.localTilePath(key);
         Gebco2026ElevationSource.TileRecord cached = this.fileCache.getIfPresent(key);
         if (cached != null) {
            if (!cached.missing()) {
               return cached.file();
            } else if (localTile == null) {
               return null;
            }
         }

         if (Files.exists(missingMarker) && localTile == null) {
            this.fileCache.put(key, MISSING_TILE);
            return null;
         } else if (!Files.isDirectory(tileCacheDir) && localTile == null) {
            return null;
         } else {
            try {
               Gebco2026ElevationSource.TileRecord record = new Gebco2026ElevationSource.TileRecord(
                  Gebco2026ElevationSource.TileFile.open(key, tileCacheDir, localTile, ReadMode.LOCAL_ONLY),
                  false
               );
               if (cached != null && cached.missing()) {
                  this.fileCache.asMap().put(key, record);
                  return record.file();
               }

               Gebco2026ElevationSource.TileRecord raced = this.fileCache.asMap().putIfAbsent(key, record);
               if (raced != null && raced.missing() && localTile != null) {
                  this.fileCache.asMap().put(key, record);
                  return record.file();
               }

               Gebco2026ElevationSource.TileRecord resolved = raced != null ? raced : record;
               return resolved.missing() ? null : resolved.file();
            } catch (IOException error) {
               Tellus.LOGGER.debug("Failed to load cached GEBCO 2026 tile {}", key, error);
               return null;
            }
         }
      } else {
         Path localTile = this.localTilePath(key);
         Gebco2026ElevationSource.TileRecord cached = this.fileCache.getIfPresent(key);
         if (cached != null && !cached.missing()) {
            return cached.file();
         }

         if (cached != null && cached.missing() && localTile != null) {
            Path tileCacheDir = this.cacheRoot.resolve(key.cacheDirectory());
            try {
               Gebco2026ElevationSource.TileRecord record = new Gebco2026ElevationSource.TileRecord(
                  Gebco2026ElevationSource.TileFile.open(key, tileCacheDir, localTile, ReadMode.BLOCKING),
                  false
               );
               this.fileCache.asMap().put(key, record);
               return record.file();
            } catch (IOException error) {
               Tellus.LOGGER.debug("Failed to load local GEBCO 2026 tile {}", key, error);
            }
         } else if (cached != null && cached.missing()) {
            return null;
         }

         boolean probeClaimed = false;
         if (isRemoteUnavailable()) {
            Gebco2026ElevationSource.TileFile local = this.getLocalTileDuringOutage(key);
            if (local != null) {
               return local;
            }

            if (!claimRemoteProbe()) {
               return null;
            }

            probeClaimed = true;
         }

         try {
            Gebco2026ElevationSource.TileRecord record = this.fileCache.get(key);
            return record.missing() ? null : record.file();
         } catch (ExecutionException error) {
            if (!(error.getCause() instanceof Gebco2026ElevationSource.RemoteUnavailableException)) {
               Tellus.LOGGER.debug("Failed to load GEBCO 2026 tile {}", key, error);
            }
            return null;
         } finally {
            if (probeClaimed) {
               releaseRemoteProbeClaim();
            }
         }
      }
   }

   private Gebco2026ElevationSource.TileFile getLocalTileDuringOutage(Gebco2026ElevationSource.TileKey key) {
      long outageId = remoteOutageId();
      synchronized(this.localOutageLock) {
         Gebco2026ElevationSource.TileRecord cached = this.fileCache.getIfPresent(key);
         if (cached != null) {
            return cached.missing() ? null : cached.file();
         }

         if (Objects.equals(this.localOutageMisses.get(key), outageId)) {
            return null;
         }

         Gebco2026ElevationSource.TileFile local = this.getTile(key, ReadMode.LOCAL_ONLY);
         if (local == null) {
            this.localOutageMisses.put(key, outageId);
         } else {
            this.localOutageMisses.remove(key);
         }

         return local;
      }
   }

   private Gebco2026ElevationSource.TileRecord loadTile(Gebco2026ElevationSource.TileKey key) throws Exception {
      Path tileCacheDir = this.cacheRoot.resolve(key.cacheDirectory());
      Path missingMarker = tileCacheDir.resolve(".missing");
      Path localTile = this.localTilePath(key);
      if (Files.exists(missingMarker) && localTile == null) {
         return MISSING_TILE;
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.GEBCO2026);
      try {
         return new Gebco2026ElevationSource.TileRecord(
            Gebco2026ElevationSource.TileFile.open(key, tileCacheDir, localTile, ReadMode.BLOCKING),
            false
         );
      } catch (Gebco2026ElevationSource.MissingTileException error) {
         this.writeMissingMarker(missingMarker, generation);
         return MISSING_TILE;
      }
   }

   private Path localTilePath(Gebco2026ElevationSource.TileKey key) {
      Path local = this.localRoot == null ? null : this.localRoot.resolve(key.filename());
      if (local != null && Files.isRegularFile(local)) {
         return local;
      }

      Path cacheFile = this.cacheRoot.resolve(key.filename());
      if (Files.isRegularFile(cacheFile)) {
         return cacheFile;
      }

      Path nestedCacheFile = this.cacheRoot.resolve(key.cacheDirectory()).resolve(key.filename());
      return Files.isRegularFile(nestedCacheFile) ? nestedCacheFile : null;
   }

   private void writeMissingMarker(Path missingMarker, long generation) {
      try {
         if (!Files.exists(missingMarker)) {
            TellusCacheFiles.writeStringIfCurrent(TellusCacheDomain.GEBCO2026, generation, missingMarker, "missing", StandardCharsets.UTF_8);
         }
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to persist GEBCO 2026 missing-tile marker {}", missingMarker, error);
      }
   }

   static Gebco2026ElevationSource.TileKey tileKeyForLatLon(double lat, double lon) {
      if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90.0 || lat > 90.0) {
         return null;
      } else {
         double normalizedLon = normalizeLongitude(lon);
         double clampedLat = Mth.clamp(lat, -89.999999, 89.999999);
         double clampedLon = Mth.clamp(normalizedLon, -180.0, 179.999999);
         int lonBand = Mth.clamp((int)Math.floor((clampedLon + 180.0) / 90.0), 0, 3);
         int west = -180 + lonBand * 90;
         int south = clampedLat < 0.0 ? -90 : 0;
         return new Gebco2026ElevationSource.TileKey(south, west);
      }
   }

   static double normalizeLongitude(double lon) {
      if (!Double.isFinite(lon)) {
         return Double.NaN;
      }

      double normalized = lon % 360.0;
      if (normalized < -180.0) {
         normalized += 360.0;
      } else if (normalized >= 180.0) {
         normalized -= 360.0;
      }

      return normalized;
   }

   private static double longitudeForBlock(double blockX, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      return blocksPerDegree <= 0.0 ? Double.NaN : normalizeLongitude(blockX / blocksPerDegree);
   }

   private static double blendFiniteSamples(double v00, double v10, double v01, double v11, double dx, double dy) {
      double w00 = (1.0 - dx) * (1.0 - dy);
      double w10 = dx * (1.0 - dy);
      double w01 = (1.0 - dx) * dy;
      double w11 = dx * dy;
      double sum = 0.0;
      double weight = 0.0;
      if (Double.isFinite(v00)) {
         sum += v00 * w00;
         weight += w00;
      }

      if (Double.isFinite(v10)) {
         sum += v10 * w10;
         weight += w10;
      }

      if (Double.isFinite(v01)) {
         sum += v01 * w01;
         weight += w01;
      }

      if (Double.isFinite(v11)) {
         sum += v11 * w11;
         weight += w11;
      }

      return weight <= 0.0 ? Double.NaN : sum / weight;
   }

   private static HttpURLConnection openConnection(URI uri, String rangeHeader) throws IOException {
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);
      if (rangeHeader != null) {
         connection.setRequestProperty("Range", rangeHeader);
      }

      return connection;
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException error) {
            return defaultValue;
         }
      }
   }

   private static boolean booleanProperty(String key, boolean defaultValue) {
      String value = System.getProperty(key);
      return value == null ? defaultValue : Boolean.parseBoolean(value);
   }

   private static Gebco2026ElevationSource.RemotePermit acquireRemotePermit() throws Gebco2026ElevationSource.RemoteUnavailableException {
      if (!REMOTE_ENABLED) {
         throw new Gebco2026ElevationSource.RemoteUnavailableException("GEBCO 2026 remote access is disabled");
      }

      if (Boolean.TRUE.equals(REMOTE_PROBE_OWNER.get())) {
         return Gebco2026ElevationSource.RemotePermit.PROBE;
      }

      synchronized(REMOTE_HEALTH_LOCK) {
         if (remoteFailureCount == 0) {
            return Gebco2026ElevationSource.RemotePermit.NORMAL;
         }

         long now = System.currentTimeMillis();
         if (now < remoteRetryAfterMs || remoteProbeInFlight) {
            throw new Gebco2026ElevationSource.RemoteUnavailableException("GEBCO 2026 remote access is cooling down");
         }

         remoteProbeInFlight = true;
         return Gebco2026ElevationSource.RemotePermit.PROBE;
      }
   }

   private static boolean claimRemoteProbe() {
      if (!REMOTE_ENABLED) {
         return false;
      }

      synchronized(REMOTE_HEALTH_LOCK) {
         long now = System.currentTimeMillis();
         if (remoteFailureCount == 0 || !shouldStartRemoteProbe(now, remoteRetryAfterMs, remoteProbeInFlight)) {
            return false;
         }

         remoteProbeInFlight = true;
         REMOTE_PROBE_OWNER.set(Boolean.TRUE);
         return true;
      }
   }

   private static void releaseRemoteProbeClaim() {
      REMOTE_PROBE_OWNER.remove();
      synchronized(REMOTE_HEALTH_LOCK) {
         if (remoteFailureCount > 0) {
            remoteProbeInFlight = false;
         }
      }
   }

   static boolean shouldStartRemoteProbe(long now, long retryAfter, boolean probeInFlight) {
      return now >= retryAfter && !probeInFlight;
   }

   private static void recordRemoteSuccess() {
      boolean recovered;
      synchronized(REMOTE_HEALTH_LOCK) {
         recovered = remoteFailureCount > 0;
         remoteFailureCount = 0;
         remoteRetryAfterMs = 0L;
         remoteProbeInFlight = false;
      }

      if (recovered) {
         Tellus.LOGGER.info("GEBCO 2026 remote access recovered.");
      }
   }

   private static void recordRemoteFailure(Gebco2026ElevationSource.RemotePermit permit, Throwable error) {
      boolean newlyUnavailable = false;
      long retryDelayMs;
      synchronized(REMOTE_HEALTH_LOCK) {
         if (permit == Gebco2026ElevationSource.RemotePermit.NORMAL && remoteFailureCount > 0) {
            return;
         }

         newlyUnavailable = remoteFailureCount == 0;
         if (newlyUnavailable) {
            remoteOutageId++;
            remoteFailureCount = 1;
         } else {
            remoteFailureCount = Math.min(remoteFailureCount + 1, 30);
         }

         retryDelayMs = retryDelayMillis(remoteFailureCount);
         remoteRetryAfterMs = System.currentTimeMillis() + retryDelayMs;
         remoteProbeInFlight = false;
      }

      if (newlyUnavailable) {
         Tellus.LOGGER.warn(
            "GEBCO 2026 is unavailable ({}). Falling back to Terrain Tiles; retrying in {} seconds.",
            failureSummary(error),
            Math.max(1L, retryDelayMs / 1000L)
         );
      } else {
         Tellus.LOGGER.debug(
            "GEBCO 2026 retry failed ({}); next retry in {} seconds.",
            failureSummary(error),
            Math.max(1L, retryDelayMs / 1000L)
         );
      }
   }

   static long retryDelayMillis(int failureCount) {
      long maxDelay = Math.max(REMOTE_INITIAL_RETRY_MS, REMOTE_MAX_RETRY_MS);
      long delay = REMOTE_INITIAL_RETRY_MS;
      for (int i = 1; i < Math.max(1, failureCount) && delay < maxDelay; i++) {
         delay = Math.min(maxDelay, delay * 2L);
      }

      return Math.max(1L, delay);
   }

   private static String failureSummary(Throwable error) {
      String message = error.getMessage();
      return message == null || message.isBlank() ? error.getClass().getSimpleName() : error.getClass().getSimpleName() + ": " + message;
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.GEBCO2026;
   }

   @Override
   public void clearCache() {
      this.fileCache.invalidateAll();
      this.fileCache.cleanUp();
      synchronized(this.localOutageLock) {
         this.localOutageMisses.clear();
      }
   }

   private enum ReadMode {
      BLOCKING,
      LOCAL_ONLY,
      MEMORY_ONLY
   }

   private enum RemotePermit {
      NORMAL,
      PROBE
   }

   private static final class MissingTileException extends IOException {
      private MissingTileException(String message) {
         super(message);
      }
   }

   private static final class RangeNotSatisfiableException extends EOFException {
      private RangeNotSatisfiableException(String message) {
         super(message);
      }
   }

   private static final class RemoteUnavailableException extends IOException {
      private RemoteUnavailableException(String message) {
         super(message);
      }
   }

   private static final class RangeReader {
      private final String url;
      private final Path cacheDir;
      private final Path localFile;

      private RangeReader(String url, Path cacheDir, Path localFile) {
         this.url = Objects.requireNonNull(url, "url");
         this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
         this.localFile = localFile;
      }

      private byte[] read(long offset, int length, ReadMode mode) throws IOException {
         if (length <= 0) {
            return new byte[0];
         } else if (mode == ReadMode.MEMORY_ONLY) {
            throw new EOFException("GEBCO 2026 range is not loaded in memory for " + this.url);
         } else if (this.localFile != null) {
            return this.readLocalFile(offset, length);
         } else {
            Path cachePath = this.cacheDir.resolve(offset + "-" + length + ".bin");
            byte[] cached = this.readCachedRange(cachePath, length);
            if (cached != null) {
               return cached;
            } else if (mode == ReadMode.LOCAL_ONLY) {
               throw new EOFException("GEBCO 2026 range not cached for " + this.url);
            }

            String rangeHeader = "bytes=" + offset + "-" + (offset + length - 1L);
            long generation = TellusCacheRegistry.generation(TellusCacheDomain.GEBCO2026);
            Gebco2026ElevationSource.RemotePermit permit = Gebco2026ElevationSource.acquireRemotePermit();
            HttpURLConnection connection = null;
            try {
               connection = Gebco2026ElevationSource.openConnection(URI.create(this.url), rangeHeader);
               int status = connection.getResponseCode();
               if (status == 404) {
                  Gebco2026ElevationSource.recordRemoteSuccess();
                  throw new Gebco2026ElevationSource.MissingTileException("Missing GEBCO 2026 tile " + this.url);
               } else if (status == 416) {
                  Gebco2026ElevationSource.recordRemoteSuccess();
                  throw new Gebco2026ElevationSource.RangeNotSatisfiableException("Range not satisfiable for " + this.url);
               } else if (status != 206 && status != 200) {
                  throw new IOException("Unexpected HTTP status " + status + " for " + this.url);
               } else if (status == 200 && connection.getContentLengthLong() != length) {
                  throw new IOException("GEBCO 2026 server ignored range request for " + this.url);
               } else {
                  DownloadProgressReporter.requestStarted((long)length);
                  try (InputStream input = connection.getInputStream()) {
                     byte[] data = DownloadProgressReporter.readAllBytesWithProgress(input);
                     if (data.length != length) {
                        throw new EOFException("Unexpected GEBCO 2026 range length " + data.length + " for " + this.url);
                     } else {
                        this.writeCachedRange(cachePath, data, generation);
                        Gebco2026ElevationSource.recordRemoteSuccess();
                        return data;
                     }
                  } finally {
                     DownloadProgressReporter.requestFinished();
                  }
               }
            } catch (Gebco2026ElevationSource.MissingTileException | Gebco2026ElevationSource.RangeNotSatisfiableException error) {
               throw error;
            } catch (IOException | RuntimeException error) {
               Gebco2026ElevationSource.recordRemoteFailure(permit, error);
               throw error;
            } finally {
               if (connection != null) {
                  connection.disconnect();
               }
            }
         }
      }

      private byte[] readLocalFile(long offset, int length) throws IOException {
         byte[] data = new byte[length];
         try (FileChannel channel = FileChannel.open(this.localFile, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            long position = offset;
            while (buffer.hasRemaining()) {
               int read = channel.read(buffer, position);
               if (read < 0) {
                  throw new EOFException("Unexpected GEBCO 2026 local file length for " + this.localFile);
               }

               position += read;
            }
         }

         return data;
      }

      private byte[] readCachedRange(Path cachePath, int expectedLength) throws IOException {
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            byte[] data = Files.readAllBytes(cachePath);
            if (data.length == expectedLength) {
               return data;
            } else {
               Files.deleteIfExists(cachePath);
               return null;
            }
         }
      }

      private void writeCachedRange(Path cachePath, byte[] data, long generation) {
         try {
            TellusCacheFiles.writeBytesIfCurrent(TellusCacheDomain.GEBCO2026, generation, cachePath, data);
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to cache GEBCO 2026 range {}", cachePath, error);
         }
      }
   }

   static final class TileFile {
      private static final int TAG_IMAGE_WIDTH = 256;
      private static final int TAG_IMAGE_HEIGHT = 257;
      private static final int TAG_BITS_PER_SAMPLE = 258;
      private static final int TAG_COMPRESSION = 259;
      private static final int TAG_STRIP_OFFSETS = 273;
      private static final int TAG_SAMPLES_PER_PIXEL = 277;
      private static final int TAG_ROWS_PER_STRIP = 278;
      private static final int TAG_STRIP_BYTE_COUNTS = 279;
      private static final int TAG_SAMPLE_FORMAT = 339;
      private static final int TAG_MODEL_PIXEL_SCALE = 33550;
      private static final int TAG_MODEL_TIEPOINT = 33922;
      private static final int TAG_GDAL_NO_DATA = 42113;
      private static final int TYPE_BYTE = 1;
      private static final int TYPE_ASCII = 2;
      private static final int TYPE_SHORT = 3;
      private static final int TYPE_LONG = 4;
      private static final int TYPE_DOUBLE = 12;
      private static final int COMPRESSION_NONE = 1;
      private static final int SAMPLE_FORMAT_SIGNED_INT = 2;
      private final RangeReader reader;
      private final ByteOrder order;
      private final int width;
      private final int height;
      private final int rowsPerStrip;
      private final long[] stripOffsets;
      private final int[] stripByteCounts;
      private final int noDataValue;
      private final double tiepointPixelX;
      private final double tiepointPixelY;
      private final double tiepointLon;
      private final double tiepointLat;
      private final double pixelWidth;
      private final double pixelHeight;
      private final Map<Integer, short[]> stripCache = new LinkedHashMap<Integer, short[]>(16, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<Integer, short[]> eldest) {
            return this.size() > MAX_STRIP_CACHE;
         }
      };

      private TileFile(
         RangeReader reader,
         ByteOrder order,
         int width,
         int height,
         int rowsPerStrip,
         long[] stripOffsets,
         int[] stripByteCounts,
         int noDataValue,
         double tiepointPixelX,
         double tiepointPixelY,
         double tiepointLon,
         double tiepointLat,
         double pixelWidth,
         double pixelHeight
      ) {
         this.reader = reader;
         this.order = order;
         this.width = width;
         this.height = height;
         this.rowsPerStrip = rowsPerStrip;
         this.stripOffsets = stripOffsets;
         this.stripByteCounts = stripByteCounts;
         this.noDataValue = noDataValue;
         this.tiepointPixelX = tiepointPixelX;
         this.tiepointPixelY = tiepointPixelY;
         this.tiepointLon = tiepointLon;
         this.tiepointLat = tiepointLat;
         this.pixelWidth = pixelWidth;
         this.pixelHeight = pixelHeight;
      }

      private double sample(double lat, double lon, ReadMode mode) {
         double pixelX = pixelCenterCoordinate(this.tiepointPixelX, lon - this.tiepointLon, this.pixelWidth);
         double pixelY = pixelCenterCoordinate(this.tiepointPixelY, this.tiepointLat - lat, this.pixelHeight);
         pixelX = Mth.clamp(pixelX, 0.0, this.width - 1.0);
         pixelY = Mth.clamp(pixelY, 0.0, this.height - 1.0);
         int x0 = Mth.floor(pixelX);
         int y0 = Mth.floor(pixelY);
         int x1 = Math.min(x0 + 1, this.width - 1);
         int y1 = Math.min(y0 + 1, this.height - 1);
         double dx = pixelX - x0;
         double dy = pixelY - y0;
         double v00 = this.samplePixel(x0, y0, mode);
         double v10 = this.samplePixel(x1, y0, mode);
         double v01 = this.samplePixel(x0, y1, mode);
         double v11 = this.samplePixel(x1, y1, mode);
         return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
      }

      private void prefetch(double lat, double lon, ReadMode mode) {
         this.sample(lat, lon, mode);
      }

      static double pixelCenterCoordinate(double tiepointPixel, double worldDistance, double pixelSize) {
         return tiepointPixel + worldDistance / pixelSize - 0.5;
      }

      private double samplePixel(int x, int y, ReadMode mode) {
         try {
            int stripIndex = y / this.rowsPerStrip;
            short[] strip = this.getStrip(stripIndex, mode);
            int localY = y - stripIndex * this.rowsPerStrip;
            int value = strip[x + localY * this.width];
            return value == this.noDataValue ? Double.NaN : value;
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to sample GEBCO 2026 pixel {},{}", x, y, error);
            return Double.NaN;
         }
      }

      private short[] getStrip(int stripIndex, ReadMode mode) throws IOException {
         synchronized(this.stripCache) {
            short[] cached = this.stripCache.get(stripIndex);
            if (cached != null) {
               return cached;
            } else if (mode == ReadMode.MEMORY_ONLY) {
               throw new EOFException("GEBCO 2026 strip is not loaded in memory");
            }
         }

         short[] strip = this.readStrip(stripIndex, mode);
         synchronized(this.stripCache) {
            this.stripCache.put(stripIndex, strip);
            return strip;
         }
      }

      private short[] readStrip(int stripIndex, ReadMode mode) throws IOException {
         if (stripIndex < 0 || stripIndex >= this.stripOffsets.length) {
            throw new IOException("Invalid GEBCO 2026 strip index " + stripIndex);
         }

         int stripHeight = Math.min(this.rowsPerStrip, this.height - stripIndex * this.rowsPerStrip);
         int expectedValues = this.width * stripHeight;
         int expectedBytes = expectedValues * 2;
         int byteCount = this.stripByteCounts[stripIndex];
         if (byteCount < expectedBytes) {
            throw new EOFException("Invalid GEBCO 2026 strip byte count " + byteCount);
         }

         byte[] raw = this.reader.read(this.stripOffsets[stripIndex], byteCount, mode);
         ByteBuffer buffer = ByteBuffer.wrap(raw).order(this.order);
         short[] values = new short[expectedValues];

         for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getShort();
         }

         return values;
      }

      private static Gebco2026ElevationSource.TileFile open(
         Gebco2026ElevationSource.TileKey key, Path cacheDir, Path localFile, ReadMode mode
      ) throws IOException {
         RangeReader reader = new RangeReader(key.url(), cacheDir, localFile);
         byte[] header = reader.read(0L, 16, mode);
         ByteOrder order = switch (header[0]) {
            case 73 -> ByteOrder.LITTLE_ENDIAN;
            case 77 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid GEBCO 2026 TIFF byte order");
         };
         ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(order);
         headerBuffer.getShort();
         short magic = headerBuffer.getShort();
         if (magic != 42) {
            throw new IOException("Expected standard GEBCO 2026 TIFF magic");
         }

         long ifdOffset = Integer.toUnsignedLong(headerBuffer.getInt());
         int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2, mode)).order(order).getShort());
         byte[] entryBytes = reader.read(ifdOffset + 2L, entryCount * 12, mode);
         ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
         int width = -1;
         int height = -1;
         int bitsPerSample = -1;
         int compression = COMPRESSION_NONE;
         int sampleFormat = SAMPLE_FORMAT_SIGNED_INT;
         int samplesPerPixel = 1;
         int rowsPerStrip = -1;
         long[] stripOffsets = null;
         int[] stripByteCounts = null;
         int noDataValue = DEFAULT_NO_DATA;
         double[] pixelScale = null;
         double[] tiepoints = null;

         for (int i = 0; i < entryCount; i++) {
            int tag = Short.toUnsignedInt(entries.getShort());
            int type = Short.toUnsignedInt(entries.getShort());
            long count = Integer.toUnsignedLong(entries.getInt());
            byte[] valueBytes = new byte[4];
            entries.get(valueBytes);
            TiffEntry entry = new TiffEntry(type, count, valueBytes, Integer.toUnsignedLong(ByteBuffer.wrap(valueBytes).order(order).getInt()));

            switch (tag) {
               case TAG_IMAGE_WIDTH:
                  width = readIntValue(entry, order);
                  break;
               case TAG_IMAGE_HEIGHT:
                  height = readIntValue(entry, order);
                  break;
               case TAG_BITS_PER_SAMPLE:
                  bitsPerSample = readIntValue(entry, order);
                  break;
               case TAG_COMPRESSION:
                  compression = readIntValue(entry, order);
                  break;
               case TAG_STRIP_OFFSETS:
                  stripOffsets = readLongArray(reader, entry, order, mode);
                  break;
               case TAG_SAMPLES_PER_PIXEL:
                  samplesPerPixel = readIntValue(entry, order);
                  break;
               case TAG_ROWS_PER_STRIP:
                  rowsPerStrip = readIntValue(entry, order);
                  break;
               case TAG_STRIP_BYTE_COUNTS:
                  stripByteCounts = readIntArray(reader, entry, order, mode);
                  break;
               case TAG_SAMPLE_FORMAT:
                  sampleFormat = readIntValue(entry, order);
                  break;
               case TAG_MODEL_PIXEL_SCALE:
                  pixelScale = readDoubleArray(reader, entry, order, mode);
                  break;
               case TAG_MODEL_TIEPOINT:
                  tiepoints = readDoubleArray(reader, entry, order, mode);
                  break;
               case TAG_GDAL_NO_DATA:
                  noDataValue = readNoDataValue(reader, entry, order, mode);
            }
         }

         if (width <= 0 || height <= 0) {
            throw new IOException("Missing GEBCO 2026 TIFF size tags");
         } else if (bitsPerSample != 16 || sampleFormat != SAMPLE_FORMAT_SIGNED_INT || samplesPerPixel != 1) {
            throw new IOException("Unsupported GEBCO 2026 TIFF sample format bits=" + bitsPerSample + " format=" + sampleFormat);
         } else if (compression != COMPRESSION_NONE) {
            throw new IOException("Unsupported GEBCO 2026 TIFF compression " + compression);
         } else if (rowsPerStrip <= 0 || stripOffsets == null || stripByteCounts == null) {
            throw new IOException("Missing GEBCO 2026 TIFF strip tags");
         } else {
            int expectedStrips = (height + rowsPerStrip - 1) / rowsPerStrip;
            if (stripOffsets.length < expectedStrips || stripByteCounts.length < expectedStrips) {
               throw new IOException(
                  "GEBCO 2026 TIFF strip arrays too short offsets=" + stripOffsets.length
                     + " byteCounts=" + stripByteCounts.length
                     + " expected=" + expectedStrips
               );
            }
         }

         double pixelWidth = pixelScale != null && pixelScale.length >= 2 ? pixelScale[0] : 90.0 / width;
         double pixelHeight = pixelScale != null && pixelScale.length >= 2 ? pixelScale[1] : 90.0 / height;
         double tiepointPixelX = tiepoints != null && tiepoints.length >= 6 ? tiepoints[0] : 0.0;
         double tiepointPixelY = tiepoints != null && tiepoints.length >= 6 ? tiepoints[1] : 0.0;
         double tiepointLon = tiepoints != null && tiepoints.length >= 6 ? tiepoints[3] : key.west();
         double tiepointLat = tiepoints != null && tiepoints.length >= 6 ? tiepoints[4] : key.north();
         return new Gebco2026ElevationSource.TileFile(
            reader,
            order,
            width,
            height,
            rowsPerStrip,
            stripOffsets,
            stripByteCounts,
            noDataValue,
            tiepointPixelX,
            tiepointPixelY,
            tiepointLon,
            tiepointLat,
            pixelWidth,
            pixelHeight
         );
      }

      private static int readIntValue(TiffEntry entry, ByteOrder order) throws IOException {
         if (entry.count() != 1L) {
            throw new IOException("Expected single GEBCO 2026 TIFF value");
         } else {
            ByteBuffer inline = ByteBuffer.wrap(entry.valueBytes()).order(order);
            return switch (entry.type()) {
               case TYPE_BYTE -> entry.valueBytes()[0] & 255;
               case TYPE_SHORT -> Short.toUnsignedInt(inline.getShort());
               case TYPE_LONG -> (int)entry.valueOffset();
               default -> throw new IOException("Unsupported GEBCO 2026 TIFF value type " + entry.type());
            };
         }
      }

      private static int readNoDataValue(RangeReader reader, TiffEntry entry, ByteOrder order, ReadMode mode) throws IOException {
         String text = readAscii(reader, entry, order, mode).trim();
         if (text.isEmpty()) {
            return DEFAULT_NO_DATA;
         } else {
            try {
               return (int)Math.round(Double.parseDouble(text));
            } catch (NumberFormatException error) {
               return DEFAULT_NO_DATA;
            }
         }
      }

      private static String readAscii(RangeReader reader, TiffEntry entry, ByteOrder order, ReadMode mode) throws IOException {
         if (entry.type() != TYPE_ASCII) {
            throw new IOException("Unsupported GEBCO 2026 ASCII type " + entry.type());
         }

         byte[] data = readFieldBytes(reader, entry, order, mode);
         int length = data.length;
         while (length > 0 && data[length - 1] == 0) {
            length--;
         }

         return new String(data, 0, length, StandardCharsets.US_ASCII);
      }

      private static long[] readLongArray(RangeReader reader, TiffEntry entry, ByteOrder order, ReadMode mode) throws IOException {
         if (entry.count() <= 0L) {
            return new long[0];
         } else if (entry.count() > Integer.MAX_VALUE) {
            throw new IOException("GEBCO 2026 TIFF array too large " + entry.count());
         } else {
            byte[] data = readFieldBytes(reader, entry, order, mode);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            long[] values = new long[(int)entry.count()];

            for (int i = 0; i < values.length; i++) {
               values[i] = switch (entry.type()) {
                  case TYPE_BYTE -> buffer.get() & 255;
                  case TYPE_SHORT -> Short.toUnsignedInt(buffer.getShort());
                  case TYPE_LONG -> Integer.toUnsignedLong(buffer.getInt());
                  default -> throw new IOException("Unsupported GEBCO 2026 TIFF array type " + entry.type());
               };
            }

            return values;
         }
      }

      private static int[] readIntArray(RangeReader reader, TiffEntry entry, ByteOrder order, ReadMode mode) throws IOException {
         long[] values = readLongArray(reader, entry, order, mode);
         int[] output = new int[values.length];

         for (int i = 0; i < values.length; i++) {
            if (values[i] > Integer.MAX_VALUE) {
               throw new IOException("GEBCO 2026 TIFF value too large " + values[i]);
            }

            output[i] = (int)values[i];
         }

         return output;
      }

      private static double[] readDoubleArray(RangeReader reader, TiffEntry entry, ByteOrder order, ReadMode mode) throws IOException {
         if (entry.type() != TYPE_DOUBLE) {
            throw new IOException("Unsupported GEBCO 2026 TIFF double type " + entry.type());
         } else if (entry.count() > Integer.MAX_VALUE) {
            throw new IOException("GEBCO 2026 TIFF double array too large");
         } else {
            byte[] data = readFieldBytes(reader, entry, order, mode);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            double[] values = new double[(int)entry.count()];

            for (int i = 0; i < values.length; i++) {
               values[i] = buffer.getDouble();
            }

            return values;
         }
      }

      private static byte[] readFieldBytes(RangeReader reader, TiffEntry entry, ByteOrder order, ReadMode mode) throws IOException {
         int size = typeSize(entry.type());
         if (size <= 0) {
            throw new IOException("Unsupported GEBCO 2026 TIFF field type " + entry.type());
         } else if (entry.count() > Integer.MAX_VALUE / size) {
            throw new IOException("GEBCO 2026 TIFF field too large");
         } else {
            int byteCount = (int)entry.count() * size;
            if (byteCount <= 4) {
               byte[] inline = new byte[byteCount];
               System.arraycopy(entry.valueBytes(), 0, inline, 0, byteCount);
               return inline;
            } else {
               return reader.read(entry.valueOffset(), byteCount, mode);
            }
         }
      }

      private static int typeSize(int type) {
         return switch (type) {
            case TYPE_BYTE, TYPE_ASCII -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG -> 4;
            case TYPE_DOUBLE -> 8;
            default -> 0;
         };
      }
   }

   record TileKey(int south, int west) {
      int north() {
         return this.south + 90;
      }

      int east() {
         return this.west + 90;
      }

      String filename() {
         return String.format(
            Locale.ROOT,
            "gebco_2026_n%s_s%s_w%s_e%s_geotiff.tif",
            coordinate(this.north()),
            coordinate(this.south),
            coordinate(this.west),
            coordinate(this.east())
         );
      }

      private String url() {
         return String.format(Locale.ROOT, TILE_URL_PATTERN, this.filename());
      }

      private String cacheDirectory() {
         return this.filename().replace(".tif", "");
      }

      private static String coordinate(int value) {
         return String.format(Locale.ROOT, "%d.0", value);
      }
   }

   private record TileRecord(Gebco2026ElevationSource.TileFile file, boolean missing) {
   }

   private record TiffEntry(int type, long count, byte[] valueBytes, long valueOffset) {
   }
}
