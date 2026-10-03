package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.UndergroundGenerationDepthPolicy;
import com.yucareux.tellus.worldgen.caves.TellusCaveDepthMapper;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla underground placed features sample a fixed absolute Y profile. In a
 * Tellus terrain shell that profile may be far from the local underground
 * range. Sample it against a fixed vanilla surface and project the result into
 * Tellus's fixed 64-block generation band instead.
 */
@Mixin(HeightRangePlacement.class)
public abstract class HeightRangePlacementMixin {
   @Shadow
   @Final
   private HeightProvider height;

   @Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
   private void tellus$surfaceRelativeUndergroundHeight(
      PlacementContext context,
      RandomSource random,
      BlockPos origin,
      CallbackInfoReturnable<Stream<BlockPos>> callback
   ) {
      if (!(context.generator() instanceof EarthChunkGenerator earthGenerator)
         || !earthGenerator.settings().usesTerrainShell()) {
         return;
      }

      WorldGenerationContext vanillaContext = new WorldGenerationContext(
         context.generator(),
         LevelHeightAccessor.create(
            TellusCaveDepthMapper.VANILLA_MIN_Y,
            TellusCaveDepthMapper.VANILLA_MAX_Y - TellusCaveDepthMapper.VANILLA_MIN_Y + 1
         )
      ) {
         @Override
         public int getMinGenY() {
            return TellusCaveDepthMapper.VANILLA_MIN_Y;
         }

         @Override
         public int getGenDepth() {
            return TellusCaveDepthMapper.VANILLA_MAX_Y - TellusCaveDepthMapper.VANILLA_MIN_Y + 1;
         }
      };
      int virtualY = this.height.sample(random, vanillaContext);
      int actualSurfaceY = context.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, origin.getX(), origin.getZ()) - 1;
      int actualBottomY = findUsableUndergroundBottom(context, earthGenerator, origin, actualSurfaceY);
      int virtualSurfaceY = TellusCaveDepthMapper.VANILLA_SEA_LEVEL;
      int actualY = TellusCaveDepthMapper.actualYForVirtualFeature(
         virtualY, virtualSurfaceY, actualSurfaceY, actualBottomY
      );
      callback.setReturnValue(
         actualY == Integer.MIN_VALUE ? Stream.empty() : Stream.of(origin.atY(actualY))
      );
   }

   private static int findUsableUndergroundBottom(
      PlacementContext context,
      EarthChunkGenerator generator,
      BlockPos origin,
      int surfaceY
   ) {
      int searchBottom = Math.max(
         generator.getMinY() + 1,
         UndergroundGenerationDepthPolicy.deepestGenerationY(surfaceY, generator.settings().undergroundDepth())
      );
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(origin.getX(), surfaceY - 1, origin.getZ());
      for (int y = surfaceY - 1; y >= searchBottom; y--) {
         cursor.setY(y);
         if (context.getBlockState(cursor).is(Blocks.BEDROCK)) {
            return Math.min(surfaceY - 1, y + 1);
         }
      }
      return searchBottom;
   }
}
