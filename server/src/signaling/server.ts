import type { Server } from "node:http";
import type { Duplex } from "node:stream";
import { WebSocketServer, type WebSocket } from "ws";
import type { AppConfig } from "../config.js";
import type { Logger } from "../shared/logger.js";
import { SignalingHub } from "./hub.js";
import type { DeviceRegistry } from "./registry.js";
import { isBearerAuthorized } from "../http/auth.js";

interface AttachedSignalingServer {
  close(): Promise<void>;
}

type HeartbeatSocket = WebSocket & {
  isAlive?: boolean;
  missedHeartbeats?: number;
  deviceId?: string;
};

/** ws 的 readyState 常量：WebSocket 只是类型导入，取不到静态属性。 */
const WS_CLOSED = 3;
const WS_OPEN = 1;

/**
 * 心跳周期 10 分钟，连续 3 次没有回应才判定失联（约 30 分钟）。
 *
 * 这个值的取舍很直接：待机时每次 ping 都会把手机的射频从 idle 拉到高功耗状态
 * 数秒，60 秒一次等于一天 1440 次唤醒，是待机耗电的主要来源之一。
 * 拉到 10 分钟，唤醒次数降到 144 次，而代价只是「掉线要被发现得晚一点」。
 *
 * 为什么敢放到 10 分钟：运营商 NAT 的 TCP 会话超时通常在 5 分钟以上，
 * 加上客户端有自己的重连与精确闹钟兜底，即使这条连接被中间设备回收也能自愈。
 *
 * 为什么是 3 次而不是 1 次：一次丢包、一次弱网、一次射频休眠都可能漏掉单轮心跳，
 * 用 1 次判定会把「短暂抖动」误判成「失联」，控制端就会反复看到设备上下线。
 */
const HEARTBEAT_INTERVAL_MS = 10 * 60_000;
const MAX_MISSED_HEARTBEATS = 3;

export function attachSignalingServer(
  server: Server,
  config: AppConfig,
  registry: DeviceRegistry,
  logger: Logger,
): AttachedSignalingServer {
  const webSocketServer = new WebSocketServer({
    noServer: true,
    maxPayload: config.maxWebSocketPayloadBytes,
  });
  const hub = new SignalingHub({
    registry,
    logger,
    maximumMessageBytes: config.maxWebSocketPayloadBytes,
    iceServers: config.iceServers,
  });

  webSocketServer.on("connection", (socket: HeartbeatSocket) => {
    socket.isAlive = true;
    socket.missedHeartbeats = 0;
    socket.on("pong", () => {
      socket.isAlive = true;
      socket.missedHeartbeats = 0;
      // pong 是设备「还活着」的直接证据，立刻刷新它的最后上报时间，
      // 控制端据此判断这台设备是「真在线」还是「连接挂着但已经哑了」。
      if (socket.deviceId) {
        registry.touch(socket.deviceId);
      }
    });
    hub.attachSocket(socket);
  });

  /**
   * 心跳循环：每 10 分钟探一次，连续 3 次没回应就掐掉这条连接。
   *
   * 注意这里**只**判定传输层死活。设备「连着但很卡」不会因此掉线，
   * 那是控制端根据 `lastSeenAt` 自己决定的。
   */
  const heartbeat = setInterval(() => {
    for (const client of webSocketServer.clients) {
      const socket = client as HeartbeatSocket;
      if (socket.readyState !== WS_OPEN) {
        continue;
      }
      const missed = socket.missedHeartbeats ?? 0;
      if (missed >= MAX_MISSED_HEARTBEATS) {
        logger.warn("Device missed heartbeats, terminating", {
          deviceId: socket.deviceId ?? "unknown",
          missed,
        });
        socket.terminate();
        continue;
      }
      socket.missedHeartbeats = missed + 1;
      socket.ping();
    }
  }, HEARTBEAT_INTERVAL_MS);
  heartbeat.unref();

  const handleUpgrade = (request: import("node:http").IncomingMessage, socket: Duplex, head: Buffer): void => {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (url.pathname !== "/ws") {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }

    if (!isBearerAuthorized(request.headers.authorization, config.authToken)) {
      socket.write(
        "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nWWW-Authenticate: Bearer\r\nContent-Length: 0\r\n\r\n",
      );
      socket.destroy();
      return;
    }

    webSocketServer.handleUpgrade(request, socket, head, (webSocket) => {
      webSocketServer.emit("connection", webSocket, request);
    });
  };

  server.on("upgrade", handleUpgrade);

  return {
    close: async () => {
      clearInterval(heartbeat);
      server.off("upgrade", handleUpgrade);
      for (const client of webSocketServer.clients) {
        client.close(1001, "Server shutting down");
        // ws 的 close 是握手式的：对端不回 close 帧，连接就一直挂着，
        // webSocketServer.close() 的回调永远不会触发，整个进程只能等
        // systemd 超时强杀。这里给一个短兜底，超时直接掐断 TCP。
        setTimeout(() => {
          if (client.readyState !== WS_CLOSED) {
            client.terminate();
          }
        }, 1_000).unref();
      }
      await new Promise<void>((resolve) => webSocketServer.close(() => resolve()));
    },
  };
}
