/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TellusProtocol 单元测试
 *
 * 覆盖场景：本端版本、最低支持版本、负数与 0 等非法版本、高于本端版本。
 * 该测试只依赖 JDK 与 JUnit，不依赖 Minecraft / Fabric 类。
 */
class TellusProtocolTest {

   /**
    * 本端自己的版本必须兼容。
    */
   @Test
   void currentVersionIsCompatible() {
      // 本端版本应当判定为兼容
      assertTrue(TellusProtocol.isCompatible(TellusProtocol.PROTOCOL_VERSION));
   }

   /**
    * 最低支持版本必须兼容。
    */
   @Test
   void minimumSupportedVersionIsCompatible() {
      // 最低支持版本应当判定为兼容
      assertTrue(TellusProtocol.isCompatible(TellusProtocol.MIN_SUPPORTED_PROTOCOL));
   }

   /**
    * 低于最低支持版本的值必须不兼容。
    */
   @Test
   void versionBelowMinimumIsIncompatible() {
      // 比最低版本还低 1 的版本应当不兼容
      assertFalse(TellusProtocol.isCompatible(TellusProtocol.MIN_SUPPORTED_PROTOCOL - 1));
   }

   /**
    * 负数与 0 都是非法版本号，必须不兼容。
    */
   @Test
   void nonPositiveVersionsAreIncompatible() {
      // 0 不是合法协议版本
      assertFalse(TellusProtocol.isCompatible(0));
      // 负数更不是合法协议版本（可能来自改包客户端）
      assertFalse(TellusProtocol.isCompatible(-1));
      // 极端负值同样必须被拒绝
      assertFalse(TellusProtocol.isCompatible(Integer.MIN_VALUE));
   }

   /**
    * 高于本端已知版本的协议不做兼容假设，必须不兼容。
    */
   @Test
   void versionAboveCurrentIsIncompatible() {
      // 比本端版本高 1 的版本应当不兼容（避免错位解析新字段）
      assertFalse(TellusProtocol.isCompatible(TellusProtocol.PROTOCOL_VERSION + 1));
      // 极端高值同样必须被拒绝
      assertFalse(TellusProtocol.isCompatible(Integer.MAX_VALUE));
   }

   /**
    * 通道名必须非空且互不重复，避免两个 payload 抢同一个通道。
    */
   @Test
   void channelNamesAreDistinctAndNonBlank() {
      // 逐个检查通道名非空
      assertFalse(TellusProtocol.CHANNEL_CLIENT_HELLO.isBlank());
      assertFalse(TellusProtocol.CHANNEL_SERVER_HELLO.isBlank());
      assertFalse(TellusProtocol.CHANNEL_GEOTP_TELEPORT.isBlank());
      assertFalse(TellusProtocol.CHANNEL_GEOTP_OPEN_MAP.isBlank());
      assertFalse(TellusProtocol.CHANNEL_MANAGED_TERRAIN_VIEW.isBlank());
      assertFalse(TellusProtocol.CHANNEL_MANAGED_TERRAIN_STATUS.isBlank());
      assertFalse(TellusProtocol.CHANNEL_REALTIME_WEATHER.isBlank());
      // 握手两个通道名必须不同
      assertNotEquals(TellusProtocol.CHANNEL_CLIENT_HELLO, TellusProtocol.CHANNEL_SERVER_HELLO);
      // 传送的两个通道名必须不同（方向相反，绝不能混用）
      assertNotEquals(TellusProtocol.CHANNEL_GEOTP_TELEPORT, TellusProtocol.CHANNEL_GEOTP_OPEN_MAP);
      // 地形上传与下发的两个通道名必须不同
      assertNotEquals(TellusProtocol.CHANNEL_MANAGED_TERRAIN_VIEW, TellusProtocol.CHANNEL_MANAGED_TERRAIN_STATUS);
   }
}
