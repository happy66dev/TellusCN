package com.yucareux.tellus.world.data.osm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedTileCodecWaterKindTest {
   @TempDir
   Path tempDir;

   @Test
   void roundTripsWaterKindInParsedCache() throws Exception {
      double[][] longitudes = new double[][]{{0.0, 0.01, 0.01, 0.0, 0.0}};
      double[][] latitudes = new double[][]{{0.0, 0.0, 0.01, 0.01, 0.0}};
      OsmWaterFeature feature = new OsmWaterFeature(42L, false, false, OsmWaterKind.RIVER, longitudes, latitudes);
      Path path = this.tempDir.resolve("water.tile");

      ParsedTileCodec.writeWaterTile(path, new OsmWaterTile(List.of(feature), 0.0, 0.0, 0.01, 0.01));
      OsmWaterTile decoded = ParsedTileCodec.readWaterTile(path);

      assertEquals(1, decoded.features().size());
      OsmWaterFeature decodedFeature = decoded.features().get(0);
      assertEquals(OsmWaterKind.RIVER, decodedFeature.kind());
      assertTrue(decodedFeature.flowingWater());
      assertFalse(decodedFeature.oceanHint());
   }

   @Test
   void derivesFlowingAndOceanKindsFromTags() {
      assertEquals(OsmWaterKind.RIVER, OsmWaterKind.fromTags("water", "river"));
      assertEquals(OsmWaterKind.CANAL, OsmWaterKind.fromTags("canal", null));
      assertEquals(OsmWaterKind.OCEAN, OsmWaterKind.fromTags("water", "ocean"));
   }
}
