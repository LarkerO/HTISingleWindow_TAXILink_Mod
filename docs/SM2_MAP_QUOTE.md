# 0.4.0 联调指南

## SM2 配置

两端需要可执行的 OpenSSL 3.x。Windows 可使用 Git for Windows 附带的 OpenSSL。以下命令生成两套独立密钥，文件存在时脚本会报错退出。

```powershell
cd examples/php
./init-sm2.ps1 -OpenSsl 'C:/Program Files/Git/usr/bin/openssl.exe'
```

- 把 `sm2-keys/mod` 中的模组私钥和 Gateway 公钥放入 Minecraft 的 `config`。模组的 `opensslExecutable` 可配置为 OpenSSL 的绝对路径。
- 把 `sm2-keys/gateway` 中的 Gateway 私钥和模组公钥放到 PHP 的 `examples/php` 目录。并入 webman 时放在 webman 项目根目录。
- PHP 可通过 `TAXILINK_OPENSSL`、`TAXILINK_GATEWAY_PRIVATE_KEY` 和 `TAXILINK_MOD_PUBLIC_KEY` 覆盖默认路径。两端私钥只留在各自运行目录。生产链路继续使用 WSS。
- 网页调试仍使用已有 `.env.taxilink-debug`。Gateway 代表调试台签发模组命令。显式设置模组 `allowLegacyTransport=true` 和 PHP `TAXILINK_ALLOW_LEGACY_TRANSPORT=1` 可临时对接旧版，默认使用 SM2。

SM2 使用 SM3，区分 ID 为 `TAXILink-v2`，签名格式为 DER。信封字段为 `ts`（毫秒）、`nonce`（32 位小写十六进制）、`body`（UTF-8 JSON 的 Base64）、`sig`（签名的 Base64）。签名原文为以 LF 连接的 `TAXILink-v2`、角色、十进制 ts、nonce、body，无末尾换行。验签拒绝超出 30 秒窗口或已使用的 nonce。

模组握手使用 `X-TAXILink-Proof`，角色为 `handshake`，内层是含 `serverId` 的 JSON。模组帧使用角色 `mod`，Gateway 帧使用 `gateway`。需对接业务前端服务时，通过 `TAXILINK_BUSINESS_PUBLIC_KEY` 固定业务端公钥。其 `POST /signed-command` 使用角色 `frontend`，成功验签后的 HTTP 响应使用 `backend`。内层格式与 `POST /command` 一致。允许询价、发单、订单状态和轨迹查询。该公钥代表受信任的业务服务，业务服务负责自身用户权限。业务端须验签并核对请求关联，浏览器不应持有服务私钥。

## 报价与发单

1. `taxi.quote` 接收拟下单的 `taxi.create` 参数与维度。模组使用 MTR 寻路检查车厂至上车点、上车点至下车点、下车点至车厂三段连通性。询价只读，不创建路线、车辆或订单。
2. Gateway 返回乘客路段的 `distanceMeters`、`estimatedSeconds`、`estimatedLowSpeedSeconds`、路线采样、`fare`、`quoteId` 和 `quoteExpiresAt`。`fare.amountMinor` 以分计，`componentsMinor` 列出起步价、里程费、低速费和远途费。
3. 业务侧在用户确认并完成自己的支付流程后，才发送 `taxi.create`。参数与报价一致，并额外传入 `params.quoteId`。Gateway 验证报价绑定后转发给模组。报价保存在 Gateway 内存中，默认有效 5 分钟。它是估算，不锁定车厂或轨道，派车时会重新检查路线和可用性。

费率位于 PHP 的 `webman/config/taxilink-fares.json`，每次询价读取。可通过 `TAXILINK_FARE_FILE` 指向其他文件。初始规则按截图录入，默认一格等于一米，以报价时刻的 Asia/Shanghai 时段计价。低速阈值默认 10 km/h。低速时长和总时长来自轨道限速和起终点加减速近似，未计信号等待。远途费按超过 5 km 和 15 km 的各自区间累进。

## 轨迹绘制

`taxi.geometry` 接收 `orderId`，返回运行中车辆完整行程的 `progressMeters`、`totalMeters` 和 `points`。每行是 `[累计距离, x, y, z, 水平半径一, 水平半径二]`。坐标按 MTR 实际轨道几何取样，半径直接来自 MTR 的双半径接口，不有限的半径为 null。前端按 X/Z 俯视绘制，并在累计距离达到进度的地方分段着色。调试台可勾选「跟踪当前订单」定时刷新。采样上限为 601 个点，长路线会降低采样密度。
