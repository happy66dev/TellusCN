/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.config;

import com.yucareux.tellus.network.TellusTeleportPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TellusServerConfig 单元测试
 *
 * 覆盖场景：缺省值、正常解析、非法策略名回退、冷却值非数字回退、冷却值范围夹取、
 * properties 往返序列化、文件不存在、文件读写往返、null 路径，
 * 以及三条走全局懒加载路径的回归用例（改策略不覆盖磁盘冷却值、坏转义不崩、首次读取自动生成模板）。
 * 前半部分只调用纯逻辑接缝（parse / toProperties / load / save / clampCooldownMs）；
 * 后半部分通过 -Dtellus.configDir 把全局配置目录指向 @TempDir，测完立即还原，不依赖加载器初始化。
 */
class TellusServerConfigTest {

   /**
    * 缺省属性时应当全部回退到默认值。
    */
   @Test
   void parseReturnsDefaultsWhenPropertiesAreEmpty() {
      // 空属性表
      Properties properties = new Properties();

      // 解析出配置快照
      TellusServerConfig.Values values = TellusServerConfig.parse(properties);

      // 策略应当是默认的「仅 OP」
      assertEquals(TellusTeleportPolicy.OP_ONLY, values.teleportPolicy());
      // 传送冷却应当是默认值
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_COOLDOWN_MS, values.teleportCooldownMs());
      // 渲染距离上报冷却应当是默认值
      assertEquals(TellusServerConfig.DEFAULT_TERRAIN_VIEW_COOLDOWN_MS, values.terrainViewCooldownMs());
   }

   /**
    * null 属性表应当被当成空表处理，而不是抛空指针。
    */
   @Test
   void parseTreatsNullPropertiesAsEmpty() {
      // 喵~防御：解析 null 不应抛异常
      TellusServerConfig.Values values = TellusServerConfig.parse(null);

      // 结果应当与空表一致，全部是默认值
      assertEquals(TellusTeleportPolicy.OP_ONLY, values.teleportPolicy());
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_COOLDOWN_MS, values.teleportCooldownMs());
   }

   /**
    * 合法取值应当被正确读出。
    */
   @Test
   void parseReadsValidValues() {
      // 准备一份写满合法值的属性表
      Properties properties = new Properties();
      // 策略设为所有人可用
      properties.setProperty("geotp.policy", "everyone");
      // 传送冷却设为 4500 毫秒
      properties.setProperty("geotp.cooldown_ms", "4500");
      // 渲染距离上报冷却设为 250 毫秒
      properties.setProperty("terrain_view.cooldown_ms", "250");

      // 解析出配置快照
      TellusServerConfig.Values values = TellusServerConfig.parse(properties);

      // 三项都应当被正确读出
      assertEquals(TellusTeleportPolicy.EVERYONE, values.teleportPolicy());
      assertEquals(4500L, values.teleportCooldownMs());
      assertEquals(250L, values.terrainViewCooldownMs());
   }

   /**
    * 策略名大小写与首尾空白应当被容忍。
    */
   @Test
   void parseToleratesPolicyNameCasingAndWhitespace() {
      // 写成大写并带首尾空格
      Properties properties = new Properties();
      // 大写 + 首尾空白
      properties.setProperty("geotp.policy", "  DISABLED  ");

      // 解析出配置快照
      TellusServerConfig.Values values = TellusServerConfig.parse(properties);

      // 应当被识别为「完全禁用」
      assertEquals(TellusTeleportPolicy.DISABLED, values.teleportPolicy());
   }

   /**
    * 策略名拼错时应当回退到默认的「仅 OP」，而不是让服务端起不来。
    */
   @Test
   void parseFallsBackToDefaultOnUnknownPolicyName() {
      // 故意写一个不存在的策略名
      Properties properties = new Properties();
      // 拼错的策略名
      properties.setProperty("geotp.policy", "op-only-please");

      // 解析出配置快照
      TellusServerConfig.Values values = TellusServerConfig.parse(properties);

      // 应当回退到默认策略
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, values.teleportPolicy());
   }

   /**
    * 冷却值不是数字时应当回退到该项默认值。
    */
   @Test
   void parseFallsBackOnNonNumericCooldown() {
      // 故意写一个非数字的冷却值
      Properties properties = new Properties();
      // 非数字内容
      properties.setProperty("geotp.cooldown_ms", "abc");

      // 解析出配置快照
      TellusServerConfig.Values values = TellusServerConfig.parse(properties);

      // 应当回退到该项默认值
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_COOLDOWN_MS, values.teleportCooldownMs());
   }

   /**
    * 冷却值为负数时应当夹到 0（表示关闭冷却），而不是「永久冷却」。
    */
   @Test
   void parseClampsNegativeCooldownToZero() {
      // 负数冷却
      Properties properties = new Properties();
      // 故意写负数
      properties.setProperty("geotp.cooldown_ms", "-12345");

      // 解析出配置快照
      TellusServerConfig.Values values = TellusServerConfig.parse(properties);

      // 应当夹到 0
      assertEquals(0L, values.teleportCooldownMs());
   }

   /**
    * 冷却值超出上限时应当夹到上限。
    */
   @Test
   void parseClampsHugeCooldownToMaximum() {
      // 远超上限的冷却值
      Properties properties = new Properties();
      // 写入一个天文数字
      properties.setProperty("terrain_view.cooldown_ms", Long.toString(TellusServerConfig.MAX_COOLDOWN_MS * 100L));

      // 解析出配置快照
      TellusServerConfig.Values values = TellusServerConfig.parse(properties);

      // 应当被夹到上限
      assertEquals(TellusServerConfig.MAX_COOLDOWN_MS, values.terrainViewCooldownMs());
   }

   /**
    * toProperties 的输出应当能被 parse 原样读回。
    */
   @Test
   void toPropertiesRoundTripsThroughParse() {
      // 准备一份非默认的快照
      TellusServerConfig.Values original = new TellusServerConfig.Values(TellusTeleportPolicy.EVERYONE, 1234L, 567L);

      // 序列化成 properties 再解析回来
      TellusServerConfig.Values roundTripped = TellusServerConfig.parse(TellusServerConfig.toProperties(original));

      // 三项都应当与原始快照一致
      assertEquals(original.teleportPolicy(), roundTripped.teleportPolicy());
      assertEquals(original.teleportCooldownMs(), roundTripped.teleportCooldownMs());
      assertEquals(original.terrainViewCooldownMs(), roundTripped.terrainViewCooldownMs());
   }

   /**
    * toProperties 收到 null 快照时应当输出默认值，而不是写出空配置。
    */
   @Test
   void toPropertiesFallsBackOnNullValues() {
      // 喵~防御：null 快照不应抛异常
      TellusServerConfig.Values roundTripped = TellusServerConfig.parse(TellusServerConfig.toProperties(null));

      // 结果应当是默认配置
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, roundTripped.teleportPolicy());
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_COOLDOWN_MS, roundTripped.teleportCooldownMs());
      assertEquals(TellusServerConfig.DEFAULT_TERRAIN_VIEW_COOLDOWN_MS, roundTripped.terrainViewCooldownMs());
   }

   /**
    * clampCooldownMs 的边界行为。
    */
   @Test
   void clampCooldownMsHandlesBoundaries() {
      // 负数夹到 0
      assertEquals(0L, TellusServerConfig.clampCooldownMs(-1L));
      // 0 保持 0
      assertEquals(0L, TellusServerConfig.clampCooldownMs(0L));
      // 正常值原样返回
      assertEquals(3000L, TellusServerConfig.clampCooldownMs(3000L));
      // 上限值原样返回
      assertEquals(TellusServerConfig.MAX_COOLDOWN_MS, TellusServerConfig.clampCooldownMs(TellusServerConfig.MAX_COOLDOWN_MS));
      // 超过上限时夹到上限
      assertEquals(TellusServerConfig.MAX_COOLDOWN_MS, TellusServerConfig.clampCooldownMs(Long.MAX_VALUE));
   }

   /**
    * 配置文件不存在时 load 应当返回默认值而不是抛异常。
    */
   @Test
   void loadReturnsDefaultsWhenFileIsMissing(@TempDir Path tempDir) throws IOException {
      // 指向一个不存在的文件
      Path missingConfigPath = tempDir.resolve("not-created-yet.properties");

      // 读取应当返回默认快照
      TellusServerConfig.Values values = TellusServerConfig.load(missingConfigPath);

      // 三项都应当是默认值
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, values.teleportPolicy());
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_COOLDOWN_MS, values.teleportCooldownMs());
      assertEquals(TellusServerConfig.DEFAULT_TERRAIN_VIEW_COOLDOWN_MS, values.terrainViewCooldownMs());
   }

   /**
    * 路径为 null 时 load 应当返回默认值而不是抛异常。
    */
   @Test
   void loadReturnsDefaultsWhenPathIsNull() throws IOException {
      // 喵~防御：null 路径不应抛异常
      TellusServerConfig.Values values = TellusServerConfig.load(null);

      // 结果应当是默认配置
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, values.teleportPolicy());
   }

   /**
    * save 之后 load 应当能读回同一份配置（真实落盘往返）。
    */
   @Test
   void saveThenLoadRoundTripsOnDisk(@TempDir Path tempDir) throws IOException {
      // 在临时目录下准备目标文件路径（父目录尚不存在，交给 save 自动创建）
      Path configPath = tempDir.resolve("nested").resolve("tellus-server.properties");
      // 准备一份非默认配置
      TellusServerConfig.Values original = new TellusServerConfig.Values(TellusTeleportPolicy.DISABLED, 777L, 0L);

      // 写盘
      TellusServerConfig.save(configPath, original);
      // 确认文件真的被创建
      assertTrue(Files.exists(configPath));
      // 从磁盘读回
      TellusServerConfig.Values loaded = TellusServerConfig.load(configPath);

      // 三项都应当与写盘前一致
      assertEquals(TellusTeleportPolicy.DISABLED, loaded.teleportPolicy());
      assertEquals(777L, loaded.teleportCooldownMs());
      assertEquals(0L, loaded.terrainViewCooldownMs());
   }

   /**
    * save 收到 null 路径时应当静默跳过，不抛异常也不创建任何文件。
    */
   @Test
   void saveSkipsSilentlyWhenPathIsNull() throws IOException {
      // 喵~防御：null 路径不应抛异常
      TellusServerConfig.save(null, new TellusServerConfig.Values(TellusTeleportPolicy.EVERYONE, 1L, 1L));
   }

   /**
    * save 收到 null 快照时应当写出默认值而不是崩掉。
    */
   @Test
   void saveWritesDefaultsWhenValuesAreNull(@TempDir Path tempDir) throws IOException {
      // 准备目标文件路径
      Path configPath = tempDir.resolve("tellus-server.properties");

      // 快照传 null
      TellusServerConfig.save(configPath, null);
      // 读回应当得到默认配置
      TellusServerConfig.Values loaded = TellusServerConfig.load(configPath);

      // 确认写的是默认值
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, loaded.teleportPolicy());
   }

   /**
    * load 遇到损坏的文件内容时不应抛异常（Properties 语法错误会被跳过或按默认值处理）。
    */
   @Test
   void loadSurvivesMalformedValues(@TempDir Path tempDir) throws IOException {
      // 写一个策略名与冷却值都非法的配置文件
      Path configPath = tempDir.resolve("tellus-server.properties");
      // 写入非法内容
      Files.writeString(configPath, "geotp.policy=???\ngeotp.cooldown_ms=not-a-number\n");

      // 读取应当成功并回退到默认值
      TellusServerConfig.Values values = TellusServerConfig.load(configPath);

      // 策略与冷却都应当回退到默认值
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, values.teleportPolicy());
      assertEquals(TellusServerConfig.DEFAULT_GEOTP_COOLDOWN_MS, values.teleportCooldownMs());
   }

   /**
    * save 写入可写目录时不应抛任何异常。
    */
   @Test
   void saveDoesNotThrowOnWritableTempDirectory(@TempDir Path tempDir) {
      // 准备目标文件路径
      Path configPath = tempDir.resolve("tellus-server.properties");

      // 正常写入不应抛异常（受检的 IOException 由 assertDoesNotThrow 一并覆盖）
      assertDoesNotThrow(() -> TellusServerConfig.save(configPath, null));
   }

   // ============ 以下用例会走全局懒加载路径，靠 -Dtellus.configDir 把配置目录指向临时目录 ============

   /**
    * 允许抛受检 IOException 的断言片段，供 {@link #withConfigDir} 包装使用。
    */
   @FunctionalInterface
   private interface ConfigDirAssertions {
      /** 在临时配置目录下执行的断言逻辑 */
      void run() throws IOException;
   }

   /**
    * 把全局配置目录临时指向给定目录，并在结束后还原系统属性与内存状态。
    *
    * 输入：临时配置目录与一段断言逻辑。
    * 输出：无返回值。
    * 边界条件：无论断言是否失败都会在 finally 里还原，保证测试之间互不污染。
    */
   private static void withConfigDir(Path configDir, ConfigDirAssertions assertions) throws IOException {
      // 记录调用前的系统属性，便于结束后还原
      String previousConfigDir = System.getProperty("tellus.configDir");
      try {
         // 让 TellusPlatform.configDir() 指向临时目录
         System.setProperty("tellus.configDir", configDir.toString());
         // 清掉上一轮测试留下的内存缓存，让下一次访问重新读盘
         TellusServerConfig.resetForTests();
         // 执行断言逻辑
         assertions.run();
      } finally {
         // 还原系统属性
         if (previousConfigDir == null) {
            System.clearProperty("tellus.configDir");
         } else {
            System.setProperty("tellus.configDir", previousConfigDir);
         }
         // 还原内存状态，避免影响其它用例
         TellusServerConfig.resetForTests();
      }
   }

   /**
    * 回归测试：改传送策略不得把服主写在文件里的冷却值覆盖成默认值。
    *
    * 背景：setTeleportPolicy 会把内存中的三项配置整份落盘。如果本会话还没读过磁盘，
    *       内存里的冷却值仍是默认的 3000 / 1000，一次改策略就会静默抹掉服主的自定义值。
    */
   @Test
   void setTeleportPolicyKeepsCooldownsStoredOnDisk(@TempDir Path tempDir) throws IOException {
      withConfigDir(tempDir, () -> {
         // 先手工写一份带自定义冷却的配置文件，模拟服主自己编辑过
         Path configPath = tempDir.resolve("tellus-server.properties");
         Files.writeString(configPath, "geotp.policy=op_only\ngeotp.cooldown_ms=10000\nterrain_view.cooldown_ms=2000\n");

         // 管理员把策略改成「所有人可用」
         TellusServerConfig.setTeleportPolicy(TellusTeleportPolicy.EVERYONE);

         // 从磁盘读回改写后的内容
         TellusServerConfig.Values reloaded = TellusServerConfig.load(configPath);

         // 策略应当已经被改成 EVERYONE
         assertEquals(TellusTeleportPolicy.EVERYONE, reloaded.teleportPolicy());
         // 服主写的传送冷却必须被保留，而不是被默认值 3000 覆盖
         assertEquals(10000L, reloaded.teleportCooldownMs());
         // 服主写的渲染距离上报冷却同样必须被保留，而不是被默认值 1000 覆盖
         assertEquals(2000L, reloaded.terrainViewCooldownMs());
      });
   }

   /**
    * 回归测试：配置文件里含非法 Unicode 转义时，全局读取不应抛异常。
    *
    * 背景：Properties.load 遇到坏转义抛的是 IllegalArgumentException 而不是 IOException，
    *       而配置读取发生在服务端 tick 里，异常冒泡会直接把服务器带崩。
    */
   @Test
   void teleportPolicySurvivesMalformedUnicodeEscapeInConfig(@TempDir Path tempDir) throws IOException {
      withConfigDir(tempDir, () -> {
         // 写入一行含非法 Unicode 转义的配置（转义序列后面不是 4 位十六进制）
         Path configPath = tempDir.resolve("tellus-server.properties");
         Files.writeString(configPath, "backup.path=C:\\users\\admin\n");

         // 全局读取不应抛异常，应当退回默认策略而不是崩服务端
         assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, TellusServerConfig.teleportPolicy());
      });
   }

   /**
    * 首次读取全局配置时应当自动生成一份带默认值的模板文件，方便服主发现可配置项。
    */
   @Test
   void firstGlobalReadCreatesDefaultConfigFile(@TempDir Path tempDir) throws IOException {
      withConfigDir(tempDir, () -> {
         // 触发一次全局配置读取
         TellusServerConfig.teleportPolicy();

         // 配置文件应当已经被自动创建出来
         Path configPath = tempDir.resolve("tellus-server.properties");
         assertTrue(Files.exists(configPath), "首次读取应当自动生成配置文件模板");

         // 自动生成的内容应当是默认配置
         TellusServerConfig.Values values = TellusServerConfig.load(configPath);
         assertEquals(TellusServerConfig.DEFAULT_GEOTP_POLICY, values.teleportPolicy());
         assertEquals(TellusServerConfig.DEFAULT_GEOTP_COOLDOWN_MS, values.teleportCooldownMs());
         assertEquals(TellusServerConfig.DEFAULT_TERRAIN_VIEW_COOLDOWN_MS, values.terrainViewCooldownMs());
      });
   }
}
