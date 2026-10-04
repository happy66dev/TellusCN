/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.network;

/**
 * 联机协议常量与版本兼容性判定。
 *
 * 作用：集中存放所有网络通道名与协议版本号，避免通道字符串散落在各个 payload 里导致漂移。
 *
 * 说明：本类刻意只依赖 JDK，**不引用** PacketType / CustomPacketPayload / StreamCodec
 *       这些各 MC 版本各不相同的网络 API，因为 Forge / NeoForge 目标也会编译这份共享源码；
 *       一旦引用了版本相关 API，非 Fabric 目标就会编译失败。
 *
 * 协议升级约定：
 *   1. payload 字段只允许在**末尾追加**，不允许插入或删除，否则字段顺序错位会导致解码崩溃；
 *   2. 出现不兼容变更时把 {@link #PROTOCOL_VERSION} 加一；
 *   3. {@link #MIN_SUPPORTED_PROTOCOL} 保持不动即代表向后兼容，只有确定不再支持老版本时才抬高它。
 */
public final class TellusProtocol {

   /** 客户端 → 服务端：客户端握手包的通道名 */
   public static final String CHANNEL_CLIENT_HELLO = "client_hello";
   /** 服务端 → 客户端：服务端握手包的通道名 */
   public static final String CHANNEL_SERVER_HELLO = "server_hello";
   /** 客户端 → 服务端：经纬度传送请求的通道名（既有包，收敛到常量避免手写字符串漂移） */
   public static final String CHANNEL_GEOTP_TELEPORT = "geotp_teleport";
   /** 服务端 → 客户端：让客户端弹出传送地图界面的通道名（既有包） */
   public static final String CHANNEL_GEOTP_OPEN_MAP = "geotp_open_map";
   /** 客户端 → 服务端：客户端上报地形渲染距离的通道名（既有包） */
   public static final String CHANNEL_MANAGED_TERRAIN_VIEW = "managed_terrain_view";
   /** 服务端 → 客户端：地形预下载进度状态的通道名（既有包） */
   public static final String CHANNEL_MANAGED_TERRAIN_STATUS = "managed_terrain_status";
   /** 服务端 → 客户端：实时天气网格的通道名（既有包） */
   public static final String CHANNEL_REALTIME_WEATHER = "realtime_weather";

   /** 当前 TellusCN 的协议版本号，单位：无（纯计数），不兼容变更时 +1 */
   public static final int PROTOCOL_VERSION = 1;
   /** 仍能与之通信的最低协议版本号，单位：无；与 {@link #PROTOCOL_VERSION} 相等即表示只支持同版本 */
   public static final int MIN_SUPPORTED_PROTOCOL = 1;

   /** 工具类不允许被实例化 */
   private TellusProtocol() {
   }

   /**
    * 判断对端上报的协议版本是否可以用当前版本与之通信。
    *
    * 输入：对端（客户端或服务端）上报的协议版本号。
    * 输出：落在 [{@link #MIN_SUPPORTED_PROTOCOL}, {@link #PROTOCOL_VERSION}] 闭区间内返回 true。
    * 边界条件：负数、0、以及高于本端版本的值一律判定为不兼容；
    *          高于本端版本时不尝试猜测新字段，直接拒绝，避免错位解码。
    */
   public static boolean isCompatible(int remoteProtocolVersion) {
      // 喵~防御：负数或 0 都是非法版本号（可能来自改包客户端），一律判为不兼容
      if (remoteProtocolVersion < MIN_SUPPORTED_PROTOCOL) {
         return false;
      }
      // 高于本端已知版本的协议不做兼容假设，直接拒绝
      return remoteProtocolVersion <= PROTOCOL_VERSION;
   }
}
