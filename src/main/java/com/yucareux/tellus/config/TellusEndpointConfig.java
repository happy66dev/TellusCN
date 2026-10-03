/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 * 
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.config;

import java.util.List;

/**
 * Tellus 端点配置工具类
 *
 * 提供统一的数据源端点获取方法
 * 优先级：游戏内设置 > JVM 启动参数 > 默认官方源
 */
public final class TellusEndpointConfig {
   
   private TellusEndpointConfig() {
   }
   
   /**
    * 获取端点 URL
    * 
    * @param systemPropertyKey JVM 参数 key
    * @param defaultValue 默认值
    * @return 实际使用的端点 URL
    */
   public static String getEndpoint(String systemPropertyKey, String defaultValue) {
      // 1. 首先检查游戏内配置（如果启用了镜像）
      if (MirrorConfig.isEnabled()) {
         String mirrorEndpoint = getMirrorEndpoint(systemPropertyKey);
         if (!mirrorEndpoint.isBlank()) {
            return mirrorEndpoint;
         }
      }
      
      // 2. 检查 JVM 启动参数
      String jvmValue = System.getProperty(systemPropertyKey);
      if (jvmValue != null && !jvmValue.isBlank()) {
         return jvmValue;
      }
      
      // 3. 返回默认值
      return defaultValue;
   }

   /**
    * 获取端点候选列表：优先地址在前，官方默认地址兜底在后
    *
    * 用途：PMTiles 这类"整文件读取"的数据源，一旦镜像路由失效就会整体不可用，
    * 因此需要准备一个备用地址，在优先地址读不到数据时自动降级重试。
    *
    * @param systemPropertyKey JVM 参数 key，用于解析优先地址
    * @param defaultValue      官方默认地址，同时作为兜底地址
    * @return 去重且已过滤空值的候选地址列表
    */
   public static List<String> getEndpointCandidates(String systemPropertyKey, String defaultValue) {
      // 先按已有关键字解析逻辑取出优先地址，它可能是镜像地址、JVM 覆盖值或默认值
      String primaryEndpoint = getEndpoint(systemPropertyKey, defaultValue);
      // 交给纯函数完成空值过滤、去重与顺序编排
      return OvertureDataConfig.sourceCandidates(primaryEndpoint, defaultValue);
   }

   /**
    * 获取 Overture 建筑数据的候选地址列表
    *
    * @param officialUrl 官方默认的建筑瓦片地址
    * @return 优先地址在前、官方地址兜底的候选列表
    */
   public static List<String> getOvertureBuildingsCandidates(String officialUrl) {
      // 复用通用候选逻辑，JVM 参数 key 固定为建筑端点
      return getEndpointCandidates("tellus.overture.buildings.endpoint", officialUrl);
   }

   /**
    * 获取 Overture 道路数据的候选地址列表
    *
    * @param officialUrl 官方默认的道路瓦片地址
    * @return 优先地址在前、官方地址兜底的候选列表
    */
   public static List<String> getOvertureRoadsCandidates(String officialUrl) {
      // 复用通用候选逻辑，JVM 参数 key 固定为道路端点
      return getEndpointCandidates("tellus.overture.roads.endpoint", officialUrl);
   }

   /**
    * 获取 Overture base 主题数据的候选地址列表
    *
    * 说明：上游把陆地表覆盖改用 Overture 的 base 主题 PMTiles，而 base 主题
    * 与沙地共用同一个 base.pmtiles 压缩包，因此这里直接复用沙地的镜像路由，
    * 不额外假设 CDN 上存在新的路径。镜像未启用时只返回官方地址。
    *
    * @param officialUrl 官方默认的 base 主题瓦片地址
    * @return 优先地址在前、官方地址兜底的候选列表
    */
   public static List<String> getOvertureBaseCandidates(String officialUrl) {
      // 复用沙地端点：两者指向同一个 base.pmtiles 压缩包
      return getEndpointCandidates("tellus.overture.sand.endpoint", officialUrl);
   }

   /**
    * 获取 Overture 水域数据的候选地址列表
    *
    * @param officialUrl 官方默认的水域瓦片地址
    * @return 优先地址在前、官方地址兜底的候选列表
    */
   public static List<String> getOvertureWaterCandidates(String officialUrl) {
      // 复用通用候选逻辑，JVM 参数 key 固定为水域端点
      return getEndpointCandidates("tellus.overture.water.endpoint", officialUrl);
   }

   /**
    * 获取 Overture 沙地数据的候选地址列表
    *
    * @param officialUrl 官方默认的沙地瓦片地址
    * @return 优先地址在前、官方地址兜底的候选列表
    */
   public static List<String> getOvertureSandCandidates(String officialUrl) {
      // 复用通用候选逻辑，JVM 参数 key 固定为沙地端点
      return getEndpointCandidates("tellus.overture.sand.endpoint", officialUrl);
   }

   /**
    * 根据系统属性 key 获取对应的镜像端点
    */
   private static String getMirrorEndpoint(String systemPropertyKey) {
      String domain = MirrorConfig.getActiveDomain();
      if (domain.isBlank()) {
         return "";
      }
      
      // 根据系统属性 key 映射到对应的路由。
      // 说明：上游 0.8.x 删除了 4 个国别高程源，并把地表覆盖换成 Overture base 主题 PMTiles，
      // 因此 elevation / copernicus / usgs / japangsi / arcticdem / rema / landcover / s3proxy
      // 这些旧路由已经没有任何调用点，一并移除，避免文档与代码对不上。
      return switch (systemPropertyKey) {
         case "tellus.weather.endpoint" -> domain + "/weather";
         case "tellus.geocoding.endpoint" -> domain + "/geocoding";
         case "tellus.osm.overpass.endpoints" -> domain + "/overpass";
         case "tellus.landmask.baseUrl" -> domain + "/landmask/";
         case "tellus.map.tiles.endpoint" -> domain + "/tiles";
         case "tellus.overture.roads.endpoint" -> domain + "/overture/roads";
         case "tellus.overture.buildings.endpoint" -> domain + "/overture/buildings";
         case "tellus.overture.water.endpoint" -> domain + "/overture/water";
         case "tellus.overture.sand.endpoint" -> domain + "/overture/sand";
         default -> "";
      };
   }

   /**
    * 获取天气端点
    */
   public static String getWeatherEndpoint(String defaultValue) {
      return getEndpoint("tellus.weather.endpoint", defaultValue);
   }
   
   /**
    * 获取地理编码端点
    */
   public static String getGeocodingEndpoint(String defaultValue) {
      return getEndpoint("tellus.geocoding.endpoint", defaultValue);
   }
   
   /**
    * 获取 Overpass 端点
    */
   public static String getOverpassEndpoint(String defaultValue) {
      return getEndpoint("tellus.osm.overpass.endpoints", defaultValue);
   }
   
   /**
    * 获取 Land Mask 基础 URL
    */
   public static String getLandMaskBaseUrl(String defaultValue) {
      return getEndpoint("tellus.landmask.baseUrl", defaultValue);
   }
   
   /**
    * 获取地图瓦片端点
    */
   public static String getMapTilesEndpoint(String defaultValue) {
      return getEndpoint("tellus.map.tiles.endpoint", defaultValue);
   }
   
   /**
    * 获取 Overture Roads 端点
    */
   public static String getOvertureRoadsEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.roads.endpoint", defaultValue);
   }
   
   /**
    * 获取 Overture Buildings 端点
    */
   public static String getOvertureBuildingsEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.buildings.endpoint", defaultValue);
   }
   
   /**
    * 获取 Overture Water 端点
    */
   public static String getOvertureWaterEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.water.endpoint", defaultValue);
   }
   
   /**
    * 获取 Overture Sand 端点
    */
   public static String getOvertureSandEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.sand.endpoint", defaultValue);
   }
}
