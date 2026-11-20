import { z } from "zod";

export const deviceRoleSchema = z.enum(["controller", "agent"]);
export type DeviceRole = z.infer<typeof deviceRoleSchema>;

export const registerMessageSchema = z.object({
  type: z.literal("register"),
  deviceId: z.string().uuid(),
  role: deviceRoleSchema,
  deviceName: z.string().trim().min(1).max(64),
});

export const listDevicesMessageSchema = z.object({
  type: z.literal("list_devices"),
});

export const signalMessageSchema = z.object({
  type: z.literal("signal"),
  targetDeviceId: z.string().uuid(),
  payload: z.record(z.unknown()),
});

export const pingMessageSchema = z.object({
  type: z.literal("ping"),
  nonce: z.string().min(1).max(64),
});

export const clientMessageSchema = z.discriminatedUnion("type", [
  registerMessageSchema,
  listDevicesMessageSchema,
  signalMessageSchema,
  pingMessageSchema,
]);

export type ClientMessage = z.infer<typeof clientMessageSchema>;
export type SignalMessage = z.infer<typeof signalMessageSchema>;

export interface PublicDevice {
  deviceId: string;
  role: DeviceRole;
  deviceName: string;
  connectedAt: string;
}

export function parseClientMessage(raw: string, maximumBytes: number): ClientMessage {
  if (Buffer.byteLength(raw, "utf8") > maximumBytes) {
    throw new Error("Message exceeds the size limit");
  }

  let decoded: unknown;
  try {
    decoded = JSON.parse(raw);
  } catch {
    throw new Error("Message is not valid JSON");
  }

  return clientMessageSchema.parse(decoded);
}
