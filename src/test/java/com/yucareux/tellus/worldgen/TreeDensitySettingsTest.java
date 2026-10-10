// 本文件验证树木密度强度在世界设置里的默认值、编解码往返与夹取行为喵。
package com.yucareux.tellus.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EarthGeneratorSettings} 中 {@code treeDensity} 字段的行为测试喵。
 *
 * <p>测试范围：缺省存档回退到默认值 1.0、自定义值能原样往返、越界值在构造时被夹取、
 * 以及 withTreeDensity 的替换语义喵。</p>
 */
class TreeDensitySettingsTest {
   // 浮点比较容差，单位：无量纲比例；取 1e-9 足以覆盖编解码往返的舍入误差喵。
   private static final double EPSILON = 1.0E-9;

   /**
    * 旧存档没有 tree_density 字段时，必须回退到默认强度 1.0（按真实覆盖度稀疏）喵。
    */
   @Test
   void defaultsToNaturalDensityForUnmarkedWorlds() {
      // 用一个空 JSON 模拟不含该字段的旧存档喵。
      EarthGeneratorSettings decoded = requireSuccess(
         EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}"))
      );

      // 默认值必须是 1.0喵。
      assertEquals(1.0, EarthGeneratorSettings.DEFAULT.treeDensity(), EPSILON, "默认树木密度必须是 1.0");
      // 缺省存档解析出的值也必须回退到 1.0喵。
      assertEquals(1.0, decoded.treeDensity(), EPSILON, "缺省存档必须回退到默认强度");
   }

   /**
    * 自定义的强度值必须能原样写入并读回，保证玩家选择被存档记住喵。
    */
   @Test
   void customDensityRoundTrips() {
      // 构造一个带自定义强度（0.5）的存档并解析喵。
      EarthGeneratorSettings decoded = requireSuccess(
         EarthGeneratorSettings.CODEC.parse(
            JsonOps.INSTANCE,
            JsonParser.parseString("{\"tree_density\":0.5}")
         )
      );
      // 再把它编码回 JSON，检查往返一致喵。
      JsonElement encoded = requireSuccess(
         EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded)
      );
      // 取出编码后的对象喵。
      JsonObject encodedObject = encoded.getAsJsonObject();

      // 解析出的强度必须等于 0.5喵。
      assertEquals(0.5, decoded.treeDensity(), EPSILON, "自定义强度必须被原样解析");
      // 编码回去的字段也必须等于 0.5喵。
      assertEquals(0.5, encodedObject.get("tree_density").getAsDouble(), EPSILON, "自定义强度必须被原样编码");
   }

   /**
    * withTreeDensity 必须只替换强度字段，其余字段保持不变喵。
    */
   @Test
   void withTreeDensityReplacesOnlyThatField() {
      // 从默认设置出发，把强度替换成 2.0喵。
      EarthGeneratorSettings updated = EarthGeneratorSettings.DEFAULT.withTreeDensity(2.0);
      // 替换后的强度必须是 2.0喵。
      assertEquals(2.0, updated.treeDensity(), EPSILON, "withTreeDensity 必须替换强度");
      // 其余字段（这里抽查世界比例尺）必须与默认一致喵。
      assertEquals(
         EarthGeneratorSettings.DEFAULT.worldScale(), updated.worldScale(), EPSILON, "其余字段必须保持不变"
      );
   }

   /**
    * 构造时越界的强度必须被夹到 [0,2]，防止脏数据污染后续概率计算喵。
    */
   @Test
   void outOfRangeDensityIsClampedOnConstruction() {
      // 超过上限的强度必须被夹到 2.0喵。
      assertEquals(2.0, EarthGeneratorSettings.DEFAULT.withTreeDensity(5.0).treeDensity(), EPSILON, "超界强度必须被夹到 2.0");
      // 低于下限的强度必须被夹到 0.0喵。
      assertEquals(0.0, EarthGeneratorSettings.DEFAULT.withTreeDensity(-1.0).treeDensity(), EPSILON, "负强度必须被夹到 0.0");
   }

   /**
    * 把 DataResult 解包成具体值，失败时直接抛断言错误喵。
    *
    * @param result 待解包的解析结果喵
    * @param <T>    结果类型喵
    * @return 解包出的值喵
    */
   private static <T> T requireSuccess(DataResult<T> result) {
      // 失败时抛出断言错误，把编解码错误信息带出来喵。
      Optional<T> value = result.resultOrPartial(message -> {
         throw new AssertionError(message);
      });
      // 必须解析成功喵。
      assertTrue(value.isPresent());
      // 返回解包出的值喵。
      return value.get();
   }
}
