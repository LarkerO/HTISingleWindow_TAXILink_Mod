# Webman / Workerman 调试网关

建议用 webman 承载常驻 WebSocket 服务、后续订单 HTTP API 和管理页面。Minecraft 模组始终是主动连接的 WebSocket **客户端**；这里的 PHP 是服务端。现有 `TaxilinkProtocol.php` 继续复用。

本例提供同一个 `app\process\TaxilinkGateway` 处理器，可独立用 Workerman 运行，也可并入已有 webman 2.x + Workerman 5.x 项目。无需为了调试强制迁移整个已有后端。官方接口参考：[webman 自定义进程](https://webman.workerman.net/doc/en/process.html)、[Workerman WebSocket 鉴权](https://manual.workerman.net/doc/en/appendices/about-websocket.html)、[同进程监听多个端口](https://manual.workerman.net/doc/en/worker/listen.html)。

## 独立启动（最少步骤）

PHP 8.1+、Composer。每次解压到新的版本目录时，需要在该目录安装 PHP 依赖。Windows 的 `init.bat` 会在缺少依赖时调用 Composer 安装。在同一个 CMD 窗口运行：

```cmd
call init.bat
php start.php start
```

`init.bat` 优先保留当前环境变量，其次读取脚本旁的 `.env.taxilink-mod` 和 `.env.taxilink-debug`，只为缺失项生成随机令牌。网页使用调试令牌。Minecraft 0.2.2 起可在 `config/taxilink.json` 中把 `tokenFile` 指向 `.env.taxilink-mod` 的绝对路径。Windows 路径可用正斜杠，或在 JSON 中写成双反斜杠。若网关使用手动设置的模组令牌，令牌文件内容也要与之匹配。

若调试令牌由脚本生成，可在启动网关前用 `type .env.taxilink-debug` 查看并复制到网页。

也可用 PowerShell 手动设置环境变量：

```powershell
$env:TAXILINK_SERVER_ID = 'taxilink-01'
$env:TAXILINK_TOKEN = 'replace-with-a-random-mod-token'
$env:TAXILINK_DEBUG_TOKEN = 'replace-with-a-different-random-debug-token'
php start.php start
```

Linux 可用 `export` 设置相同环境变量，再 `php start.php start`（或 `start -d`）。两个令牌必须非空且不同。没有硬编码生产令牌。

默认监听：

- Minecraft WebSocket：`ws://127.0.0.1:8788/taxilink`
- 调试 HTTP API：`http://127.0.0.1:8789`

Minecraft 的 `config/taxilink.json` 将 endpoint 设为上面的 ws 地址，并设置 `serverId=taxilink-01` 和 `allowInsecureLocalhost=true`。跨机器部署用反向代理 WSS，并关闭 allowInsecureLocalhost。代理须传递 Authorization、X-TAXILink-Server、Upgrade 和 Connection 头。可通过 TAXILINK_WS_LISTEN 和 TAXILINK_HTTP_LISTEN 修改监听地址。调试 HTTP 默认仅监听回环地址。

## 浏览器调试台

启动网关后，打开 [http://127.0.0.1:8789/](http://127.0.0.1:8789/)，输入 `TAXILINK_DEBUG_TOKEN` 并连接。连接栏会显示 Minecraft 模组是否在线，令牌保存在当前页面的内存中。

选择维度，加载车厂和站台，再加载所选车厂的轨道。可以采用轨道上已配置的车型，也可以编辑编组 JSON。填写人数和行车参数后发送订单。受理后，订单号会自动填入查询区，可查询状态、查看乘客、开关门和销毁车辆。页面也提供订单列表、地图目录和实时事件。

从 0.3.0 起，可在「车厂白名单」中查看地图上的车厂，并对专用单轨车厂添加或移除授权。网页操作会保存到游戏实例的 `config/taxilink-depots.json` 并立即生效。手动编辑文件后，可从网页重新读取，或在游戏里用管理员权限执行 `/taxilink reload-depots`。

发单超时时，先用原订单号查询。重试同一笔订单时保留原订单号和参数。

单机调试时，如果页面显示模组已连接但查询出现 `MOD_RESPONSE_TIMEOUT`，先回游戏退出暂停菜单，按 `F3 + P` 关闭失焦自动暂停，再切回网页重试。WebSocket 在线时，暂停的单机世界仍可能暂时停止处理 MTR 模拟队列。

## HTTP 调试

所有接口都要求 `Authorization: Bearer <TAXILINK_DEBUG_TOKEN>`。

```powershell
$headers = @{ Authorization = "Bearer $env:TAXILINK_DEBUG_TOKEN" }
Invoke-RestMethod http://127.0.0.1:8789/health -Headers $headers

$body = @{ type='mtr.depots'; dimension='minecraft/overworld'; params=@{limit=100} } | ConvertTo-Json -Depth 10
Invoke-RestMethod http://127.0.0.1:8789/command -Method Post -Headers $headers -ContentType application/json -Body $body
```

`POST /command`：body 为 `{type,dimension?,params?,requestId?}`，gateway 自动加入 v/serverId/requestId 并向模组发送，异步等待对应 response，原样返回。可用 `mtr.dimensions`、`mtr.depots`、`mtr.routes` 等全部命令，完整拼写见协议。默认维度 minecraft/overworld。业务失败仍为 HTTP 200，检查 `ok/error`；离线 503、请求无效 400、超时 504、重复未完成 requestId 409。超时不能证明创建失败，用同 orderId 查询/重试。

`GET /health`：连接和排队情况。`GET /events?afterCursor=0`：最近最多 128 条、约 2 MiB 的订阅、响应和遥测事件；保存 cursor 增量查询，缓冲不是持久消息队列。

创建多人订单的 params 可加入 `passengerCount=3`、`routeName='网约车 · 三人同行'`，其余必填参数见主协议。地图列表需要按 nextOffset 分页才能得到全量。网关保留请求 ID 对应关系，不自动做订单持久化或断线重派，后端仍须 list/status 对账。

## 并入已有 webman

1. 确认 PHP >=8.1、Workerman >=5.1。将 `app/process/TaxilinkGateway.php` 和同目录的 `taxilink-debug.html` 一起复制到 webman 的 `app/process` 目录。
2. 把上一级 `TaxilinkProtocol.php` 复制到 webman 项目根目录；把 `config/process.taxilink.php` 复制到 webman 的 config。
3. 在现有 `config/process.php` 的返回数组中合并 `(require __DIR__ . '/process.taxilink.php')`。不要另建普通 HTTP controller 去读取处理器内存。
4. 设置同样的环境变量，正常启动 webman。调试 HTTP 端口仍是 8789，业务 webman HTTP 端口不受影响。

处理器在自己的 WebSocket 进程内动态启动 HTTP listener，所以二者共享连接和 pending 表。**count 必须为 1**；把普通 HTTP 路由放到另一 worker 后直接读静态变量会拿不到模组连接。若未来扩展多 worker/多台服务器，应改为 Redis/Channel 等进程间路由。本例只对接配置的一个 serverId。

## 验证

```powershell
php tests/protocol.php
```

项目的可选 `PhpGatewayTest` 会启动本处理器，使用真实 JDK WebSocket 与真实 MTR Core 查询，验证 HTTP 鉴权、离线状态、订阅、请求往返、事件与断线。设置 TAXILINK_TEST_PHP 为 php 可执行文件绝对路径、TAXILINK_TEST_MOD_TOKEN 为一次性测试令牌后，从仓库根运行 `gradlew.bat test`。未配置 PHP 时该集成测试会跳过。
