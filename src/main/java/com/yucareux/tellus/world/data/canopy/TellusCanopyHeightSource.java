package com.yucareux.tellus.world.data.canopy;

import com.yucareux.tellus.compat.MinecraftRelease;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.ParallelDownloadRunner;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Samples the ETH Global Canopy Height 2020 layer from its official ArcGIS
 * Living Atlas image-tile cache.
 *
 * <p>Only the small LERC tiles actually encountered by a world are downloaded.
 * The service's overview levels are selected for coarse previews, while
 * full-detail generation keeps native data at the map scales where individual
 * tree placement benefits from it.</p>
 */
public final class TellusCanopyHeightSource implements TellusCacheHandle {
   public static final String DATASET_NAME = "ETH Global Canopy Height 2020";
   public static final String SERVICE_URL =
      "https://tiledimageservices.arcgis.com/P3ePLMYs2RVChkJx/arcgis/rest/services/10m_Tree_Canopy_Height/ImageServer";
   private static final int TILE_SIZE = 256;
   private static final int MIN_LEVEL = 0;
   private static final int NATIVE_LEVEL = 13;
   private static final double NATIVE_RESOLUTION_DEGREES = 1.0 / 12000.0;
   private static final double NATIVE_RESOLUTION_METERS = EarthProjection.METERS_PER_DEGREE * NATIVE_RESOLUTION_DEGREES;
   private static final double ORIGIN_LONGITUDE = -180.0;
   private static final double ORIGIN_LATITUDE = 84.0;
   private static final double MIN_LATITUDE = -60.0;
   private static final double MAX_LATITUDE = 84.0;
   private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
   private static final int MEMORY_TILE_COUNT = intProperty("tellus.canopyHeight.memoryTiles", 64, 4, 1024);
   private static final long DISK_CACHE_BYTES =
      (long)intProperty("tellus.canopyHeight.diskCacheMiB", 256, 16, 4096) * 1024L * 1024L;
   private static final int CONNECT_TIMEOUT_SECONDS = intProperty("tellus.canopyHeight.connectTimeoutSeconds", 10, 1, 120);
   private static final int REQUEST_TIMEOUT_SECONDS = intProperty("tellus.canopyHeight.requestTimeoutSeconds", 30, 1, 180);
   private static final int FETCH_ATTEMPTS = intProperty("tellus.canopyHeight.fetchAttempts", 2, 1, 5);
   private static final int MAX_AREA_TILE_COUNT = intProperty("tellus.canopyHeight.maxAreaTiles", 4096, 64, 65536);
   private static final int DECODER_COUNT = intProperty(
      "tellus.canopyHeight.decodeThreads",
      Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())),
      1,
      8
   );
   private static final long FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30L);
   // 判定"这个像素算有树冠"的最小高度，单位：米；与树木生成器把低于此值的格子降级为灌木的阈值保持一致喵。
   private static final int COVER_HEIGHT_THRESHOLD_METERS = 2;
   // 覆盖率采样窗口在单轴上的最少像素数，单位：个；低于 3 个样本的覆盖率噪声过大喵。
   private static final int MIN_COVER_WINDOW_PIXELS = 3;
   // 覆盖率采样窗口在单轴上的最多像素数，单位：个；上限用来把每个放置格的开销钉死在常数级喵。
   private static final int MAX_COVER_WINDOW_PIXELS = 9;

   private final String serviceUrl = configuredServiceUrl();
   private final Path cacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/canopy-height-eth-2020-v1/arcgis-living-atlas");
   private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .version(HttpClient.Version.HTTP_2)
      .build();
   private final Cache<TileKey, RasterTile> cache = CacheBuilder.newBuilder().maximumSize(MEMORY_TILE_COUNT).build();
   private final Cache<TileKey, Long> failedUntil = CacheBuilder.newBuilder().maximumSize(512).build();
   private final ArrayBlockingQueue<LercU8Decoder> decoders = new ArrayBlockingQueue<>(DECODER_COUNT);
   private final AtomicInteger writesSincePrune = new AtomicInteger();
   private final AtomicBoolean serviceFailureLogged = new AtomicBoolean();

   public TellusCanopyHeightSource() {
      for (int index = 0; index < DECODER_COUNT; index++) {
         this.decoders.add(new LercU8Decoder());
      }
      TellusCacheRegistry.register(this);
   }

   public CanopySample sampleCanopy(double blockX, double blockZ, double worldScale) {
      return this.sampleCanopy(blockX, blockZ, worldScale, worldScale, managedLookupMode());
   }

   public CanopySample sampleCanopy(
      double blockX, double blockZ, double worldScale, double previewResolutionMeters
   ) {
      return this.sampleCanopy(blockX, blockZ, worldScale, previewResolutionMeters, managedLookupMode());
   }

   /**
    * 采样冠层高度，并按指定的物理窗口宽度额外统计树冠覆盖率喵。
    *
    * <p>当 {@code coverWindowMeters} 大于 0 时，覆盖率会在一个与该米数相当、但采样点数被钉死在
    * 常数级的稀疏窗口上统计，这样即使地图比例尺变大也不会让单格开销爆掉喵。
    * 高度统计仍然只使用原来的 3×3 邻域，保证树高结果与旧版完全一致喵。</p>
    *
    * @param blockX                采样点在世界坐标中的 X，单位：方块喵
    * @param blockZ                采样点在世界坐标中的 Z，单位：方块喵
    * @param worldScale            地图比例尺，即一个方块代表多少米喵
    * @param previewResolutionMeters 预览模式下的采样分辨率，单位：米；正常生成时等于 worldScale喵
    * @param coverWindowMeters     覆盖率统计窗口的物理宽度，单位：米；小于等于 0 时退化为使用 3×3 邻域喵
    * @return 冠层采样结果，含高度统计与树冠覆盖率喵
    */
   public CanopySample sampleCanopy(
      double blockX, double blockZ, double worldScale, double previewResolutionMeters, double coverWindowMeters
   ) {
      // 使用托管的查找模式，与正常生成路径保持一致喵。
      return this.sampleCanopy(
         blockX, blockZ, worldScale, previewResolutionMeters, coverWindowMeters, managedLookupMode()
      );
   }

   public CanopySample sampleCanopyLocalOnly(
      double blockX, double blockZ, double worldScale, double previewResolutionMeters
   ) {
      return this.sampleCanopy(blockX, blockZ, worldScale, previewResolutionMeters, LookupMode.LOCAL_ONLY);
   }

   public CanopySample sampleCanopyMemoryOnly(
      double blockX, double blockZ, double worldScale, double previewResolutionMeters
   ) {
      return this.sampleCanopy(blockX, blockZ, worldScale, previewResolutionMeters, LookupMode.MEMORY_ONLY);
   }

   private CanopySample sampleCanopy(
      double blockX,
      double blockZ,
      double worldScale,
      double previewResolutionMeters,
      LookupMode lookupMode
   ) {
      // 不指定窗口时按旧行为处理，覆盖率直接来自原来的 3×3 邻域喵。
      return this.sampleCanopy(blockX, blockZ, worldScale, previewResolutionMeters, 0.0, lookupMode);
   }

   private CanopySample sampleCanopy(
      double blockX,
      double blockZ,
      double worldScale,
      double previewResolutionMeters,
      double coverWindowMeters,
      LookupMode lookupMode
   ) {
      GeoPoint point = geoPoint(blockX, blockZ, worldScale);
      if (point == null || !withinCoverage(point.longitude(), point.latitude())) {
         return CanopySample.unavailable();
      }

      int level = levelForResolution(worldScale, previewResolutionMeters);
      PixelPosition center = pixelPosition(point.longitude(), point.latitude(), level);
      if (center == null) {
         return CanopySample.unavailable();
      }

      int[] values = new int[9];
      int valueCount = 0;
      int centerHeight = -1;
      // 3×3 邻域内达到"有树冠"阈值的像素个数，单位：个；它是覆盖率的分子喵。
      int coverPixelCount = 0;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            int value = this.sampleGlobalPixel(level, center.globalPixelX() + dx, center.globalPixelY() + dy, lookupMode);
            if (dx == 0 && dy == 0) {
               centerHeight = value;
            }
            if (value >= 0) {
               values[valueCount++] = value;
               // 顺手统计有树冠的像素：这一步不产生任何额外 IO，只是把原本读到的数值利用起来喵。
               if (value >= COVER_HEIGHT_THRESHOLD_METERS) {
                  coverPixelCount++;
               }
            }
         }
      }
      if (valueCount == 0) {
         return CanopySample.unavailable();
      }

      java.util.Arrays.sort(values, 0, valueCount);
      double sum = 0.0;
      for (int index = 0; index < valueCount; index++) {
         sum += values[index];
      }
      double median = percentile(values, valueCount, 0.5);
      double percentile75 = percentile(values, valueCount, 0.75);
      double percentile90 = percentile(values, valueCount, 0.9);
      double maximum = values[valueCount - 1];
      double centerValue = centerHeight >= 0 ? centerHeight : median;
      // 3×3 邻域得出的树冠覆盖率，单位：无量纲比例；它是窗口版覆盖率不可用时的兜底喵。
      double coverFraction = (double)coverPixelCount / valueCount;
      if (coverWindowMeters > 0.0) {
         // 当调用方要求按放置格的物理尺寸统计覆盖率时，改用稀疏大窗口；返回负值表示这窗口里没有任何有效像素喵。
         double windowCoverFraction = this.sampleWindowCoverFraction(
            level, center.globalPixelX(), center.globalPixelY(), coverWindowMeters, lookupMode
         );
         if (windowCoverFraction >= 0.0) {
            coverFraction = windowCoverFraction;
         }
      }
      return new CanopySample(
         true, centerValue, sum / valueCount, median, percentile75, percentile90, maximum, level, valueCount, coverFraction
      );
   }

   /**
    * 在一个稀疏大窗口上统计树冠覆盖率喵。
    *
    * <p>整体思路：先把窗口的物理宽度换算成该层级下的像素跨度，再把采样点数夹到
    * [MIN_COVER_WINDOW_PIXELS, MAX_COVER_WINDOW_PIXELS] 个，用等距步长铺开取点，
    * 这样无论地图比例尺多大，单次统计的像素读取次数都有上界喵。</p>
    *
    * <p>输入：层级、窗口中心像素坐标、窗口物理宽度（米）。<br>
    * 输出：树冠覆盖率，单位：无量纲比例；窗口内没有任何有效像素时返回 -1。边界条件：
    * 层级超出原生范围时步长会自然退化，仍然只会读到该层级已有的像素喵。</p>
    *
    * @param level             当前采样所用的栅格层级喵
    * @param centerPixelX      窗口中心的全局像素 X 坐标喵
    * @param centerPixelY      窗口中心的全局像素 Y 坐标喵
    * @param coverWindowMeters 窗口的物理宽度，单位：米喵
    * @param lookupMode        瓦片查找模式，决定是否允许联网补取瓦片喵
    * @return 覆盖率，单位：无量纲比例；无有效像素时为 -1喵
    */
   private double sampleWindowCoverFraction(
      int level, long centerPixelX, long centerPixelY, double coverWindowMeters, LookupMode lookupMode
   ) {
      // 该层级单个像素代表的物理宽度，单位：米喵。
      double resolutionMeters = resolutionMetersAtLevel(level);
      // 喵~防御：解析度异常（NaN 或非正）时直接放弃窗口统计，让调用方回退到 3×3 结果喵。
      if (!(resolutionMeters > 0.0)) {
         return -1.0;
      }
      // 窗口横跨的像素个数，单位：个；至少为 1，避免除零喵。
      double windowPixels = Math.max(1.0, coverWindowMeters / resolutionMeters);
      // 把采样点数夹到常数上界，保证每个放置格的开销不随比例尺膨胀喵。
      int gridSteps = (int)Math.round(windowPixels);
      gridSteps = Math.max(MIN_COVER_WINDOW_PIXELS, Math.min(MAX_COVER_WINDOW_PIXELS, gridSteps));
      // 采样点在窗口内的对称半跨度，单位：个采样点喵。
      double halfSpan = (gridSteps - 1) / 2.0;
      // 相邻采样点之间的像素步长，单位：像素；窗口像素数小于采样点数时退化为逐像素喵。
      double stride = windowPixels > gridSteps ? windowPixels / gridSteps : 1.0;
      // 窗口内有效像素计数（分子分母共用），单位：个喵。
      int validCount = 0;
      // 窗口内有树冠的像素计数，单位：个喵。
      int coveredCount = 0;
      for (int gridY = 0; gridY < gridSteps; gridY++) {
         // 当前采样行的像素偏移，单位：像素喵。
         long offsetY = Math.round((gridY - halfSpan) * stride);
         for (int gridX = 0; gridX < gridSteps; gridX++) {
            // 当前采样列的像素偏移，单位：像素喵。
            long offsetX = Math.round((gridX - halfSpan) * stride);
            // 读取该偏移处的冠层高度，单位：米；负值表示无数据喵。
            int value = this.sampleGlobalPixel(level, centerPixelX + offsetX, centerPixelY + offsetY, lookupMode);
            if (value >= 0) {
               validCount++;
               if (value >= COVER_HEIGHT_THRESHOLD_METERS) {
                  coveredCount++;
               }
            }
         }
      }
      // 喵~防御：窗口内一个有效像素都没有时返回 -1，让调用方保留 3×3 的兜底覆盖率喵。
      return validCount == 0 ? -1.0 : (double)coveredCount / validCount;
   }

   /**
    * 计算某一层级下单个像素代表的物理宽度喵。
    *
    * @param level 栅格层级喵
    * @return 像素宽度，单位：米喵
    */
   private static double resolutionMetersAtLevel(int level) {
      // 每降一级，像素物理尺寸翻倍，所以用 2 的 (原生层级 - 当前层级) 次方做缩放喵。
      double levelScale = Math.pow(2.0, NATIVE_LEVEL - level);
      // 原生像素宽度乘以缩放系数即得当前层级的像素宽度喵。
      return NATIVE_RESOLUTION_METERS * levelScale;
   }

   public void prefetchTiles(
      int centerBlockX, int centerBlockZ, double worldScale, int radiusChunks, double previewResolutionMeters
   ) {
      int radiusBlocks = Math.max(8, Math.max(0, radiusChunks) * 16 + 8);
      this.preloadAreaInputs(
         centerBlockX - radiusBlocks,
         centerBlockZ - radiusBlocks,
         centerBlockX + radiusBlocks,
         centerBlockZ + radiusBlocks,
         worldScale,
         previewResolutionMeters,
         0,
         null
      );
   }

   public int preloadAreaTaskCount(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      double worldScale,
      double previewResolutionMeters
   ) {
      return tileKeysForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, previewResolutionMeters).size();
   }

   /**
    * Selects the finest available overview that keeps a large, transient preview
    * inside its tile budget. Regular chunk generation never uses this adjustment.
    */
   public static double resolutionForAreaTileBudget(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      double worldScale,
      double requestedResolutionMeters,
      int maxTileCount
   ) {
      double resolutionMeters = finitePositive(requestedResolutionMeters, worldScale);
      int tileBudget = Math.max(1, maxTileCount);
      for (int overviewStep = 0; overviewStep <= NATIVE_LEVEL + 4; overviewStep++) {
         if (areaTileCount(
               minBlockX,
               minBlockZ,
               maxBlockX,
               maxBlockZ,
               worldScale,
               resolutionMeters
            ) <= tileBudget) {
            break;
         }
         resolutionMeters *= 2.0;
         if (!Double.isFinite(resolutionMeters)) {
            return Double.MAX_VALUE;
         }
      }
      return resolutionMeters;
   }

   static int areaTileCount(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      double worldScale,
      double previewResolutionMeters
   ) {
      return tileKeysForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, previewResolutionMeters).size();
   }

   public int preloadAreaInputs(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      double worldScale,
      double previewResolutionMeters,
      int completedOffset,
      BiConsumer<Integer, String> progress
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return completedOffset;
      }

      List<TileKey> keys = tileKeysForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, previewResolutionMeters);
      if (keys.isEmpty()) {
         return completedOffset;
      }

      BiConsumer<Integer, String> progressConsumer = progress == null ? (completed, detail) -> {
      } : progress;
      int startingUnits = completedOffset;
      progressConsumer.accept(completedOffset, "Loading " + keys.size() + " ETH canopy-height source tiles");
      return ParallelDownloadRunner.run(
         ParallelDownloadRunner.scope(
            "canopy-height-memory",
            TellusCacheRegistry.generation(TellusCacheDomain.CANOPY_HEIGHT)
         ),
         keys,
         completedOffset,
         this::loadTileIntoCache,
         (key, completed, phaseTotal) -> progressConsumer.accept(
            completed,
            "Loaded ETH canopy-height tile "
               + (completed - startingUnits)
               + "/"
               + phaseTotal
               + " ("
               + key.level()
               + "/"
               + key.row()
               + "/"
               + key.column()
               + ")"
         )
      );
   }

   private void loadTileIntoCache(TileKey key) {
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.CANOPY_HEIGHT);
      this.tile(key, LookupMode.BLOCKING);
      if (Thread.currentThread().isInterrupted()) {
         throw new java.util.concurrent.CancellationException("Interrupted while preloading ETH canopy-height tile " + key);
      }
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.CANOPY_HEIGHT, generation)) {
         this.cache.invalidate(key);
         throw new IllegalStateException("Discarded stale ETH canopy-height preload for " + key);
      }
   }

   private int sampleGlobalPixel(int level, long globalPixelX, long globalPixelY, LookupMode lookupMode) {
      if (globalPixelX < 0L || globalPixelY < 0L) {
         return -1;
      }
      int tileColumn = (int)Math.floorDiv(globalPixelX, TILE_SIZE);
      int tileRow = (int)Math.floorDiv(globalPixelY, TILE_SIZE);
      int pixelX = (int)Math.floorMod(globalPixelX, TILE_SIZE);
      int pixelY = (int)Math.floorMod(globalPixelY, TILE_SIZE);
      RasterTile tile = this.tile(new TileKey(level, tileRow, tileColumn), lookupMode);
      return tile == null ? -1 : tile.sample(pixelX, pixelY);
   }

   private RasterTile tile(TileKey key, LookupMode lookupMode) {
      RasterTile memoryTile = this.cache.getIfPresent(key);
      if (memoryTile != null || lookupMode == LookupMode.MEMORY_ONLY) {
         return memoryTile;
      }

      Long retryAt = this.failedUntil.getIfPresent(key);
      if (retryAt != null && System.nanoTime() < retryAt) {
         return null;
      }

      try {
         return this.cache.get(key, () -> this.loadTile(key, lookupMode));
      } catch (ExecutionException | RuntimeException error) {
         if (lookupMode != LookupMode.BLOCKING) {
            return null;
         }
         this.failedUntil.put(key, System.nanoTime() + FAILURE_RETRY_NANOS);
         Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
         if (this.serviceFailureLogged.compareAndSet(false, true)) {
            Tellus.LOGGER.warn("ETH canopy-height tiles are currently unavailable; procedural trees will use biome fallbacks", cause);
         } else {
            Tellus.LOGGER.debug("Failed to load ETH canopy tile {}", key, cause);
         }
         return null;
      }
   }

   private RasterTile loadTile(TileKey key, LookupMode lookupMode) throws IOException {
      Path cachePath = this.cachePath(key);
      if (Files.isRegularFile(cachePath)) {
         try {
            long cachedSize = Files.size(cachePath);
            if (cachedSize > 0L && cachedSize <= MAX_RESPONSE_BYTES) {
               byte[] bytes = Files.readAllBytes(cachePath);
               return RasterTile.from(this.decode(bytes));
            }
            throw new IOException("Unexpected cached ETH canopy tile size " + cachedSize);
         } catch (IOException | RuntimeException error) {
            Tellus.LOGGER.debug("Discarding invalid cached ETH canopy tile {}", cachePath, error);
            Files.deleteIfExists(cachePath);
         }
      }

      if (lookupMode != LookupMode.BLOCKING || ManagedTerrainNetworkPolicy.isCacheOnly()) {
         throw new IOException("ETH canopy tile is not in the local cache");
      }

      byte[] bytes = this.fetchTile(key);
      RasterTile tile = RasterTile.from(this.decode(bytes));
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.CANOPY_HEIGHT);
      if (TellusCacheFiles.writeBytesIfCurrent(TellusCacheDomain.CANOPY_HEIGHT, generation, cachePath, bytes)) {
         this.pruneDiskCachePeriodically();
      }
      this.serviceFailureLogged.set(false);
      return tile;
   }

   private LercU8Decoder.DecodedRaster decode(byte[] bytes) throws IOException {
      LercU8Decoder decoder;
      try {
         decoder = this.decoders.take();
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while waiting for an ETH canopy-height decoder", error);
      }

      try {
         return decoder.decode(bytes);
      } finally {
         this.decoders.offer(decoder);
      }
   }

   private byte[] fetchTile(TileKey key) throws IOException {
      URI uri = URI.create(this.serviceUrl + "/tile/" + key.level() + "/" + key.row() + "/" + key.column());
      IOException lastError = null;
      for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
         try {
            HttpRequest request = HttpRequest.newBuilder(uri)
               .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
               .header("Accept", "application/octet-stream")
               .header("User-Agent", "Tellus-Minecraft-Mod/" + MinecraftRelease.VERSION)
               .GET()
               .build();
            HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
               throw new IOException("HTTP " + response.statusCode() + " from ETH canopy tile service");
            }
            byte[] body = response.body();
            if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
               throw new IOException("Unexpected ETH canopy tile size " + body.length);
            }
            return body;
         } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading ETH canopy tile", error);
         } catch (IOException error) {
            lastError = error;
         }
      }
      throw Objects.requireNonNullElseGet(lastError, () -> new IOException("Unable to download ETH canopy tile"));
   }

   private void pruneDiskCachePeriodically() {
      if (this.writesSincePrune.incrementAndGet() < 32) {
         return;
      }
      this.writesSincePrune.set(0);
      try (var paths = Files.walk(this.cacheRoot)) {
         List<Path> files = paths.filter(Files::isRegularFile).toList();
         long total = 0L;
         List<CacheFile> candidates = new ArrayList<>(files.size());
         for (Path file : files) {
            try {
               long size = Files.size(file);
               total += size;
               candidates.add(new CacheFile(file, size, Files.getLastModifiedTime(file).toMillis()));
            } catch (IOException ignored) {
            }
         }
         if (total <= DISK_CACHE_BYTES) {
            return;
         }
         candidates.sort(Comparator.comparingLong(CacheFile::modifiedMillis));
         for (CacheFile candidate : candidates) {
            if (total <= DISK_CACHE_BYTES) {
               break;
            }
            try {
               if (Files.deleteIfExists(candidate.path())) {
                  total -= candidate.size();
               }
            } catch (IOException ignored) {
            }
         }
      } catch (IOException error) {
         Tellus.LOGGER.debug("Unable to prune ETH canopy-height cache", error);
      }
   }

   private Path cachePath(TileKey key) {
      return this.cacheRoot.resolve(Integer.toString(key.level())).resolve(Integer.toString(key.row())).resolve(key.column() + ".lerc");
   }

   static int levelForResolution(double worldScale, double previewResolutionMeters) {
      double requested = Math.max(NATIVE_RESOLUTION_METERS, finitePositive(previewResolutionMeters, worldScale) / 3.0);
      double ratio = requested / NATIVE_RESOLUTION_METERS;
      int overviewSteps = ratio <= 1.0 ? 0 : (int)Math.floor(Math.log(ratio) / Math.log(2.0));
      return Math.max(MIN_LEVEL, Math.min(NATIVE_LEVEL, NATIVE_LEVEL - overviewSteps));
   }

   static TileKey tileKey(double longitude, double latitude, int level) {
      PixelPosition position = pixelPosition(longitude, latitude, level);
      return position == null
         ? null
         : new TileKey(
            level,
            (int)Math.floorDiv(position.globalPixelY(), TILE_SIZE),
            (int)Math.floorDiv(position.globalPixelX(), TILE_SIZE)
         );
   }

   private static PixelPosition pixelPosition(double longitude, double latitude, int level) {
      if (!withinCoverage(longitude, latitude) || level < MIN_LEVEL || level > NATIVE_LEVEL) {
         return null;
      }
      double resolution = resolutionDegrees(level);
      long pixelX = (long)Math.floor((longitude - ORIGIN_LONGITUDE) / resolution);
      long pixelY = (long)Math.floor((ORIGIN_LATITUDE - latitude) / resolution);
      return pixelX < 0L || pixelY < 0L ? null : new PixelPosition(pixelX, pixelY);
   }

   private static List<TileKey> tileKeysForArea(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      double worldScale,
      double previewResolutionMeters
   ) {
      GeoPoint first = geoPoint(Math.min(minBlockX, maxBlockX), Math.min(minBlockZ, maxBlockZ), worldScale);
      GeoPoint second = geoPoint(Math.max(minBlockX, maxBlockX), Math.max(minBlockZ, maxBlockZ), worldScale);
      if (first == null || second == null) {
         return List.of();
      }

      double minLon = Math.max(-180.0, Math.min(first.longitude(), second.longitude()));
      double maxLon = Math.min(Math.nextDown(180.0), Math.max(first.longitude(), second.longitude()));
      double minLat = Math.max(MIN_LATITUDE, Math.min(first.latitude(), second.latitude()));
      double maxLat = Math.min(Math.nextDown(MAX_LATITUDE), Math.max(first.latitude(), second.latitude()));
      if (minLon > maxLon || minLat > maxLat) {
         return List.of();
      }

      int level = levelForResolution(worldScale, previewResolutionMeters);
      TileKey northWest;
      TileKey southEast;
      long tileCount;
      do {
         northWest = tileKey(minLon, maxLat, level);
         southEast = tileKey(maxLon, minLat, level);
         if (northWest == null || southEast == null) {
            return List.of();
         }
         tileCount = (long)(southEast.row() - northWest.row() + 1)
            * (long)(southEast.column() - northWest.column() + 1);
         if (tileCount <= MAX_AREA_TILE_COUNT || level == MIN_LEVEL) {
            break;
         }
         level--;
      } while (true);

      List<TileKey> keys = new ArrayList<>((int)Math.min(Integer.MAX_VALUE, tileCount));
      for (int row = northWest.row(); row <= southEast.row(); row++) {
         for (int column = northWest.column(); column <= southEast.column(); column++) {
            keys.add(new TileKey(level, row, column));
         }
      }
      return List.copyOf(keys);
   }

   private static GeoPoint geoPoint(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      if (!(blocksPerDegree > 0.0) || !Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
         return null;
      }
      return new GeoPoint(blockX / blocksPerDegree, EarthProjection.blockZToLat(blockZ, worldScale));
   }

   private static double resolutionDegrees(int level) {
      return NATIVE_RESOLUTION_DEGREES * (1L << (NATIVE_LEVEL - level));
   }

   private static boolean withinCoverage(double longitude, double latitude) {
      return Double.isFinite(longitude)
         && Double.isFinite(latitude)
         && longitude >= -180.0
         && longitude < 180.0
         && latitude >= MIN_LATITUDE
         && latitude < MAX_LATITUDE;
   }

   private static double percentile(int[] sorted, int length, double fraction) {
      if (length == 1) {
         return sorted[0];
      }
      double index = Math.max(0.0, Math.min(length - 1.0, fraction * (length - 1.0)));
      int lower = (int)Math.floor(index);
      int upper = Math.min(length - 1, lower + 1);
      double blend = index - lower;
      return sorted[lower] * (1.0 - blend) + sorted[upper] * blend;
   }

   private static double finitePositive(double primary, double fallback) {
      if (Double.isFinite(primary) && primary > 0.0) {
         return primary;
      }
      return Double.isFinite(fallback) && fallback > 0.0 ? fallback : NATIVE_RESOLUTION_METERS;
   }

   private static LookupMode managedLookupMode() {
      return ManagedTerrainNetworkPolicy.isCacheOnly() ? LookupMode.LOCAL_ONLY : LookupMode.BLOCKING;
   }

   private static int intProperty(String key, int fallback, int minimum, int maximum) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return fallback;
      }
      try {
         return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim())));
      } catch (NumberFormatException error) {
         Tellus.LOGGER.warn("Invalid integer system property {}={}, using {}", key, value, fallback);
         return fallback;
      }
   }

   private static String configuredServiceUrl() {
      String configured = System.getProperty("tellus.canopyHeight.serviceUrl", SERVICE_URL);
      String normalized = configured == null || configured.isBlank() ? SERVICE_URL : configured.trim();
      while (normalized.endsWith("/")) {
         normalized = normalized.substring(0, normalized.length() - 1);
      }
      return normalized;
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.CANOPY_HEIGHT;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.failedUntil.invalidateAll();
      this.failedUntil.cleanUp();
      this.serviceFailureLogged.set(false);
   }

   private enum LookupMode {
      BLOCKING,
      LOCAL_ONLY,
      MEMORY_ONLY
   }

   public record CanopySample(
      boolean available,
      double centerHeightMeters,
      double meanHeightMeters,
      double medianHeightMeters,
      double percentile75Meters,
      double percentile90Meters,
      double maximumHeightMeters,
      int sourceLevel,
      int validSampleCount,
      double coverFraction
   ) {
      private static CanopySample unavailable() {
         // 不可用时把覆盖率记为 0，调用方据此走"无数据、不削减树木"的回退路径喵。
         return new CanopySample(false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, 0, 0.0);
      }
   }

   static record TileKey(int level, int row, int column) {
   }

   private record PixelPosition(long globalPixelX, long globalPixelY) {
   }

   private record GeoPoint(double longitude, double latitude) {
   }

   private record CacheFile(Path path, long size, long modifiedMillis) {
   }

   private static final class RasterTile {
      private final int width;
      private final int height;
      private final byte[] pixels;
      private final byte[] mask;

      private RasterTile(int width, int height, byte[] pixels, byte[] mask) {
         this.width = width;
         this.height = height;
         this.pixels = pixels;
         this.mask = mask;
      }

      private static RasterTile from(LercU8Decoder.DecodedRaster decoded) throws IOException {
         if (decoded.width() != TILE_SIZE || decoded.height() != TILE_SIZE) {
            throw new IOException("Unexpected ETH canopy tile size " + decoded.width() + "x" + decoded.height());
         }
         return new RasterTile(decoded.width(), decoded.height(), decoded.pixels(), decoded.mask());
      }

      private int sample(int pixelX, int pixelY) {
         if (pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
            return -1;
         }
         int index = pixelY * this.width + pixelX;
         return this.mask != null && this.mask[index] == 0 ? -1 : this.pixels[index] & 255;
      }
   }
}
