package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TellusVanillaNoiseCaveSamplerTest {
   @BeforeAll
   static void bootstrapMinecraft() {
      Bootstrap.bootStrap();
   }

   @Test
   void preservesEveryVanillaLargeOreVeinMaterial() {
      assertPreserved(Blocks.COPPER_ORE);
      assertPreserved(Blocks.RAW_COPPER_BLOCK);
      assertPreserved(Blocks.GRANITE);
      assertPreserved(Blocks.DEEPSLATE_IRON_ORE);
      assertPreserved(Blocks.RAW_IRON_BLOCK);
      assertPreserved(Blocks.TUFF);
   }

   @Test
   void rejectsOrdinaryNoiseTerrain() {
      assertNull(TellusVanillaNoiseCaveSampler.oreVeinReplacement(Blocks.STONE.defaultBlockState()));
      assertNull(TellusVanillaNoiseCaveSampler.oreVeinReplacement(Blocks.DEEPSLATE.defaultBlockState()));
      assertNull(TellusVanillaNoiseCaveSampler.oreVeinReplacement(Blocks.IRON_ORE.defaultBlockState()));
   }

   @Test
   void onlyExposesConfirmedSurfaceEntranceGaps() {
      assertEquals(80, TellusVanillaNoiseCaveSampler.surfaceReferenceY(80, 100, false));
      assertEquals(80, TellusVanillaNoiseCaveSampler.surfaceReferenceY(80, 84, true));
      assertEquals(85, TellusVanillaNoiseCaveSampler.surfaceReferenceY(80, 85, true));
   }

   private static void assertPreserved(Block block) {
      BlockState state = block.defaultBlockState();
      assertSame(state, TellusVanillaNoiseCaveSampler.oreVeinReplacement(state));
   }
}
