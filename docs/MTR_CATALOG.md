# MTR 地图目录接口（0.2.0 起）

这些只读命令通过现有 WebSocket 传输，使用相同鉴权、v/serverId/requestId 信封。数据来自选定维度的真实 MTR 模拟器，不限于 TAXILink 创建的对象，也不受派车车厂白名单过滤。Minecraft 客户端仍无需 TAXILink。

## 命令

| type | 功能 | 可选筛选 |
|---|---|---|
| `mtr.dimensions` | 所有已初始化的 MTR 维度名 | 无；不要求 dimension |
| `mtr.depots` | 全部车厂 | 无 |
| `mtr.sidings` | 全部车厂轨道及已配置车型 | `depotId` |
| `mtr.routes` | 全部路线，包括临时/隐藏路线 | 无 |
| `mtr.stations` | 全部车站 | 无 |
| `mtr.platforms` | 全部站台轨道，包括未归属车站的站台 | `stationId` |

先获取维度，再逐个维度分页获取列表；不会把不同维度的 ID 混在一起。

```json
{"v":1,"type":"mtr.dimensions","serverId":"taxilink-01","requestId":"map-dimensions","params":{}}
```

成功 `data`：`{"dimensions":["minecraft/overworld","minecraft/the_nether","minecraft/the_end"]}`，以服务端实际初始化结果为准。

```json
{"v":1,"type":"mtr.depots","serverId":"taxilink-01","requestId":"depots-1","dimension":"minecraft/overworld","params":{"offset":0,"limit":100}}
```

成功 `data`：

```json
{
  "dimension":"minecraft/overworld",
  "kind":"depots",
  "revision":"服务端返回的SHA256字符串",
  "offset":0,
  "limit":100,
  "total":1,
  "nextOffset":null,
  "sampledAt":1793894400000,
  "items":[{
    "id":"-123456789012345678",
    "name":"网约车专用车厂",
    "color":"000000",
    "transportMode":"TRAIN",
    "position":{"x":100,"y":64,"z":200},
    "sidingCount":1,
    "allowedForTaxi":true,
    "routeIds":[]
  }]
}
```

## 拉取全量与变更检测

除 dimensions 外每个命令都分页，`offset` 默认 0，`limit` 默认 100、范围 1..500；items 按有符号数值 ID 升序。响应按约 48 KiB 的 items JSON 预算自动缩页，即使未达到 limit 也可能还有下一页。务必使用返回的 `nextOffset`，直到其为 null；不要自行用 limit 推算。

首次响应保存 `revision`，后续页在相同 params 中传 `revision` 与新的 offset。revision 针对当前维度、当前命令及筛选结果；途中数据变化返回 `CATALOG_CHANGED`，丢弃旧分页，从 offset=0 重新开始。各类列表不保证跨命令同一时刻的事务快照。offset 超出总数、负数或小数返回 `INVALID_REQUEST`。空结果 `total=0,items=[],nextOffset=null`。极端单条记录大于 48 KiB 返回 `CATALOG_ITEM_TOO_LARGE`，需检查该 MTR 对象配置。

PHP 伪代码（sendAndWait 由异步网关/业务端实现）：

```php
$params = ['limit' => 100];
$items = [];
do {
    $response = sendAndWait($protocol->command('mtr.depots', $params));
    // ok=false 时按错误码处理；CATALOG_CHANGED 时重启整个分页，不保留旧 items。
    $data = $response['data'];
    $items = array_merge($items, $data['items']);
    $params['revision'] = $data['revision'];
    $params['offset'] = $data['nextOffset'];
} while ($params['offset'] !== null);
```

## 列表字段

每个 item 都有 `id/name/color/transportMode`。ID 全部为有符号十进制字符串，负数合法；color 是 MTR 的颜色十六进制文本。车站的 transportMode 可能为 null。position 为区域中心或轨道中点，不是车辆位置。

| 列表 | 额外字段 |
|---|---|
| depots | `position, sidingCount, allowedForTaxi, routeIds[]` |
| sidings | `depotId, position, railLength, accelerationMps2, decelerationMps2, vehicleCars[]` |
| routes | `routeNumber, hidden, routeType, platformIds[], taxilinkManaged, orderId` |
| stations | `position, platformCount` |
| platforms | `stationId, stationName, position, dwellTimeMillis` |

`allowedForTaxi` 只表示已入白名单，不保证当前空闲或满足单轨车厂条件。`vehicleCars` 是 MTR 已配置的车辆参数，可作创建订单的车型参考，空数组表示未配置；仍须确保模型/资源有效。未归属区域的 depotId/stationId/stationName 为 null。routes 的 platformIds 保持路线停站顺序，包括重复停靠；`taxilinkManaged` 依据本模组订单日志识别，不靠路线名称前缀猜测。普通路线 orderId 为 null。

常用筛选：

```json
{"v":1,"type":"mtr.sidings","serverId":"taxilink-01","requestId":"sidings-1","dimension":"minecraft/overworld","params":{"depotId":"-123456789012345678"}}
```

```json
{"v":1,"type":"mtr.platforms","serverId":"taxilink-01","requestId":"platforms-1","dimension":"minecraft/overworld","params":{"stationId":"123456789012345680"}}
```

车站 ID 不能直接作为上下车点：创建订单的 pickupPlatformId/dropoffPlatformId 必须用站台轨道 item 的 id。
