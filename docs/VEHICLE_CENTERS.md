# 0.5.0 车辆中心与运营接口

## 升级与存档隔离

全局连接配置和密钥仍在实例的 `config` 目录。白名单、车辆中心及订单日志改为随存档和维度保存，单机和独立服务器使用同一套定位逻辑：以 MTR 该维度 `depots` 数据目录的父目录为基准，创建 `taxilink-state`。

例如 MTR 数据位于 `<存档>/mtr/minecraft/overworld/depots` 时：

```text
<存档>/mtr/minecraft/overworld/taxilink-state/
  taxilink-depots.json
  taxilink-centers.json
  <维度摘要>/<订单摘要>.json
```

实际 MTR 数据根目录可能因安装方式不同而不同，以该存档的 MTR 目录为准。两个存档即使维度、车厂 ID 相同也不会共享授权或订单。网页增删白名单即时生效，游戏内 `/taxilink reload-depots` 重新读取当前已加载存档的白名单。

**升级前先让 0.4.x 的活动订单结束，再停服备份。** 独立服务器旧工作目录 `taxilink-state` 中的历史订单不会自动猜测归属存档并迁移。需保留历史去重时，将其中的维度摘要目录复制到新位置，再启动。单机原有订单目录位置保持不变。新版本不会自动授权旧 `config/taxilink-depots.json` 中的 ID；首次进入每个存档后通过网页重新授权，或停服后将旧文件复制到该存档的新位置。保留旧文件备份，避免误导入其他存档。

新发起的候补订单和活动订单均记录日志。服务器重启后，中断订单进入 `RECOVERED`，不会自动重复出车；重新下单使用新订单号。有人占用时保持原有恢复保护，需先处理乘客再恢复。

## 车辆中心

所有命令仍使用标准 `v/serverId/requestId/dimension/params` 信封和 SM2 签名。

- `taxi.centers.list`：`params={}`，返回 `data.centers`（中心 ID 到定义的映射）。
- `taxi.centers.put`：保存完整定义，`params={centerId,center}`。
- `taxi.centers.remove`：`params={centerId}`。
- `taxi.centers.reload`：从该存档的 `taxilink-centers.json` 重新导入。解析失败保留原内存快照。

定义示例：

```json
{
  "centerId": "city",
  "center": {
    "name": "城区车辆中心",
    "depotIds": ["123456789012345678", "123456789012345679"],
    "routes": [
      {"id":"airport","name":"机场接送","pickupPlatformId":"123456789012345680","dropoffPlatformId":"123456789012345681"}
    ]
  }
}
```

每个中心至少一个车厂；每个车厂须已授权、专用于 TAXILink、恰有一条停车轨道。同一中心可有多条路线，`routes` 也可为空。每维度全部中心定义的紧凑 JSON 上限为 32 KiB，防止目录超过通信包限制。中心按定义顺序寻找空闲车厂，排除被订单占用、有在途车辆或有乘客的车厂。多个中心共享同一车厂时仍只能有一单占用它。**一个车厂多条停车轨道的并行调度尚不支持**：MTR 的路线表和发车计划属于整个车厂，不能将其视为可独立改写的轨道参数。

中心有活动或候补订单时拒绝修改/删除它；有中心订单时拒绝整体重载中心文件。先结束或取消订单，再修改。删除授权不影响已运行订单，但候补订单不能使用已撤销授权的车厂。

询价和发单时传 `centerId` 代替 `depotId/sidingId`；可用 `centerRouteId` 代替上下车站台，或不传它并直接指定站台。车辆编组、人数、限速等参数保持不变：

```json
{
  "centerId": "city",
  "centerRouteId": "airport",
  "orderId": "trip-001",
  "passengerCount": 2,
  "maxSpeedKmh": 30,
  "accelerationMps2": 1,
  "decelerationMps2": 1,
  "vehicleCars": [{"vehicleId":"minecart","length":1,"width":1,"capacity":2,"bogie1Position":0,"bogie2Position":0,"couplingPadding1":0,"couplingPadding2":0}]
}
```

仍先 `taxi.quote`、用户确认后 `taxi.create`，后者加 `quoteId`。中心报价使用第一个车厂检查三段连通性；实际派车可能选中心内另一车厂并重新寻路，其接客里程和到达时间可能不同，乘客接送路段保持相同。运营者应保证所有中心车厂与该中心各条接送路线连通，并能容纳指定编组；实际生成失败仍会返回订单失败事件。Gateway 自动将报价时的中心配置 SHA-256 摘要 `centerRevision` 传给模组，配置改变后返回 `QUOTE_STALE`，需要重新询价。

## 候补队列

无空闲车厂或达到活动订单上限时返回 `phase=QUEUED`，包含 `centerId`、`queuePosition`（从 1 开始）及 `queuedUntil`（Unix 毫秒）。每个中心按接收顺序兑现候补，释放车厂后自动派下一单。超时进入 `CANCELLED`，`error=QUEUE_TIMEOUT`。`taxi.destroy` 可取消候补，不会修改其正在等待的车厂。

实例 `config/taxilink.json` 新增：

```json
"queueTimeoutSeconds": 120,
"maxQueuedRides": 256
```

等待阈值范围 1～86400 秒；队列上限为每维度。排队时间使用实际时间；游戏暂停期间无法处理模拟器任务，恢复后会检查已到期订单。默认调度脉冲 250 毫秒。

## 状态、乘客与订单动态

兼容保留 `phase` 生命周期；前端应显示 `stageLabel`，按 `stage` 判断行程阶段：

| stage | 中文 |
| --- | --- |
| TO_PICKUP | 前往上车地 |
| WAITING_PICKUP | 等待上客 |
| ON_TRIP | 行程中 |
| WAITING_DROPOFF | 等待下客 |
| RETURNING | 回库中 |

`taxi.passengers` 返回当前 `passengers`（`uuid/playerName/car/driver/boardedAt`）及上下车访问记录 `passengerHistory`（额外含 `alightedAt`，尚在车上时省略）。同一个玩家重复上车分别记录。名字来自 Minecraft 服务端在线 GameProfile；暂未取得或非玩家实体时名称为空。上下车时间是模组观察到 MTR 登车记录变化的实际时间，精度受模拟器脉冲和 MTR 客户端上报影响。掉线造成登车记录消失也记为一次离车观察。

历史记录支持 `offset`（默认 0）和 `limit`（默认 100，最多 100），返回 `historyTotal/nextOffset`。当前在车乘客每页最多 100 人，使用 `passengerOffset` 翻页，返回 `nextPassengerOffset`；`onboardCount` 始终为实际全部人数。`taxi.status` 保留当前在车乘客首页。历史记录随订单持久化，终态后仍可查询。

`taxi.list` 也支持 `offset/limit`（最多 100），返回 `rides/total/nextOffset`，同时按字节预算提前结束一页。Gateway 重新连接时应按 `nextOffset` 枚举所有订单；网页已自动翻页。

`taxi.logs`：`params={orderId,offset?}`，每页最多 100 条，返回 `items/total/nextOffset`。每条包含 `sequence/timestamp/type/stage/detail`。记录创建、候补、超时、路线生成、出库、等客、上下车、车门指令及实际开闭变化、满员和等客超时、出发放行、行程阶段变化、回库、完成、销毁、失败和控速操作。时间均为 Unix 毫秒。持久化日志用于补查，WebSocket 实时事件仍为有界 best effort。

网页会定时刷新选中订单的中文状态和动态。乘客历史通过「查看乘客」读取；列表当前展示一页 100 条，完整历史可通过分页接口查询。

## 轨迹与广播

`taxi.geometry` 保留完整轨迹及进度，增加 `legs`：每段含 `stage/label/startMeters/endMeters`，对应前往上车点、乘客行程、回库三段。网页默认显示总轨迹，可筛选任一段；已走和未走颜色不同。订单完成或销毁后仍能查询已缓存轨迹；车辆尚未生成时可能返回 `VEHICLE_NOT_READY`。完成时进度标记为全程，取消时保留最后进度。

新建 TAXILink 路线会通过 MTR 的服务端广播开关查询接口返回“禁用广播”，客户端只安装原版 MTR 即可。该行为只针对 TAXILink 创建且仍在管理中的临时路线；不改普通路线。广播开关 Mixin 与车门、急停 Mixin 一并包含在两个加载器的相同 common 代码中。

## 运行控制

`taxi.control` 的 `params`：

```json
{"orderId":"trip-001","mode":"speed","speedKmh":15}
{"orderId":"trip-001","mode":"emergency"}
{"orderId":"trip-001","mode":"resume"}
```

`speed` 范围为 1 到该订单原限速，使用 MTR ATO 加减速并保留轨道限速、信号和自动停站。`emergency` 立即归零速度并锁止运动；仍执行 MTR 的占用位置写入和登车同步。急停后拒绝直接改速和出发，必须显式 `resume`（或 `automatic`）解锁。恢复后回到订单原限速，重新遵守信号、车门及站台等待。急停不提供物理制动距离模拟。

这里实现的是远程限速接管，没有打开 MTR 会跳过正常停站的人工驾驶模式。网页提供限速、紧急停车、恢复按钮。生产仍须针对路口、折返、有人乘坐、站台等待等场景实际联调。

受信任业务服务的 SM2 `/signed-command` 支持上述订单控制、乘客及动态查询，但车辆中心和白名单管理仍只允许管理员调试入口。业务端负责订单归属和用户权限校验。
