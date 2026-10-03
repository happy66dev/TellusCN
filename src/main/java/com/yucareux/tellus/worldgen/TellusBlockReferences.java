package com.yucareux.tellus.worldgen;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class TellusBlockReferences {
   private TellusBlockReferences() {
   }

   public static Block concreteBlock(String colorName) {
      return coloredBlock("CONCRETE", colorName, legacyColorFieldName(colorName, "CONCRETE"));
   }

   public static BlockState concreteState(String colorName) {
      return concreteBlock(colorName).defaultBlockState();
   }

   public static Block terracottaBlock(String colorName) {
      return coloredBlock("DYED_TERRACOTTA", colorName, legacyColorFieldName(colorName, "TERRACOTTA"));
   }

   public static BlockState terracottaState(String colorName) {
      return terracottaBlock(colorName).defaultBlockState();
   }

   public static Block stainedGlassBlock(String colorName) {
      return coloredBlock("STAINED_GLASS", colorName, legacyColorFieldName(colorName, "STAINED_GLASS"));
   }

   public static BlockState stainedGlassState(String colorName) {
      return stainedGlassBlock(colorName).defaultBlockState();
   }

   public static Block woolBlock(String colorName) {
      return coloredBlock("WOOL", colorName, legacyColorFieldName(colorName, "WOOL"));
   }

   public static BlockState woolState(String colorName) {
      return woolBlock(colorName).defaultBlockState();
   }

   public static BlockState waxedOxidizedCopperState() {
      return weatheringCopperBlock("COPPER_BLOCK", true, "oxidized", "WAXED_OXIDIZED_COPPER").defaultBlockState();
   }

   public static BlockState lightningRodState() {
      return weatheringCopperBlock("LIGHTNING_ROD", false, "unaffected", "LIGHTNING_ROD").defaultBlockState();
   }

   private static Block coloredBlock(String collectionFieldName, String colorName, String legacyFieldName) {
      Block legacyBlock = blockByField(legacyFieldName);
      if (legacyBlock != null) {
         return legacyBlock;
      }

      Object collection = fieldValue(collectionFieldName);
      Object value = invoke(collection, colorMethodName(colorName));
      if (value instanceof Block block) {
         return block;
      }

      throw new IllegalStateException("Minecraft block collection " + collectionFieldName + "." + colorName + " did not resolve to a block");
   }

   private static Block weatheringCopperBlock(String collectionFieldName, boolean waxed, String stateMethodName, String legacyFieldName) {
      Block legacyBlock = blockByField(legacyFieldName);
      if (legacyBlock != null) {
         return legacyBlock;
      }

      Object collection = fieldValue(collectionFieldName);
      Object byState = invoke(collection, waxed ? "waxed" : "weathering");
      Object value = invoke(byState, stateMethodName);
      if (value instanceof Block block) {
         return block;
      }

      throw new IllegalStateException("Minecraft copper collection " + collectionFieldName + "." + stateMethodName + " did not resolve to a block");
   }

   private static Block blockByField(String fieldName) {
      try {
         Object value = Blocks.class.getField(fieldName).get(null);
         return value instanceof Block block ? block : null;
      } catch (IllegalAccessException | NoSuchFieldException error) {
         return null;
      }
   }

   private static Object fieldValue(String fieldName) {
      try {
         return Blocks.class.getField(fieldName).get(null);
      } catch (IllegalAccessException | NoSuchFieldException error) {
         throw new IllegalStateException("Missing Minecraft block field " + fieldName, error);
      }
   }

   private static Object invoke(Object target, String methodName) {
      try {
         return target.getClass().getMethod(methodName).invoke(target);
      } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException error) {
         throw new IllegalStateException("Failed to call " + target.getClass().getName() + "." + methodName, error);
      }
   }

   private static String colorMethodName(String colorName) {
      String[] parts = colorName.toLowerCase(Locale.ROOT).split("_");
      StringBuilder methodName = new StringBuilder(parts[0]);
      for (int index = 1; index < parts.length; index++) {
         if (!parts[index].isEmpty()) {
            methodName.append(Character.toUpperCase(parts[index].charAt(0))).append(parts[index].substring(1));
         }
      }

      return methodName.toString();
   }

   private static String legacyColorFieldName(String colorName, String suffix) {
      return colorName.toUpperCase(Locale.ROOT) + "_" + suffix;
   }
}
