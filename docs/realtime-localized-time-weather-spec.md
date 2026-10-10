# 实时「异地不同」时间与天气设计方案（Spec / Plan）

> 状态：草案 / 未来实现（本文件仅为设计说明，当前不落地编码）
> 适用分支：待定（功能级大改，建议独立 feature 分支）
> 关联代码：`world/realtime/TellusRealtimeManager`、`TellusRealtimeState`、`RealtimeVersionCompat`、`network/TellusWeatherPayload`、`mixin/BiomePrecipitationMixin`

## 1. 目标

在**单一无缝主世界**（`EarthChunkGenerator` 生成的地球地图、所有玩家共用同一个 `ServerLevel`）中，让**时间与天气按真实地理位置局部不同**，并且**玩法物理效果（刷怪、天空亮度、下雨的物理效果、雷暴/落雷）也跟随当地**，而不仅仅是视觉。

数据来源沿用现有实时体系：Open-Meteo 天气 + 时区，经纬度由 `EarthChunkGenerator` 的投影换算。

## 2. 非目标

- 不切分维度（分维度是另一条路线，不在本方案内）。
- 不追求跨 mod 的完美兼容；对会改时间/天气/光照的 mod（季节、光影、Distant Horizons 等）默认视为高风险，提供总开关回退。
- 不保证地理无缝导致的交界处 100% 无突变（见边界处理）。

## 3. 核心难点

原版把 `dayTime` 与 `raining/thundering`（含 rain/thunder 梯度）存成**每个 Level 一份的单值**，服务端玩法逻辑和客户端渲染都读这一份。要做到异地不同，必须把**所有读取点改成「按坐标取值」**。这是一个面大但有界的 mixin 改造。

## 4. 总体架构：位置感知环境层

新增服务端组件 `TellusEnvironmentSampler`，输入 `(blockX, blockZ)` 与当前真实时刻，输出该位置的：

- `localDayTimeTicks`：当地当日 tick（0..23999），由经度/时区 + 真实时钟推导（复用现有 `applyRealtimeTime` 的算法，按单元格计算）。
- `localWeather`：降水模式（CLEAR/RAIN/SNOW/THUNDER）+ 雨量/雷暴强度（0..1）+ 温度（用于雨雪判定与结冰）。

数据底座：现有体系只在「玩家附近 3×3」采样，而本方案需要为**所有已加载区块所在的区域**提供值，因此改为**环境单元格（environment cell）网格 + 缓存**：

- 单元格尺寸：参考现有 `computeGridSpacing(worldScale)` 思路按比例尺定；时间可进一步按时区带对齐。
- 时间：由经度→UTC 偏移推导（优先用 Open-Meteo 返回的真实时区，缺失时回退 `approximateUtcOffsetSeconds` 的「经度/15」），**确定性、几乎零网络**，可长时间缓存。
- 天气：按单元格中心请求 Open-Meteo，TTL 复用 `WEATHER_REFRESH_MS`（10 分钟），存入 `ConcurrentHashMap<cellKey, snapshot>`。**只请求「含已加载区块/玩家」的有限单元格**，无法为整颗星球拉取；缺数据的单元格回退最近已知值或 CLEAR。

## 5. 服务端拦截点（玩法）

以下为需要「改成按坐标取值」的原版读取点；各 MC 版本签名不同，统一经 `RealtimeVersionCompat` 式桥接 + 分版本 mixin 落地。

1. **天空变暗 / 亮度**（驱动刷怪、僵尸日晒、睡眠、作物、红石）：原版 `getSkyDarken()` 全局且无坐标；按坐标的消费路径是 `Level#getRawBrightness(BlockPos,int)`、`getMaxLocalRawBrightness(BlockPos)`。改法：在这些方法里用**当地时间+当地天气**算出的 skyDarken 替换全局值。只影响天空光分量，方块光不变。
2. **按坐标是否在下雨**（驱动灭火、炼药锅接水、耕地湿润、结冰/积雪等）：`Level#isRainingAt(BlockPos)` 是**最高价值单点**，HEAD 覆盖为按当地降水模式判定，大量雨物理会自动跟随。
3. **结冰 / 积雪 / 冰**：`Biome#shouldFreeze/shouldSnow/coldEnoughToSnow` 走当地温度；现有 `canWaterFreeze`、`snowTemperatureOverride` 已基于温度网格，接入 sampler 即可。
4. **雷暴 / 落雷**：`ServerLevel` 打雷逻辑先按全局 `isThundering()` 再随机选点；改为对选中坐标按**当地**雷暴判定；充能苦力怕、骷髅等随之局部化。
5. **昼夜相关行为**：`isDay/isNight/getTimeOfDay/getSunAngle` 用于幻翼、村民作息、阳光探测器、僵尸/骷髅白天燃烧；在能拿到坐标处改读当地时间（阳光探测器用自身方块坐标、幻翼用玩家坐标）。
6. **睡觉跳夜**：实时模式已禁用昼夜循环并把睡眠百分比设为 101；局部时间下没有统一的「夜」可跳，保持禁用并在文档说明。

> 重要：本方案**取代**当前「向 Level 写全局 dayTime/weather」的做法。必须停止全局写入（或写入中性值），否则全局写入会与按坐标覆盖互相打架。

## 6. 客户端拦截点（渲染 / 表现）

即便服务端玩法已局部化，客户端仍从自己的 `ClientLevel` 渲染**一份**天空/时间/天气，必须同步局部化，保证「看到的」与「发生的」一致：

1. 天空 / 日月星 / 天空色：`LevelRenderer#renderSky`、`ClientLevel#getTimeOfDay`、`DimensionSpecialEffects`，按**相机/玩家坐标**的当地时间渲染。
2. 世界亮度：客户端 skyDarken / gamma，按相机所在区域当地时间调整，使画面明暗与当地一致。
3. 降水渲染：`LevelRenderer#renderSnowAndRain`、天气/雾渲染，按相机当地天气渲染雨雪粒子与天空压暗。
4. 数据下发：扩展 `TellusWeatherPayload`（或新增 payload）携带玩家周边**多单元格**的 `{当地时间, 降水模式, 雨/雷强度, 温度}`，客户端按相机位置插值。

## 7. 边界与正确性（含防御）

- **交界突变**：相邻单元格的亮度/时间/天气不同会产生可见接缝。时间本身随经度连续，建议**按经度连续插值**；天气建议对雨量做软过渡；仍可能有带状过渡，列为已知取舍。
- **空数据防御**：网格为空、单元格无数据、坐标越界时回退到全局/CLEAR（遵循错误处理规范）。
- **热路径性能**：`getRawBrightness`/`isRainingAt` 每 tick 被调用成千上万次，**严禁**每次做时区/天气运算；必须按单元格缓存、每 tick 预计算 skyDarken，查表 O(1)。
- **服务端权威**：以服务端为准，客户端渲染必须用同一套当地函数，避免「客户端黑夜但服务端在刷怪」的 desync 观感。
- **光照引擎**：天空光最大暴露值仍为静态存储，仅局部化「随时间的压暗系数」，洞穴/遮挡仍正确。

## 8. 版本兼容

- pre-26（1.20.1 / 1.21.1）：`getDayTime/setDayTime`、`setWeatherParameters`、`GameRules.RULE_*`；亮度 `getRawBrightness(BlockPos,int)`、`getBrightness(LightLayer,BlockPos)`；`isRainingAt(BlockPos)`；`Biome#getPrecipitationAt(BlockPos)`。
- mc262（26.2）：`clockManager()/WorldClock`、`WeatherData`、`GameRules.ADVANCE_TIME/ADVANCE_WEATHER`；亮度与 `isRainingAt` 签名需实现时逐一核对。
- 所有新 mixin 必须像现有 `RealtimeVersionCompat` 一样在 `shared/pre-26`、`shared/mc262`（及 `mc12111` 等）分别适配；**落地前务必核对各版本真实方法签名，禁止臆测**。
- 仅维护 Fabric 目标（见项目既有约定）。

## 9. 分阶段落地（未来）

- 阶段 0：加功能开关 `realtimeLocalized`（默认关），保留现有全局行为作回退。
- 阶段 1：服务端 sampler + 时间局部化（亮度压暗 + 昼夜消费点 + 客户端天空/时间渲染），验证刷怪跟随当地明暗。
- 阶段 2：天气局部化（`isRainingAt` + 生物群系降水 + 结冰/积雪 + 客户端天气渲染）。
- 阶段 3：雷暴/落雷局部化 + 刷怪速率。
- 阶段 4：交界插值打磨 + 性能缓存 + 兼容开关。

## 10. 测试策略

- 纯函数单测：单元格查找、经度→当地时间、单元格降水判定、插值、亮度压暗计算；覆盖 null/空/越界/异步失败（遵循测试规范）。
- 缓存与采样的模拟测试。
- 手动冒烟清单（客户端渲染无法完全自动化）：两名玩家分处相反经度 → 一昼一夜且刷怪各随当地；一人暴雨一人晴 → 灭火/接水/耕地各随当地。

## 11. 已敲定决策（2026-10-10）

1. **时间粒度：连续经度**。当地时间随经度平滑连续变化，跨区无接缝；时区仅用于校正真实 UTC 偏移，太阳角度随移动渐变。时间插值走连续经度，不按时区带整点跳变。
2. **天气粒度：粗单元格 + 软过渡**。单元格复用 `computeGridSpacing(worldScale)` 的大网格以减少 Open-Meteo 请求；相邻单元格之间对雨量/强度做渐变插值，避免天气硬切换。
3. **覆盖范围：配置项可切换，默认「仅玩家周边已加载区」**。新增配置项控制局部化作用范围：默认只在有人/已加载区块处生效、其余回退全局；可切换为「含强加载/预载区」以追求无人区一致性（代价是每 tick 开销与请求更大）。
4. **总开关：`realtimeLocalized`，默认关闭、可回退**。关闭时保留现有全局时间/天气行为，便于与光影、季节等会改时间/天气/光照的 mod 共存。
5. **性能基线**：由「粗单元格」+「默认仅已加载区」共同约束每 tick 开销；热路径（`getRawBrightness`/`isRainingAt`）必须按单元格缓存、每 tick 预计算，查表 O(1)（见第 7 节）。

> 以上为设计决策；具体数值（单元格边长、过渡带宽度、缓存 TTL）在实现阶段结合实测再定。
