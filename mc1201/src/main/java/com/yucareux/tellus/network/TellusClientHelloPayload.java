/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：客户端握手包（MC 1.20.1 老 API 版本）。
 *
 * 作用：与 shared/post-1201 下的新 API 版本语义完全相同——客户端进入游戏后上报自己的
 *       联机协议版本与模组版本，让服务端判断双端协议是否匹配。
 *
 * 说明：MC 1.20.1 用的是 Fabric API v1 的 FabricPacket / PacketType 老接口，
 *       与 1.21+ 的 CustomPacketPayload / StreamCodec 不兼容，因此必须单独实现一份。
 *       **两版的字段顺序必须严格一致**，否则同一个包在双端解码时字段会错位。
 */
public record TellusClientHelloPayload(int protocolVersion, String clientModVersion) implements FabricPacket {

   /** 模组版本字符串允许的最大长度，单位：字符；限长是为了防止改包客户端用超长字符串撑爆缓冲区 */
   private static final int MAX_MOD_VERSION_LENGTH = 32;

   /** 本包的通道类型标识，通道名取自共享常量以免手写字符串漂移 */
   public static final PacketType<TellusClientHelloPayload> TYPE =
      PacketType.create(Tellus.id(TellusProtocol.CHANNEL_CLIENT_HELLO), TellusClientHelloPayload::new);

   /**
    * 从网络缓冲区构造客户端握手包。
    *
    * 输入：网络缓冲区，字段顺序为 protocolVersion、clientModVersion。
    * 输出：解码后的客户端握手包。
    * 边界条件：字符串按上限 32 读取；本端发送前一定截断到 32，只有被篡改的畸形包才会超长，
    *          此时 readUtf 抛异常，由网络层按「非法包」断开该连接——这是预期行为。
    */
   public TellusClientHelloPayload(FriendlyByteBuf buffer) {
      // 依次读取两个字段，顺序必须与新 API 版本一致
      this(buffer.readVarInt(), buffer.readUtf(MAX_MOD_VERSION_LENGTH));
   }

   /** 紧凑构造器：在对象建立时就把版本字符串规整好，后续发送都不用再判空 */
   public TellusClientHelloPayload {
      // 喵~防御：null、超长、控制字符都会在发送时炸掉或撑爆缓冲，这里统一规整
      clientModVersion = normalizeModVersion(clientModVersion);
   }

   /** 把本包写进网络缓冲区，字段顺序必须与新 API 版本严格一致 */
   @Override
   public void write(FriendlyByteBuf buffer) {
      // 写入协议版本号，单位：无
      buffer.writeVarInt(this.protocolVersion());
      // 写入模组版本字符串，限长 32 个字符
      buffer.writeUtf(normalizeModVersion(this.clientModVersion()), MAX_MOD_VERSION_LENGTH);
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
}
