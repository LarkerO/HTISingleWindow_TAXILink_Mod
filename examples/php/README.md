# PHP 对接

`TaxilinkProtocol.php` 是 PHP 8.1+ 的消息编解码与鉴权辅助类，不绑定 Workerman、Swoole 或 Ratchet，也不自己启动 WebSocket 服务。

0.2.0 附带可直接启动的 Workerman 调试网关，以及同一处理器的 webman 自定义进程配置。启动和 HTTP 调试方法见 [`webman/README.md`](webman/README.md)。地图接口见 [`../../docs/MTR_CATALOG.md`](../../docs/MTR_CATALOG.md)。

```php
require __DIR__ . '/TaxilinkProtocol.php';
$protocol = new TaxilinkProtocol('taxilink-01');

// 在你使用的框架中，先用 authenticate() 校验握手头，再允许升级。
// 模组发 subscribe 时，记录 serverId 对应的已鉴权连接。
// $connection->send($protocol->command('taxi.list'));
// $connection->send($protocol->status('trip-20261106-0001'));
// $connection->send($protocol->doors('trip-20261106-0001', false));

// 收到消息：
// $message = $protocol->decode($text);
// 用 response.requestId 匹配请求；用 data.orderId 更新订单状态。
```

后端至少保存：serverId、dimension、orderId、原始 params、requestId、plate、routeId、vehicleId、最新 phase 和收到时间。重连收到 subscribe 后查询 list，再对本地未完成订单查询 status。所有创建重试保留原始 params 和 orderId，requestId 可重新生成。不要将 WebSocket 发送成功等同于车辆已发出。

实际创建 JSON 见 `docs/WEBSOCKET_PROTOCOL.md`。PHP 后端必须配置 TLS、HTTP 升级鉴权、RFC 6455 ping/pong 响应和连接断开清理，依具体 WebSocket 框架实现。
