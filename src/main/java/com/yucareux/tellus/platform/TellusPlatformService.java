package com.yucareux.tellus.platform;

import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.TellusServerHelloPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import java.nio.file.Path;
import net.minecraft.server.level.ServerPlayer;

/**
 * loader 无关的平台服务接口。
 *
 * 说明：实现类由各 loader 通过 META-INF/services 注册，再经 ServiceLoader 加载
 *       （见 TellusPlatform.loadService）。公共代码只通过 TellusPlatform 这个门面访问本接口。
 *
 * 约定：所有 send* 方法都必须**先判断对端是否声明了对应网络通道**再发送。
 *       未安装 TellusCN（或原版）的客户端不会声明这些通道，盲发只会产生无意义的包
 *       甚至触发底层异常，因此实现类内部一律要做 canSend 守卫。
 */
public interface TellusPlatformService {
   Path gameDir();

   Path configDir();

   boolean isModLoaded(String modId);

   void sendWeatherPayload(ServerPlayer player, TellusWeatherPayload payload);

   void sendGeoTpOpenMapPayload(ServerPlayer player, GeoTpOpenMapPayload payload);

   void sendManagedTerrainStatusPayload(ServerPlayer player, ManagedTerrainStatusPayload payload);

   /**
    * 向指定玩家下发服务端握手包。
    *
    * 输入：目标玩家与握手包。
    * 输出：真正发出返回 true；因对端未声明通道而跳过返回 false。
    * 说明：返回 false 时调用方会稍后重试（见 TellusCommon 的 pending 机制），
    *       因为客户端在刚进入游戏时可能还没来得及声明通道。
    */
   boolean sendServerHelloPayload(ServerPlayer player, TellusServerHelloPayload payload);

   /**
    * 取当前安装的 TellusCN 模组版本字符串。
    *
    * 输入：无。
    * 输出：版本字符串；取不到时返回 "unknown"，绝不返回 null。
    * 说明：仅用于握手包展示与日志诊断，不参与任何协议判定。
    */
   String modVersion();

   void registerDistantHorizonsLifecycle(Runnable onServerStart, Runnable onServerStop, Runnable onPlayerJoin);
}
