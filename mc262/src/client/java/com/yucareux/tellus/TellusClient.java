package com.yucareux.tellus;

import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.client.hud.ManagedTerrainDownloadOverlay;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainClientState;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainViewDistance;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.network.TellusClientHelloPayload;
import com.yucareux.tellus.network.TellusProtocol;
import com.yucareux.tellus.network.TellusServerHelloPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.platform.TellusClientPlatform;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TemperatureGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Objects;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class TellusClient implements ClientModInitializer {
   private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Tellus.id("controls"));
   private static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
      "key.tellus.open_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KEY_CATEGORY
   );
   private int managedTerrainViewUpdateTicks;
   /** 本次连接是否已经发过客户端握手包，单位：无；避免每 tick 重复发 */
   private boolean clientHelloSent;

   @Override
   public void onInitializeClient() {
      TellusClientPlatform.configureGeoTeleport(
         () -> ClientPlayNetworking.canSend(GeoTpTeleportPayload.TYPE),
         (latitude, longitude) -> ClientPlayNetworking.send(new GeoTpTeleportPayload(latitude, longitude))
      );
      // 注入「服务端是否装了 TellusCN」的探测：能往 GeoTP 通道发包就说明服务端声明了该通道
      TellusClientPlatform.configureServerPresence(() -> ClientPlayNetworking.canSend(GeoTpTeleportPayload.TYPE));
      KeyMappingHelper.registerKeyMapping(OPEN_MAP_KEY);
      HudElementRegistry.addLast(Tellus.id("managed_terrain_status"), (graphics, deltaTracker) -> ManagedTerrainDownloadOverlay.render(graphics));
      ClientPlayNetworking.registerGlobalReceiver(Objects.requireNonNull(GeoTpOpenMapPayload.TYPE, "GeoTpOpenMapPayload.TYPE"), (payload, context) -> context.client().execute(() -> {
         Minecraft minecraft = context.client();
         Screen parent = minecraft.gui.screen();
         minecraft.gui.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude()));
      }));
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusWeatherPayload.TYPE, "TellusWeatherPayload.TYPE"),
         (payload, context) -> context.client()
            .execute(
               () -> {
                  SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
                     ? new SnowGrid(payload.centerX(), payload.centerZ(), payload.spacingBlocks(), payload.snowIndex())
                     : SnowGrid.empty();
                  TemperatureGrid temperatureGrid = payload.spacingBlocks() > 0
                     ? new TemperatureGrid(
                        payload.centerX(),
                        payload.centerZ(),
                        payload.spacingBlocks(),
                        payload.temperatureC(),
                        System.currentTimeMillis() - Math.max(0L, payload.temperatureAgeMs())
                     )
                     : TemperatureGrid.empty();
                  TellusRealtimeState.updateWeatherState(
                     payload.weatherEnabled(), payload.precipitationMode(), payload.historicalSnowEnabled(), grid, temperatureGrid
                  );
               }
            )
      );
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(ManagedTerrainStatusPayload.TYPE, "ManagedTerrainStatusPayload.TYPE"),
         (payload, context) -> context.client().execute(() -> ManagedTerrainClientState.update(payload.status()))
      );
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusServerHelloPayload.TYPE, "TellusServerHelloPayload.TYPE"),
         (payload, context) -> context.client().execute(() -> handleServerHello(payload))
      );
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         while (OPEN_MAP_KEY.consumeClick()) {
            if (client.gui.screen() == null && client.player != null && client.getConnection() != null) {
               client.getConnection().sendCommand("tellus map");
            }
         }

         if (client.player != null && !this.clientHelloSent && ClientPlayNetworking.canSend(TellusClientHelloPayload.TYPE)) {
            // 通道声明完成（canSend 为真）后再发握手，避开原版注册包尚未到达的时序竞争
            this.clientHelloSent = true;
            ClientPlayNetworking.send(new TellusClientHelloPayload(TellusProtocol.PROTOCOL_VERSION, TellusPlatform.modVersion()));
         }

         if (client.player != null && ++this.managedTerrainViewUpdateTicks >= 40) {
            this.managedTerrainViewUpdateTicks = 0;
            if (ClientPlayNetworking.canSend(ManagedTerrainViewPayload.TYPE)) {
               ClientPlayNetworking.send(new ManagedTerrainViewPayload(ManagedTerrainViewDistance.detect()));
            }
         }
      });
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         TellusRealtimeState.reset();
         ManagedTerrainClientState.reset();
         // 清空握手状态，避免把上一个服务器的传送策略带到下一个服务器
         TellusClientPlatform.resetServerHello();
         this.clientHelloSent = false;
         this.managedTerrainViewUpdateTicks = 0;
      });
   }

   /**
    * 处理服务端下发的握手响应。
    *
    * 输入：服务端握手包，可能为 null。
    * 输出：无返回值；协议不兼容时主动断开当前连接。
    * 边界条件：payload 为 null 时直接忽略；断开失败时静默忽略，不影响游戏继续运行。
    */
   private static void handleServerHello(TellusServerHelloPayload payload) {
      // 喵~防御：畸形空包直接忽略
      if (payload == null) {
         return;
      }
      // 记录服务端状态；返回 false 表示协议不兼容，需要主动断线
      boolean protocolCompatible = TellusClientPlatform.receiveServerHello(
         payload.protocolVersion(), payload.teleportPolicy(), payload.playerCanTeleport()
      );
      if (protocolCompatible) {
         // 协议兼容，正常游玩
         return;
      }

      // 协议不兼容：主动断开，并把双方版本号写进断线原因，方便玩家对照排查
      disconnectFromServer(
         Component.translatable(
            "tellus.multiplayer.protocol_mismatch",
            Integer.toString(TellusProtocol.PROTOCOL_VERSION),
            Integer.toString(payload.protocolVersion())
         )
      );
   }

   /**
    * 让客户端主动断开当前服务器连接。
    *
    * 输入：断开原因 reason，会原样显示在断线提示界面上。
    * 输出：无返回值。
    * 边界条件：reason 为 null，或当前根本没有连接时，直接返回不做任何操作。
    * 说明：MC 26.2 的客户端源码集没有 ClientMinecraftCompat 桥接类，因此这里就地实现。
    */
   private static void disconnectFromServer(Component reason) {
      // 喵~防御：没有断开原因就没必要断线，直接返回避免空指针
      if (reason == null) {
         return;
      }
      // 取出当前这条连接的包监听器，它持有底层网络连接
      ClientPacketListener packetListener = Minecraft.getInstance().getConnection();
      // 喵~防御：连接可能刚刚被拆除，此时静静返回即可
      if (packetListener == null) {
         return;
      }
      // 调用底层连接断开，并把原因文本交给原版断线界面渲染
      packetListener.getConnection().disconnect(reason);
   }
}
