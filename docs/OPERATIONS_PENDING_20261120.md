# 待发布运营修复（源码提交，不发版、不部署）

本轮保持版本号不变。配套业务仓库的 `docs/OPERATIONS_PENDING_20261120.md` 给出完整操作、数据保留升级和客户端扩展说明。

旧逻辑仅接管上车点 dwell；下车点受 MTR 原停站时间控制，未等待乘客全部下车。现在使用订单独立路径副本延长下车点 dwell，由 Simulator 线程等待至少 10 秒并连续确认空车 5 秒，关门后覆盖门动画/关门延迟才释放回库；重新检测到乘客会重置确认。人数取自 MTR 骑乘同步，不以附近玩家距离代替。运动保持不取消 MTR 的占用写入及乘客同步。

`taxilink.json` 自动补充 `dropoffMinWaitSeconds=10`、`dropoffEmptyConfirmSeconds=5`、`obstructionWaitSeconds=60`。人工开门保持停车，人工恢复不绕过下客保护。有人时拒绝销毁，下车点有人时拒绝关门；移动中先停稳才能清除。

占轨使用 MTR `railBlockedDistance` 实际结果计时，排除上下客/人工暂停，达到阈值报告 assistanceRequired。占用车辆从当前/上一帧轨道占用段匹配，未找到具体车时可能是信号占用，不能猜测或自动清除。`taxi.clear_returning {orderId,targetOrderId}` 在单次 Simulator 操作中重新核验占用关系、回库阶段、空载与静止，再恢复目标车厂。等待车辆及其车厂不受影响。

客户端无站台侧上车需要玩家额外安装 TAXILink 及其依赖。扩展仅作用于已开门、静止、具有专用 TX 路线号的车辆；不改 MTR jar，不影响普通列车。覆盖的是门口附近站台方块检查，停车轨道/Platform 记录仍供寻路停站使用。Fabric MTR beta.2 的门区过滤目标为 `RenderVehicles.lambda$render$7`，NeoForge 为 `lambda$render$4`（均为 transform/vehicle/doorway → boolean）；只在客户端加载各平台对应 Mixin。JVM `-Dtaxilink.disablePlatformlessBoarding=true` 可禁用。尚需真实客户端/资源包登车验收。

车厂从准备派车至完整回库/人工安全回收期间保持独占；乘客下车、前端订单结算都不释放车厂。原车厂与停车轨道快照、临时路线及控制状态恢复后，候补再重新尝试派车。测试覆盖连续四轮完整复用、回库后候补推进、人工清除及乘客/移动保护。

升级前结束活动行程，备份游戏存档与 taxilink-state，连同后端数据库、安装配置和密钥一起保留。更新模组与配套后端后再启用新控制，不在运行订单中覆盖车厂。旧活动订单重启仍按原有恢复机制处理，不自动重新派车。

验证结果：48/48 模组测试通过，包含真实本地 PHP 网关签名 WebSocket 和新增控制指令对接；Fabric/NeoForge 编译通过。另以 Fabric MTR 测试依赖验证通用模块测试通过。真实 Minecraft 客户端登车仍需测试世界验收，本轮不发布或部署。
