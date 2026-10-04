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
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 → 客户端：服务端握手包（MC 1.20.1 老 API 版本）。
 *
 * 作用：与 shared/post-1201 下的新 API 版本语义完全相同——服务端把自己的协议版本、模组版本、
 *       传送策略、该玩家本人能否传送、世界缩放、是否开启实验性提升高度、镜像是否启用告诉客户端。
 *
 * 说明：MC 1.20.1 用的是 Fabric API v1 的 FabricPacket / PacketType 老接口，必须单独实现一份。
 *       **两版的字段顺序必须严格一致**，新增字段只能追加到末尾。
 */
public record TellusServerHelloPayload(
   int protocolVersion,
   String serverModVersion,
   TellusTeleportPolicy teleportPolicy,
   boolean playerCanTeleport,
   double worldScale,
   boolean experimentalHeight,
   boolean mirrorEnabled
) implements FabricPacket {

   /** 模组版本字符串允许的最大长度，单位：字符；限长是为了防止畸形包撑爆缓冲区 */
   private static final int MAX_MOD_VERSION_LENGTH = 32;

   /** 本包的通道类型标识，通道名取自共享常量以免手写字符串漂移 */
   public static final PacketType<TellusServerHelloPayload> TYPE =
      PacketType.create(Tellus.id(TellusProtocol.CHANNEL_SERVER_HELLO), TellusServerHelloPayload::new);

   /**
    * 从网络缓冲区构造服务端握手包。
    *
    * 输入：网络缓冲区，字段顺序为 protocolVersion、serverModVersion、teleportPolicy、
    *       playerCanTeleport、worldScale、experimentalHeight、mirrorEnabled。
    * 输出：解码后的服务端握手包。
    * 边界条件：策略编号越界会回退到 DISABLED；worldScale 非有限或非正数会回退到默认值。
    */
   public TellusServerHelloPayload(FriendlyByteBuf buffer) {
      // 依次读取七个字段，顺序必须与新 API 版本一致
      this(
         buffer.readVarInt(),
         buffer.readUtf(MAX_MOD_VERSION_LENGTH),
         TellusTeleportPolicy.fromWireId(buffer.readUnsignedByte()),
         buffer.readBoolean(),
         sanitizeWorldScale(buffer.readDouble()),
         buffer.readBoolean(),
         buffer.readBoolean()
      );
   }

   /** 紧凑构造器：在对象建立时就把可能为 null 的字段规整好，后续发送都不用再判空 */
   public TellusServerHelloPayload {
      // 喵~防御：null、超长、控制字符都会在发送时炸掉或撑爆缓冲，这里统一规整
      serverModVersion = normalizeModVersion(serverModVersion);
      // 喵~防御：null 策略会让后续 wireId() 抛空指针，统一回退到最保守的 DISABLED
      teleportPolicy = teleportPolicy == null ? TellusTeleportPolicy.DISABLED : teleportPolicy;
   }

   /** 把本包写进网络缓冲区，字段顺序必须与新 API 版本严格一致 */
   @Override
   public void write(FriendlyByteBuf buffer) {
      // 写入协议版本号，单位：无
      buffer.writeVarInt(this.protocolVersion());
      // 写入服务端模组版本字符串，限长 32 个字符
      buffer.writeUtf(normalizeModVersion(this.serverModVersion()), MAX_MOD_VERSION_LENGTH);
      // 写入传送策略编号，越界处理交给接收方
      buffer.writeByte(this.teleportPolicy().wireId());
      // 写入「该玩家本人能否传送」标记
      buffer.writeBoolean(this.playerCanTeleport());
      // 写入世界缩放
      buffer.writeDouble(this.worldScale());
      // 写入「是否开启实验性提升高度」标记
      buffer.writeBoolean(this.experimentalHeight());
      // 写入「服务端镜像是否启用」标记
      buffer.writeBoolean(this.mirrorEnabled());
   }

   /** 返回本包的通道类型，供 Fabric 网络层路由 */
   @Override
   public PacketType<?> getType() {
      // 通道类型是常量，非空断言只是为了满足老 API 的可空签名
      return Objects.requireNonNull(TYPE, "TYPE");
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
