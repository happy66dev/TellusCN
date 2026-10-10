package com.yucareux.tellus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.config.MirrorConfig;
import com.yucareux.tellus.config.TellusServerConfig;
import com.yucareux.tellus.integration.distant_horizons.DistantHorizonsIntegration;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainDownloadManager;
import com.yucareux.tellus.integration.voxy.TellusVoxyPregenManager;
import com.yucareux.tellus.compat.MinecraftRelease;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.compat.TellusMinecraftCompat;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.network.TellusClientHelloPayload;
import com.yucareux.tellus.network.TellusProtocol;
import com.yucareux.tellus.network.TellusServerHelloPayload;
import com.yucareux.tellus.network.TellusTeleportPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.platform.TellusRuntimePlatform;
import com.yucareux.tellus.server.PlayerActionCooldown;
import com.yucareux.tellus.world.realtime.TellusRealtimeManager;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.world.realtime.WeatherTemperaturePolicy;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.ExperimentalHeightSupport;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TellusCommon {

   public static final String MOD_ID = "tellus";
   private static final String DYNAMIC_DIMENSION_PACK_NAME = "tellus_dynamic_dimension";
   private static final String DYNAMIC_DIMENSION_PACK_ID = "file/tellus_dynamic_dimension";

   private static final ResourceKey<DimensionType> EARTH_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, MinecraftRelease.resourceLocation("tellus", "earth")), "earthDimensionKey"
   );

   private static final ResourceKey<DimensionType> DYNAMIC_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, MinecraftRelease.resourceLocation("tellus", "earth_dynamic")), "dynamicDimensionKey"
   );
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final TellusRealtimeManager REALTIME_MANAGER = new TellusRealtimeManager();
   private static final TellusVoxyPregenManager VOXY_PREGEN_MANAGER = new TellusVoxyPregenManager();
   private static final ManagedTerrainDownloadManager MANAGED_TERRAIN_DOWNLOAD_MANAGER = new ManagedTerrainDownloadManager();
   public static final Logger LOGGER = LoggerFactory.getLogger("tellus");

   /** 传送请求的每玩家冷却器；冷却时长每次调用时从 TellusServerConfig 实时读取，因此命令热改立刻生效 */
   private static final PlayerActionCooldown GEO_TELEPORT_COOLDOWN = new PlayerActionCooldown(System::currentTimeMillis);
   /** 地形渲染距离上报的每玩家冷却器；同上，冷却时长实时从配置读取 */
   private static final PlayerActionCooldown TERRAIN_VIEW_COOLDOWN = new PlayerActionCooldown(System::currentTimeMillis);
   /** 客户端握手包的每玩家冷却器；握手包同样是 C2S 包，需要防止改包客户端按包率刷它 */
   private static final PlayerActionCooldown CLIENT_HELLO_COOLDOWN = new PlayerActionCooldown(System::currentTimeMillis);
   /**
    * 客户端握手包的每玩家冷却时长，单位：毫秒。
    *
    * 说明：这不是给玩家调的游戏手感参数，而是防滥用的下限，所以不做成配置项。
    *       正常客户端每次连接只会发一次握手包，500 毫秒的冷却对正常流程没有任何影响。
    */
   private static final long CLIENT_HELLO_COOLDOWN_MS = 500L;
   /**
    * 等待下发服务端握手包的玩家 → 重试截止时间戳（单位：毫秒）。
    *
    * 说明：Fabric 客户端在收到 GameJoin 包之前无法向服务端声明自己注册了哪些通道，
    *       而服务端的玩家加入事件与它几乎同时发生，因此不能在加入的那一 tick 就假设通道已就绪。
    *       这里先登记，再由每 tick 的派发逻辑重试，直到发出或超时。
    */
   private static final Map<UUID, Long> PENDING_SERVER_HELLO = new ConcurrentHashMap<>();
   /** 握手包的重试窗口，单位：毫秒；超过这个时间仍未发出就放弃，避免对不兼容客户端无限重试 */
   private static final long SERVER_HELLO_RETRY_WINDOW_MS = 10_000L;


   public static void validateRuntime() {
      ExperimentalHeightSupport.validateActiveRuntimeProfileOrThrow();
   }

   public static void initializeRuntime(TellusRuntimePlatform runtime) {
      runtime.registerCommands(
            dispatcher -> dispatcher.register(
               ((Commands.literal("tellus")
                        .then(
                           (Commands.literal("map")
                                 .requires(TellusCommon::canOpenGeoTpMap))
                              .executes(context -> openGeoTpMap((CommandSourceStack)context.getSource()))
                        ))
                     .then(
                        (Commands.literal("weather")
                              .executes(context -> showTellusWeather((CommandSourceStack)context.getSource())))
                           .then(
                              Commands.literal("enable_realtime_time")
                                 .requires(TellusMinecraftCompat::hasGamemasterPermission)
                                 .then(
                                    Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                       .executes(
                                          context -> setRealtimeTimeOverride(
                                             (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                          )
                                       )
                                 )
                           )
                           .then(
                              Commands.literal("enable_realtime_weather")
                                 .requires(TellusMinecraftCompat::hasGamemasterPermission)
                                 .then(
                                    Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                       .executes(
                                          context -> setRealtimeWeatherOverride(
                                             (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                          )
                                       )
                                 )
                           )
                     ))
                  .then(
                     ((Commands.literal("config")
                              .requires(TellusMinecraftCompat::hasGamemasterPermission))
                           .then(
                              (Commands.literal("weather")
                                    .then(
                                       Commands.literal("enable_realtime_time")
                                          .then(
                                             Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                                .executes(
                                                   context -> setRealtimeTimeOverride(
                                                      (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                   )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("enable_realtime_weather")
                                       .then(
                                          Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                             .executes(
                                                context -> setRealtimeWeatherOverride(
                                                   (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                )
                                             )
                                       )
                                 )
                           ))
                           .then(
                              // 传送策略设置子树：/tellus config geotp policy <档位>
                              Commands.literal("geotp")
                                 .then(
                                    // policy 子节点，下面三个字面量分别是三档策略
                                    Commands.literal("policy")
                                       .then(
                                          // 档位一：仅管理员可传送（默认档）
                                          Commands.literal("op_only")
                                             .executes(
                                                context -> setGeoTpPolicy(
                                                   (CommandSourceStack)context.getSource(), TellusTeleportPolicy.OP_ONLY
                                                )
                                             )
                                       )
                                       .then(
                                          // 档位二：所有玩家都可传送
                                          Commands.literal("everyone")
                                             .executes(
                                                context -> setGeoTpPolicy(
                                                   (CommandSourceStack)context.getSource(), TellusTeleportPolicy.EVERYONE
                                                )
                                             )
                                       )
                                       .then(
                                          // 档位三：完全关闭传送，所有人都不能传送
                                          Commands.literal("disabled")
                                             .executes(
                                                context -> setGeoTpPolicy(
                                                   (CommandSourceStack)context.getSource(), TellusTeleportPolicy.DISABLED
                                                )
                                             )
                                       )
                                 )
                           )
                        .then(
                           ((((Commands.literal("voxy")
                                          .then(Commands.literal("status").executes(context -> showVoxyPregenStatus((CommandSourceStack)context.getSource()))))
                                       .then(
                                          Commands.literal("enable_pregen")
                                             .then(
                                                Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                                   .executes(
                                                      context -> setVoxyPregenEnabledOverride(
                                                         (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                      )
                                                   )
                                             )
                                       ))
                                    .then(
                                       Commands.literal("max_radius")
                                          .then(
                                             Commands.argument("chunks", Objects.requireNonNull(IntegerArgumentType.integer(0, 1024), "maxRadiusArgument"))
                                                .executes(
                                                   context -> setVoxyPregenMaxRadiusOverride(
                                                      (CommandSourceStack)context.getSource(), IntegerArgumentType.getInteger(context, "chunks")
                                                   )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("chunks_per_tick")
                                       .then(
                                          Commands.argument("value", Objects.requireNonNull(IntegerArgumentType.integer(1, 200), "chunksPerTickArgument"))
                                             .executes(
                                                context -> setVoxyPregenChunksPerTickOverride(
                                                   (CommandSourceStack)context.getSource(), IntegerArgumentType.getInteger(context, "value")
                                                )
                                             )
                                       )
                                 ))
                              .then(Commands.literal("reset").executes(context -> resetVoxyPregenOverrides((CommandSourceStack)context.getSource())))
                        )
                  )
            )
         );
      runtime.onServerStarted(server -> server.execute(() -> {
         ServerLevel world = server.getLevel(Level.OVERWORLD);
         if (world != null) {
            ChunkGenerator generator = world.getChunkSource().getGenerator();
            logOverworldSettings(server, world, generator);
            if (generator instanceof EarthChunkGenerator earthGenerator) {
               ExperimentalHeightSupport.configureWorldBorder(earthGenerator.settings(), world.getWorldBorder());
               TellusMinecraftCompat.configureInitialSpawn(world, earthGenerator);
               ensureDynamicDimensionPack(server, world.dimensionTypeRegistration(), world.dimensionType(), earthGenerator);
            }
         }
      }));
      runtime.onServerStopping(server -> {
         REALTIME_MANAGER.onServerStopping(server);
         VOXY_PREGEN_MANAGER.shutdown();
         MANAGED_TERRAIN_DOWNLOAD_MANAGER.reset();
         // 清空联机相关的每玩家状态，避免服务器重载后残留上一局的冷却记录与待发握手
         GEO_TELEPORT_COOLDOWN.clear();
         TERRAIN_VIEW_COOLDOWN.clear();
         CLIENT_HELLO_COOLDOWN.clear();
         PENDING_SERVER_HELLO.clear();
      });
      // 每 tick 尝试把还没发出的服务端握手包补发出去（客户端声明通道存在时序竞争，见 PENDING_SERVER_HELLO 的说明）
      runtime.onServerTick(TellusCommon::dispatchPendingServerHellos);
      runtime.onServerTick(REALTIME_MANAGER::onServerTick);
      runtime.onServerTick(VOXY_PREGEN_MANAGER::onServerTick);
      runtime.onServerTick(MANAGED_TERRAIN_DOWNLOAD_MANAGER::onServerTick);
      runtime.onServerTick(server -> {
         if (MANAGED_TERRAIN_DOWNLOAD_MANAGER.shouldBroadcastStatus()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               MANAGED_TERRAIN_DOWNLOAD_MANAGER.statusFor(player)
                  .ifPresent(status -> TellusPlatform.sendManagedTerrainStatusPayload(player, new ManagedTerrainStatusPayload(status)));
            }
         }
      });
      runtime.onServerTick(server -> {
         for (ServerLevel level : server.getAllLevels()) {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (generator instanceof EarthChunkGenerator earthGenerator) {
               earthGenerator.processDeferredChunkDetailTick(level);
            }
         }
      });
      runtime.onChunkUnload((level, chunkPos) -> {
         ChunkGenerator generator = level.getChunkSource().getGenerator();
         if (generator instanceof EarthChunkGenerator earthGenerator) {
            earthGenerator.discardPreparedChunkState(chunkPos);
         }
      });
      runtime.onPlayerJoin(REALTIME_MANAGER::onPlayerJoin);
      // 玩家一进游戏就登记「待发服务端握手包」：此刻客户端的通道声明可能还没到，交给每 tick 的重试逻辑
      runtime.onPlayerJoin((server, player) -> PENDING_SERVER_HELLO.put(
         player.getUUID(), System.currentTimeMillis() + SERVER_HELLO_RETRY_WINDOW_MS
      ));
      runtime.onPlayerDisconnect(MANAGED_TERRAIN_DOWNLOAD_MANAGER::onPlayerDisconnect);
      // 玩家离线时清掉本局的冷却记录与待发握手，避免内存里残留无用条目
      runtime.onPlayerDisconnect(player -> {
         GEO_TELEPORT_COOLDOWN.release(player.getUUID());
         TERRAIN_VIEW_COOLDOWN.release(player.getUUID());
         CLIENT_HELLO_COOLDOWN.release(player.getUUID());
         PENDING_SERVER_HELLO.remove(player.getUUID());
      });
      if (TellusPlatform.isModLoaded("distanthorizons")) {
         DistantHorizonsIntegration.bootstrap();
      }

      LOGGER.info(
         "Tellus worldgen initialized{}",
         ExperimentalHeightSupport.isRuntimeProfileActive() ? " with the dense global packed-coordinate profile" : ""
      );
   }

   private static int openGeoTpMap(CommandSourceStack source) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.translatable("tellus.command.geotp.player_only"));
         return 0;
      } else {
         ServerLevel level = MinecraftVersionCompat.serverLevel(player);
         if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
            double latitude = clampLatitude(earthGenerator.latitudeFromBlock(player.getZ()));
            double longitude = clampLongitude(earthGenerator.longitudeFromBlock(player.getX()));
            TellusPlatform.sendGeoTpOpenMapPayload(player, new GeoTpOpenMapPayload(latitude, longitude));
            return 1;
         } else {
            source.sendFailure(Component.translatable("tellus.command.geotp.tellus_world_only"));
            return 0;
         }
      }
   }

   private static int showTellusWeather(CommandSourceStack source) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.translatable("tellus.command.weather.player_only"));
         return 0;
      }

      ServerLevel level = MinecraftVersionCompat.serverLevel(player);
      if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
         source.sendFailure(Component.translatable("tellus.command.weather.tellus_world_only"));
         return 0;
      }

      BlockPos pos = player.blockPosition();
      source.sendSuccess(() -> Component.translatable("tellus.command.weather.fetching").withStyle(ChatFormatting.GRAY), false);
      TellusRealtimeManager.WeatherReportRequestResult requestResult = REALTIME_MANAGER.requestWeatherReport(
         source.getServer(),
         player.getUUID(),
         earthGenerator,
         pos,
         report -> sendTellusWeatherReport(source, level, pos, earthGenerator.settings(), report)
      );
      if (requestResult == TellusRealtimeManager.WeatherReportRequestResult.RATE_LIMITED) {
         source.sendFailure(Component.translatable("tellus.command.weather.rate_limited"));
         return 0;
      }
      if (requestResult == TellusRealtimeManager.WeatherReportRequestResult.UNAVAILABLE) {
         source.sendFailure(Component.translatable("tellus.command.weather.unavailable"));
         return 0;
      }

      return 1;
   }

   private static void sendTellusWeatherReport(
      CommandSourceStack source,
      ServerLevel level,
      BlockPos pos,
      EarthGeneratorSettings settings,
      TellusRealtimeManager.WeatherReport report
   ) {
      boolean realtimeTime = REALTIME_MANAGER.isRealtimeTimeEnabled(settings);
      boolean realtimeWeather = REALTIME_MANAGER.isRealtimeWeatherEnabled(settings);
      boolean realtimeWeatherActive = realtimeWeather && TellusRealtimeState.isWeatherEnabled();
      TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.precipitationMode();
      TellusCommon.WeatherDisplay weather = realtimeWeatherActive
         ? weatherFromRealtime(mode)
         : weatherFromVanilla(level, pos, report.temperatureC());
      source.sendSuccess(
         () -> Component.translatable("tellus.command.weather.title").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false
      );
      String coordinates = Objects.requireNonNull(
         String.format(Locale.ROOT, "%.4f, %.4f", report.latitude(), report.longitude()), "coordinates"
      );
      String locationText = report.locationName() == null || report.locationName().isBlank()
         ? coordinates
         : report.locationName() + " (" + coordinates + ")";
      source.sendSuccess(
         () -> Component.translatable("tellus.command.weather.location")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(locationText).withStyle(ChatFormatting.AQUA)),
         false
      );
      String gameTime = Objects.requireNonNull(formatGameTime(level), "gameTime");
      source.sendSuccess(
         () -> Component.translatable("tellus.command.weather.game_time")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(gameTime).withStyle(ChatFormatting.YELLOW)),
         false
      );
      ZoneId zone = resolveZoneId(report.timeZoneId());
      boolean approximateTime = zone == null;
      if (zone == null && realtimeTime && REALTIME_MANAGER.hasTimeOffset()) {
         ZoneId managerZone = REALTIME_MANAGER.currentTimeZone();
         if (managerZone != null) {
            zone = managerZone;
            approximateTime = false;
         }
      }

      if (zone == null) {
         int offsetSeconds = approximateUtcOffsetSeconds(report.longitude());
         zone = ZoneOffset.ofTotalSeconds(offsetSeconds);
         approximateTime = true;
      }

      Instant now = REALTIME_MANAGER.currentInstant();
      int offsetSeconds = zone.getRules().getOffset(now).getTotalSeconds();
      String timeLabel = formatLocalTime(now, zone);
      String utcOffsetLabel = formatUtcOffset(offsetSeconds);
      MutableComponent timeLine = Component.translatable("tellus.command.weather.real_time")
         .withStyle(ChatFormatting.GRAY)
         .append(Component.literal(timeLabel + " " + utcOffsetLabel).withStyle(ChatFormatting.YELLOW));
      if (approximateTime) {
         timeLine.append(Component.translatable("tellus.command.weather.approximate").withStyle(ChatFormatting.DARK_GRAY));
      }

      source.sendSuccess(() -> timeLine, false);
      MutableComponent tempLine = Component.translatable("tellus.command.weather.temperature").withStyle(ChatFormatting.GRAY);
      if (Float.isFinite(report.temperatureC())) {
         String tempLabel = Objects.requireNonNull(String.format(Locale.ROOT, "%.1f C", report.temperatureC()), "tempLabel");
         tempLine.append(Component.literal(tempLabel).withStyle(ChatFormatting.YELLOW));
      } else {
         tempLine.append(Component.translatable("tellus.command.weather.not_available").withStyle(ChatFormatting.DARK_GRAY));
      }

      source.sendSuccess(() -> tempLine, false);
      ChatFormatting weatherColor = Objects.requireNonNull(weather.color(), "weatherColor");
      MutableComponent weatherLine = Component.translatable("tellus.command.weather.weather")
         .withStyle(ChatFormatting.GRAY)
         .append(Component.translatable(weather.translationKey()).withStyle(weatherColor));
      if (!realtimeWeather) {
         String weatherSourceKey = Float.isFinite(report.temperatureC())
            ? "tellus.command.weather.source.vanilla_temperature"
            : "tellus.command.weather.source.vanilla_fallback";
         weatherLine.append(Component.translatable(weatherSourceKey).withStyle(ChatFormatting.DARK_GRAY));
      } else if (!realtimeWeatherActive) {
         weatherLine.append(Component.translatable("tellus.command.weather.source.realtime_pending").withStyle(ChatFormatting.DARK_GRAY));
      }

      source.sendSuccess(() -> weatherLine, false);
   }

   private static int setRealtimeTimeOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.weather_world_only"));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeTimeOverride(enabled);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.config.time_set", booleanLabel(enabled)), false
         );
         return 1;
      }
   }

   private static int setRealtimeWeatherOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.weather_world_only"));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeWeatherOverride(enabled);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.config.weather_set", booleanLabel(enabled)), false
         );
         return 1;
      }
   }

   private static int setVoxyPregenEnabledOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setEnabledOverride(enabled);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.config.voxy_enabled_set", booleanLabel(enabled)), false
         );
         return 1;
      }
   }

   private static int setVoxyPregenMaxRadiusOverride(CommandSourceStack source, int chunks) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setMaxRadiusOverride(chunks);
         source.sendSuccess(() -> Component.translatable("tellus.command.config.voxy_radius_set", chunks), false);
         return 1;
      }
   }

   private static int setVoxyPregenChunksPerTickOverride(CommandSourceStack source, int chunksPerTick) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setChunksPerTickOverride(chunksPerTick);
         source.sendSuccess(() -> Component.translatable("tellus.command.config.voxy_budget_set", chunksPerTick), false);
         return 1;
      }
   }

   private static int resetVoxyPregenOverrides(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.clearOverrides();
         source.sendSuccess(() -> Component.translatable("tellus.command.config.voxy_reset"), false);
         return 1;
      }
   }

   private static int showVoxyPregenStatus(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         EarthGeneratorSettings settings = earthGenerator.settings();
         boolean enabled = VOXY_PREGEN_MANAGER.effectiveEnabled(settings);
         int maxRadius = VOXY_PREGEN_MANAGER.effectiveMaxRadius(settings);
         int chunksPerTick = VOXY_PREGEN_MANAGER.effectiveChunksPerTick(settings);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.voxy.title").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.enabled",
                  booleanLabel(enabled),
                  booleanLabel(settings.voxyChunkPregenEnabled()),
                  overrideLabel(VOXY_PREGEN_MANAGER.enabledOverride())
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.radius",
                  maxRadius,
                  settings.voxyChunkPregenMaxRadius(),
                  overrideLabel(VOXY_PREGEN_MANAGER.maxRadiusOverride())
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.budget",
                  chunksPerTick,
                  settings.voxyChunkPregenChunksPerTick(),
                  overrideLabel(VOXY_PREGEN_MANAGER.chunksPerTickOverride())
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.effective_radius",
                  VOXY_PREGEN_MANAGER.lastConfiguredVoxyRadiusChunks(),
                  VOXY_PREGEN_MANAGER.lastEffectiveRadiusChunks()
               )
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.queue", VOXY_PREGEN_MANAGER.queuedChunkCount(), VOXY_PREGEN_MANAGER.inFlightChunkCount()
               )
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         return 1;
      }
   }

   private static TellusCommon.WeatherDisplay weatherFromRealtime(TellusRealtimeState.PrecipitationMode mode) {
      return switch (mode) {
         case THUNDER -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.thunder", ChatFormatting.DARK_PURPLE);
         case SNOW -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.snow", ChatFormatting.AQUA);
         case RAIN -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.rain", ChatFormatting.BLUE);
         case CLEAR -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.clear", ChatFormatting.GREEN);
      };
   }

   private static TellusCommon.WeatherDisplay weatherFromVanilla(ServerLevel level, BlockPos pos, float temperatureC) {
      if (!level.isRaining()) {
         return new TellusCommon.WeatherDisplay("tellus.command.weather.value.clear", ChatFormatting.GREEN);
      }

      Biome biome = (Biome)level.getBiome(pos).value();
      if (!biome.hasPrecipitation()) {
         return new TellusCommon.WeatherDisplay("tellus.command.weather.value.clear", ChatFormatting.GREEN);
      }

      boolean snow = Float.isFinite(temperatureC)
         ? WeatherTemperaturePolicy.shouldSnow(temperatureC)
         : TellusMinecraftCompat.vanillaPrecipitationIsSnow(biome, pos, level);
      if (snow) {
         return new TellusCommon.WeatherDisplay("tellus.command.weather.value.snow", ChatFormatting.AQUA);
      }

      return level.isThundering()
         ? new TellusCommon.WeatherDisplay("tellus.command.weather.value.thunder", ChatFormatting.DARK_PURPLE)
         : new TellusCommon.WeatherDisplay("tellus.command.weather.value.rain", ChatFormatting.BLUE);
   }

   private static Component booleanLabel(boolean value) {
      return Component.translatable(value ? "options.on" : "options.off");
   }

   private static Component overrideLabel(Object value) {
      if (value == null) {
         return Component.translatable("tellus.value.not_set");
      }
      return value instanceof Boolean booleanValue ? booleanLabel(booleanValue) : Component.literal(value.toString());
   }

   private static ZoneId resolveZoneId(String zoneId) {
      if (zoneId != null && !zoneId.isBlank()) {
         try {
            return ZoneId.of(zoneId);
         } catch (Exception var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static int approximateUtcOffsetSeconds(double longitude) {
      double hours = longitude / 15.0;
      return (int)Math.round(hours * 3600.0);
   }

   private static String formatLocalTime(Instant instant, ZoneId zone) {
      int daySeconds = instant.atZone(zone).toLocalTime().toSecondOfDay();
      int hour = daySeconds / 3600;
      int minute = daySeconds % 3600 / 60;
      return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
   }

   private static String formatGameTime(ServerLevel level) {
      long timeOfDay = TellusMinecraftCompat.dayTime(level);
      int totalMinutes = (int)Math.floor(timeOfDay * 60.0 / 1000.0);
      int hour = (totalMinutes / 60 + 6) % 24;
      int minute = totalMinutes % 60;
      return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
   }

   private static String formatUtcOffset(int offsetSeconds) {
      int totalMinutes = offsetSeconds / 60;
      int hours = totalMinutes / 60;
      int minutes = Math.abs(totalMinutes % 60);
      return String.format(Locale.ROOT, "UTC%+03d:%02d", hours, minutes);
   }

   /**
    * 处理服务端收到的经纬度传送请求。
    *
    * 输入：客户端发来的传送包与发起请求的玩家。
    * 输出：无返回值；满足条件时把玩家传送到目标地表坐标。
    * 边界条件：先做频率限制，再做权限 / 策略校验；两者任一不通过都只发一条提示并放弃。
    *          顺序刻意是「先限流后鉴权」，这样未授权玩家刷包时不会反复触发权限判断。
    */
   public static void handleGeoTeleport(GeoTpTeleportPayload payload, ServerPlayer player) {
      // 喵~防御：payload 或玩家为空说明调用链出了问题，直接返回而不是继续解引用
      if (payload == null || player == null) {
         return;
      }
      // 坐标必须是有限值，否则下面的地表换算会产生非法坐标
      if (Double.isFinite(payload.latitude()) && Double.isFinite(payload.longitude())) {
         MinecraftServer server = MinecraftVersionCompat.serverLevel(player).getServer();
         if (server == null) {
            return;
         }

         server.execute(() -> {
            // 频率限制：冷却期内直接拒绝并提示，防止改包客户端反复刷传送请求
            if (!GEO_TELEPORT_COOLDOWN.tryAcquire(player.getUUID(), TellusServerConfig.teleportCooldownMs())) {
               player.sendSystemMessage(Component.translatable("tellus.geotp.rate_limited"));
               return;
            }
            // 策略为「完全禁用」时连 OP 也不放行，用专门的提示语说明是服主关掉了功能
            if (TellusServerConfig.teleportPolicy() == TellusTeleportPolicy.DISABLED) {
               player.sendSystemMessage(Component.translatable("tellus.geotp.disabled"));
               return;
            }
            // 策略为「仅 OP」时校验权限；「所有人可用」会在 canUseGeoTeleport 内直接放行
            if (!canUseGeoTeleport(player)) {
               player.sendSystemMessage(Component.translatable("tellus.command.geotp.no_permission"));
               return;
            }

            ServerLevel level = MinecraftVersionCompat.serverLevel(player);
            if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
               double latitude = clampLatitude(payload.latitude());
               double longitude = clampLongitude(payload.longitude());
               BlockPos target = earthGenerator.getSurfacePosition(level, latitude, longitude);
               player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            } else {
               player.sendSystemMessage(Component.translatable("tellus.command.geotp.tellus_world_only"));
            }
         });
      }
   }

   /**
    * 处理客户端上报的地形渲染距离。
    *
    * 输入：客户端发来的距离包与上报的玩家。
    * 输出：无返回值；通过校验后把请求交给下载管理器（管理器自身会把距离夹取到 32..4096）。
    * 边界条件：冷却期内静默丢弃，**不发提示**——这是每 2 秒一次的高频包，
    *          若每次都回一条消息，反而会放大恶意刷包的伤害。
    */
   public static void handleManagedTerrainView(ManagedTerrainViewPayload payload, ServerPlayer player) {
      // 喵~防御：payload 或玩家为空时直接返回
      if (payload == null || player == null) {
         return;
      }
      // 频率限制：冷却期内静默丢弃，不做任何回包
      if (!TERRAIN_VIEW_COOLDOWN.tryAcquire(player.getUUID(), TellusServerConfig.terrainViewCooldownMs())) {
         return;
      }
      MANAGED_TERRAIN_DOWNLOAD_MANAGER.updateViewDistance(player, payload.renderRadiusChunks());
   }

   /**
    * 处理客户端握手包。
    *
    * 输入：客户端上报的协议版本与模组版本，以及发起握手的玩家。
    * 输出：无返回值。协议不匹配时把该玩家踢下线；匹配时立刻回发服务端握手包。
    * 边界条件：客户端在发出握手包时，它自己的通道声明必然已经完成（否则它发不出来），
    *          因此这里回发通常一次就能成功；万一失败仍会保留待发登记，交给每 tick 的补发逻辑继续重试。
    *          冷却窗口内的重复包直接丢弃，防止改包客户端按包率刷它。
    */
   public static void handleClientHello(TellusClientHelloPayload payload, ServerPlayer player) {
      // 喵~防御：空值直接返回
      if (payload == null || player == null) {
         return;
      }
      // 喵~防御：握手包是本模组为数不多的 C2S 包，改包客户端可以按包率刷它；
      //          这里先做一次每玩家冷却，避免每个包都在主线程重新组装一次服务端握手包
      if (!CLIENT_HELLO_COOLDOWN.tryAcquire(player.getUUID(), CLIENT_HELLO_COOLDOWN_MS)) {
         return;
      }
      // 取服务端实例，拿不到说明玩家所在世界还没绑定服务器
      MinecraftServer server = MinecraftVersionCompat.serverLevel(player).getServer();
      if (server == null) {
         return;
      }
      // 切回主线程执行，避免在网络线程里操作世界与玩家状态
      server.execute(() -> {
         // 协议不兼容：按主人的要求直接踢出，并在断开原因里写清双方版本，方便玩家自行核对
         if (!TellusProtocol.isCompatible(payload.protocolVersion())) {
            TellusMinecraftCompat.disconnectPlayer(
               player,
               Component.translatable(
                  "tellus.multiplayer.protocol_mismatch",
                  Integer.toString(payload.protocolVersion()),
                  Integer.toString(TellusProtocol.PROTOCOL_VERSION)
               )
            );
            // 已被踢下线，清掉待发登记，避免重试逻辑继续对着一个已断开的连接发包
            PENDING_SERVER_HELLO.remove(player.getUUID());
            return;
         }
         // 协议匹配：立刻回发服务端握手包
         if (TellusPlatform.sendServerHelloPayload(player, buildServerHelloPayload(player))) {
            // 发送成功，清掉待发登记，不用再补发
            PENDING_SERVER_HELLO.remove(player.getUUID());
         }
         // 喵~防御：发送失败时**保留**登记，交给每 tick 的补发逻辑在重试窗口内继续尝试；
         //          这里若顺手删掉登记，就等于把 pending 机制存在的意义（容忍发送失败）直接抵消了
      });
   }

   /**
    * 判断某个玩家本人当前是否有权使用传送。
    *
    * 输入：目标玩家。
    * 输出：有权返回 true，否则 false。
    * 边界条件：玩家为 null、策略为 DISABLED、或策略为 OP_ONLY 而该玩家不是 OP，都返回 false。
    *
    * 说明：本方法同时被传送校验与握手包使用，保证「服务端实际放行」与「下发给客户端的提示」
    *       永远基于同一套判定，不会出现「按钮亮着但服务端拒绝」这类逻辑漂移。
    */
   public static boolean canUseGeoTeleport(ServerPlayer player) {
      // 喵~防御：空玩家无从判断权限
      if (player == null) {
         return false;
      }
      // 读一次当前策略，避免多次读取期间配置被改导致判定不一致
      TellusTeleportPolicy policy = TellusServerConfig.teleportPolicy();
      // 完全禁用时任何人都不能传送
      if (policy == TellusTeleportPolicy.DISABLED) {
         return false;
      }
      // 所有人可用时无需再查权限
      if (policy == TellusTeleportPolicy.EVERYONE) {
         return true;
      }
      // 默认档位：仅 OP
      return TellusMinecraftCompat.hasGamemasterPermission(player.createCommandSourceStack());
   }

   /**
    * 判断某个命令来源是否可以打开传送地图界面。
    *
    * 输入：命令来源（可能是玩家，也可能是命令方块 / 控制台）。
    * 输出：允许打开返回 true，否则 false。
    * 边界条件：来源为 null、策略为 DISABLED、或策略为 OP_ONLY 而来源没有权限，都返回 false。
    *
    * 说明：命令来源未必是玩家，所以这里不能复用 canUseGeoTeleport（它要求 ServerPlayer）。
    */
   public static boolean canOpenGeoTpMap(CommandSourceStack source) {
      // 喵~防御：空来源直接拒绝
      if (source == null) {
         return false;
      }
      // 读一次当前策略
      TellusTeleportPolicy policy = TellusServerConfig.teleportPolicy();
      // 完全禁用时谁都不能开地图
      if (policy == TellusTeleportPolicy.DISABLED) {
         return false;
      }
      // 所有人可用时无需再查权限
      if (policy == TellusTeleportPolicy.EVERYONE) {
         return true;
      }
      // 默认档位：仅 OP
      return TellusMinecraftCompat.hasGamemasterPermission(source);
   }

   /**
    * 设置传送策略并落盘。
    *
    * 输入：命令来源与新的策略。
    * 输出：命令返回值（恒为 1，表示执行成功）。
    * 边界条件：策略为 null 时回退到默认档位，由 TellusServerConfig 内部保证。
    *
    * 主人注意：本命令的注册刻意**只要求 OP、且不读取传送策略**，
    *          否则把策略设成 DISABLED 之后连 OP 都无法再改回来，会形成自锁。
    */
   private static int setGeoTpPolicy(CommandSourceStack source, TellusTeleportPolicy policy) {
      // 写入配置并落盘，下一次传送校验立刻生效
      TellusServerConfig.setTeleportPolicy(policy);
      // 策略变了要立刻同步给在线玩家，否则客户端上的传送按钮要重连才会更新
      broadcastServerHello(source);
      // 把生效后的策略名回显给执行者，方便确认
      source.sendSuccess(
         () -> Component.translatable(
            "tellus.command.config.geotp_policy_set", TellusServerConfig.teleportPolicy().configName()
         ),
         true
      );
      // 返回 1 表示命令执行成功
      return 1;
   }

   /**
    * 把最新的服务端握手包重新下发给服务器上的所有在线玩家。
    *
    * 输入：命令来源，用来取当前服务器实例。
    * 输出：无返回值。
    * 边界条件：取不到服务器实例时静默跳过；对端没声明通道时平台层返回 false，这里直接忽略。
    *
    * 说明：服务端握手包原本只在玩家进服时下发一次，所以像「传送策略热改」这类运行期变化
    *       必须主动重发，否则客户端的按钮状态会一直停留在进服那一刻，与「改完立刻生效」不符。
    */
   private static void broadcastServerHello(CommandSourceStack source) {
      // 取当前服务器实例；取不到说明这条命令不在服务器上下文里执行
      MinecraftServer server = source == null ? null : source.getServer();
      // 喵~防御：没有服务器实例时什么都不做，避免空指针
      if (server == null) {
         return;
      }
      // 复制一份玩家列表再遍历，避免循环途中玩家离线改动底层集合
      for (ServerPlayer onlinePlayer : List.copyOf(server.getPlayerList().getPlayers())) {
         // 逐个重发；发不出去（对端没装模组）时平台层返回 false，这里无需特殊处理
         TellusPlatform.sendServerHelloPayload(onlinePlayer, buildServerHelloPayload(onlinePlayer));
      }
   }

   /**
    * 把还没发出的服务端握手包补发出去。
    *
    * 输入：当前服务器实例。
    * 输出：无返回值。
    * 边界条件：玩家已离线时直接清掉登记；超过重试窗口仍发不出去（对端不认这个通道）时放弃并记一条 debug 日志。
    *
    * 说明：Fabric 客户端的通道声明包与「玩家加入」事件几乎同时发生，
    *       因此不能在加入那一刻就假设通道就绪，必须靠每 tick 重试。
    *       这里遍历的是快照，避免在迭代过程中修改 Map 触发并发问题。
    */
   private static void dispatchPendingServerHellos(MinecraftServer server) {
      // 没有待发任务时立刻返回，避免每个 tick 都做无谓的遍历
      if (PENDING_SERVER_HELLO.isEmpty() || server == null) {
         return;
      }
      // 取当前时间戳，单位：毫秒，用于判断是否超过重试窗口
      long nowMs = System.currentTimeMillis();
      // 复制一份条目快照再遍历，允许在循环内安全地移除条目
      for (Map.Entry<UUID, Long> entry : List.copyOf(PENDING_SERVER_HELLO.entrySet())) {
         // 待发玩家身份
         UUID playerId = entry.getKey();
         // 该玩家的重试截止时间戳，单位：毫秒
         long deadlineMs = entry.getValue();
         // 按 UUID 取出在线玩家对象；已离线时为 null
         ServerPlayer player = server.getPlayerList().getPlayer(playerId);
         // 喵~防御：玩家已经离线，清掉登记即可
         if (player == null) {
            PENDING_SERVER_HELLO.remove(playerId);
            continue;
         }
         // 主人注意：先判超时再组装握手包。组装要查世界生成器、算权限、查模组版本，
         //          若放在前面的实参位置，对原版客户端会白白连续算满整个重试窗口（约 200 tick）。
         if (nowMs > deadlineMs) {
            // 超过重试窗口仍未成功，说明对端根本不认识这个通道（未装模组或版本过旧），放弃并记录
            PENDING_SERVER_HELLO.remove(playerId);
            LOGGER.debug(
               "Gave up sending the TellusCN handshake to {} (the client never declared the channel)",
               player.getName().getString()
            );
            continue;
         }
         // 尝试下发；成功（对端已声明通道）就移除登记，不再重试
         if (TellusPlatform.sendServerHelloPayload(player, buildServerHelloPayload(player))) {
            PENDING_SERVER_HELLO.remove(playerId);
         }
      }
   }

   /**
    * 组装发给某个玩家的服务端握手包。
    *
    * 输入：目标玩家。
    * 输出：填好协议版本、模组版本、传送策略、该玩家本人能否传送、世界缩放等信息的握手包。
    * 边界条件：玩家不在 Tellus 地球世界时，世界缩放与实验高度退回默认值，其余字段照常下发。
    */
   private static TellusServerHelloPayload buildServerHelloPayload(ServerPlayer player) {
      // 先尝试取该玩家所在世界的地球生成器设置；不是地球世界时为 null
      EarthGeneratorSettings settings = resolvePlayerEarthSettings(player);
      // 世界缩放：拿不到设置时退回官方默认值，保证客户端不会因为 0 而算崩
      double worldScale = settings == null ? EarthGeneratorSettings.DEFAULT.worldScale() : settings.worldScale();
      // 是否开启实验性提升高度：拿不到设置时按关闭处理
      boolean experimentalHeight = settings != null && settings.experimentalIncreaseHeight();
      // 逐个字段组装握手包
      return new TellusServerHelloPayload(
         TellusProtocol.PROTOCOL_VERSION,
         TellusPlatform.modVersion(),
         TellusServerConfig.teleportPolicy(),
         canUseGeoTeleport(player),
         worldScale,
         experimentalHeight,
         MirrorConfig.isEnabled()
      );
   }

   /**
    * 取某个玩家所在世界的地球生成器设置。
    *
    * 输入：目标玩家。
    * 输出：玩家所在世界的 EarthChunkGenerator 设置；不是地球世界或玩家为空时返回 null。
    * 边界条件：绝不抛异常，任何异常都按「不是地球世界」处理。
    */
   private static EarthGeneratorSettings resolvePlayerEarthSettings(ServerPlayer player) {
      // 喵~防御：空玩家直接返回 null
      if (player == null) {
         return null;
      }
      // 取玩家所在的服务端世界，失败时按「不是地球世界」处理
      try {
         ServerLevel level = MinecraftVersionCompat.serverLevel(player);
         return level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator
            ? earthGenerator.settings()
            : null;
      } catch (RuntimeException error) {
         return null;
      }
   }

   private static EarthChunkGenerator resolveEarthGenerator(CommandSourceStack source) {
      MinecraftServer server = source.getServer();
      ServerLevel level = server.getLevel(Level.OVERWORLD);
      if (level == null) {
         return null;
      } else {
         return level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator ? earthGenerator : null;
      }
   }

   private static double clampLatitude(double latitude) {
      return Mth.clamp(latitude, -85.05112878, 85.05112878);
   }

   private static double clampLongitude(double longitude) {
      return Mth.clamp(longitude, -180.0, 180.0);
   }

   private static void logOverworldSettings(MinecraftServer server, Level world, ChunkGenerator generator) {
      DimensionType worldType = world.dimensionType();
      LOGGER.info("Overworld dimension type: {}", describeDimensionType(worldType));
      LOGGER.info(
         "Overworld generator: type={}, minY={}, height={}", new Object[]{generator.getClass().getSimpleName(), generator.getMinY(), generator.getGenDepth()}
      );
      LevelStem stem = TellusMinecraftCompat.overworldStem(server);
      if (stem == null) {
         LOGGER.warn("Overworld level stem missing from registry");
      } else {
         DimensionType stemType = (DimensionType)stem.type().value();
         LOGGER.info("Overworld level stem: dimensionType={}, generatorType={}", describeDimensionType(stemType), stem.generator().getClass().getSimpleName());
      }
   }

   private static String describeDimensionType(DimensionType type) {
      return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
   }

   private static void ensureDynamicDimensionPack(
      MinecraftServer server, Holder<DimensionType> dimensionTypeHolder, DimensionType currentDimensionType, EarthChunkGenerator earthGenerator
   ) {
      ResourceKey<DimensionType> dimensionKey = resolveTellusDimensionKey(dimensionTypeHolder).orElse(null);
      if (dimensionKey != null) {
         EarthGeneratorSettings settings = earthGenerator.settings();
         EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
         TellusMinecraftCompat.validateDynamicHeight(settings, limits);
         DimensionType updatedType = EarthGeneratorSettings.applyHeightLimits(currentDimensionType, limits);
         DynamicOps<JsonElement> jsonOps = Objects.requireNonNull(JsonOps.INSTANCE, "jsonOps");
         RegistryOps<JsonElement> registryOps = RegistryOps.create(jsonOps, server.registryAccess());
         JsonElement dimensionJson = (JsonElement)DimensionType.DIRECT_CODEC
            .encodeStart(registryOps, updatedType)
            .resultOrPartial(message -> LOGGER.error("Failed to encode dynamic dimension type: {}", message))
            .orElse(null);
         if (dimensionJson != null) {
            Path packDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(DYNAMIC_DIMENSION_PACK_NAME);
            Path packMetaPath = packDir.resolve("pack.mcmeta");
            Path dimensionPath = packDir.resolve(
               "data/"
                  + TellusMinecraftCompat.dimensionNamespace(dimensionKey)
                  + "/dimension_type/"
                  + TellusMinecraftCompat.dimensionPath(dimensionKey)
                  + ".json"
            );

            try {
               Files.createDirectories(dimensionPath.getParent());
               writeJson(packMetaPath, createPackMeta());
               writeJson(dimensionPath, dimensionJson);
            } catch (IOException var16) {
               LOGGER.warn("Failed to persist dynamic dimension type pack", var16);
               return;
            }

            enableDynamicPack(server.getWorldData());
         }
      }
   }

   private static Optional<ResourceKey<DimensionType>> resolveTellusDimensionKey(Holder<DimensionType> dimensionTypeHolder) {
      return dimensionTypeHolder.unwrapKey().filter(TellusCommon::isTellusDimensionKey);
   }

   private static boolean isTellusDimensionKey(ResourceKey<DimensionType> key) {
      return key.equals(DYNAMIC_DIMENSION_KEY) || key.equals(EARTH_DIMENSION_KEY);
   }

   private static JsonObject createPackMeta() {
      JsonObject pack = new JsonObject();
      TellusMinecraftCompat.writePackFormat(pack);
      pack.addProperty("description", "Tellus dynamic dimension settings");
      JsonObject root = new JsonObject();
      root.add("pack", pack);
      return root;
   }

   private static void writeJson(Path path, JsonElement payload) throws IOException {
      try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
         GSON.toJson(payload, writer);
      }
   }

   private static void enableDynamicPack(WorldData worldData) {
      WorldDataConfiguration configuration = worldData.getDataConfiguration();
      DataPackConfig dataPacks = configuration.dataPacks();
      List<String> enabled = new ArrayList<>(dataPacks.getEnabled());
      List<String> disabled = new ArrayList<>(dataPacks.getDisabled());
      if (!enabled.contains(DYNAMIC_DIMENSION_PACK_ID)) {
         enabled.add(DYNAMIC_DIMENSION_PACK_ID);
      }

      disabled.remove(DYNAMIC_DIMENSION_PACK_ID);
      if (!enabled.equals(dataPacks.getEnabled()) || !disabled.equals(dataPacks.getDisabled())) {
         WorldDataConfiguration updated = new WorldDataConfiguration(new DataPackConfig(enabled, disabled), configuration.enabledFeatures());
         worldData.setDataConfiguration(updated);
      }
   }

   private record WeatherDisplay(String translationKey, ChatFormatting color) {
   }
}
