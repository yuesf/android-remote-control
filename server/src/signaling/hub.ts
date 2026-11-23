import type { RawData, WebSocket } from "ws";
import { ZodError } from "zod";
import type { IceServerSetting } from "../config.js";
import type { Logger } from "../shared/logger.js";
import { parseClientMessage, type ClientMessage } from "./protocol.js";
import type { PairingCodeStore } from "./pairing.js";
import { DeviceRegistry, type DeviceSink } from "./registry.js";

interface HubOptions {
  registry: DeviceRegistry;
  logger: Logger;
  maximumMessageBytes: number;
  iceServers?: IceServerSetting[];
  /** 两位配对码分配表。缺省时退化为「不分配」，仅为兼容旧测试。 */
  pairing?: PairingCodeStore;
}

export class SignalingHub {
  constructor(private readonly options: HubOptions) {}

  attachSocket(socket: WebSocket, remoteAddress?: string): void {
    let registeredDeviceId: string | null = null;
    let registeredSink: DeviceSink | null = null;
    const openedAt = Date.now();
    const peer = remoteAddress ?? "unknown";
    // 心跳循环在 server.ts 里，它需要知道这条 socket 对应哪台设备，
    // 才能在 pong 回来时刷新 lastSeenAt。
    const tracked = socket as WebSocket & { deviceId?: string };

    const sink: DeviceSink = {
      send: (data) => {
        if (socket.readyState !== socket.OPEN) {
          return false;
        }
        socket.send(data, (error) => {
          if (error) {
            this.options.logger.warn("WebSocket send failed", { error: error.message });
          }
        });
        return true;
      },
      close: (code, reason) => socket.close(code, reason),
    };

    const send = (message: unknown): void => {
      try {
        sink.send(JSON.stringify(message));
      } catch (error) {
        this.options.logger.warn("Failed to send signaling message", {
          error: error instanceof Error ? error.message : "unknown error",
        });
      }
    };

    const sendError = (code: string, message: string): void => {
      send({ type: "error", code, message });
    };

    const handleMessage = (raw: string): void => {
      let message: ClientMessage;
      try {
        message = parseClientMessage(raw, this.options.maximumMessageBytes);
      } catch (error) {
        if (error instanceof ZodError) {
          sendError("INVALID_MESSAGE", "Message schema validation failed");
        } else {
          sendError(
            "INVALID_MESSAGE",
            error instanceof Error ? error.message : "Message could not be parsed",
          );
        }
        return;
      }

      if (message.type === "register") {
        if (registeredDeviceId !== null) {
          sendError("ALREADY_REGISTERED", "This connection is already registered");
          return;
        }

        // 配对码在首次注册时领取，此后固定不变；分配失败（码池满）不阻断注册，
        // 只是这台设备暂时无法被「按码」找到。
        let pairCode: string | undefined;
        try {
          pairCode = this.options.pairing?.assign(message.deviceId, message.deviceName).code;
        } catch (error) {
          this.options.logger.warn("Pairing code assignment failed", {
            deviceId: message.deviceId,
            error: error instanceof Error ? error.message : "unknown error",
          });
        }

        const device = this.options.registry.register(
          message.deviceId,
          message.role ?? "agent",
          message.deviceName,
          sink,
          pairCode,
        );
        registeredDeviceId = message.deviceId;
        registeredSink = sink;
        tracked.deviceId = message.deviceId;
        this.options.logger.info("Device registered", {
          deviceId: device.deviceId,
          role: device.role,
          deviceName: device.deviceName,
          pairCode: device.pairCode ?? "-",
          remoteAddress: peer,
        });

        send({
          type: "ready",
          deviceId: device.deviceId,
          pairCode: device.pairCode ?? null,
          connectedAt: device.connectedAt,
          iceServers: this.options.iceServers ?? [],
        });
        this.broadcastPresence();
        return;
      }

      if (registeredDeviceId === null) {
        sendError("NOT_REGISTERED", "Register before sending other messages");
        return;
      }

      // 任何一条消息都是「还活着」的证据，比心跳更准时。
      this.options.registry.touch(registeredDeviceId);

      if (message.type === "list_devices") {
        send({ type: "devices", devices: this.options.registry.listPublic() });
        return;
      }

      // 按配对码寻址：客户端只需要「输两位数字」，deviceId 永远不暴露给用户。
      // 码有效但设备离线时照样返回 deviceId，让客户端能区分
      // 「码不存在」和「这台设备现在没上线」——两者的提示完全不同。
      if (message.type === "resolve_code") {
        const assignment = this.options.pairing?.resolve(message.code);
        if (!assignment) {
          sendError("CODE_NOT_FOUND", "没有设备使用这个配对码");
          return;
        }
        const online = this.options.registry.findByPairCode(message.code);
        send({
          type: "code-resolved",
          code: message.code,
          deviceId: assignment.deviceId,
          deviceName: online?.deviceName ?? assignment.deviceName,
          online: online !== undefined,
        });
        return;
      }

      if (message.type === "ping") {
        send({ type: "pong", nonce: message.nonce });
        return;
      }

      if (message.type === "signal") {
        if (message.targetDeviceId === registeredDeviceId) {
          sendError("INVALID_TARGET", "A device cannot signal itself");
          return;
        }

        const target = this.options.registry.find(message.targetDeviceId);
        if (!target) {
          sendError("TARGET_OFFLINE", "Target device is not online");
          return;
        }

        try {
          const delivered = target.sink.send(
            JSON.stringify({
              type: "signal",
              fromDeviceId: registeredDeviceId,
              payload: message.payload,
            }),
          );
          if (!delivered) {
            sendError("TARGET_UNAVAILABLE", "Target device is no longer reachable");
          }
        } catch (error) {
          this.options.logger.warn("Signal forwarding failed", {
            targetDeviceId: message.targetDeviceId,
            error: error instanceof Error ? error.message : "unknown error",
          });
          sendError("TARGET_UNAVAILABLE", "Target device is no longer reachable");
        }
      }
    };

    socket.on("message", (data: RawData, isBinary: boolean) => {
      if (isBinary) {
        sendError("UNSUPPORTED_MESSAGE", "Binary signaling messages are not supported");
        return;
      }
      handleMessage(data.toString("utf8"));
    });

    socket.on("close", (code: number, reason: Buffer) => {
      if (registeredDeviceId === null || registeredSink === null) {
        return;
      }

      const removed = this.options.registry.unregister(registeredDeviceId, registeredSink);
      if (removed) {
        // code/reason 是排查「设备莫名其妙掉线」的关键证据：
        // 1006 = 对端没发 close 帧，TCP 直接没了（网络中断或被 RST）；
        // 1000/4001 = 有人主动关的，两者处置方式完全不同。
        this.options.logger.info("Device disconnected", {
          deviceId: registeredDeviceId,
          code,
          reason: reason.toString() || "no reason",
          durationMs: Date.now() - openedAt,
          remoteAddress: peer,
        });
        this.broadcastPresence();
      }
    });

    socket.on("error", (error) => {
      this.options.logger.warn("WebSocket error", { error: error.message });
    });
  }

  private broadcastPresence(): void {
    const message = JSON.stringify({
      type: "devices",
      devices: this.options.registry.listPublic(),
    });

    this.options.registry.forEachSink((sink) => {
      try {
        sink.send(message);
      } catch {
        // A close event will remove the failed sink from the registry.
      }
    });
  }
}
