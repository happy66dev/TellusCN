/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * 每玩家动作冷却器（限流器）。
 *
 * 用途：给服务端收到的高频 C2S 网络包做「每玩家每动作」的最小间隔限制，防止改包客户端反复刷包
 *       推动服务端做昂贵操作（例如反复触发地形预下载规划）。
 *
 * 设计说明：
 *   1. **只依赖 JDK**，不引用任何 Minecraft / Fabric 类，因此可以直接单元测试（见 PlayerActionCooldownTest）；
 *   2. **时钟通过构造器注入**（LongSupplier），测试可以传一个可控的假时钟，无需 Thread.sleep；
 *   3. 借鉴 TellusRealtimeManager 的 Map&lt;UUID, Long&gt; 冷却范式，但**修掉了它的时钟回拨缺陷**：
 *      TellusRealtimeManager 在 now 变小（NTP 校时、系统改时间）时会把时间差算成负数而误判为「冷却中」，
 *      本类在 now &lt; last 时直接放行并刷新记录；
 *   4. **刻意不采用** OverpassRoadClient 那种「在网络线程上 Thread.sleep」的做法——那会阻塞线程，
 *      而且它是全局串行而非每玩家独立。
 */
public final class PlayerActionCooldown {

   /** 提供「当前毫秒时间戳」的时钟，单位：毫秒；注入是为了让测试可以精确控制时间流逝 */
   private final LongSupplier clockMs;

   /** 每个玩家最后一次被放行的时间戳，单位：毫秒；key 是玩家 UUID */
   private final ConcurrentMap<UUID, Long> lastAcceptedMs = new ConcurrentHashMap<>();

   /**
    * 构造一个冷却器。
    *
    * 输入：毫秒时钟。生产环境传 System::currentTimeMillis，测试传可控的假时钟。
    * 输出：冷却器实例。
    * 边界条件：clockMs 为 null 属于调用方编程错误，直接抛 NullPointerException 而不是静默容错，
    *          否则会变成「永远不冷却」这种极难排查的静默失效。
    */
   public PlayerActionCooldown(LongSupplier clockMs) {
      // 喵~防御：null 时钟会让所有判定静默失效，必须立刻暴露
      if (clockMs == null) {
         throw new NullPointerException("clockMs");
      }
      // 记录注入的时钟
      this.clockMs = clockMs;
   }

   /**
    * 尝试取得一次动作许可。
    *
    * 输入：玩家 UUID 与冷却时长（单位毫秒；0 或负数表示关闭冷却）。
    * 输出：允许执行返回 true，处于冷却中返回 false。
    * 边界条件：
    *   - playerId 为 null 时返回 false（拒绝），因为无法定位是哪个玩家，放行会给攻击者留后门；
    *   - cooldownMs &lt;= 0 时永远返回 true（冷却被关闭）；
    *   - 时钟回拨（now &lt; last）时放行并刷新记录，绝不把玩家永久锁死。
    */
   public boolean tryAcquire(UUID playerId, long cooldownMs) {
      // 喵~防御：拿不到玩家身份就无法按玩家限流，保守拒绝
      if (playerId == null) {
         return false;
      }
      // 冷却被关闭时直接放行，不写任何状态
      if (cooldownMs <= 0L) {
         return true;
      }
      // 取当前时间戳，单位毫秒
      long nowMs = this.clockMs.getAsLong();
      // 用长度 1 的数组把 compute 内的判定结果带出来
      boolean[] allowed = new boolean[1];
      // ConcurrentHashMap.compute 对同一个 key 是原子的，避免并发下重复放行
      this.lastAcceptedMs.compute(playerId, (key, lastAccepted) -> {
         // 首次调用、时钟回拨、或已过冷却期，都放行并刷新时间戳
         if (lastAccepted == null || nowMs < lastAccepted || nowMs - lastAccepted >= cooldownMs) {
            // 记录本次放行
            allowed[0] = true;
            // 刷新该玩家的最后放行时间
            return nowMs;
         }
         // 仍处于冷却期，拒绝并保留原有时间戳
         allowed[0] = false;
         return lastAccepted;
      });
      // 返回本次判定结果
      return allowed[0];
   }

   /**
    * 查询某玩家当前是否处于冷却中（只读，不消耗许可）。
    *
    * 输入：玩家 UUID 与冷却时长（单位毫秒）。
    * 输出：处于冷却中返回 true，否则 false。
    * 边界条件：playerId 为 null、冷却被关闭、从未记录过、时钟回拨，四种情况都返回 false。
    */
   public boolean isOnCooldown(UUID playerId, long cooldownMs) {
      // 喵~防御：没有玩家身份就无从判断，返回「不在冷却」
      if (playerId == null) {
         return false;
      }
      // 冷却关闭时永远不算冷却中
      if (cooldownMs <= 0L) {
         return false;
      }
      // 取出该玩家上次放行时间，从未记录过说明没冷却
      Long lastAccepted = this.lastAcceptedMs.get(playerId);
      // 没有记录就直接返回 false
      if (lastAccepted == null) {
         return false;
      }
      // 取当前时间戳，单位毫秒
      long nowMs = this.clockMs.getAsLong();
      // 喵~防御：时钟回拨时不算冷却，避免把玩家误锁
      if (nowMs < lastAccepted) {
         return false;
      }
      // 时间差小于冷却时长即为冷却中
      return nowMs - lastAccepted < cooldownMs;
   }

   /**
    * 遗忘某个玩家的冷却记录。
    *
    * 输入：玩家 UUID。
    * 输出：无返回值。
    * 边界条件：playerId 为 null 时静默返回；未记录过的玩家也静默返回。
    */
   public void release(UUID playerId) {
      // 喵~防御：null 直接跳过，避免 NPE
      if (playerId == null) {
         return;
      }
      // 移除该玩家的记录，玩家下次进来重新开始计时
      this.lastAcceptedMs.remove(playerId);
   }

   /**
    * 清空所有玩家的冷却记录。
    *
    * 输入：无。
    * 输出：无返回值。
    * 边界条件：无；服务端停止或重载时调用。
    */
   public void clear() {
      // 一次性清空全部记录
      this.lastAcceptedMs.clear();
   }

   /**
    * 当前记录了多少个玩家。
    *
    * 输入：无。
    * 输出：已记录玩家数，单位：个。
    * 边界条件：无；主要用于单元测试与诊断日志。
    *
    * 主人注意：本 Map 随玩家数增长，正常依赖 release() 在玩家断线时清理。
    *          若有玩家异常掉线导致 release 没被调用，条目会残留，但单个 UUID 只占几十字节，
    *          即使上千玩家也只是几十 KB，因此暂不额外做定期清扫——如果未来发现泄漏再加。
    */
   public int trackedPlayers() {
      // 返回当前 map 的条目数
      return this.lastAcceptedMs.size();
   }
}
