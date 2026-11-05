# TAXILink WebSocket 协议 v1（PHP 对接）

## 连接方向与鉴权

Minecraft 模组作为 WebSocket **客户端**主动连接 PHP 的 WebSocket 服务端。URL 可带路由路径；生产必须 `wss://`。本机调试只有显式设置 `allowInsecureLocalhost=true` 才允许 `ws://localhost` 或回环地址。

HTTP 握手头：

```http
Authorization: Bearer <TAXILINK_TOKEN 环境变量>
X-TAXILink-Server: taxilink-01
```

PHP 在升级连接前验证令牌与服务端身份，按 serverId 路由订单，禁止向未鉴权连接下发消息。应用不再传明文令牌。服务端需响应 RFC 6455 ping/pong：模组每 20 秒 ping，60 秒没有 pong 断线重连。指数重试约 1、2、4、8、16、32、60 秒并加随机抖动。只接受 JSON 文本，默认单消息上限 64 KiB。二进制关闭码 1003，超长消息 1009。

连接成功，模组发订阅消息：

```json
{"v":1,"type":"subscribe","serverId":"taxilink-01","timestamp":1793894400000,"topics":["taxilink.commands.taxilink-01"],"capabilities":["taxi.create","taxi.status","taxi.list","taxi.doors","taxi.destroy","taxi.passengers"]}
```

PHP 将该已鉴权连接绑定订阅频道，可回 `{"type":"subscribed"}`；不要求特定消息代理或广播封装。下发命令就是以下顶层 JSON 对象，不能包成 `message/data/payload` 外层。

0.2.0 起 capabilities 还包含 `mtr.dimensions/mtr.depots/mtr.sidings/mtr.routes/mtr.stations/mtr.platforms`，用于获取地图全部已有 MTR 对象。完整字段与分页方法见 [`MTR_CATALOG.md`](MTR_CATALOG.md)。协议版本仍是 v1，旧订单参数兼容。

## 通用约定

- `v` 为数字 1。`serverId` 必须匹配配置。`requestId` 是后端生成的非空字符串，每个请求唯一；响应原样带回。
- `dimension` 使用 MTR 的 `namespace/path`，例如 `minecraft/overworld`，不是 Minecraft 的冒号写法。
- `depotId`、`sidingId`、`pickupPlatformId`、`dropoffPlatformId`、`routeId`、`vehicleId` 都是**有符号十进制 long 字符串**，允许负数，禁止零。MTR 随机 ID 可能为负数。禁止用 JSON number，避免 JavaScript/PHP 平台整数精度丢失。若仪表盘显示十六进制 ID，先按 64 位二进制补码无损转换；高位为 1 时十进制结果为负数。
- `orderId` 是后端业务幂等键，在 serverId + dimension 内唯一，长度 1..256。同键同 params 重试不重复派车；同键不同 params 返回 `ORDER_CONFLICT`。字段顺序可变，数值与内容保持一致，不加入会变化的重试时间戳。
- 时间戳毫秒 Unix 时间；速度 km/h，加减速度 m/s²，坐标和车身尺寸按方块/米。
- 回包/events 默认 best effort，最多排队 256 条，队列溢出会断线重连。断线不停止车辆。PHP 必须重连后 `taxi.list` + `taxi.status` 对账，不能只依赖事件，不能用新的 orderId 盲目重试。
- 状态日志持久化到服务端 `taxilink-state`。终态记录保留供去重，清理需在停服并确认 PHP 不再重试后进行。

## 创建订单

```json
{
  "v": 1,
  "type": "taxi.create",
  "serverId": "taxilink-01",
  "requestId": "req-0001",
  "dimension": "minecraft/overworld",
  "params": {
    "orderId": "trip-20261106-0001",
    "routeName": "网约车 · 三人同行",
    "passengerCount": 3,
    "depotId": "123456789012345678",
    "sidingId": "123456789012345679",
    "pickupPlatformId": "123456789012345680",
    "dropoffPlatformId": "123456789012345681",
    "maxSpeedKmh": 30,
    "accelerationMps2": 1.0,
    "decelerationMps2": 1.0,
    "vehicleCars": [{
      "vehicleId": "your_actual_mtr_vehicle_resource_id",
      "length": 4.5,
      "width": 2.0,
      "capacity": 4,
      "bogie1Position": -1.2,
      "bogie2Position": 1.2,
      "couplingPadding1": 0.0,
      "couplingPadding2": 0.0
    }]
  }
}
```

`routeName` 和 `passengerCount` 可选，其余参数必填。速度 1..160，加减速度 0.1..4。编组 1..16 节且受 MTR 交通模式长度限制；长度 0.5..100，宽度 0.1..20，容量整数 1..1000，转向架位置在半车长以内，端部连接间隙 0..10。模型 ID 应从实际资源获取，示例不能直接当作存在的模型。尺寸、容量不得与车型不符。站台停站时间须事先在 MTR 设置大于零，模组不修改共享站台停站参数。

`routeName` 为 1..256 字符的非空字符串，默认 `TAXILink <车牌>`；路线号固定为车牌，路线保持 `hidden=true`。概要及事件新增 `routeName/routeNumber/routeHidden`。名称可重复，后端仍须以 `orderId/routeId` 识别订单。

`passengerCount` 为预约人数，默认 1，必须为正整数且不超过所有车辆 `capacity` 之和，否则返回 `INSUFFICIENT_CAPACITY`。同一订单可同时上多名玩家，共用上、下车点；不需要为每个人新建列车。概要返回 `capacity/requestedPassengerCount`，状态/乘客查询返回 `onboardCount/remainingCapacity/overCapacity` 及所有 `passengers`。预约人数不保证实际乘客身份或严格限制登车，模组不主动拦截其他玩家；不同目的地拼单仍不支持。

成功响应：

```json
{"v":1,"type":"response","serverId":"taxilink-01","requestId":"req-0001","timestamp":1793894400000,"ok":true,"data":{"orderId":"trip-20261106-0001","plate":"TX-0123456789ABCDEF0123456789ABCDEF","dimension":"minecraft/overworld","phase":"GENERATING","depotId":"123456789012345678","sidingId":"123456789012345679","routeId":"123456789012345690","vehicleId":null,"error":null}}
```

接单后异步事件为 `ride.accepted` → `ride.route_ready` → `ride.dispatched`，或者 `ride.failed`。`data` 和上述订单概要结构相同。不要在收到 `ok=true` 时就告知乘客车辆已出发。

## 查询、开关门、销毁

以下共用信封，只替换 `type`、`requestId` 和 `params`：

```json
{"v":1,"type":"taxi.status","serverId":"taxilink-01","requestId":"req-0002","dimension":"minecraft/overworld","params":{"orderId":"trip-20261106-0001"}}
```

| type | params | data |
|---|---|---|
| `taxi.status` | `orderId` | 概要、位置、速度、ETA、乘客、车门目标 |
| `taxi.passengers` | `orderId` | `orderId, capacity, onboardCount, remainingCapacity, overCapacity, passengers:[{uuid,car,driver}]` |
| `taxi.doors` | `orderId, state:"open"` 或 `"closed"` | `orderId, doorTarget, control` |
| `taxi.destroy` | `orderId` | 销毁并恢复后的概要，重复调用保持终态 |
| `taxi.list` | `{}` | `rides:[订单概要]`，含历史终态 |

门控只接受已分配车辆、且 `isMoving()==false`。`control=held_until_next_command` 表示该目标会拦截相反的 MTR 自动门控，直到后端发下一次目标；开门期间需要后端完成上客确认并关门以继续行程。有人时销毁返回 `VEHICLE_OCCUPIED`，请让乘客先下车，不会自动踢客。

状态 data 的附加字段：

```json
{
  "sampledAt": 1793894402000,
  "position": {"x":100.5,"y":64.0,"z":20.5},
  "speedKmh": 23.4,
  "doorsOpen": false,
  "etaModel":"free_flow_distance_and_dwell",
  "etaPickupSeconds": 18,
  "etaDropoffSeconds": 96,
  "passengers":[{"uuid":"00000000-0000-0000-0000-000000000000","car":0,"driver":false}]
}
```

`position` 是车辆头部实际坐标。`doorsOpen` 实际表示 MTR 开门**目标**，不是门动画完全打开。`car` 从零开始。ETA 返回秒数或 null；沿途距离/分段限速加停站时间估算，不包括加减速、信号、堵塞、后台长时间开门等待；它通常低于实际所需时间。目标已通过时 null，不是零；车辆未生成时 `position` 与 ETA 均 null，`speedKmh/doorsOpen/etaModel` 可能不存在。

默认每 2 秒推送 `{"type":"taxi.telemetry","data":状态data,...}`，仅非终态。所有模组发出的消息都带 `v/serverId/timestamp`。

## 状态机与恢复

`PREPARED`（写前日志）→ `GENERATING` → `DISPATCHING` → `ACTIVE` → `COMPLETED`。

回厂仍有人时进入 `AWAITING_DISEMBARK`，等待下车后完成。销毁为 `DESTROYED`，失败为 `FAILED`。异常重启恢复为 `RECOVERED` 且 error=`SERVER_RESTART`，不重新发车。这四个为终态。

其他事件：`ride.awaiting_disembark`、`ride.completed`、`ride.destroyed`、`ride.recovered`、`ride.error`。重启过程发出的恢复事件可能早于 WebSocket 连接建立，必须主动对账。错误恢复时仍有乘客则保留车辆并报告 `RECOVERY_REQUIRES_DISEMBARK`，交给后端和管理员处理。

## 错误

```json
{"v":1,"type":"response","serverId":"taxilink-01","requestId":"req-0001","timestamp":1793894400000,"ok":false,"error":{"code":"DEPOT_BUSY","message":"Depot is assigned to another order"}}
```

| code | 处理建议 |
|---|---|
| `INVALID_REQUEST`, `INVALID_JSON`, `UNSUPPORTED_VERSION`, `UNKNOWN_COMMAND` | 修正格式；非法 JSON 为 `protocol.error` 无 requestId |
| `WRONG_SERVER`, `DIMENSION_NOT_READY` | 修正身份/维度或稍后重试 |
| `DEPOT_NOT_ALLOWED`, `DEDICATED_DEPOT_REQUIRED` | 配置专用单轨车厂 |
| `DEPOT_BUSY`, `CAPACITY_EXCEEDED`, `SERVER_BUSY` | 等待或重新分配车厂；同订单重试保持 params |
| `MTR_ID_NOT_FOUND`, `INVALID_ROUTE`, `VEHICLE_TOO_LONG` | 修正 MTR ID、停站、车型和尺寸 |
| `INSUFFICIENT_CAPACITY` | 降低同单人数，或使用容量更大的真实车型/编组 |
| `ORDER_CONFLICT`, `ORDER_NOT_FOUND` | 检查业务幂等键；换维度查询不是同一订单 |
| `VEHICLE_NOT_READY`, `VEHICLE_MOVING`, `VEHICLE_OCCUPIED` | 等待准备、停车或下客 |
| `ROUTE_GENERATION_FAILED`, `GENERATION_TIMEOUT`, `DISPATCH_FAILED` | 异步失败事件，检查全程连接、方向、折返和车厂轨道长度 |
| `INFRASTRUCTURE_REMOVED`, `CREATE_FAILED`, `MTR_ERROR` | 检查服务端日志与运行中编辑 |
| `STORAGE_ERROR`, `RECOVERY_FAILED` | 检查状态目录、备份一致性和管理员恢复 |

异步寻路失败通过 `ride.failed.data.error` 返回错误码；MTR 详细异常记录在服务器日志。后端应为无响应设置超时并重查同 orderId，而非认定创建失败后立即新建订单。
