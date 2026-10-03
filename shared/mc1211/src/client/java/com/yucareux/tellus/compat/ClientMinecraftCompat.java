package com.yucareux.tellus.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;

/** Client rendering API differences specific to Minecraft 1.21.1. */
public final class ClientMinecraftCompat {
   private ClientMinecraftCompat() {
   }

   public static ResourceLocation resourceLocation(String namespace, String path) {
      return ResourceLocation.fromNamespaceAndPath(namespace, path);
   }

   public static BufferBuilder beginPositionColorQuads() {
      return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
   }

   public static void upload(VertexBuffer vertexBuffer, BufferBuilder buffer) {
      vertexBuffer.upload(buffer.buildOrThrow());
   }

   public static void emitVertex(BufferBuilder buffer, float x, float y, float z, int color) {
      buffer.addVertex(x, y, z).setColor(color);
   }

   public static void setWidgetHeight(AbstractWidget widget, int height) {
      widget.setHeight(height);
   }

   public static int loadingWidgetTop(int centerY, int chunkDisplayRadius, int lineHeight) {
      return centerY - chunkDisplayRadius - lineHeight - 2;
   }

   public static int loadingWidgetBottom(int centerY, int chunkDisplayRadius) {
      return centerY + chunkDisplayRadius;
   }
}
