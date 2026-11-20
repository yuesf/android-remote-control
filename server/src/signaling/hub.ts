import type { RawData, WebSocket } from "ws";
import { ZodError } from "zod";
import type { IceServerSetting } from "../config.js";
import type { Logger } from "../shared/logger.js";
import { parseClientMessage, type ClientMessage } from "./protocol.js";
import { DeviceRegistry, type DeviceSink } from "./registry.js";

interface HubOptions {
  registry: DeviceRegistry;
  logger: Logger;
  maximumMessageBytes: number;
  iceServers?: IceServerSetting[];
}

export class SignalingHub {
  constructor(private readonly options: HubOptions) {}

  attachSocket(socket: WebSocket): void {
    let registeredDeviceId: string | null = null;
    let registeredSink: DeviceSink | null = null;

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

        const device = this.options.registry.register(
          message.deviceId,
          message.role,
          message.deviceName,
          sink,
        );
        registeredDeviceId = message.deviceId;
        registeredSink = sink;
        this.options.logger.info("Device registered", {
          deviceId: device.deviceId,
          role: device.role,
          deviceName: device.deviceName,
        });

        send({
          type: "ready",
          deviceId: device.deviceId,
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

      if (message.type === "list_devices") {
        send({ type: "devices", devices: this.options.registry.listPublic() });
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

    socket.on("close", () => {
      if (registeredDeviceId === null || registeredSink === null) {
        return;
      }

      const removed = this.options.registry.unregister(registeredDeviceId, registeredSink);
      if (removed) {
        this.options.logger.info("Device disconnected", { deviceId: registeredDeviceId });
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
