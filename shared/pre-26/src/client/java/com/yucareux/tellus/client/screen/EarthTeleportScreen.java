package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.PlaceSearchWidget;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.MarkerMapComponent;
import com.yucareux.tellus.client.compat.AbstractTellusScreen;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.network.TellusTeleportPolicy;
import com.yucareux.tellus.platform.TellusClientPlatform;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public class EarthTeleportScreen extends AbstractTellusScreen {
   private static final int DEFAULT_ZOOM = 6;

   private final Screen parent;
   private final double initialLatitude;
   private final double initialLongitude;
   private SlippyMapWidget mapWidget;
   private MarkerMapComponent markerComponent;
   private PlaceSearchWidget searchWidget;
   /** 传送按钮控件，单位：无；存成字段是为了按服务端下发的策略动态置灰 */
   private Button teleportButton;
   private boolean suppressMapRelease;

   public EarthTeleportScreen( Screen parent, double latitude, double longitude) {
      super(Component.translatable("gui.earth.teleport_map"));
      this.parent = parent;
      this.initialLatitude = latitude;
      this.initialLongitude = longitude;
   }

   protected void init() {
      MapScreenResources.close(this.mapWidget, this.searchWidget);

      int mapX = 20;
      int mapY = 20;
      int mapWidth = this.width - 40;
      int mapHeight = this.height - 60;
      this.mapWidget = new SlippyMapWidget(mapX, mapY, mapWidth, mapHeight);
      this.markerComponent = new MarkerMapComponent(new SlippyMapPoint(this.initialLatitude, this.initialLongitude)).allowMovement();
      this.mapWidget.addComponent(this.markerComponent);
      this.mapWidget.getMap().focus(this.initialLatitude, this.initialLongitude, DEFAULT_ZOOM);
      Geocoder geocoder = new NominatimGeocoder(() -> this.minecraft == null ? null : this.minecraft.getLanguageManager().getSelected());
      this.searchWidget = new PlaceSearchWidget(mapX + 5, mapY + 5, 200, 20, geocoder, this::handleSearch);
      this.addRenderableOnly(this.mapWidget);
      this.addRenderableWidget(this.searchWidget);
      int buttonY = this.height - 28;
      this.teleportButton = Button.builder(
            Component.translatable("gui.earth.teleport"), button -> this.sendTeleport()
         )
         .bounds(this.width / 2 - 154, buttonY, 150, 20)
         .build();
      // 按服务端下发的传送策略决定按钮能否点击，并挂上禁用原因提示
      this.updateTeleportButtonState();
      this.addRenderableWidget(this.teleportButton);
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel"), button -> this.closeScreen()).bounds(this.width / 2 + 4, buttonY, 150, 20).build()
      );
      this.addWidget(this.mapWidget);
   }

   /**
    * 刷新传送按钮的可用状态与提示。
    *
    * 输入：无，内部读取客户端缓存的服务端传送策略。
    * 输出：无返回值，直接修改按钮控件的 active 与 tooltip。
    * 边界条件：按钮还没创建时（init 之前）直接返回，避免空指针；策略变化时本方法会被重复调用。
    */
   private void updateTeleportButtonState() {
      // 喵~防御：按钮尚未创建时什么都不做
      if (this.teleportButton == null) {
         return;
      }
      // 询问客户端平台：已握手按服务端策略，未握手退回通道可用性判断
      boolean teleportAvailable = TellusClientPlatform.isGeoTeleportAvailable();
      // 同步按钮的点亮/置灰状态
      this.teleportButton.active = teleportAvailable;
      // 置灰时给出可读原因，避免玩家看到灰按钮却不知道为何
      this.teleportButton.setTooltip(
         teleportAvailable ? null : Tooltip.create(tellusTeleportUnavailableReason())
      );
   }

   /**
    * 返回传送按钮被禁用时的原因文案。
    *
    * 输入：无。
    * 输出：本地化后的提示文本。
    * 边界条件：未握手时按钮不会禁用，此分支不会被触发；其余禁用情形统一按「权限不足」提示。
    */
   private static Component tellusTeleportUnavailableReason() {
      // 服务端把传送功能整个关掉了
      if (TellusClientPlatform.serverTeleportPolicy() == TellusTeleportPolicy.DISABLED) {
         return Component.translatable("tellus.geotp.disabled");
      }
      // 其余情况都是「本人不是管理员」，按仅管理员可用提示
      return Component.translatable("tellus.geotp.requires_op");
   }

   protected void setInitialFocus() {
      if (this.searchWidget != null) {
         this.setInitialFocus(this.searchWidget);
      }
   }

   private void handleSearch(double latitude, double longitude) {
      this.markerComponent.moveMarker(latitude, longitude);
      this.mapWidget.getMap().focus(latitude, longitude, 12);
   }

   private void sendTeleport() {
      if (this.markerComponent != null) {
         SlippyMapPoint marker = this.markerComponent.getMarker();
         if (marker != null && this.minecraft != null) {
            if (!TellusClientPlatform.sendGeoTeleport(marker.getLatitude(), marker.getLongitude())) {
               if (this.minecraft.player != null) {
                  this.minecraft.player.displayClientMessage(Component.translatable("tellus.geotp.server_rejected"), true);
               }
            }

            this.closeScreen();
         }
      }
   }

   @Override
   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      if (this.isSearchOverlayMouseOver(mouseX, mouseY)) {
         this.suppressMapRelease = true;
         this.cancelMapInteraction();
         this.setFocused(this.searchWidget);
         this.searchWidget.setFocused(true);
         ClientMinecraftCompat.mouseClicked(this.searchWidget, mouseX, mouseY, button);
         return true;
      }

      this.suppressMapRelease = false;
      return super.tellusMouseClicked(mouseX, mouseY, button);
   }

   @Override
   protected boolean tellusMouseReleased(double mouseX, double mouseY, int button) {
      if (this.suppressMapRelease || this.isSearchOverlayMouseOver(mouseX, mouseY)) {
         this.suppressMapRelease = false;
         this.cancelMapInteraction();
         return true;
      }

      return super.tellusMouseReleased(mouseX, mouseY, button);
   }

   private void closeScreen() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   public void render( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1072689136);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 4, 16777215);
      super.render(graphics, mouseX, mouseY, delta);
   }

   public void tick() {
      super.tick();
      if (this.searchWidget != null) {
         this.searchWidget.tick();
      }
      // 服务端策略可能在界面打开期间变化（例如管理员刚改了配置），每 tick 刷新一次按钮状态
      this.updateTeleportButtonState();
   }

   public void onClose() {
      this.closeScreen();
   }

   public void removed() {
      MapScreenResources.close(this.mapWidget, this.searchWidget);
   }

   private boolean isSearchOverlayMouseOver(double mouseX, double mouseY) {
      return this.searchWidget != null && this.searchWidget.isMouseOver(mouseX, mouseY);
   }

   private void cancelMapInteraction() {
      if (this.mapWidget != null) {
         this.mapWidget.cancelInteraction();
      }
   }
}
