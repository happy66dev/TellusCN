// 本文件属于 Tellus 的树木生成模块，负责把"真实世界的树冠覆盖度"换算成"这一格要不要长树"的确定性判定喵。
package com.yucareux.tellus.worldgen.tree;

/**
 * 树木密度策略喵。
 *
 * <p>整体思路：真实世界的树冠覆盖度来自两路互相独立的栅格信号——
 * 一路是 ETH 冠层高度栅格（能看出"这里树冠有多高、是否有树冠"），
 * 另一路是 ESA WorldCover 土地覆盖栅格（能看出"这里是不是森林"）。
 * 单看任何一路都会误判，所以这里把两路按权重融合成一个 [0,1] 的密度值，
 * 再把它换算成"保留这个放置格的概率"喵。</p>
 *
 * <p>输入：两路信号各自的可用性与覆盖率（0 表示完全无覆盖，1 表示完全被树冠覆盖）喵。</p>
 * <p>输出：{@link Density}（融合后的密度）与 {@link #keepsTree}（本格是否保留）喵。</p>
 *
 * <p>边界条件与防御：</p>
 * <ul>
 *   <li>两路信号都不可用时返回 unavailable，调用方一律按"保留"处理，保证退化为改动前的旧行为喵；</li>
 *   <li>任何一路传入 NaN、无穷或越界值都会被夹到 [0,1]，绝不抛出异常喵；</li>
 *   <li>保留判定使用与格坐标、世界种子绑定的确定性哈希，保证同一个格在完整区块与远景 LOD 得到完全相同的结果喵。</li>
 * </ul>
 */
public final class TreeDensityPolicy {
   // 冠层栅格在融合时的权重，单位：无量纲比例；冠层高度是最直接的"这里有乔木"证据，所以权重更高喵。
   private static final double CANOPY_WEIGHT = 0.6;
   // 土地覆盖栅格在融合时的权重，单位：无量纲比例；它与冠层栅格权重之和必须为 1.0，保证融合结果仍是 [0,1]喵。
   private static final double WORLD_COVER_WEIGHT = 0.4;
   // 密度为 0 时仍然保留的最低概率，单位：无量纲比例；留一点底是为了避免把稀树草原直接剃成裸地喵。
   private static final double MINIMUM_KEEP_CHANCE = 0.15;
   // 密度为 1 时的保留概率，单位：无量纲比例；1.0 表示密林里每个放置格都保留喵。
   private static final double MAXIMUM_KEEP_CHANCE = 1.0;
   // 密度到保留概率的曲线指数，单位：无量纲；取大于 1 让"中等密度"偏稀疏，更接近真实疏林观感喵。
   private static final double KEEP_CHANCE_EXPONENT = 1.3;
   // 树木密度强度的中性值，单位：无量纲比例；1.0 表示完全按真实树冠覆盖度稀疏，不额外加减喵。
   private static final double NEUTRAL_MULTIPLIER = 1.0;
   // 树木密度强度的下限，单位：无量纲比例；0 表示把保留概率压到 0、完全不长树喵。
   private static final double MIN_MULTIPLIER = 0.0;
   // 树木密度强度的上限，单位：无量纲比例；2 表示把保留概率拉满到 1、保留全部树木（旧版观感）喵。
   private static final double MAX_MULTIPLIER = 2.0;
   // 保留判定所用随机数的盐值，单位：无量纲常数；换掉它会让全世界的树重新洗牌，非必要不要改喵。
   private static final long KEEP_ROLL_SALT = 0x5A17C3E9B4D208F7L;
   // splitmix64 的黄金比例增量常数，单位：无量纲常数；用于打散输入种子的低位规律喵。
   private static final long MIX_GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
   // splitmix64 的第一混合乘数，单位：无量纲常数喵。
   private static final long MIX_MULTIPLIER_A = 0xBF58476D1CE4E5B9L;
   // splitmix64 的第二混合乘数，单位：无量纲常数喵。
   private static final long MIX_MULTIPLIER_B = 0x94D049BB133111EBL;
   // 把 64 位哈希右移掉低位后，用 2^-53 缩放成 [0,1) 的浮点随机数所需的常量喵。
   private static final double DOUBLE_UNIT = 0x1.0p-53;

   // 工具类不允许被实例化，构造器私有化喵。
   private TreeDensityPolicy() {
   }

   /**
    * 融合后的树木密度喵。
    *
    * @param available 是否存在任何可用的真实数据信号；false 时调用方必须按旧行为处理喵
    * @param value     融合后的密度，单位：无量纲比例，取值范围 [0,1]；available 为 false 时该值无意义喵
    */
   public record Density(boolean available, double value) {
      /**
       * 构造一个"没有数据"的密度喵。
       *
       * @return 不可用的密度对象，value 固定为 0 以避免调用方误用喵
       */
      public static Density unavailable() {
         // 这里刻意把 value 设为 0，而不是 NaN，避免任何遗漏的调用方把 NaN 传播进几何计算喵。
         return new Density(false, 0.0);
      }

      /**
       * 用单个覆盖率构造一个可用密度喵。
       *
       * @param coverFraction 覆盖率，单位：无量纲比例；传入 NaN 或越界值会被夹到 [0,1]喵
       * @return 可用的密度对象喵
       */
      public static Density of(double coverFraction) {
         // 喵~防御：先把非法数值（NaN、无穷、越界）夹到合法区间，避免脏数据一路传播到概率计算喵。
         return new Density(true, clampUnit(coverFraction));
      }
   }

   /**
    * 把两路真实数据信号融合成一个密度喵。
    *
    * <p>融合规则：两路都可用时按权重加权；只有一路可用时该路权重独占，避免用 0 去"稀释"另一路；
    * 两路都不可用时返回 {@link Density#unavailable()}喵。</p>
    *
    * @param canopyAvailable    冠层高度栅格是否可用喵
    * @param canopyCoverFraction 冠层覆盖率，单位：无量纲比例；不可用时该参数会被忽略喵
    * @param worldCoverAvailable 土地覆盖栅格是否可用喵
    * @param worldCoverFraction  土地覆盖中的森林覆盖率，单位：无量纲比例；不可用时该参数会被忽略喵
    * @return 融合后的密度喵
    */
   public static Density fuse(
      boolean canopyAvailable,
      double canopyCoverFraction,
      boolean worldCoverAvailable,
      double worldCoverFraction
   ) {
      // 只有冠层可用时，直接采用冠层覆盖率，不让缺失的土地覆盖把结果拉低喵。
      if (canopyAvailable && !worldCoverAvailable) {
         return Density.of(canopyCoverFraction);
      }
      // 只有土地覆盖可用时，直接采用土地覆盖覆盖率，原因同上喵。
      if (worldCoverAvailable && !canopyAvailable) {
         return Density.of(worldCoverFraction);
      }
      // 两路都不可用时返回不可用，交给调用方走"保留全部"的旧行为喵。
      if (!canopyAvailable) {
         return Density.unavailable();
      }

      // 喵~防御：先把两路输入都夹到 [0,1]，即使上游给了脏数据，加权结果也一定落在合法范围喵。
      double safeCanopyFraction = clampUnit(canopyCoverFraction);
      // 喵~防御：第二路输入同样夹到 [0,1]，保证融合结果不会越界喵。
      double safeWorldCoverFraction = clampUnit(worldCoverFraction);
      // 按权重加权求和；两路权重之和为 1.0，所以结果天然落在 [0,1]喵。
      double fusedDensity = safeCanopyFraction * CANOPY_WEIGHT + safeWorldCoverFraction * WORLD_COVER_WEIGHT;
      // 再次夹取，纯粹作为对权重常量被误改的兜底防护喵。
      return new Density(true, clampUnit(fusedDensity));
   }

   /**
    * 计算给定密度下每个放置格的保留概率喵。
    *
    * @param density 融合后的树木密度喵
    * @return 保留概率，单位：无量纲比例，取值范围 [MINIMUM_KEEP_CHANCE, 1.0]喵
    */
   public static double keepChance(Density density) {
      // 喵~防御：传入 null 时按"无数据"处理，返回 1.0 表示不做任何削减，绝不让空指针冒泡喵。
      if (density == null || !density.available()) {
         return MAXIMUM_KEEP_CHANCE;
      }
      // 喵~防御：把密度夹到 [0,1]，防止上游漏夹导致幂运算出现异常数值喵。
      double unitDensity = clampUnit(density.value());
      // 用幂曲线把密度映射成 0..1 的形状因子；指数大于 1，使中等密度更偏向稀疏喵。
      double shaped = Math.pow(unitDensity, KEEP_CHANCE_EXPONENT);
      // 在最低概率与最高概率之间按形状因子线性插值喵。
      double chance = MINIMUM_KEEP_CHANCE + (MAXIMUM_KEEP_CHANCE - MINIMUM_KEEP_CHANCE) * shaped;
      // 喵~防御：插值结果夹到 [0,1]，保证它可以安全地当作概率使用喵。
      return clampUnit(chance);
   }

   /**
    * 在自然密度的基础上，按玩家设定的强度倍率换算出最终的保留概率喵。
    *
    * <p>强度倍率语义：0 表示完全不长树；1 表示完全按真实树冠覆盖度稀疏（中性）；2 表示保留全部、等同旧版观感喵。</p>
    * <p>映射思路：以自然保留概率 base 为锚点，在 [0,1] 区间把倍率线性插值到两端——
    * 倍率在 [0,1] 之间时在"0 概率"与 base 之间插值；在 [1,2] 之间时在 base 与"1 概率"之间插值喵。</p>
    *
    * @param density    融合后的树木密度喵
    * @param multiplier 玩家设定的强度倍率，单位：无量纲比例；越界或 NaN 会被夹到 [0,2]，NaN 视为中性 1.0喵
    * @return 最终保留概率，单位：无量纲比例，取值范围 [0,1]喵
    */
   public static double effectiveKeepChance(Density density, double multiplier) {
      // 自然密度对应的保留概率；density 为 null 或不可用时 base 固定为 1.0（不削减）喵。
      double base = keepChance(density);
      // 喵~防御：NaN 无法参与比较，先回退到中性倍率 1.0，避免概率一路变成 NaN喵。
      double safeMultiplier = Double.isNaN(multiplier) ? NEUTRAL_MULTIPLIER : multiplier;
      // 喵~防御：把倍率夹到 [0,2]，防止极端输入把概率推出合法范围喵。
      if (safeMultiplier <= MIN_MULTIPLIER) {
         safeMultiplier = MIN_MULTIPLIER;
      } else if (safeMultiplier >= MAX_MULTIPLIER) {
         safeMultiplier = MAX_MULTIPLIER;
      }
      // 倍率不超过中性值时，在"0 概率"与自然概率之间按倍率线性插值（倍率越小越稀疏）喵。
      double chance;
      if (safeMultiplier <= NEUTRAL_MULTIPLIER) {
         chance = base * safeMultiplier;
      } else {
         // 倍率超过中性值时，在自然概率与"满概率 1.0"之间按超出部分线性插值（倍率越大越密）喵。
         chance = base + (1.0 - base) * (safeMultiplier - NEUTRAL_MULTIPLIER);
      }
      // 喵~防御：最终结果夹到 [0,1]，保证它可以安全地当作概率使用喵。
      return clampUnit(chance);
   }

   /**
    * 判定某个放置格是否保留一棵树喵（中性强度，等价于强度倍率为 1.0）喵。
    *
    * <p>同一个 (density, seed) 组合永远返回同一个结果，这是完整区块与远景 LOD 对齐的前提喵。</p>
    *
    * @param density 融合后的树木密度喵
    * @param seed    该放置格的确定性种子，来自锚点计算，必须与树形规划使用同一个种子喵
    * @return true 表示保留，false 表示该格不长树喵
    */
   public static boolean keepsTree(Density density, long seed) {
      // 不带强度的旧接口直接委托给中性倍率版本，保证历史行为与单测结果不变喵。
      return keepsTree(density, NEUTRAL_MULTIPLIER, seed);
   }

   /**
    * 判定某个放置格是否保留一棵树，并叠加玩家设定的强度倍率喵。
    *
    * <p>同一个 (density, multiplier, seed) 组合永远返回同一个结果，这是完整区块与远景 LOD 对齐的前提喵。</p>
    *
    * @param density    融合后的树木密度喵
    * @param multiplier 玩家设定的强度倍率，单位：无量纲比例，取值范围 [0,2]喵
    * @param seed       该放置格的确定性种子，来自锚点计算，必须与树形规划使用同一个种子喵
    * @return true 表示保留，false 表示该格不长树喵
    */
   public static boolean keepsTree(Density density, double multiplier, long seed) {
      // 先把自然密度与强度倍率合成最终保留概率喵。
      double chance = effectiveKeepChance(density, multiplier);
      // 概率不小于 1.0 时直接短路保留，省掉一次哈希计算喵。
      if (chance >= MAXIMUM_KEEP_CHANCE) {
         return true;
      }
      // 概率不大于 0 时直接短路丢弃，省掉一次哈希计算喵。
      if (chance <= 0.0) {
         return false;
      }

      // 用盐值把种子打散后再混合，避免相邻格的种子规律导致保留结果出现条纹喵。
      long mixedSeed = mixSeed(seed ^ KEEP_ROLL_SALT);
      // 取哈希的高 53 位并缩放成 [0,1) 的浮点随机数，与旧版的整数取模相比分布更均匀喵。
      double roll = (mixedSeed >>> 11) * DOUBLE_UNIT;
      // 随机数小于保留概率即保留该格喵。
      return roll < chance;
   }

   /**
    * 把任意浮点数夹到 [0,1]，并吃掉 NaN 与无穷喵。
    *
    * @param value 待夹取的数值，单位：无量纲比例喵
    * @return 落在 [0,1] 内的合法数值；输入为 NaN 时返回 0喵
    */
   static double clampUnit(double value) {
      // 喵~防御：NaN 无法参与任何比较，必须先单独拦下，否则后续 min/max 会一路返回 NaN喵。
      if (Double.isNaN(value)) {
         return 0.0;
      }
      // 喵~防御：负值（包含负无穷）统一夹到 0，表示"完全没有树冠覆盖"喵。
      if (value <= 0.0) {
         return 0.0;
      }
      // 喵~防御：大于 1 的值（包含正无穷）统一夹到 1，表示"完全被树冠覆盖"喵。
      if (value >= 1.0) {
         return 1.0;
      }
      // 落在合法区间内的原值直接返回喵。
      return value;
   }

   /**
    * splitmix64 风格的种子混合函数喵。
    *
    * <p>思路：先加黄金比例常数打破低位规律，再连续做两次"异或右移 + 乘法"把信息扩散到全部位，
    * 最后再做一次异或右移收尾。输入输出都是 64 位整数，无状态、纯函数喵。</p>
    *
    * @param value 待混合的种子值喵
    * @return 混合后的种子值，与输入无可见相关性喵
    */
   static long mixSeed(long value) {
      // 加上黄金比例增量，避免相邻输入的哈希在低位高度相似喵。
      long mixed = value + MIX_GOLDEN_GAMMA;
      // 第一次扩散：异或高位后乘上混合常数A喵。
      mixed = (mixed ^ (mixed >>> 30)) * MIX_MULTIPLIER_A;
      // 第二次扩散：再次异或高位后乘上混合常数B喵。
      mixed = (mixed ^ (mixed >>> 27)) * MIX_MULTIPLIER_B;
      // 收尾：再异或一次高位，让最高位也参与结果喵。
      return mixed ^ (mixed >>> 31);
   }
}
