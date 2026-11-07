# 验证与实服验收

记录日期：2026-11-07（Asia/Shanghai）。

## 0.5.0 回归

- 使用用户实际安装的 Fabric MTR 4.1.0-beta.2 JAR 执行完整 38 项 Java 测试，38 项通过、无跳过；根目录 NeoForge MTR JAR 使用同一套测试。可通过 `TAXILINK_TEST_MTR_JAR` 切换测试依赖，不会将 MTR 打包进成品。
- 覆盖世界白名单/中心隔离、中心多车厂分配、FIFO 候补、活动上限下超时取消、取消候补不修改忙碌车厂、报价配置变化、实际回库后自动兑现下一单、订单分页。
- 覆盖中文行程阶段、完整三段与终态轨迹、重复上下车历史及持久化、日志顺序、两套玩家名称映射、路径远程限速、急停登记和恢复。
- 真实 PHP/SM2 测试覆盖签名握手、HTTP 往返、报价绑定与业务请求验签/防重放。PHP 协议辅助类、费率边界和语法检查通过。
- Chrome 无界面网页测试覆盖中心保存、中心路线询价/发单、玩家名和上下车时间显示、动态列表、限速/急停/恢复、定时轨迹、行程段筛选及 390px 布局。脚本为 `examples/php/tests/ui.cjs`，通过 `TAXILINK_PLAYWRIGHT` 与 `TAXILINK_TEST_CHROME` 指定测试工具路径。
- 原版两平台 MTR 的 Vehicle、VehicleExtraData、PathData、Siding、Depot 关键反汇编在去除常量池编号与格式差异后相同；广播开关方法的名称和参数也一致。

Core 测试不加载 Minecraft/Mixin，急停测试验证控制登记、速度归零及恢复；持续运动锁止和广播关闭的 Mixin 仍需在两种加载器的真实游戏内验收。所有自动化通过不代表不存在未知问题。

新增实服验收：分别进入两个 ID 相同的存档确认授权不串档；中心连续发三单确认候补顺序和超时；真实乘客上下车并核对名字、时间；检查车内下一站广播关闭；行驶/站台/信号前急停，确认停稳、占用不丢失且可恢复。操作前结束旧版本活动订单，并按 [迁移说明](VEHICLE_CENTERS.md) 备份。

## 已验证

- Java 21 + Gradle 8.14 构建 Fabric、NeoForge 两个独立服务端 jar。
- 0.2.0 自动化测试共 21 项（协议 3、实际 MTR 调度/恢复/地图目录 16、WebSocket 1、真实 PHP 网关 1）。未配置 PHP 时最后一项会跳过；本次已配置 PHP 8.3.36 并执行。
- 自动化测试直接使用根目录提供的 **MTR 4.1.0-beta.2 实际 Core 类**，不是 MTR mock。
- 完整模拟行程：生成路线→从车厂出发→经过上下车点→回厂→清理路线和恢复配置。
- 行程中多次 `Simulator.setGameTime` 后仍运行并完成；实际速度不超过订单限速。
- 主路径断开时 MTR 寻路失败，订单返回失败并恢复。
- 同单去重、不同参数冲突、车厂占用、白名单拒绝、销毁幂等、加速度与车型恢复。
- 重启中断订单恢复，不重派；日志终态比 MTR 世界自动保存更新时也清理旧出租车，避免复活。
- 真实 MTR 登车/下车记录检测，有人拒绝销毁，静止车辆门控命令与覆盖状态登记。
- 64 位正负 ID、类型和范围校验。
- 本机真实 RFC 6455 握手，Authorization/服务端身份头，订阅消息，分片文本重组。
- 地图车厂/车厂轨道/路线/车站/站台读取，非白名单车厂仍可查询，路线停站顺序和来源识别，稳定分页与地图变更检测。
- 自定义路线名称、预约人数与容量校验；三名玩家同时登车、部分下车后仍禁止销毁、最后一人下车后销毁。
- PHP 协议辅助类脚本测试通过，PHP 文件语法检查通过。真实启动 Workerman 5.2.3，验证 HTTP 鉴权/离线/请求往返/事件与断线，查询响应来自实际 MTR Core。

测试报告：`common/build/reports/tests/test/index.html`。测试不启动 Minecraft，所以**不等于**游戏服内 Fabric/NeoForge 的加载、Mixin 应用和乘坐联调。PHP 网关测试为本机独立 Workerman runner，webman 接入配置依官方自定义进程接口提供，未在用户现有 webman/生产 PHP 项目内执行。

目标依赖 SHA-256：

```text
mtr-4.1.0-beta.2.jar
22f95f3210c595535cd3f25d7d6bf8b650012ca0e315093d98270a6432720e17
architectury-13.0.8.jar
5ec578f814e8cca87aeffa6e424032e78d9ea5ea6b603dd834c2dc13c31141ee
```

## 服主与 PHP 联调清单（尚待执行）

请分别在 Minecraft 1.21.1 Fabric 和 NeoForge 测试存档执行，不应直接把未联调版本用于生产派单。

1. 服务端装对应平台 MTR 4.1.0-beta.2、Architectury 13.0.8、TAXILink；客户端装 MTR 及依赖和车型资源，确认没有 TAXILink 也能入服。
2. 检查日志没有依赖或 `taxilink.mixins.json` 注入错误。配置启用前 TAXILink 不主动联网。
3. 专用车厂仅留一条车厂轨道，铺好全程连接，站台正反向、折返和停站时间正确。白名单填实际有符号 ID。
4. 设置 WSS、令牌和 serverId，PHP 验证握手后收到 subscribe。发 taxi.list 确认维度。
5. 用实际车辆资源 ID 创建订单，观察 accepted、route_ready、dispatched；MTR 仪表盘与实际车辆、车牌路线号一致。
6. 乘客在上车点登车，PHP passenger UUID/车厢正确。静止时 open 保持开门、closed 关门；运行时门控返回 VEHICLE_MOVING。
7. 检查位置对应实际车辆头部，ETA 显示为估算。下客后回厂终态完成，车厂参数恢复。
8. 创建不可达路径，确认 ride.failed；重复相同 orderId 不增发。第二个订单占用相同车厂返回 DEPOT_BUSY。
9. 有乘客时销毁被拒绝；下客后销毁成功。核查客户端玩家没有悬空、重力或穿墙状态异常。
10. 重启 PHP/WebSocket，车辆不中止，自动重连；PHP 用 list/status 对账。
11. 测试存档中中断 Minecraft 进程并重启，确认没有重复出租车，原订单 RECOVERED；正常结束后重启也没有鬼车或残留路线。
12. 多车厂并发派单，核查各单路线、限速、车门和回收互不干扰。

正式接入前需确认后端的 WebSocket URL、令牌、serverId、维度、实际车厂/站台 ID 和车型几何参数；这些没有硬编码在 jar 中。
