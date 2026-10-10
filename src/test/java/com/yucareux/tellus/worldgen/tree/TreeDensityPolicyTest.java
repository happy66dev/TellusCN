// 本文件是树木密度策略的单元测试，覆盖正常输入、边界值与非法输入喵。
package com.yucareux.tellus.worldgen.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.tree.TreeDensityPolicy.Density;
import org.junit.jupiter.api.Test;

/**
 * {@link TreeDensityPolicy} 的行为测试喵。
 *
 * <p>测试范围：正常密度、密度 0 与 1 的端点、NaN / 无穷 / 越界这些非法值、
 * 两路信号缺失时的回退、以及保留判定的确定性与单调性喵。</p>
 */
class TreeDensityPolicyTest {
   // 测试用浮点比较容差，单位：无量纲比例；取 1e-9 足以覆盖 pow 与乘加带来的舍入误差喵。
   private static final double EPSILON = 1.0E-9;

   /**
    * 两路信号都不可用时，必须返回"不可用"，让调用方走旧行为喵。
    */
   @Test
   void fuseReturnsUnavailableWhenBothSignalsMissing() {
      // 两路都标记为不可用喵。
      Density density = TreeDensityPolicy.fuse(false, 0.8, false, 0.9);
      // 结果必须标记为不可用喵。
      assertFalse(density.available(), "两路信号都缺失时必须返回不可用");
      // 不可用时数值固定为 0，避免调用方误用喵。
      assertEquals(0.0, density.value(), EPSILON, "不可用密度的数值必须是 0");
   }

   /**
    * 只有冠层一路可用时，结果必须等于冠层覆盖率本身，不能被缺失的另一路稀释喵。
    */
   @Test
   void fuseUsesCanopyAloneWhenWorldCoverMissing() {
      // 只提供冠层信号，土地覆盖标记为缺失喵。
      Density density = TreeDensityPolicy.fuse(true, 0.75, false, 0.0);
      // 结果必须可用喵。
      assertTrue(density.available(), "冠层可用时结果必须可用");
      // 数值必须等于冠层覆盖率，而不是被乘以权重喵。
      assertEquals(0.75, density.value(), EPSILON, "单路可用时不应被权重稀释");
   }

   /**
    * 只有土地覆盖一路可用时，结果必须等于土地覆盖覆盖率本身喵。
    */
   @Test
   void fuseUsesWorldCoverAloneWhenCanopyMissing() {
      // 只提供土地覆盖信号，冠层标记为缺失喵。
      Density density = TreeDensityPolicy.fuse(false, 0.0, true, 0.4);
      // 结果必须可用喵。
      assertTrue(density.available(), "土地覆盖可用时结果必须可用");
      // 数值必须等于土地覆盖覆盖率喵。
      assertEquals(0.4, density.value(), EPSILON, "单路可用时不应被权重稀释");
   }

   /**
    * 两路都可用时必须按 0.6 / 0.4 的权重加权喵。
    */
   @Test
   void fuseBlendsBothSignalsByWeight() {
      // 冠层 1.0、土地覆盖 0.0，期望结果是 0.6 * 1.0 + 0.4 * 0.0 = 0.6喵。
      Density density = TreeDensityPolicy.fuse(true, 1.0, true, 0.0);
      // 校验加权结果喵。
      assertEquals(0.6, density.value(), EPSILON, "两路可用时必须按权重加权");
   }

   /**
    * 零覆盖或无覆盖时，密度必须是 0 喵。
    */
   @Test
   void fuseHandlesZeroCoverage() {
      // 两路都报告完全无树冠覆盖喵。
      Density density = TreeDensityPolicy.fuse(true, 0.0, true, 0.0);
      // 结果必须可用喵。
      assertTrue(density.available(), "两路可用时结果必须可用");
      // 数值必须是 0喵。
      assertEquals(0.0, density.value(), EPSILON, "无覆盖时密度必须是 0");
   }

   /**
    * 完全覆盖时，密度必须是 1，并且保留概率必须是上限 1.0喵。
    */
   @Test
   void fuseSaturatesAtFullCoverage() {
      // 两路都报告完全被树冠覆盖喵。
      Density density = TreeDensityPolicy.fuse(true, 1.0, true, 1.0);
      // 数值必须是 1喵。
      assertEquals(1.0, density.value(), EPSILON, "满覆盖时密度必须是 1");
      // 满覆盖时保留概率必须是 1.0，即不做任何削减喵。
      assertEquals(1.0, TreeDensityPolicy.keepChance(density), EPSILON, "满覆盖时保留概率必须是 1");
   }

   /**
    * 非法数值（NaN、正负无穷、越界）必须被安静地夹到 [0,1]，而不是抛异常或传播 NaN喵。
    */
   @Test
   void fuseSanitisesIllegalValues() {
      // NaN 必须被当作 0 处理，且结果不能是 NaN喵。
      Density fromNaN = TreeDensityPolicy.fuse(true, Double.NaN, true, Double.NaN);
      // 结果必须可用喵。
      assertTrue(fromNaN.available(), "非法值不应导致不可用");
      // NaN 必须被吃掉，结果必须等于 0喵。
      assertEquals(0.0, fromNaN.value(), EPSILON, "NaN 必须被夹到 0");

      // 负无穷必须被夹到 0喵。
      Density fromNegativeInfinity = TreeDensityPolicy.fuse(true, Double.NEGATIVE_INFINITY, true, 0.0);
      // 校验下界夹取喵。
      assertEquals(0.0, fromNegativeInfinity.value(), EPSILON, "负无穷必须被夹到 0");

      // 正无穷必须被夹到 1，参与加权后结果应为 0.6喵。
      Density fromPositiveInfinity = TreeDensityPolicy.fuse(true, Double.POSITIVE_INFINITY, true, 0.0);
      // 校验上界夹取喵。
      assertEquals(0.6, fromPositiveInfinity.value(), EPSILON, "正无穷必须被夹到 1");

      // 大于 1 的超界值同样必须被夹到 1喵。
      Density fromOvershoot = TreeDensityPolicy.fuse(true, 42.0, true, 0.0);
      // 校验超界夹取喵。
      assertEquals(0.6, fromOvershoot.value(), EPSILON, "超界值必须被夹到 1");
   }

   /**
    * 保留概率必须落在 [0.15, 1.0] 区间内，并且随密度单调不减喵。
    */
   @Test
   void keepChanceIsBoundedAndMonotonic() {
      // 从 0 到 1 以 0.1 为步长遍历密度，检查区间与单调性喵。
      double previousChance = -1.0;
      for (int step = 0; step <= 10; step++) {
         // 当前遍历到的密度值，单位：无量纲比例喵。
         double densityValue = step / 10.0;
         // 换算成当前保留概率喵。
         double chance = TreeDensityPolicy.keepChance(Density.of(densityValue));
         // 概率必须不小于下限喵。
         assertTrue(chance >= 0.15 - EPSILON, "保留概率不得低于下限 0.15，实际=" + chance);
         // 概率必须不大于上限喵。
         assertTrue(chance <= 1.0 + EPSILON, "保留概率不得高于上限 1.0，实际=" + chance);
         // 概率必须随密度单调不减喵。
         assertTrue(chance >= previousChance - EPSILON, "保留概率必须随密度单调不减");
         // 记录本次概率，供下一轮比较喵。
         previousChance = chance;
      }
   }

   /**
    * 传入 null 密度时，保留概率必须是 1.0，判定必须为保留，绝不抛空指针喵。
    */
   @Test
   void nullDensityFallsBackToKeepEverything() {
      // 喵~防御：null 必须被当作无数据处理喵。
      assertEquals(1.0, TreeDensityPolicy.keepChance(null), EPSILON, "null 密度的保留概率必须是 1");
      // null 密度下必须保留该格喵。
      assertTrue(TreeDensityPolicy.keepsTree(null, 12345L), "null 密度必须保留该格");
   }

   /**
    * 不可用密度下必须保留该格，保证开关打开时无覆盖区域行为与旧版一致喵。
    */
   @Test
   void unavailableDensityKeepsEveryCell() {
      // 构造一个不可用密度喵。
      Density unavailable = Density.unavailable();
      // 用同一个种子重复判定多次，必须始终为保留喵。
      for (int index = 0; index < 64; index++) {
         // 每次换一个种子，覆盖面更广喵。
         assertTrue(TreeDensityPolicy.keepsTree(unavailable, index), "不可用密度必须保留该格");
      }
   }

   /**
    * 密度为 1 时必须保留该格，不得被随机数误杀喵。
    */
   @Test
   void fullDensityKeepsEveryCell() {
      // 满覆盖密度的保留概率是 1.0，任何随机数都应该通过喵。
      Density full = TreeDensityPolicy.fuse(true, 1.0, true, 1.0);
      // 用多个种子重复判定喵。
      for (int index = 0; index < 64; index++) {
         // 满密度下必须始终保留喵。
         assertTrue(TreeDensityPolicy.keepsTree(full, index), "满密度必须保留该格");
      }
   }

   /**
    * 同一个 (密度, 种子) 组合必须永远得到同一个结果，否则完整区块与远景 LOD 会错位喵。
    */
   @Test
   void keepsTreeIsDeterministic() {
      // 取一个中等密度，让保留概率既不是 0 也不是 1，判定才有随机性可言喵。
      Density medium = Density.of(0.5);
      // 固定一个种子喵。
      long seed = 0x0123456789ABCDEFL;
      // 记录第一次判定结果喵。
      boolean firstResult = TreeDensityPolicy.keepsTree(medium, seed);
      // 重复判定 32 次，结果必须完全一致喵。
      for (int index = 0; index < 32; index++) {
         // 每次都必须与第一次相同喵。
         assertEquals(firstResult, TreeDensityPolicy.keepsTree(medium, seed), "同一 (密度, 种子) 的判定必须可复现");
      }
   }

   /**
    * 中等密度下，大量种子的保留比例应该接近理论概率（允许统计误差）喵。
    */
   @Test
   void keepsTreeRespectsKeepChanceOnAverage() {
      // 取一个中等密度，理论保留概率约为 0.5 左右喵。
      Density medium = Density.of(0.5);
      // 理论保留概率，单位：无量纲比例喵。
      double expectedChance = TreeDensityPolicy.keepChance(medium);
      // 统计实际保留次数喵。
      int keptCount = 0;
      // 总采样次数，取 20000 次让统计误差足够小喵。
      int sampleCount = 20000;
      for (int index = 0; index < sampleCount; index++) {
         // 逐个种子判定喵。
         if (TreeDensityPolicy.keepsTree(medium, index)) {
            // 命中则计数喵。
            keptCount++;
         }
      }
      // 实际保留比例，单位：无量纲比例喵。
      double actualChance = (double)keptCount / sampleCount;
      // 允许 ±0.02 的统计误差喵。
      assertTrue(
         Math.abs(actualChance - expectedChance) < 0.02,
         "实际保留比例 " + actualChance + " 应接近理论概率 " + expectedChance
      );
   }

   /**
    * 低密度与高密度的保留数量必须有明显差异，否则密度信号就是无效的喵。
    */
   @Test
   void lowerDensityKeepsFewerTrees() {
      // 稀疏森林密度，单位：无量纲比例喵。
      Density sparse = Density.of(0.2);
      // 茂密森林密度，单位：无量纲比例喵。
      Density dense = Density.of(0.9);
      // 稀疏场景的保留计数喵。
      int sparseKept = 0;
      // 茂密场景的保留计数喵。
      int denseKept = 0;
      // 两组各采样 5000 次喵。
      for (int index = 0; index < 5000; index++) {
         // 统计稀疏密度下的保留次数喵。
         if (TreeDensityPolicy.keepsTree(sparse, index)) {
            sparseKept++;
         }
         // 统计茂密密度下的保留次数喵。
         if (TreeDensityPolicy.keepsTree(dense, index)) {
            denseKept++;
         }
      }
      // 茂密场景必须明显保留更多树喵。
      assertTrue(denseKept > sparseKept, "高密度必须比低密度保留更多树");
      // 两者的差距必须足够大，避免策略形同虚设喵。
      assertNotEquals(sparseKept, denseKept, "高低密度的保留数量必须不同");
   }

   /**
    * splitmix64 混合函数必须是纯函数，并且对相邻输入给出不相关的输出喵。
    */
   @Test
   void mixSeedIsPureAndAvalanches() {
      // 同一个输入重复混合必须得到同一个输出喵。
      assertEquals(TreeDensityPolicy.mixSeed(7L), TreeDensityPolicy.mixSeed(7L), "混合函数必须是纯函数");
      // 相邻输入（7 与 8）的输出必须不同，否则低位规律会残留喵。
      assertNotEquals(TreeDensityPolicy.mixSeed(7L), TreeDensityPolicy.mixSeed(8L), "相邻输入必须产生不同输出");
      // 只差最高位一比特的输入，输出也必须不同，说明高位参与了扩散喵。
      assertNotEquals(
         TreeDensityPolicy.mixSeed(0L), TreeDensityPolicy.mixSeed(Long.MIN_VALUE), "高位变化必须影响输出"
      );
   }

   /**
    * 夹取函数的边界行为喵。
    */
   @Test
   void clampUnitCoversBoundaries() {
      // NaN 必须落到 0喵。
      assertEquals(0.0, TreeDensityPolicy.clampUnit(Double.NaN), EPSILON, "NaN 必须夹到 0");
      // 负数必须落到 0喵。
      assertEquals(0.0, TreeDensityPolicy.clampUnit(-3.5), EPSILON, "负数必须夹到 0");
      // 恰好 0 必须保持 0喵。
      assertEquals(0.0, TreeDensityPolicy.clampUnit(0.0), EPSILON, "0 必须保持 0");
      // 区间内数值必须原样返回喵。
      assertEquals(0.42, TreeDensityPolicy.clampUnit(0.42), EPSILON, "区间内数值必须原样返回");
      // 恰好 1 必须保持 1喵。
      assertEquals(1.0, TreeDensityPolicy.clampUnit(1.0), EPSILON, "1 必须保持 1");
      // 超过 1 的数值必须夹到 1喵。
      assertEquals(1.0, TreeDensityPolicy.clampUnit(9.9), EPSILON, "超过 1 的数值必须夹到 1");
      // 正无穷必须夹到 1喵。
      assertEquals(1.0, TreeDensityPolicy.clampUnit(Double.POSITIVE_INFINITY), EPSILON, "正无穷必须夹到 1");
      // 负无穷必须夹到 0喵。
      assertEquals(0.0, TreeDensityPolicy.clampUnit(Double.NEGATIVE_INFINITY), EPSILON, "负无穷必须夹到 0");
   }

   /**
    * 中性倍率 1.0 必须恰好等于自然保留概率，保证默认滑条位置不改变观感喵。
    */
   @Test
   void effectiveKeepChanceAtNeutralEqualsNaturalChance() {
      // 取一个中等密度，自然概率既不是 0 也不是 1喵。
      Density medium = Density.of(0.5);
      // 倍率 1.0 下的有效概率必须等于不带倍率的自然概率喵。
      assertEquals(
         TreeDensityPolicy.keepChance(medium),
         TreeDensityPolicy.effectiveKeepChance(medium, 1.0),
         EPSILON,
         "中性倍率必须等于自然保留概率"
      );
   }

   /**
    * 倍率 0 必须把保留概率压到 0（完全不长树），即使自然概率很高也一样喵。
    */
   @Test
   void effectiveKeepChanceAtZeroMultiplierIsZero() {
      // 高密度自然概率接近 1，但倍率 0 必须把它压到 0喵。
      assertEquals(0.0, TreeDensityPolicy.effectiveKeepChance(Density.of(0.95), 0.0), EPSILON, "倍率 0 必须让保留概率归零");
   }

   /**
    * 倍率 2 必须把保留概率拉满到 1（保留全部），即使自然概率很低也一样喵。
    */
   @Test
   void effectiveKeepChanceAtMaxMultiplierIsOne() {
      // 低密度自然概率很低，但倍率 2 必须把它拉满到 1喵。
      assertEquals(1.0, TreeDensityPolicy.effectiveKeepChance(Density.of(0.05), 2.0), EPSILON, "倍率 2 必须让保留概率拉满");
   }

   /**
    * 非法倍率（NaN、负数、超过 2）必须被安静地处理，绝不产生越界概率或 NaN喵。
    */
   @Test
   void effectiveKeepChanceClampsIllegalMultipliers() {
      // 取一个中等密度做基准喵。
      Density medium = Density.of(0.5);
      // NaN 倍率必须回退到中性 1.0，有效概率等于自然概率喵。
      assertEquals(
         TreeDensityPolicy.keepChance(medium),
         TreeDensityPolicy.effectiveKeepChance(medium, Double.NaN),
         EPSILON,
         "NaN 倍率必须回退到中性值"
      );
      // 负倍率必须被夹到 0，有效概率归零喵。
      assertEquals(0.0, TreeDensityPolicy.effectiveKeepChance(medium, -5.0), EPSILON, "负倍率必须被夹到 0");
      // 超过 2 的倍率必须被夹到 2，有效概率拉满喵。
      assertEquals(1.0, TreeDensityPolicy.effectiveKeepChance(medium, 99.0), EPSILON, "超界倍率必须被夹到 2");
   }

   /**
    * 有效保留概率必须随倍率单调不减，这样滑条拉大一定不会让树变少喵。
    */
   @Test
   void effectiveKeepChanceIsMonotonicInMultiplier() {
      // 取一个中等密度做基准喵。
      Density medium = Density.of(0.5);
      // 记录上一次的有效概率，初始取一个不可能的负值喵。
      double previousChance = -1.0;
      // 从倍率 0 到 2 以 0.1 为步长遍历喵。
      for (int step = 0; step <= 20; step++) {
         // 当前倍率，单位：无量纲比例喵。
         double multiplier = step / 10.0;
         // 当前有效保留概率喵。
         double chance = TreeDensityPolicy.effectiveKeepChance(medium, multiplier);
         // 概率必须随倍率单调不减喵。
         assertTrue(chance >= previousChance - EPSILON, "有效概率必须随倍率单调不减，倍率=" + multiplier);
         // 记录本次概率供下一轮比较喵。
         previousChance = chance;
      }
   }

   /**
    * 不带倍率的旧接口必须与倍率 1.0 的新接口逐种子一致，保证历史行为不变喵。
    */
   @Test
   void legacyKeepsTreeMatchesNeutralMultiplier() {
      // 取一个中等密度，判定才有随机性可言喵。
      Density medium = Density.of(0.5);
      // 遍历大量种子，两个接口的结果必须完全一致喵。
      for (int index = 0; index < 2000; index++) {
         // 旧接口与倍率 1.0 的新接口必须对每个种子都给出相同结果喵。
         assertEquals(
            TreeDensityPolicy.keepsTree(medium, index),
            TreeDensityPolicy.keepsTree(medium, 1.0, index),
            "旧接口必须等价于中性倍率"
         );
      }
   }

   /**
    * 倍率 0 必须丢弃每一个格子，倍率 2 必须保留每一个格子喵。
    */
   @Test
   void keepsTreeHonoursExtremeMultipliers() {
      // 取一个中等密度做基准喵。
      Density medium = Density.of(0.5);
      // 遍历多个种子验证两端行为喵。
      for (int index = 0; index < 256; index++) {
         // 倍率 0 时必须丢弃该格喵。
         assertFalse(TreeDensityPolicy.keepsTree(medium, 0.0, index), "倍率 0 必须丢弃每一个格子");
         // 倍率 2 时必须保留该格喵。
         assertTrue(TreeDensityPolicy.keepsTree(medium, 2.0, index), "倍率 2 必须保留每一个格子");
      }
   }
}
