/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：客户端握手包。
 *
 * 作用：在玩家进入游戏后，客户端主动上报自己的联机协议版本与模组版本，
 *       让服务端能够判断双端协议是否匹配；不匹配时服务端会直接断开该玩家。
 *
 * 说明：本包只承载两个字段，字段顺序一旦发布就不能改动，新增信息只能追加到末尾。
 *       本文件是「MC 1.21.1 及之后」的新 API 版本；MC 1.20.1 另有一份老 API 实现在
 *       mc1201/src/main/java 下，两版的字段顺序必须严格一致。
 */
public record TellusClientHelloPayload(int protocolVersion, String clientModVersion) implements CustomPacketPayload {

   /** 模组版本字符串允许的最大长度，单位：字符；限长是为了防止改包客户端用超长字符串撑爆缓冲区 */
   private static final int MAX_MOD_VERSION_LENGTH = 32;

   /** 本包的通道类型标识，通道名取自共享常量以免手写字符串漂移 */
   public static final CustomPacketPayload.Type<TellusClientHelloPayload> TYPE =
      new CustomPacketPayload.Type<>(Tellus.id(TellusProtocol.CHANNEL_CLIENT_HELLO));

   /** 本包的编解码器：字段顺序必须与 MC 1.20.1 老 API 版本严格一致 */
   public static final StreamCodec<FriendlyByteBuf, TellusClientHelloPayload> CODEC =
      new StreamCodec<FriendlyByteBuf, TellusClientHelloPayload>() {
         /** 从网络缓冲区解出客户端握手包 */
         @Override
         public TellusClientHelloPayload decode(FriendlyByteBuf buffer) {
            // 委托给静态方法，保持解码逻辑单一来源
            return read(buffer);
         }

         /** 把客户端握手包写进网络缓冲区 */
         @Override
         public void encode(FriendlyByteBuf buffer, TellusClientHelloPayload value) {
            // 委托给静态方法，保持编码逻辑单一来源
            write(buffer, value);
         }
      };

   /** 紧凑构造器：在对象建立时就把版本字符串规整好，后续读写都不用再判空 */
   public TellusClientHelloPayload {
      // 喵~防御：null、超长、控制字符都会在发送时炸掉或撑爆缓冲，这里统一规整
      clientModVersion = normalizeModVersion(clientModVersion);
   }

   /** 返回本包的通道类型，供 Fabric 网络层路由 */
   @Override
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      // 通道类型是常量，直接返回
      return TYPE;
   }

   /**
    * 从网络缓冲区读取客户端握手包。
    *
    * 输入：网络缓冲区，字段顺序为 protocolVersion、clientModVersion。
    * 输出：解码后的客户端握手包。
    * 边界条件：字符串按上限 32 读取；本端发送前一定截断到 32，所以只有被篡改的畸形包才会超长，
    *          此时 readUtf 抛 DecoderException，由网络层按「非法包」断开该连接——这是预期行为。
    */
   static TellusClientHelloPayload read(FriendlyByteBuf buffer) {
      // 先读协议版本号，单位：无
      int protocolVersion = buffer.readVarInt();
      // 再读模组版本字符串，限长 32 个字符
      String clientModVersion = buffer.readUtf(MAX_MOD_VERSION_LENGTH);
      // 组装成不可变记录
      return new TellusClientHelloPayload(protocolVersion, clientModVersion);
   }

   /**
    * 把客户端握手包写入网络缓冲区。
    *
    * 输入：网络缓冲区与待写入的包。
    * 输出：无返回值，缓冲区被追加写入。
    * 边界条件：写入前再次规整字符串，保证长度不超过 writeUtf 的上限。
    */
   static void write(FriendlyByteBuf buffer, TellusClientHelloPayload payload) {
      // 写入协议版本号，单位：无
      buffer.writeVarInt(payload.protocolVersion());
      // 写入模组版本字符串，限长 32 个字符
      buffer.writeUtf(normalizeModVersion(payload.clientModVersion()), MAX_MOD_VERSION_LENGTH);
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
