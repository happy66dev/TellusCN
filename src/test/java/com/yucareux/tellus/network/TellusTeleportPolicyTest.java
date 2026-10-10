/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * TellusTeleportPolicy 单元测试
 *
 * 覆盖场景：网络编号往返、越界编号回退、配置名解析（大小写、空白、null、拼错）、
 * 以及「网络编号与配置名都必须互不重复」这一结构性约束。
 * 该测试只依赖 JDK 与 JUnit，不依赖 Minecraft / Fabric 类。
 */
class TellusTeleportPolicyTest {

   /**
    * 每个策略的网络编号都能原样往返。
    */
   @Test
   void wireIdRoundTripsForEveryPolicy() {
      // 逐个枚举值做往返验证
      for (TellusTeleportPolicy policy : TellusTeleportPolicy.values()) {
         // 编号还原后应当还是同一个策略
         assertEquals(policy, TellusTeleportPolicy.fromWireId(policy.wireId()));
      }
   }

   /**
    * 越界的网络编号应当回退到最保守的 DISABLED，而不是抛异常或默认放开。
    */
   @Test
   void outOfRangeWireIdFallsBackToDisabled() {
      // 远大于已知编号的值
      assertEquals(TellusTeleportPolicy.DISABLED, TellusTeleportPolicy.fromWireId(9999));
      // 负值
      assertEquals(TellusTeleportPolicy.DISABLED, TellusTeleportPolicy.fromWireId(-1));
      // Byte 读取后理论上不会出现这么大的值，但底层是 int，仍要防御
      assertEquals(TellusTeleportPolicy.DISABLED, TellusTeleportPolicy.fromWireId(Integer.MAX_VALUE));
   }

   /**
    * 三个策略的网络编号必须互不重复，否则会互相覆盖。
    */
   @Test
   void wireIdsAreDistinct() {
      // 两两比对编号
      assertNotEquals(TellusTeleportPolicy.DISABLED.wireId(), TellusTeleportPolicy.OP_ONLY.wireId());
      assertNotEquals(TellusTeleportPolicy.OP_ONLY.wireId(), TellusTeleportPolicy.EVERYONE.wireId());
      assertNotEquals(TellusTeleportPolicy.DISABLED.wireId(), TellusTeleportPolicy.EVERYONE.wireId());
   }

   /**
    * 三个策略的配置名必须互不重复，否则配置文件无法区分。
    */
   @Test
   void configNamesAreDistinct() {
      // 两两比对配置名
      assertNotEquals(TellusTeleportPolicy.DISABLED.configName(), TellusTeleportPolicy.OP_ONLY.configName());
      assertNotEquals(TellusTeleportPolicy.OP_ONLY.configName(), TellusTeleportPolicy.EVERYONE.configName());
      assertNotEquals(TellusTeleportPolicy.DISABLED.configName(), TellusTeleportPolicy.EVERYONE.configName());
   }

   /**
    * 配置名大小写与首尾空白都应当被容忍。
    */
   @Test
   void fromConfigNameToleratesCasingAndWhitespace() {
      // 全大写且带首尾空白
      assertEquals(TellusTeleportPolicy.EVERYONE, TellusTeleportPolicy.fromConfigName("  EVERYONE  "));
      // 全小写
      assertEquals(TellusTeleportPolicy.DISABLED, TellusTeleportPolicy.fromConfigName("disabled"));
      // 混合大小写
      assertEquals(TellusTeleportPolicy.OP_ONLY, TellusTeleportPolicy.fromConfigName("Op_Only"));
   }

   /**
    * null、空白与拼错的名字都应当回退到默认的 OP_ONLY。
    */
   @Test
   void fromConfigNameFallsBackToDefault() {
      // 喵~防御：这三种都属于「配置有问题」，回退到与历史行为一致的默认值
      assertEquals(TellusTeleportPolicy.OP_ONLY, TellusTeleportPolicy.fromConfigName(null));
      assertEquals(TellusTeleportPolicy.OP_ONLY, TellusTeleportPolicy.fromConfigName("   "));
      assertEquals(TellusTeleportPolicy.OP_ONLY, TellusTeleportPolicy.fromConfigName("op_only_please"));
   }
}
