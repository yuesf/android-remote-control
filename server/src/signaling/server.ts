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

type HeartbeatSocket = WebSocket & { isAlive?: boolean };

/** ws 的 readyState 常量：WebSocket 只是类型导入，取不到静态属性。 */
const WS_CLOSED = 3;

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
    socket.on("pong", () => {
      socket.isAlive = true;
    });
    hub.attachSocket(socket);
  });

  const heartbeat = setInterval(() => {
    for (const client of webSocketServer.clients) {
      const socket = client as HeartbeatSocket;
      if (socket.isAlive === false) {
        socket.terminate();
        continue;
      }
      socket.isAlive = false;
      socket.ping();
    }
  }, 30_000);
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
