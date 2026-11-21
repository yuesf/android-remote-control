import type { IncomingMessage, Server } from "node:http";
import type { Duplex } from "node:stream";
import { WebSocketServer, type WebSocket } from "ws";
import type { AppConfig } from "../config.js";
import type { Logger } from "../shared/logger.js";
import { SignalingHub } from "./hub.js";
import { MAX_MISSED_HEARTBEATS, nextDelayMs } from "./heartbeat-policy.js";
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
 * 心跳节拍不是固定值，由 [nextDelayMs] 按北京时间算：
 * 早 8:00–9:00 每 2 分钟一轮，其余每 60 分钟一轮，并自动截断到峰值窗口起点。
 * 连续 [MAX_MISSED_HEARTBEATS] 轮没有 pong 才判定失联。
 *
 * 为什么非峰值敢放到 60 分钟：这个系统的使用场景是"上班第一件事连一下手机"，
 * 其余时间设备静置。拉长间隔把一天的射频唤醒从 1440 次压到 24 次量级。
 * 代价是那条长连接在非峰值时段大概率已被 NAT 回收，下次连接需要几秒重建 —— 这是有意接受的。
 */


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

  webSocketServer.on("connection", (socket: HeartbeatSocket, request: IncomingMessage) => {
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
    // 记录对端地址：移动网络下同一台设备的公网出口会漂移（NAT 重绑定），
    // 掉线时对比「注册时的出口」和「断开时的出口」就能区分是 NAT 换端口还是链路断了。
    hub.attachSocket(socket, request.socket.remoteAddress);
  });

  /**
   * 心跳循环。
   *
   * 用自调度的 setTimeout 而不是 setInterval，因为间隔本身会变（峰值 2 分钟 /
   * 平时 60 分钟），而且每次都要按「距下一个窗口起点还有多久」重新截断。
   *
   * 这里只判定传输层死活。设备「连着但很卡」不会因此掉线，
   * 那是控制端根据 `lastSeenAt` 自己决定的。
   */
  let heartbeatTimer: NodeJS.Timeout | null = null;

  const runHeartbeatRound = (): void => {
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
  };

  const scheduleHeartbeat = (): void => {
    const delay = nextDelayMs();
    heartbeatTimer = setTimeout(() => {
      runHeartbeatRound();
      scheduleHeartbeat();
    }, delay);
    heartbeatTimer.unref();
  };

  scheduleHeartbeat();

  const handleUpgrade = (request: IncomingMessage, socket: Duplex, head: Buffer): void => {
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
      if (heartbeatTimer !== null) {
        clearTimeout(heartbeatTimer);
        heartbeatTimer = null;
      }
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
