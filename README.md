# TAXILink

服务端网约车调度桥接模组。Minecraft **1.21.1**、Architectury API **13.0.8**、MTR **4.1.0-beta.2**、Java **21**。包名前缀 `cn.bg7qvu.htisw.taxilink`。

TAXILink 只装服务端；乘客客户端仍需要正常安装 MTR、MTR 所需依赖及车辆资源包。TAXILink 不注册客户端内容或网络通道。

## 构建与安装

在本目录自行放置 `mtr-4.1.0-beta.2.jar` 后执行。参考模组 jar、参考源码、运行状态和构建产物已加入 `.gitignore`，不随仓库分发；Gradle Wrapper jar 仍保留：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
.\gradlew.bat clean build
# 可选：生成含双平台 jar、配置示例、PHP 文档的交付压缩包
.\gradlew.bat packageRelease
```

构建产物：

- `fabric/build/libs/taxilink-fabric-1.21.1-0.2.0.jar`
- `neoforge/build/libs/taxilink-neoforge-1.21.1-0.2.0.jar`

选对应加载器 jar 放入服务端 `mods`。不要安装 common jar、sources jar 或同时安装两个版本。服务端另装对应平台的 Architectury 13.0.8 和 MTR 4.1.0-beta.2。根目录提供的 MTR jar 的 NeoForge 元数据声明 Minecraft 1.21.1；Fabric 服务端需要另用 MTR 的 Fabric 发行包。

首次启动会生成 `config/taxilink.json`，默认关闭。编辑配置，设置 WebSocket 地址、服务端标识、专用车厂白名单。通过启动服务器的环境变量 `TAXILINK_TOKEN` 设置令牌，重启生效。参考 `config.example.json`。

```powershell
$env:TAXILINK_TOKEN = 'replace-with-a-long-random-token'
# 在同一进程环境下启动你的 Minecraft 服务端
```

## 调度约束

- 每个订单独占一个白名单车厂，车厂必须恰有一条指定车厂轨道。并发订单准备多个车厂。指定轨道的现有在途车辆或乘客会导致拒绝；空闲停放车辆会被替换。白名单车厂不得用于普通运营。
- 后端传 MTR 原始十进制 `long` ID 字符串，指定上、下车站台轨道，轨道网络由服主提前铺设。上下车站台须不同、交通模式相同、停站时间大于零。不创建实体道路或铺轨。
- 模组创建临时隐藏路线，调用 MTR 寻路完成车厂→上车点→下车点→车厂的连接，然后安排单次出发。返回 `GENERATING` 仅表示受理，`ride.dispatched` 才是已观察到车辆出发。
- 车辆型号、尺寸、转向架位置和容量由后端传入，须与 MTR 车辆资源匹配。模组只验证几何范围与车厂长度，不验证客户端资源是否存在。
- 每单得到 `TX-` 加 UUID 的唯一业务车牌，作为路线号和 WebSocket 标识；路线名称默认 `TAXILink <车牌>`，可用可选 `routeName` 自定义。返回 `routeName/routeNumber/routeHidden`。未添加自定义可视车牌模型。
- 多人同行使用同一个订单、同一上下车点；可选 `passengerCount` 默认 1，不能大于编组总 `capacity`。查询/遥测返回全部在车玩家、`onboardCount/remainingCapacity/overCapacity`。人数是派车容量校验，不强制阻止无关玩家登车，也不自动匹配或合并不同目的地的订单。
- 加减速度输入 m/s²，内部转换成 MTR 的 m/ms²。订单限速应用于该单路径副本，正常自动驾驶、制动预判均读取副本；不修改公共实体轨道限速。
- 开关门只接受静止车辆，目标持续到下一次车门指令。开门可能阻止自动出发，应在上客完毕后关门。车辆司机手动切换逻辑不作为后台遥控驾驶功能。
- 乘客取 MTR 登车记录，包括 UUID、车厢索引和司机标记，不用附近玩家距离推断；受 MTR 客户端上报延迟影响。有人时拒绝销毁，不提供强制踢客。
- 坐标为实际车辆头部坐标。ETA 是沿剩余路径距离/各段限速与停站时间求和的自由流估算，未计算信号等待、加减速、拥堵或额外开门等待；已越过目标或尚未生成车辆返回 null。PHP 应显示“预计”，不能用它作为计费或准点承诺。
- 完整回厂后自动结束，清除临时路线、车辆并恢复车厂和车厂轨道配置。异常退出后根据 `taxilink-state` 写前日志恢复；中断订单变为 `RECOVERED`，不自动重新派车。重试旧 orderId 只返回旧状态，新派车使用新 orderId。
- 不支持自动合单、一个车厂同时派多个目的地、运行中改道和跨维度行程。

不要在订单运行中用仪表盘编辑其车厂、车厂轨道或临时路线。恢复会用接单前快照覆盖车厂与车厂轨道配置。状态目录与世界存档需配套备份，多个服务器使用不同工作目录和 `serverId`。首次生产使用前按 `docs/ACCEPTANCE.md` 联调。

## 协议与实现

- PHP 对接协议：[`docs/WEBSOCKET_PROTOCOL.md`](docs/WEBSOCKET_PROTOCOL.md)
- 地图车厂/路线/车站和轨道列表接口：[`docs/MTR_CATALOG.md`](docs/MTR_CATALOG.md)
- PHP 消息辅助类：[`examples/php/TaxilinkProtocol.php`](examples/php/TaxilinkProtocol.php)
- 可独立运行或并入 webman 的 PHP 调试网关：[`examples/php/webman/README.md`](examples/php/webman/README.md)
- 实测记录与联调表：[`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md)

工程用 Java library 分模块构建。公共逻辑只引用 MTR Core 的无映射 API；Architectury 生命周期通过它的公开事件接口注册适配器，故相同字节码可用于 Fabric intermediary 与 NeoForge Mojmap，无 Minecraft 名称重映射需求。车门 Mixin 目标也仅为 MTR Core，`remap=false`。MTR、Architectury、Mixin 与加载器均由运行时提供，不打入成品。

MTR 没有公开枚举模拟器和遥控车门的完整 API，私有字段/方法访问集中在 `mtr/MtrAccess.java`，启动时校验。版本锁定是有意设计，升级 MTR 必须重新核对并联调。官方源码参考：<https://github.com/Minecraft-Transit-Railway/Transport-Simulation-Core>；本次适配以用户提供的 beta jar 字节码为准。
