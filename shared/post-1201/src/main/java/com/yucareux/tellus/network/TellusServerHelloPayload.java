/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：服务端握手包。
 *
 * 作用：玩家进入游戏后，服务端把自己的联机协议版本、模组版本，以及「本服务器的传送策略」
 *       「该玩家本人能不能传送」「世界缩放」「是否开启实验性提升高度」「服务端镜像是否启用」
 *       一次性告诉客户端，供客户端做兼容性提示与界面状态判断。
 *
 * 说明：本包是**逐玩家**发送的，所以同一个服务器里不同玩家看到的 playerCanTeleport 可能不同。
 *       本文件是「MC 1.21.1 及之后」的新 API 版本；MC 1.20.1 另有一份老 API 实现，
 *       两版的字段顺序必须严格一致，新增字段只能追加到末尾。
 *
 * 主人注意：末尾三个字段 worldScale / experimentalHeight / mirrorEnabled 目前是**预留的扩展位**，
 *          客户端收到后不会读取，也没有任何生产调用方。保留它们是为了避免以后要用时再改协议版本；
 *          若确认长期不用，可以考虑在下一次协议变更时一并移除。
 */
public record TellusServerHelloPayload(
   int protocolVersion,
   String serverModVersion,
   TellusTeleportPolicy teleportPolicy,
   boolean playerCanTeleport,
   double worldScale,
   boolean experimentalHeight,
   boolean mirrorEnabled
) implements CustomPacketPayload {

   /** 模组版本字符串允许的最大长度，单位：字符；限长是为了防止畸形包撑爆缓冲区 */
   private static final int MAX_MOD_VERSION_LENGTH = 32;

   /** 本包的通道类型标识，通道名取自共享常量以免手写字符串漂移 */
   public static final CustomPacketPayload.Type<TellusServerHelloPayload> TYPE =
      new CustomPacketPayload.Type<>(Tellus.id(TellusProtocol.CHANNEL_SERVER_HELLO));

   /** 本包的编解码器：字段顺序必须与 MC 1.20.1 老 API 版本严格一致 */
   public static final StreamCodec<FriendlyByteBuf, TellusServerHelloPayload> CODEC =
      new StreamCodec<FriendlyByteBuf, TellusServerHelloPayload>() {
         /** 从网络缓冲区解出服务端握手包 */
         @Override
         public TellusServerHelloPayload decode(FriendlyByteBuf buffer) {
            // 委托给静态方法，保持解码逻辑单一来源
            return read(buffer);
         }

         /** 把服务端握手包写进网络缓冲区 */
         @Override
         public void encode(FriendlyByteBuf buffer, TellusServerHelloPayload value) {
            // 委托给静态方法，保持编码逻辑单一来源
            write(buffer, value);
         }
      };

   /** 紧凑构造器：在对象建立时就把可能为 null 的字段规整好，后续读写都不用再判空 */
   public TellusServerHelloPayload {
      // 喵~防御：null、超长、控制字符都会在发送时炸掉或撑爆缓冲，这里统一规整
      serverModVersion = normalizeModVersion(serverModVersion);
      // 喵~防御：null 策略会让后续 wireId() 抛空指针，统一回退到最保守的 DISABLED
      teleportPolicy = teleportPolicy == null ? TellusTeleportPolicy.DISABLED : teleportPolicy;
   }

   /** 返回本包的通道类型，供 Fabric 网络层路由 */
   @Override
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      // 通道类型是常量，直接返回
      return TYPE;
   }

   /**
    * 从网络缓冲区读取服务端握手包。
    *
    * 输入：网络缓冲区，字段顺序为 protocolVersion、serverModVersion、teleportPolicy、
    *       playerCanTeleport、worldScale、experimentalHeight、mirrorEnabled。
    * 输出：解码后的服务端握手包。
    * 边界条件：策略编号越界会回退到 DISABLED；worldScale 非有限或非正数会回退到默认值。
    */
   static TellusServerHelloPayload read(FriendlyByteBuf buffer) {
      // 读协议版本号，单位：无
      int protocolVersion = buffer.readVarInt();
      // 读服务端模组版本字符串，限长 32 个字符
      String serverModVersion = buffer.readUtf(MAX_MOD_VERSION_LENGTH);
      // 读传送策略编号并还原成枚举，越界会安全回退
      TellusTeleportPolicy teleportPolicy = TellusTeleportPolicy.fromWireId(buffer.readUnsignedByte());
      // 读「该玩家本人能否传送」标记
      boolean playerCanTeleport = buffer.readBoolean();
      // 读世界缩放并做合法性兜底
      double worldScale = sanitizeWorldScale(buffer.readDouble());
      // 读「是否开启实验性提升高度」标记
      boolean experimentalHeight = buffer.readBoolean();
      // 读「服务端镜像是否启用」标记
      boolean mirrorEnabled = buffer.readBoolean();
      // 组装成不可变记录
      return new TellusServerHelloPayload(
         protocolVersion, serverModVersion, teleportPolicy, playerCanTeleport, worldScale, experimentalHeight, mirrorEnabled
      );
   }

   /**
    * 把服务端握手包写入网络缓冲区。
    *
    * 输入：网络缓冲区与待写入的包。
    * 输出：无返回值，缓冲区被追加写入。
    * 边界条件：写入前再次规整字符串，保证长度不超过 writeUtf 的上限。
    */
   static void write(FriendlyByteBuf buffer, TellusServerHelloPayload payload) {
      // 写入协议版本号，单位：无
      buffer.writeVarInt(payload.protocolVersion());
      // 写入服务端模组版本字符串，限长 32 个字符
      buffer.writeUtf(normalizeModVersion(payload.serverModVersion()), MAX_MOD_VERSION_LENGTH);
      // 写入传送策略编号，越界处理交给接收方
      buffer.writeByte(payload.teleportPolicy().wireId());
      // 写入「该玩家本人能否传送」标记
      buffer.writeBoolean(payload.playerCanTeleport());
      // 写入世界缩放
      buffer.writeDouble(payload.worldScale());
      // 写入「是否开启实验性提升高度」标记
      buffer.writeBoolean(payload.experimentalHeight());
      // 写入「服务端镜像是否启用」标记
      buffer.writeBoolean(payload.mirrorEnabled());
   }

   /**
    * 规整模组版本字符串。
    *
    * 输入：任意字符串，可能为 null、可能超长。
    * 输出：非 null 且长度不超过 32 个字符的字符串。
    * 边界条件：null 与空白都转成空串，超长按字符截断。
    */
   private static String normalizeModVersion(String rawVersion) {
      // 喵~防御：null 会让 writeUtf 抛异常，统一转成空串
      String safeVersion = rawVersion == null ? "" : rawVersion.trim();
      // 超过上限时按字符截断，避免 writeUtf 因超长而抛异常
      return safeVersion.length() <= MAX_MOD_VERSION_LENGTH
         ? safeVersion
         : safeVersion.substring(0, MAX_MOD_VERSION_LENGTH);
   }

   /**
    * 校验世界缩放值。
    *
    * 输入：网络收到的 double 值。
    * 输出：合法（有限且大于 0）时原样返回，否则回退到 {@link EarthGeneratorSettings#DEFAULT} 的缩放。
    * 边界条件：NaN、正负无穷、0 与负数都会破坏地形换算，统一回退而不是让客户端拿去算崩。
    */
   private static double sanitizeWorldScale(double rawWorldScale) {
      // 喵~防御：非有限值或非正数都是非法缩放，回退到官方默认值
      if (!Double.isFinite(rawWorldScale) || rawWorldScale <= 0.0) {
         return EarthGeneratorSettings.DEFAULT.worldScale();
      }
      // 数值合法，原样返回
      return rawWorldScale;
   }
}
