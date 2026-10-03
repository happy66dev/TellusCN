package com.yucareux.tellus.world.data.elevation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TellusRasterReaderTest {
   @Test
   void rejectsUnknownRasterFilter() throws IOException {
      byte[] chunk = chunkWithFilter(99);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
         output.write("TELLUS/RASTER".getBytes(StandardCharsets.US_ASCII));
         output.writeByte(0);
         output.writeInt(1);
         output.writeInt(1);
         output.writeByte(2);
         output.writeInt(chunk.length);
         output.write(chunk);
      }

      assertThrows(IOException.class, () -> TellusRasterReader.readShortRaster(new ByteArrayInputStream(bytes.toByteArray())));
   }

   private static byte[] chunkWithFilter(int filter) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
         output.writeInt(0);
         output.writeInt(0);
         output.writeInt(1);
         output.writeInt(1);
         output.writeByte(filter);
      }

      return bytes.toByteArray();
   }
}
