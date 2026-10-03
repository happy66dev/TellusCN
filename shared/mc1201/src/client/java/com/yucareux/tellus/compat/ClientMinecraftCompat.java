package com.yucareux.tellus.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yucareux.tellus.client.widget.WidgetCompat;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;

/** Client rendering API differences specific to Minecraft 1.20.1. */
public final class ClientMinecraftCompat {
   private ClientMinecraftCompat() {
   }

   public static ResourceLocation resourceLocation(String namespace, String path) {
      ResourceLocation id = ResourceLocation.tryBuild(namespace, path);
      if (id == null) {
         throw new IllegalArgumentException("Invalid resource location " + namespace + ":" + path);
      }
      return id;
   }

   public static BufferBuilder beginPositionColorQuads() {
      BufferBuilder buffer = Tesselator.getInstance().getBuilder();
      buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      return buffer;
   }

   public static void upload(VertexBuffer vertexBuffer, BufferBuilder buffer) {
      vertexBuffer.upload(buffer.end());
   }

   public static void emitVertex(BufferBuilder buffer, float x, float y, float z, int color) {
      buffer.vertex(x, y, z).color(color).endVertex();
   }

   public static void setWidgetHeight(AbstractWidget widget, int height) {
      WidgetCompat.setHeight(widget, height);
   }

   public static int loadingWidgetTop(int centerY, int chunkDisplayRadius, int lineHeight) {
      int chunkDisplayCenterY = centerY + 30;
      int progressTextY = centerY - lineHeight / 2 - 30;
      return Math.min(progressTextY, chunkDisplayCenterY - chunkDisplayRadius);
   }

   public static int loadingWidgetBottom(int centerY, int chunkDisplayRadius) {
      return centerY + 30 + chunkDisplayRadius;
   }
}
