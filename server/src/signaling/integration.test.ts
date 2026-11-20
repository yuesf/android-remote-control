import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import test from "node:test";
import WebSocket from "ws";
import type { AppConfig } from "../config.js";
import type { Logger } from "../shared/logger.js";
import { DeviceRegistry } from "./registry.js";
import { attachSignalingServer } from "./server.js";

const silentLogger: Logger = {
  debug: () => undefined,
  info: () => undefined,
  warn: () => undefined,
  error: () => undefined,
};

interface Client {
  socket: WebSocket;
  nextMessage: (predicate: (message: any) => boolean) => Promise<any>;
}

function listen(server: Server): Promise<number> {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (typeof address === "string" || address === null) {
        reject(new Error("Unexpected server address"));
        return;
      }
      resolve(address.port);
    });
  });
}

function closeSocket(socket: WebSocket): Promise<void> {
  if (socket.readyState === WebSocket.CLOSED) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    socket.once("close", () => resolve());
    socket.close();
  });
}

function connectClient(url: string, token: string, device: {
  deviceId: string;
  role: "agent" | "controller";
  deviceName: string;
}): Promise<Client> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const messages: any[] = [];
    const waiters: Array<{ predicate: (message: any) => boolean; resolve: (value: any) => void }> = [];

    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      messages.push(message);
      for (let index = waiters.length - 1; index >= 0; index -= 1) {
        const waiter = waiters[index];
        if (waiter && waiter.predicate(message)) {
          waiter.resolve(message);
          waiters.splice(index, 1);
        }
      }
    });

    const nextMessage = (predicate: (message: any) => boolean): Promise<any> => {
      const existing = messages.find(predicate);
      if (existing) {
        return Promise.resolve(existing);
      }
      return new Promise((resolveMessage, rejectMessage) => {
        const timeout = setTimeout(() => {
          const index = waiters.findIndex((waiter) => waiter.resolve === resolveMessage);
          if (index >= 0) {
            waiters.splice(index, 1);
          }
          rejectMessage(new Error("Timed out waiting for a signaling message"));
        }, 5_000);

        waiters.push({
          predicate,
          resolve: (value) => {
            clearTimeout(timeout);
            resolveMessage(value);
          },
        });
      });
    };

    socket.once("error", reject);
    socket.once("open", () => {
      socket.send(JSON.stringify({ type: "register", ...device }));
      void nextMessage((message) => message.type === "ready").then(() => {
        resolve({ socket, nextMessage });
      }, reject);
    });
  });
}

test("two authenticated devices register and relay a control request", async () => {
  const token = "integration-test-token-2026";
  const config: AppConfig = {
    port: 0,
    bindHost: "127.0.0.1",
    authToken: token,
    maxWebSocketPayloadBytes: 262_144,
    logLevel: "error",
    iceServers: [{ urls: ["stun:203.0.113.10:3478"] }],
  };
  const registry = new DeviceRegistry();
  const server = createServer();
  const signaling = attachSignalingServer(server, config, registry, silentLogger);
  const port = await listen(server);
  const url = `ws://127.0.0.1:${port}/ws`;
  const agentId = "4e9b8b87-2a62-4c34-99db-12c9f90f3b14";
  const controllerId = "2eab4078-9c08-4a87-bca4-8754d6b9cd65";

  const agent = await connectClient(url, token, {
    deviceId: agentId,
    role: "agent",
    deviceName: "Honor 70",
  });
  const controller = await connectClient(url, token, {
    deviceId: controllerId,
    role: "controller",
    deviceName: "Controller A",
  });

  const ready = await agent.nextMessage((message) => message.type === "ready");
  assert.deepEqual(ready.iceServers, [{ urls: ["stun:203.0.113.10:3478"] }]);

  const presence = await agent.nextMessage(
    (message) => message.type === "devices" && message.devices.length === 2,
  );
  assert.deepEqual(
    presence.devices.map((device: { role: string }) => device.role).sort(),
    ["agent", "controller"],
  );

  controller.socket.send(
    JSON.stringify({
      type: "signal",
      targetDeviceId: agentId,
      payload: { kind: "control-request", protocolVersion: 1 },
    }),
  );
  const signal = await agent.nextMessage((message) => message.type === "signal");
  assert.equal(signal.fromDeviceId, controllerId);
  assert.equal(signal.payload.kind, "control-request");

  await Promise.all([closeSocket(agent.socket), closeSocket(controller.socket)]);
  await signaling.close();
  await new Promise<void>((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
});
