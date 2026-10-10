/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 *
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.server;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlayerActionCooldown 单元测试
 *
 * 覆盖场景：首次放行、冷却内拒绝、冷却边界、关闭冷却（0 / 负数）、时钟回拨、
 * 手动 release、玩家之间互不影响、null 玩家、null 时钟、clear。
 * 该测试只依赖 JDK 与 JUnit，不依赖 Minecraft / Fabric 类，也不依赖真实时间（时钟是注入的假时钟），
 * 因此可以在无游戏、无网络环境下直接运行，且不受机器快慢影响。
 */
class PlayerActionCooldownTest {

   /** 冷却时长，单位：毫秒；取一个方便断言边界的值 */
   private static final long COOLDOWN_MS = 1000L;

   /**
    * 首次调用应当放行。
    */
   @Test
   void firstAcquireIsAllowed() {
      // 可变假时钟，初值 10_000 毫秒
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 首次申请应当被放行
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
   }

   /**
    * 冷却期内再次调用应当被拒绝。
    */
   @Test
   void secondAcquireWithinCooldownIsRejected() {
      // 可变假时钟
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 第一次放行
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
      // 时间只推进 999 毫秒，仍在冷却期内
      nowMs[0] += COOLDOWN_MS - 1L;
      // 第二次应当被拒绝
      assertFalse(cooldown.tryAcquire(playerId, COOLDOWN_MS));
   }

   /**
    * 刚好达到冷却边界时应当放行（闭区间语义：now - last >= cooldownMs）。
    */
   @Test
   void acquireAtExactCooldownBoundaryIsAllowed() {
      // 可变假时钟
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 第一次放行
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
      // 时间刚好推进到冷却边界
      nowMs[0] += COOLDOWN_MS;
      // 边界时刻应当放行
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
   }

   /**
    * 冷却值为 0 表示关闭冷却，每次都应当放行。
    */
   @Test
   void zeroCooldownAlwaysAllows() {
      // 假时钟固定不动，证明放行与时间无关
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 连续申请三次都应当放行
      assertTrue(cooldown.tryAcquire(playerId, 0L));
      assertTrue(cooldown.tryAcquire(playerId, 0L));
      assertTrue(cooldown.tryAcquire(playerId, 0L));
   }

   /**
    * 冷却值为负数同样表示关闭冷却（而不是「永久冷却」）。
    */
   @Test
   void negativeCooldownAlwaysAllows() {
      // 假时钟固定不动
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 负数冷却应当被当成「关闭冷却」处理
      assertTrue(cooldown.tryAcquire(playerId, -5_000L));
      assertTrue(cooldown.tryAcquire(playerId, -5_000L));
   }

   /**
    * 时钟回拨（例如 NTP 校时把系统时间调早）时应当放行并刷新记录，
    * 而不是算出负的时间差把玩家永久锁在冷却里。
    */
   @Test
   void clockRollbackAllowsInsteadOfLockingPlayerOut() {
      // 可变假时钟
      long[] nowMs = {50_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 第一次放行，记录 50_000
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
      // 模拟时钟被调早 40 秒
      nowMs[0] = 10_000L;
      // 回拨后应当放行，而不是被判定成「还在冷却」
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
   }

   /**
    * release 之后立刻可以再次申请，模拟玩家断线重连。
    */
   @Test
   void releaseAllowsImmediateReacquire() {
      // 假时钟固定不动，证明放行来自 release 而不是时间流逝
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 第一次放行
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
      // 冷却期内会被拒绝
      assertFalse(cooldown.tryAcquire(playerId, COOLDOWN_MS));
      // 模拟断线清理
      cooldown.release(playerId);
      // 清理后应当立刻可以再次申请
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
   }

   /**
    * 不同玩家之间互不影响。
    */
   @Test
   void differentPlayersAreIndependent() {
      // 假时钟固定不动
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 两个不同的玩家身份
      UUID firstPlayer = UUID.randomUUID();
      UUID secondPlayer = UUID.randomUUID();

      // 第一个玩家用掉许可
      assertTrue(cooldown.tryAcquire(firstPlayer, COOLDOWN_MS));
      // 第二个玩家不受影响，应当照常放行
      assertTrue(cooldown.tryAcquire(secondPlayer, COOLDOWN_MS));
   }

   /**
    * playerId 为 null 时应当保守拒绝，避免给「无身份」请求留后门。
    */
   @Test
   void nullPlayerIsRejected() {
      // 假时钟固定不动
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);

      // 喵~防御：拿不到玩家身份就无法按玩家限流，必须是拒绝
      assertFalse(cooldown.tryAcquire(null, COOLDOWN_MS));
      // 只读查询同样应当是「不在冷却」，避免调用方误判
      assertFalse(cooldown.isOnCooldown(null, COOLDOWN_MS));
   }

   /**
    * 时钟为 null 属于调用方编程错误，应当立刻抛异常而不是静默失效。
    */
   @Test
   void nullClockIsRejected() {
      // 构造时传 null 时钟应当抛空指针
      assertThrows(NullPointerException.class, () -> new PlayerActionCooldown(null));
   }

   /**
    * isOnCooldown 应当如实反映当前状态，且自身不会消耗许可。
    */
   @Test
   void isOnCooldownReflectsStateWithoutConsuming() {
      // 可变假时钟
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);
      // 玩家身份
      UUID playerId = UUID.randomUUID();

      // 还没有任何记录时不算冷却中
      assertFalse(cooldown.isOnCooldown(playerId, COOLDOWN_MS));
      // 申请一次许可
      assertTrue(cooldown.tryAcquire(playerId, COOLDOWN_MS));
      // 现在应当处于冷却中
      assertTrue(cooldown.isOnCooldown(playerId, COOLDOWN_MS));
      // 连续查询不会消耗许可，状态保持不变
      assertTrue(cooldown.isOnCooldown(playerId, COOLDOWN_MS));
      // 时间推进过冷却期后应当不再是冷却中
      nowMs[0] += COOLDOWN_MS;
      assertFalse(cooldown.isOnCooldown(playerId, COOLDOWN_MS));
   }

   /**
    * clear 应当清空所有玩家记录。
    */
   @Test
   void clearRemovesEveryPlayersRecord() {
      // 假时钟固定不动
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);

      // 三个玩家各自申请一次许可
      assertTrue(cooldown.tryAcquire(UUID.randomUUID(), COOLDOWN_MS));
      assertTrue(cooldown.tryAcquire(UUID.randomUUID(), COOLDOWN_MS));
      assertTrue(cooldown.tryAcquire(UUID.randomUUID(), COOLDOWN_MS));
      // 确认记录数为 3
      assertEquals(3, cooldown.trackedPlayers());
      // 清空全部记录
      cooldown.clear();
      // 清空后记录数应当为 0
      assertEquals(0, cooldown.trackedPlayers());
   }

   /**
    * release 对 null 玩家与未记录过的玩家都应当静默处理，不抛异常。
    */
   @Test
   void releaseIsSilentForUnknownOrNullPlayer() {
      // 假时钟固定不动
      long[] nowMs = {10_000L};
      // 用假时钟构造冷却器
      PlayerActionCooldown cooldown = new PlayerActionCooldown(() -> nowMs[0]);

      // 喵~防御：这两种调用都不应该抛异常
      cooldown.release(null);
      cooldown.release(UUID.randomUUID());
      // 记录数应保持为 0
      assertEquals(0, cooldown.trackedPlayers());
   }
}
