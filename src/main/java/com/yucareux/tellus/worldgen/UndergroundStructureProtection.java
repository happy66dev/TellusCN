package com.yucareux.tellus.worldgen;

/**
 * Geometry policy for the protective terrain capsule placed around buried
 * structures that extend below Tellus's configured terrain shell.
 */
final class UndergroundStructureProtection {
   static final int BEDROCK_THICKNESS = 1;
   static final int STONE_THICKNESS = 4;
   static final int TOTAL_THICKNESS = BEDROCK_THICKNESS + STONE_THICKNESS;

   private UndergroundStructureProtection() {
   }

   static int minimumStructureY(int worldMinY) {
      return worldMinY + TOTAL_THICKNESS;
   }

   static int protectionBottomY(int structureMinY) {
      return structureMinY - TOTAL_THICKNESS;
   }

   static int terrainShellBottomY(int surfaceY, int undergroundDepth) {
      return surfaceY - Math.max(0, undergroundDepth);
   }

   static boolean needsTerrainExtension(int structureMinY, int surfaceY, int undergroundDepth) {
      return protectionBottomY(structureMinY) < terrainShellBottomY(surfaceY, undergroundDepth);
   }

   /**
    * The capsule has an outer bedrock floor and side wall. Its top remains open
    * so the extended volume joins the normal terrain shell and cave network.
    */
   static boolean isOuterBedrockSkin(
      int x, int y, int z, int expandedMinX, int protectionBottomY, int expandedMinZ, int expandedMaxX, int expandedMaxZ
   ) {
      return y == protectionBottomY
         || x == expandedMinX
         || x == expandedMaxX
         || z == expandedMinZ
         || z == expandedMaxZ;
   }
}
