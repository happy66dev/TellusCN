/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.config;

import com.yucareux.tellus.network.TellusTeleportPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * TellusCN 服务端配置。
 *
 * 作用：把原本写死在 TellusCommon 里的「传送权限 = level 2」与「无任何频率限制」变成服主可配置项。
 *       配置文件位于 config/tellus-server.properties，与镜像是两个互不影响的文件。
 *
 * 配置项：
 *   1. geotp.policy           传送策略，取值 disabled / op_only / everyone，默认 op_only
 *   2. geotp.cooldown_ms      传送请求的每玩家冷却，单位：毫秒，默认 3000，0 表示关闭冷却
 *   3. terrain_view.cooldown_ms  地形渲染距离上报的每玩家冷却，单位：毫秒，默认 1000，0 表示关闭冷却
 *
 * 说明：目录来源使用 {@link TellusPlatform#configDir()} 而不是直接调 FabricLoader，
 *       这样 Forge / NeoForge 目标也能编译，并且支持用 -Dtellus.configDir 覆盖目录（便于测试）。
 */
public final class TellusServerConfig {

   /** 配置文件名，存放在 Fabric / Forge 的 config 目录下 */
   private static final String CONFIG_FILE = "tellus-server.properties";

   /** 配置项 Key：传送策略 */
   private static final String KEY_GEOTP_POLICY = "geotp.policy";
   /** 配置项 Key：传送请求冷却，单位毫秒 */
   private static final String KEY_GEOTP_COOLDOWN_MS = "geotp.cooldown_ms";
   /** 配置项 Key：地形渲染距离上报冷却，单位毫秒 */
   private static final String KEY_TERRAIN_VIEW_COOLDOWN_MS = "terrain_view.cooldown_ms";

   /** 默认传送策略：仅 OP，与 TellusCN 改造前的行为保持一致 */
   public static final TellusTeleportPolicy DEFAULT_GEOTP_POLICY = TellusTeleportPolicy.OP_ONLY;
   /** 默认传送冷却，单位：毫秒 */
   public static final long DEFAULT_GEOTP_COOLDOWN_MS = 3000L;
   /** 默认渲染距离上报冷却，单位：毫秒 */
   public static final long DEFAULT_TERRAIN_VIEW_COOLDOWN_MS = 1000L;
   /** 冷却允许的最大值，单位：毫秒（1 小时）；超过这个值配置大概率是写错了 */
   public static final long MAX_COOLDOWN_MS = 3_600_000L;

   /** 保护懒加载过程的锁对象 */
   private static final Object LOCK = new Object();

   /** 当前生效的传送策略 */
   private static volatile TellusTeleportPolicy teleportPolicy = DEFAULT_GEOTP_POLICY;
   /** 当前生效的传送冷却，单位：毫秒 */
   private static volatile long teleportCooldownMs = DEFAULT_GEOTP_COOLDOWN_MS;
   /** 当前生效的渲染距离上报冷却，单位：毫秒 */
   private static volatile long terrainViewCooldownMs = DEFAULT_TERRAIN_VIEW_COOLDOWN_MS;
   /** 是否已经把磁盘上的配置读进内存 */
   private static volatile boolean loaded = false;

   /** 工具类不允许被实例化 */
   private TellusServerConfig() {
   }

   /** 一份完整的服务端配置快照，便于读写与测试 */
   public record Values(TellusTeleportPolicy teleportPolicy, long teleportCooldownMs, long terrainViewCooldownMs) {
   }

   // ============ 纯逻辑接缝（不依赖 loader，可直接单元测试） ============

   /**
    * 把 properties 解析成配置快照。
    *
    * 输入：已加载的 Properties，可能缺少任意键、可能有非法值。
    * 输出：规整后的配置快照；缺失或非法项一律回退到默认值。
    * 边界条件：策略名拼错回退 op_only；冷却值非数字回退默认；冷却值超范围会被夹到 [0, 3600000]。
    */
   static Values parse(Properties properties) {
      // 喵~防御：null 当成「空配置」处理，全部走默认值
      Properties safeProperties = properties == null ? new Properties() : properties;
      // 解析传送策略，拼错时 fromConfigName 会回退到 OP_ONLY
      TellusTeleportPolicy parsedPolicy =
         TellusTeleportPolicy.fromConfigName(safeProperties.getProperty(KEY_GEOTP_POLICY));
      // 解析传送冷却，非法或缺失时回退默认值
      long parsedTeleportCooldownMs = parseCooldownMs(
         safeProperties.getProperty(KEY_GEOTP_COOLDOWN_MS), DEFAULT_GEOTP_COOLDOWN_MS
      );
      // 解析渲染距离上报冷却，非法或缺失时回退默认值
      long parsedTerrainViewCooldownMs = parseCooldownMs(
         safeProperties.getProperty(KEY_TERRAIN_VIEW_COOLDOWN_MS), DEFAULT_TERRAIN_VIEW_COOLDOWN_MS
      );
      // 组装成不可变快照
      return new Values(parsedPolicy, parsedTeleportCooldownMs, parsedTerrainViewCooldownMs);
   }

   /**
    * 把配置快照序列化成 properties。
    *
    * 输入：配置快照。
    * 输出：可直接 store 到磁盘的 Properties。
    * 边界条件：快照为 null 时按默认值输出，保证不会写出半个空文件。
    */
   static Properties toProperties(Values values) {
      // 喵~防御：null 快照回退到默认值，避免写出空配置让服主以为配置丢了
      Values safeValues = values == null
         ? new Values(DEFAULT_GEOTP_POLICY, DEFAULT_GEOTP_COOLDOWN_MS, DEFAULT_TERRAIN_VIEW_COOLDOWN_MS)
         : values;
      // 新建一个空的 properties 容器
      Properties properties = new Properties();
      // 写入传送策略，用可读的字符串名而不是数字编号
      properties.setProperty(KEY_GEOTP_POLICY, safeValues.teleportPolicy().configName());
      // 写入传送冷却，单位毫秒
      properties.setProperty(KEY_GEOTP_COOLDOWN_MS, Long.toString(safeValues.teleportCooldownMs()));
      // 写入渲染距离上报冷却，单位毫秒
      properties.setProperty(KEY_TERRAIN_VIEW_COOLDOWN_MS, Long.toString(safeValues.terrainViewCooldownMs()));
      // 返回组装好的容器
      return properties;
   }

   /**
    * 从指定路径读取配置。
    *
    * 输入：配置文件路径。
    * 输出：解析后的配置快照；文件不存在时返回默认快照。
    * 边界条件：路径为 null 返回默认值；IO 失败向上抛出由调用方决定是否降级。
    */
   static Values load(Path configPath) throws IOException {
      // 喵~防御：拿不到路径时直接返回默认配置，不抛异常
      if (configPath == null || !Files.exists(configPath)) {
         return new Values(DEFAULT_GEOTP_POLICY, DEFAULT_GEOTP_COOLDOWN_MS, DEFAULT_TERRAIN_VIEW_COOLDOWN_MS);
      }
      // 准备一个空的 properties 容器
      Properties properties = new Properties();
      // 用 try-with-resources 保证输入流一定被关闭
      try (InputStream input = Files.newInputStream(configPath)) {
         // 从磁盘加载键值对
         properties.load(input);
      }
      // 交给纯函数解析，非法项在其中被兜底
      return parse(properties);
   }

   /**
    * 把配置写入指定路径。
    *
    * 输入：配置文件路径与待写入的配置快照。
    * 输出：无返回值，文件被覆盖写入。
    * 边界条件：路径为 null 时静默跳过；父目录不存在会自动创建。
    */
   static void save(Path configPath, Values values) throws IOException {
      // 喵~防御：拿不到路径时直接跳过写入，避免空指针；内存中的配置依然生效
      if (configPath == null) {
         return;
      }
      // 确保父目录存在，否则 newOutputStream 会抛 NoSuchFileException
      Files.createDirectories(Objects.requireNonNull(configPath.getParent(), "configParent"));
      // 用 try-with-resources 保证输出流一定被关闭
      try (OutputStream output = Files.newOutputStream(configPath)) {
         // 序列化并落盘，第二参是文件头的说明文字
         toProperties(values).store(output, "TellusCN Server Configuration - 服务端配置");
      }
   }

   /**
    * 把冷却毫秒数夹取到合法范围。
    *
    * 输入：任意 long 值，可能为负、可能极大。
    * 输出：落在 [0, {@link #MAX_COOLDOWN_MS}] 的值；0 表示关闭冷却。
    * 边界条件：负数一律当 0（关闭冷却）处理，而不是当成「永久冷却」。
    */
   static long clampCooldownMs(long cooldownMs) {
      // 喵~防御：负数没有意义，当成「不冷却」比当成「一直冷却」安全得多
      if (cooldownMs < 0L) {
         return 0L;
      }
      // 超过上限时夹到上限，防止服主把值写成天文数字导致功能形同废弃
      return Math.min(cooldownMs, MAX_COOLDOWN_MS);
   }

   /**
    * 解析单个冷却配置项。
    *
    * 输入：原始字符串与该项的默认值。
    * 输出：夹取后的冷却毫秒数。
    * 边界条件：null、空白、非数字一律回退到默认值，再统一夹取范围。
    */
   private static long parseCooldownMs(String rawValue, long defaultValue) {
      // 喵~防御：缺失或空白直接回退默认值
      if (rawValue == null || rawValue.isBlank()) {
         return clampCooldownMs(defaultValue);
      }
      // 数字解析可能失败，用 try 包住
      try {
         // 允许字面量带首尾空白，去掉后再解析
         return clampCooldownMs(Long.parseLong(rawValue.trim()));
      } catch (NumberFormatException error) {
         // 喵~防御：写错成 "abc" 这类值时回退默认值，而不是让服务端崩掉
         return clampCooldownMs(defaultValue);
      }
   }

   // ============ 全局配置（懒加载） ============

   /**
    * 解析配置文件路径。
    *
    * 喵~防御：Fabric / Forge 加载器在单元测试、数据生成等场景下可能尚未初始化，
    * 此时底层会抛异常；这里统一降级成「无配置文件」，内存中的默认配置照常生效。
    */
   private static Path resolveConfigPath() {
      try {
         // 向平台层索取 config 目录，由各 loader 实现，支持 -Dtellus.configDir 覆盖
         Path configDir = TellusPlatform.configDir();
         // 目录解析不到时返回 null，表示「当前没有可用配置文件」
         return configDir == null ? null : configDir.resolve(CONFIG_FILE);
      } catch (Throwable error) {
         // 喵~防御：加载器未初始化时连取目录都可能抛异常，统一降级成「无配置文件」
         return null;
      }
   }

   /** 确保磁盘配置只被读取一次 */
   private static void ensureLoaded() {
      // 已经加载过就直接返回，避免每次调用都做文件 IO
      if (!loaded) {
         // 双重检查加锁，保证并发场景下只加载一次
         synchronized (LOCK) {
            if (!loaded) {
               // 从磁盘读取并写入内存字段
               loadFromDisk();
               // 标记已加载
               loaded = true;
            }
         }
      }
   }

   /** 从磁盘读取配置并写入内存字段，失败时保持默认值 */
   private static void loadFromDisk() {
      // 取配置文件路径，拿不到就保持默认值
      Path configPath = resolveConfigPath();
      // 没有可用路径时直接返回
      if (configPath == null) {
         return;
      }
      // 读取可能失败，用 try 包住
      try {
         // 配置文件不存在时，先写出一份带默认值的模板，方便服主在 config 目录里发现这些可配置项
         if (!Files.exists(configPath)) {
            // 直接把内存中的默认值落盘，写出第一份模板
            save(configPath, new Values(teleportPolicy, teleportCooldownMs, terrainViewCooldownMs));
            // 内存里已经是默认值，不需要再从磁盘读回来
            return;
         }
         // 解析出配置快照
         Values values = load(configPath);
         // 写回内存字段，供后续 getter 读取
         teleportPolicy = values.teleportPolicy();
         teleportCooldownMs = values.teleportCooldownMs();
         terrainViewCooldownMs = values.terrainViewCooldownMs();
      } catch (IOException error) {
         // 喵~防御：配置读不出来时保留默认值，绝不让服务端因为一个损坏的配置文件起不来
      } catch (RuntimeException error) {
         // 喵~防御：Properties.load 遇到非法的「反斜杠+codepoint」式转义会抛 IllegalArgumentException（不是 IOException），
         //          而本方法会在服务端 tick 中被调用，异常冒泡会直接崩掉服务器，因此这里一并兜住并退回默认值
      }
   }

   /** 把当前内存配置写回磁盘，失败时只记录不抛出 */
   private static void saveToDisk() {
      // 在锁内写入，避免并发写导致文件内容交错
      synchronized (LOCK) {
         // 取配置文件路径，拿不到就跳过写入
         Path configPath = resolveConfigPath();
         // 没有可用路径时直接返回
         if (configPath == null) {
            return;
         }
         // 写入可能失败，用 try 包住
         try {
            // 把当前内存字段序列化落盘
            save(configPath, new Values(teleportPolicy, teleportCooldownMs, terrainViewCooldownMs));
         } catch (IOException error) {
            // 喵~防御：写盘失败不影响本次会话内已经生效的内存配置
         }
      }
   }

   // ============ 对外取值 / 设置 ============

   /** 取当前生效的传送策略 */
   public static TellusTeleportPolicy teleportPolicy() {
      // 首次访问时从磁盘加载
      ensureLoaded();
      // 返回内存中的策略
      return teleportPolicy;
   }

   /** 取当前生效的传送冷却，单位：毫秒 */
   public static long teleportCooldownMs() {
      // 首次访问时从磁盘加载
      ensureLoaded();
      // 返回内存中的冷却值
      return teleportCooldownMs;
   }

   /** 取当前生效的渲染距离上报冷却，单位：毫秒 */
   public static long terrainViewCooldownMs() {
      // 首次访问时从磁盘加载
      ensureLoaded();
      // 返回内存中的冷却值
      return terrainViewCooldownMs;
   }

   /**
    * 设置传送策略并立刻落盘。
    *
    * 主人注意：这里必须**先 ensureLoaded() 再把内存配置整份落盘**。否则当本会话还没读过磁盘时，
    *  teleportCooldownMs / terrainViewCooldownMs 还停留在默认值，一次改策略就会把服主
    *  写在文件里的自定义冷却值静默覆盖掉。
    */
   public static void setTeleportPolicy(TellusTeleportPolicy policy) {
      // 先把磁盘上的既有配置读进内存，避免下面整份落盘时用默认值覆盖服主的设置
      ensureLoaded();
      // 喵~防御：null 会让后续 wireId() 抛空指针，统一回退到默认策略
      teleportPolicy = policy == null ? DEFAULT_GEOTP_POLICY : policy;
      // 立刻写回磁盘，让配置在重启后依然生效
      saveToDisk();
   }

   /** 设置传送冷却（单位毫秒）并立刻落盘 */
   public static void setTeleportCooldownMs(long cooldownMs) {
      // 先把磁盘上的既有配置读进内存，避免下面整份落盘时把其它两项也覆盖成默认值
      ensureLoaded();
      // 写入前先夹取到合法范围
      teleportCooldownMs = clampCooldownMs(cooldownMs);
      // 立刻写回磁盘，让配置在重启后依然生效
      saveToDisk();
   }

   /** 设置渲染距离上报冷却（单位毫秒）并立刻落盘 */
   public static void setTerrainViewCooldownMs(long cooldownMs) {
      // 先把磁盘上的既有配置读进内存，避免下面整份落盘时把其它两项也覆盖成默认值
      ensureLoaded();
      // 写入前先夹取到合法范围
      terrainViewCooldownMs = clampCooldownMs(cooldownMs);
      // 立刻写回磁盘，让配置在重启后依然生效
      saveToDisk();
   }

   /** 把内存配置恢复成默认值（仅供测试使用，避免测试之间互相污染） */
   static void resetForTests() {
      // 在锁内重置，保持与加载逻辑一致的并发语义
      synchronized (LOCK) {
         // 恢复默认传送策略
         teleportPolicy = DEFAULT_GEOTP_POLICY;
         // 恢复默认传送冷却
         teleportCooldownMs = DEFAULT_GEOTP_COOLDOWN_MS;
         // 恢复默认渲染距离上报冷却
         terrainViewCooldownMs = DEFAULT_TERRAIN_VIEW_COOLDOWN_MS;
         // 重新标记为未加载，下次访问会重新读磁盘
         loaded = false;
      }
   }
}
