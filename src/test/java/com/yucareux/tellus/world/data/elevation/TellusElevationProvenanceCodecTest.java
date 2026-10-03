package com.yucareux.tellus.world.data.elevation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusElevationProvenanceCodecTest {
   @Test
   void roundTripsMapterhornAvailabilityMetadata() throws Exception {
      TellusElevationProvenance provenance = new TellusElevationProvenance(
         2,
         1,
         TellusElevationSource.DemUsage.TERRAIN_TILES.bit() | TellusElevationSource.DemUsage.OPENWATERS.bit(),
         new byte[]{
            (byte)TellusElevationSource.DemUsage.TERRAIN_TILES.ordinal(),
            (byte)TellusElevationSource.DemUsage.OPENWATERS.ordinal()
         },
         new byte[]{0},
         new byte[]{1}
      );
      ByteArrayOutputStream output = new ByteArrayOutputStream();

      TellusElevationProvenanceCodec.write(output, provenance);
      TellusElevationProvenance decoded = TellusElevationProvenanceCodec.read(
         new ByteArrayInputStream(output.toByteArray())
      );

      assertEquals(TellusElevationSource.DemUsage.TERRAIN_TILES, decoded.primaryProvider(0, 0));
      assertEquals(TellusElevationSource.DemUsage.OPENWATERS, decoded.primaryProvider(1, 0));
      assertTrue(decoded.mapterhornAvailable(0, 0));
      assertFalse(decoded.mapterhornAvailable(1, 0));
   }
}
