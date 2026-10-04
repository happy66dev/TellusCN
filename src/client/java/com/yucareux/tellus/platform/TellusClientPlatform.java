package com.yucareux.tellus.platform;

import com.yucareux.tellus.network.TellusProtocol;
import com.yucareux.tellus.network.TellusTeleportPolicy;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Loader-neutral client operations used by shared screens. */
public final class TellusClientPlatform {
   private static final TellusClientPlatform.GeoTeleportTransport UNAVAILABLE = new TellusClientPlatform.GeoTeleportTransport(() -> false, (latitude, longitude) -> {
   });
   private static volatile TellusClientPlatform.GeoTeleportTransport geoTeleportTransport = UNAVAILABLE;
   /** 探测「当前连接的服务端是否装了 TellusCN」的回调，由各版本客户端在初始化时注入 */
   private static volatile BooleanSupplier serverPresenceProbe = () -> false;
   /**
    * 是否已经收到服务端下发的 server_hello 握手响应；未握手时传送按钮退回旧语义。
    *
    * 主人注意：以下 serverProtocolVersion / serverTeleportPolicy / playerCanTeleport 三项，
    *          目前**只有 serverTeleportPolicy 与 playerCanTeleport 被传送按钮使用**；
    *          serverProtocolVersion 及其 getter 是预留给「未来要在界面上显示双端版本」用的，
    *          当前没有任何生产调用方。若长期不用，可以考虑连同 getter 一起删掉。
    */
   private static volatile boolean serverHelloReceived;
   /** 服务端声明的联机协议版本号，单位：无；未握手时等于最低支持版本 */
   private static volatile int serverProtocolVersion = TellusProtocol.MIN_SUPPORTED_PROTOCOL;
   /** 服务端当前生效的传送策略，默认与配置文件默认值保持一致 */
   private static volatile TellusTeleportPolicy serverTeleportPolicy = TellusTeleportPolicy.OP_ONLY;
   /** 服务端针对「当前这名玩家」算出的能否使用 GeoTP 传送，逐玩家不同 */
   private static volatile boolean playerCanTeleport;

   private TellusClientPlatform() {
   }

   public static void configureGeoTeleport(BooleanSupplier available, TellusClientPlatform.GeoTeleportSender sender) {
      geoTeleportTransport = new TellusClientPlatform.GeoTeleportTransport(
         Objects.requireNonNull(available, "available"), Objects.requireNonNull(sender, "sender")
      );
   }

   /** Returns whether the request was accepted by the active loader transport. */
   public static boolean sendGeoTeleport(double latitude, double longitude) {
      TellusClientPlatform.GeoTeleportTransport transport = geoTeleportTransport;
      if (!transport.available().getAsBoolean()) {
         return false;
      }

      transport.sender().send(latitude, longitude);
      return true;
   }

   /**
    * 注入「当前连接的服务端是否装了 TellusCN」的探测回调。
    *
    * 输入：一个无参布尔回调，通常实现为「客户端能否向 tellus 的 GeoTP 通道发包」。
    * 输出：无返回值，回调被保存在静态字段里。
    * 边界条件：probe 为 null 会抛 NPE，由调用方保证非空（各版本客户端只会注入一次）。
    */
   public static void configureServerPresence(BooleanSupplier probe) {
      // 保存探测回调，供加载界面等共享界面查询
      serverPresenceProbe = Objects.requireNonNull(probe, "probe");
   }

   /**
    * 当前连接的服务端是否装了 TellusCN。
    *
    * 输入：无，内部使用初始化时注入的探测回调。
    * 输出：true 表示服务端声明了 TellusCN 的 GeoTP 通道；false 表示原版服务端或未连接。
    * 边界条件：连接已断开时底层探测可能抛异常，此时一律按「服务端没有 TellusCN」处理。
    */
   public static boolean isServerPresent() {
      try {
         // 询问注入的探测回调：通道可发送即代表服务端装了模组
         return serverPresenceProbe.getAsBoolean();
      } catch (Throwable error) {
         // 喵~防御：连接拆除瞬间探测会抛异常，吞掉并返回「没有 TellusCN」而不是让界面崩掉
         return false;
      }
   }

   /**
    * 记录服务端握手结果。
    *
    * 输入：服务端协议版本号、服务端传送策略、服务端针对本人算出的能否传送。
    * 输出：无返回值，结果写入静态字段并标记「已握手」。
    * 边界条件：策略为 null 时回退到最保守的 OP_ONLY（与配置默认值一致），避免空指针。
    */
   public static void applyServerHello(int protocolVersion, TellusTeleportPolicy teleportPolicy, boolean canTeleport) {
      // 保存服务端协议版本号，单位：无
      serverProtocolVersion = protocolVersion;
      // 喵~防御：null 策略会让后续判定抛空指针，回退到默认档位
      serverTeleportPolicy = teleportPolicy == null ? TellusTeleportPolicy.OP_ONLY : teleportPolicy;
      // 保存服务端对本人能否传送的判定结果
      playerCanTeleport = canTeleport;
      // 标记握手已完成，之后传送按钮改为按服务端策略判定
      serverHelloReceived = true;
   }

   /**
    * 清空握手状态。
    *
    * 输入：无。
    * 输出：无返回值，所有握手相关字段回到初始值。
    * 边界条件：无；重复调用是幂等的。
    * 说明：断开连接或切换服务器时必须调用，否则会把上一个服务器的传送策略带到下一个服务器。
    */
   public static void resetServerHello() {
      // 标记「未握手」，传送按钮退回按通道可用性判断
      serverHelloReceived = false;
      // 协议版本回退到最低支持版本
      serverProtocolVersion = TellusProtocol.MIN_SUPPORTED_PROTOCOL;
      // 策略回退到默认档位
      serverTeleportPolicy = TellusTeleportPolicy.OP_ONLY;
      // 清掉上一个服务器算出的「本人能否传送」结论
      playerCanTeleport = false;
   }

   /**
    * 处理收到的服务端握手包，并在协议不兼容时告诉调用方该断开连接。
    *
    * 输入：服务端协议版本号、服务端传送策略、服务端针对本人算出的能否传送。
    * 输出：true 表示协议兼容、可以继续游戏；false 表示协议不兼容、调用方应当断开连接。
    * 边界条件：无论兼容与否都会先把握手结果记录下来，这样断开界面也能看到服务端状态。
    */
   public static boolean receiveServerHello(int protocolVersion, TellusTeleportPolicy teleportPolicy, boolean canTeleport) {
      // 先把服务端状态记下来，保证即使随后要断线，界面也能读到正确的策略
      applyServerHello(protocolVersion, teleportPolicy, canTeleport);
      // 交给共享的协议兼容判定，未来放宽支持范围时只需改 TellusProtocol 一处
      return TellusProtocol.isCompatible(protocolVersion);
   }

   /** 是否已收到服务端握手响应，单位：无；当前无生产调用方，预留给后续的界面提示。 */
   public static boolean isServerHelloReceived() {
      // 直接返回握手标记
      return serverHelloReceived;
   }

   /** 服务端声明的联机协议版本号，单位：无；当前无生产调用方，预留给后续的版本不一致提示。 */
   public static int serverProtocolVersion() {
      // 直接返回缓存的协议版本号
      return serverProtocolVersion;
   }

   /** 服务端当前生效的传送策略。 */
   public static TellusTeleportPolicy serverTeleportPolicy() {
      // 直接返回缓存的策略枚举
      return serverTeleportPolicy;
   }

   /** 服务端针对当前玩家算出的能否使用 GeoTP 传送。 */
   public static boolean playerCanTeleport() {
      // 直接返回服务端下发的逐玩家判定结果
      return playerCanTeleport;
   }

   /**
    * GeoTP 传送按钮是否应该可用。
    *
    * 输入：无，内部读取握手状态与通道可用性。
    * 输出：true 表示可以点传送按钮；false 表示应当置灰。
    * 边界条件：未收到握手时退回「通道是否可发送」的旧语义，避免误伤老版本或原版服务端。
    */
   public static boolean isGeoTeleportAvailable() {
      if (!serverHelloReceived) {
         // 未握手：可能是老版 TellusCN 或原版服务端，保持既有行为不禁用按钮
         TellusClientPlatform.GeoTeleportTransport transport = geoTeleportTransport;
         try {
            // 直接问传送通道能不能发包
            return transport.available().getAsBoolean();
         } catch (Throwable error) {
            // 喵~防御：连接拆除瞬间探测会抛异常，按不可用处理
            return false;
         }
      }

      // 已握手：服务端关闭传送，或明确告知本人无权传送时，按钮置灰
      return serverTeleportPolicy != TellusTeleportPolicy.DISABLED && playerCanTeleport;
   }

   @FunctionalInterface
   public interface GeoTeleportSender {
      void send(double latitude, double longitude);
   }

   private record GeoTeleportTransport(BooleanSupplier available, TellusClientPlatform.GeoTeleportSender sender) {
   }
}
