/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.network;

import java.util.Locale;

/**
 * 服务端传送（geotp）权限策略。
 *
 * 作用：把原先写死在 TellusCommon 里的「level 2 才能传送」变成一个可由服主配置的三档策略，
 *       并同时用于三处：服务端校验、命令注册的 requires 判定、以及握手包下发给客户端做 UI 提示。
 *
 * 说明：每个枚举值自带一个**显式的 wireId / 配置名**，而不是直接用 ordinal()。
 *       这样以后即使调整枚举声明顺序，网络协议与配置文件都不会被悄悄改掉。
 */
public enum TellusTeleportPolicy {

   /** 完全禁用传送：连 OP 也不能用，地图入口与传送请求都会被拒绝 */
   DISABLED(0, "disabled"),
   /** 仅 OP 可用：与 TellusCN 改造前的既有行为一致，也是默认值 */
   OP_ONLY(1, "op_only"),
   /** 所有人可用：任何玩家都能打开地图并传送 */
   EVERYONE(2, "everyone");

   /** 网络传输用的数值编号，单位：无；写入握手包时使用 */
   private final int wireId;
   /** 配置文件里使用的字符串名，单位：无；形如 op_only */
   private final String configName;

   /** 构造一个策略枚举值 */
   TellusTeleportPolicy(int wireId, String configName) {
      // 记录网络编号，写入握手包时使用
      this.wireId = wireId;
      // 记录配置名，读写 properties 文件时使用
      this.configName = configName;
   }

   /** 取网络传输用的数值编号，单位：无 */
   public int wireId() {
      // 直接返回构造时固定的编号，避免依赖 ordinal() 的稳定性
      return this.wireId;
   }

   /** 取配置文件里使用的字符串名，单位：无 */
   public String configName() {
      // 直接返回构造时固定的名字，保证配置文件内容稳定可读
      return this.configName;
   }

   /**
    * 把网络收到的数值编号还原成策略枚举。
    *
    * 输入：网络包里的 int 值（可能来自改包客户端，任意取值都可能）。
    * 输出：匹配的策略；越界时回退到最保守的 {@link #DISABLED}。
    * 边界条件：故意越界时选择 DISABLED 而不是 OP_ONLY，因为「未知策略」时收紧权限比放开更安全。
    */
   public static TellusTeleportPolicy fromWireId(int wireId) {
      // 逐个比对显式编号，找到匹配项就返回
      for (TellusTeleportPolicy policy : values()) {
         if (policy.wireId == wireId) {
            return policy;
         }
      }
      // 喵~防御：未知编号说明对端协议不一致或被人为篡改，一律按「最严格」处理
      return DISABLED;
   }

   /**
    * 把配置文件里的字符串还原成策略枚举。
    *
    * 输入：配置文件中的原始字符串，可能为 null、空串、大小写混杂或拼写错误。
    * 输出：匹配的策略；无法识别时回退到默认的 {@link #OP_ONLY}。
    * 边界条件：回退用 OP_ONLY 而不是 DISABLED，是因为配置文件写错属于服主人为失误，
    *          用「与历史行为一致」的默认值比让服主突然发现传送全废更友好。
    */
   public static TellusTeleportPolicy fromConfigName(String rawName) {
      // 喵~防御：null 或空白直接按默认策略处理
      if (rawName == null || rawName.isBlank()) {
         return OP_ONLY;
      }
      // 统一转小写并去掉首尾空白，容忍 op_only / OP_ONLY / " op_only " 这类写法
      String normalizedName = rawName.trim().toLowerCase(Locale.ROOT);
      // 逐个比对配置名，找到匹配项就返回
      for (TellusTeleportPolicy policy : values()) {
         if (policy.configName.equals(normalizedName)) {
            return policy;
         }
      }
      // 拼写无法识别时回退到默认策略，避免服务端因一个错别字而拒绝启动
      return OP_ONLY;
   }
}
