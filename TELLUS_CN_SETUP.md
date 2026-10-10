# TellusCN 中国版配置指南

TellusCN 是 [Tellus](https://github.com/yucareux/Tellus) 的中文社区分支，在保留上游全部功能的前提下，为国内玩家增加了**镜像（CDN 反代）支持**，用于加速或替代访问境外的地理数据源。

> 本文档对应当前分支（已合并上游 Tellus 0.8.3）的实际行为。上游 0.8.x 更换了高程与地表覆盖数据源，旧版本文档中的 `elevation` / `copernicus` / `usgs` / `japangsi` / `arcticdem` / `rema` / `landcover` 等端点**已全部失效**，请勿再使用。

---

## 一、两种配置方式

### 方式一：游戏内镜像设置（推荐）

镜像配置保存在 `.minecraft/config/telluscn-mirror.properties`，可以直接在游戏里改：

| 入口 | 路径 |
|------|------|
| 世界创建流程 | 创建/自定义世界 → **地球自定义** 界面 → **镜像设置** 按钮 |
| Mod Menu | 主菜单 → **Mods** → TellusCN → **配置** |

设置界面提供三种模式：

- **官方预设**：使用内置的国内加速域名 `https://telluscn.ggff.net`
- **自定义**：填写自建的 Cloudflare Workers / 反向代理域名（不带 `https://` 也可以，会自动补全）
- **关闭镜像**：完全不使用镜像，所有请求走官方源

### 方式二：JVM 启动参数

不想用界面时，可以把参数直接写进启动器（HMCL、PCL2 等）的「JVM 参数」一栏：

```
-Dtellus.weather.endpoint=https://your-mirror.example.com/weather
-Dtellus.geocoding.endpoint=https://your-mirror.example.com/geocoding
-Dtellus.osm.overpass.endpoints=https://your-mirror.example.com/overpass
-Dtellus.landmask.baseUrl=https://your-mirror.example.com/landmask/
-Dtellus.map.tiles.endpoint=https://your-mirror.example.com/tiles
-Dtellus.overture.roads.endpoint=https://your-mirror.example.com/overture/roads
-Dtellus.overture.buildings.endpoint=https://your-mirror.example.com/overture/buildings
-Dtellus.overture.water.endpoint=https://your-mirror.example.com/overture/water
-Dtellus.overture.sand.endpoint=https://your-mirror.example.com/overture/sand
```

> 上述 9 个键与 `MirrorConfig.getAllEndpointArgs()` 返回的内容完全一致。

**优先级**：游戏内设置 > JVM 启动参数 > 官方默认源。

---

## 二、当前受支持的数据源与镜像路由

下表是当前分支**真正会读取**的端点。镜像域名后面拼上「镜像路由」即为完整地址。

| 数据源 | 系统属性键 | 官方默认值 | 镜像路由 |
|--------|-----------|-----------|---------|
| 天气 | `tellus.weather.endpoint` | `https://api.open-meteo.com/v1` | `/weather` |
| 地理编码 | `tellus.geocoding.endpoint` | `https://nominatim.openstreetmap.org` | `/geocoding` |
| OSM Overpass | `tellus.osm.overpass.endpoints` | 多个公共实例，逗号分隔 | `/overpass` |
| 陆地掩码 | `tellus.landmask.baseUrl` | `https://github.com/Yucareux/Tellus-Land-Polygons/releases/download/v1.0.0/` | `/landmask/` |
| 游戏内地图瓦片 | `tellus.map.tiles.endpoint` | `https://tile.openstreetmap.org` | `/tiles` |
| Overture 道路 | `tellus.overture.roads.endpoint` | 自动发现最新 release | `/overture/roads` |
| Overture 建筑 | `tellus.overture.buildings.endpoint` | 自动发现最新 release | `/overture/buildings` |
| Overture 水域 | `tellus.overture.water.endpoint` | 自动发现最新 release | `/overture/water` |
| Overture 沙地 **及地表覆盖** | `tellus.overture.sand.endpoint` | 自动发现最新 release | `/overture/sand` |

### 关于 Overture 端点

上游 0.8.x 起，Overture 的瓦片地址不再硬编码版本号，而是由 `OvertureTileUrls.defaultThemeUrl(theme)` 在运行时通过 S3 的 `ListObjectsV2` 自动发现**最新 release**，再拼出 `base.pmtiles` / `buildings.pmtiles` 等文件名。

- 主题与路由的对应关系：`buildings` → `/overture/buildings`，`transportation` → `/overture/roads`，`water` → `/overture/water`，`base` → `/overture/sand`。
- **地表覆盖（Land Cover）复用 `/overture/sand`**：上游把陆地表覆盖改成了 Overture 的 `base` 主题，而 `base` 与沙地指向同一个 `base.pmtiles` 压缩包，因此镜像端不需要新增路由。
- 镜像地址失效时会**自动降级**到官方地址重试（见下方「候选地址」说明）。

### 关于 Overture 沙地 / 地表覆盖的镜像建议

因为 `base.pmtiles` 体积很大（数百 MB 级别），自建镜像时建议：

1. 让 Workers 直接**回源到 Overture S3**并做 Range 透传；
2. 或者把整个 `base.pmtiles` 同步到国内对象存储。

PMTiles 依赖 HTTP `Range` 请求，反代**必须支持并透传 `Range` 头**，否则会退化为整文件下载。

---

## 三、候选地址与自动降级

镜像路由随时可能失效（例如反向代理配置出错返回 500，或尚未实现某条路由返回 404）。为避免"镜像一挂、功能全废"，TellusCN 对**所有支持镜像的数据源**都采用「候选地址列表」：

1. **优先地址**：镜像地址（若启用）或 JVM 参数指定的地址；
2. **兜底地址**：官方默认地址。

按数据源类型分成两套实现：

| 机制 | 适用数据源 | 行为 |
|------|-----------|------|
| `PmTilesRangeReader` 多候选 | Overture 建筑 / 道路 / 水域 / 沙地（含地表覆盖） | 读取文件头时按顺序尝试每个候选地址，某个地址失败只记录一条警告并换下一个，只有全部失败才抛错；读取期间缓存并复用当前生效的地址。 |
| `EndpointFallback` 通用降级 | 天气、地理编码、游戏内地图瓦片 | 逐个候选地址执行一次请求，第一个成功的结果直接返回；中途的 `IOException`（网络错误、非 2xx、响应体损坏）会被记录并继续尝试下一个，全部失败才抛出最后一个错误。 |

需要注意的边界条件：

- **参数错误不降级**：经纬度越界这类调用方参数问题会直接抛出 `IllegalArgumentException`，不会被当成"地址挂了"而白试一遍。
- **地图瓦片的取消语义**：任务被取消时抛出的 `InterruptedIOException` 会立即向上传递，不会继续尝试下一个候选，避免做无用功。
- **候选列表为空**：抛出带描述的 `IOException`，而不是静默返回空结果。

由于有这层兜底，**镜像路由部分失效不会导致游戏功能不可用**，只会在日志里留下降级警告，请求会改走官方源（但官方源在国内的直连速度通常不如镜像，可能变慢）。

---

## 四、尚未支持镜像的数据源

以下数据源在合并上游 0.8.3 之后**没有对应的镜像路由**，请求始终走官方地址。如果国内直连缓慢，目前只能靠代理：

| 数据源 | 说明 | 官方地址 | 相关属性 |
|--------|------|---------|---------|
| 陆地高程 | 上游新的主高程源（WebP 瓦片格式） | `https://tiles.mapterhorn.com` | 无系统属性 |
| 海面高度 | 海域高程填充 | `https://tiles.openwaters.io/seascape` | 无系统属性 |
| Mapterhorn 覆盖范围 | 高程可用范围元数据 | `https://single-archive-tiles.mapterhorn.com/coverage` | 无系统属性 |
| ESA WorldCover COG | 地表覆盖兜底数据源（分级分块 TIFF） | `https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map` | `tellus.worldcover.baseUrl` |
| OISST 海温 | 海洋气候数据 | `https://www.ncei.noaa.gov/erddap/griddap/ncdc_oisst_v2_avhrr_by_time_zlev_lat_lon` | `tellus.oisst.endpoint` |
| ETH 冠层高度 | 树木高度栅格 | `https://tiledimageservices.arcgis.com/.../10m_Tree_Canopy_Height/ImageServer` | `tellus.canopyHeight.serviceUrl` |

> **为什么高程没有镜像了？**
> 旧版本的 `tellus.elevation.endpoint` 指向的 `/elevation` 路由返回的是 Terrarium PNG 瓦片；上游 0.8.x 换成了 Mapterhorn / OpenWaters 的 **WebP** 瓦片，两者格式与瓦片编码都不同，旧路由无法直接复用。如需镜像高程，需要在 Workers 上**新增** `/mapterhorn` 与 `/openwaters` 路由并透传 Range，再在代码里为 `TellusElevationSource` 增加对应的系统属性。

---

## 五、缓存目录

数据会自动缓存到游戏目录下的 `tellus/cache/`：

```
.minecraft/tellus/cache/
├── elevation-mapterhorn/            # 陆地高程瓦片（当前主源）
├── elevation-mapterhorn-coverage/   # 高程覆盖范围元数据
├── elevation-openwaters/            # 海面高程瓦片
├── elevation-normalized/            # 归一化后的高程数据
├── land-cover-overture/             # Overture base 主题地表覆盖
├── worldcover-2021-v200-range/      # ESA WorldCover COG 兜底数据
├── canopy-height-eth-2020-v1/       # ETH 冠层高度
├── ocean-oisst-v21/                 # OISST 海温
├── koppen/                          # 柯本气候分类栅格
├── preloaded-terrain/v1/            # 预载地形
└── map/                             # OSM 相关数据
    ├── buildings/                   # Overture 建筑 PMTiles 与解析结果
    ├── roads/                       # Overture 道路 PMTiles
    ├── water/                       # Overture 水域 PMTiles
    ├── sand/                        # Overture base / 沙地 PMTiles
    ├── infrastructure/              # Overture 基础设施
    └── land-mask/                   # 陆地掩码
```

> 注：`elevation-tellus/` 与 `worldcover2021/` 是旧版本遗留目录，新版本不会再写入，可以直接删除。

缓存目录中每个子目录下还有一层 `cacheNamespace(...)` 生成的子目录，用于在**切换数据源地址或上游 release 版本**时自动隔离新旧数据，避免读到不匹配的缓存。

删除整个 `tellus/cache/` 可以强制重新下载；缓存本身可安全删除，不会损坏存档。

---

## 六、自己搭建镜像（Cloudflare Workers）

镜像只需是一个反向代理，把上表中的「镜像路由」映射到对应的官方地址，并满足：

1. **透传 `Range` 请求头与 `206 Partial Content` 响应**（PMTiles 必需）；
2. 透传 `Accept` / `Content-Type`，不要对二进制内容做改写；
3. 对 GitHub Releases（`/landmask/`）做 302 跳转或回源；
4. 建议加上 CORS 头与合理的超时/重试。

配置好之后，在游戏内的「镜像设置」里选择**自定义**并填入域名即可，无需重新打包模组。

---

## 七、代理方案（不使用镜像时）

```
-Dhttp.proxyHost=proxy.example.com
-Dhttp.proxyPort=8080
-Dhttps.proxyHost=proxy.example.com
-Dhttps.proxyPort=8080
```

注意 Java 的 `HttpURLConnection` 默认走系统代理设置，若使用 SOCKS 代理需额外配置 `socksProxyHost` / `socksProxyPort`。

---

## 八、联机（多人游戏）部署

TellusCN 是**服务端权威**模组：地形、高程、道路、天气等数据全部由服务端抓取，客户端只负责展示。因此联机时有几条硬性要求。

### 8.1 基本要求

1. **服务端必须安装 TellusCN**，否则不会生成地球地形。
2. **服务端必须能访问外网**。所有地形与天气数据都从服务端侧拉取。
3. 客户端**建议**一并安装。未安装 TellusCN 的客户端可以进服并正常游玩，但收不到天气、地形下载状态与握手包，也无法使用 GeoTP 传送界面。

### 8.2 数据源配置只在服务端生效

镜像开关、自定义镜像域名、代理参数等配置**只在服务端对地形生成生效**。在客户端改动这些配置不会影响它从服务端收到什么。

### 8.3 服务器配置文件 `config/tellus-server.properties`

服务端首次读取配置时会自动生成一份带默认值的模板，包含以下键：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `geotp.policy` | `op_only` | GeoTP 传送策略，可选 `op_only` / `everyone` / `disabled` |
| `geotp.cooldown_ms` | `3000` | 单个玩家两次传送请求之间的最小间隔，单位毫秒；`0` 表示不限流 |
| `terrain_view.cooldown_ms` | `1000` | 单个玩家两次地形视距上报之间的最小间隔，单位毫秒；`0` 表示不限流 |

冷却值允许范围是 `0` 到 `3600000`（1 小时），超出范围会被自动夹取到边界值。

### 8.4 传送策略

| 策略 | 行为 |
| --- | --- |
| `op_only` | 只有权限等级 2 及以上的玩家能打开传送地图并使用 GeoTP。默认值。 |
| `everyone` | 所有玩家都能传送。 |
| `disabled` | 完全关闭传送功能，所有人都不能传送（包括管理员）。 |

管理员可以在游戏内用以下命令热改策略，**无需重启服务器，也无需让玩家重连**：

```
/tellus config geotp policy <op_only|everyone|disabled>
```

该命令自身始终要求权限等级 2，不受当前策略影响，因此把策略设成 `disabled` 之后仍然能改回来。

> 提示：无论策略怎么设，**客户端界面上的按钮状态只是提示**，真正的放行判断始终在服务端执行。

### 8.5 协议版本与断开行为

客户端进入游戏后会主动上报自己的 TellusCN 联机协议版本，服务端据此判断是否匹配：

- **双端都装了 TellusCN 且协议不匹配**：服务端会直接断开该玩家，断线界面上会写明双方的协议版本号，按提示把双端模组升级到同一版本即可。
- **服务端装了 TellusCN、客户端没装**：客户端不会上报，服务端也不会主动断开；玩家可以正常游玩，只是用不了传送与天气等联机功能。
- **服务端没装 TellusCN**：客户端不会主动断开，按原版服务器正常游玩。

### 8.6 已知限制

- 高程数据源（Mapterhorn / OpenWaters）没有镜像，服务端必须能直连或走代理，否则地形会生成失败。
- 联机时加载界面的数据来源署名依赖「服务端是否装了 TellusCN」的判断，这是加载阶段能拿到的最可靠近似。

---

## 九、常见问题

**Q：建筑/道路不生成？**
先确认 Overture 端点能访问。若启用了镜像，检查镜像的 `/overture/*` 路由是否可用；镜像失败会自动降级到官方地址，日志里会出现 `falling back to the next candidate` 相关的警告。如果官方地址在国内也连不上，那就只能走代理。

**Q：天气/地理编码报错或搜索不到地点？**
这两项同样有镜像优先、官方兜底的降级机制，请查看日志里是否有降级警告。若警告显示两个地址都失败，说明是网络问题，需要配置代理。

**Q：地表覆盖看起来不对？**
检查 `/overture/sand` 路由。地表覆盖与沙地共用 Overture `base` 主题，路由失效会降级到官方源。

**Q：地形高程加载很慢？**
高程走 Mapterhorn / OpenWaters，**目前没有镜像**，只能靠网络或代理。这是当前版本的已知限制。

**Q：镜像设置改了要重启游戏吗？**
不需要，设置会立即写入配置文件并在下次请求时生效。已有的 PMTiles 连接会复用旧地址直到缓存过期。

**Q：联机时传送按钮是灰的？**
先确认服务端的传送策略：`op_only` 时非管理员玩家会看到灰按钮（鼠标悬停会提示「仅管理员可用」），`disabled` 时会提示「本服务器已关闭传送功能」。改策略请见第八节。注意按钮只是提示，就算按钮是亮的，服务端仍会独立校验一次权限。

**Q：进服后提示协议不匹配被踢了？**
说明双端 TellusCN 版本不一致。断线界面会写出客户端与服务端各自的协议版本号，把双端模组升级到同一版本即可。

---

## 十、反馈

- 上游 Tellus 问题：https://github.com/yucareux/Tellus/issues
- TellusCN（本分支）问题：https://github.com/happy66dev/TellusCN/issues

## 十一、许可证

本分支与上游一致，遵循 LGPL-3.0-only。
