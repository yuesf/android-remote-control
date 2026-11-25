import { z } from "zod";
import { PAIRING_CODE_PATTERN } from "./pairing.js";

export const deviceRoleSchema = z.enum(["controller", "agent"]);
export type DeviceRole = z.infer<typeof deviceRoleSchema>;

/**
 * role 保留为可选字段只为兼容旧版 APK。
 * 新版客户端不再区分「控制端/被控端」——每台设备随时可以被控，也随时可以控别人，
 * 身份由「谁发起了 control-request」决定，而不是由设备本身申报。
 */
export const registerMessageSchema = z.object({
  type: z.literal("register"),
  deviceId: z.string().uuid(),
  role: deviceRoleSchema.optional(),
  deviceName: z.string().trim().min(1).max(64),
});

export const resolveCodeMessageSchema = z.object({
  type: z.literal("resolve_code"),
  /** 四位配对码，大写字母 + 数字，剔除 0/O/1/I/L。与生成端共用同一正则。 */
  code: z.string().trim().regex(PAIRING_CODE_PATTERN, "配对码必须是四位字母或数字"),
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
  resolveCodeMessageSchema,
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
  /** 四位配对码。连接时用它寻址，比 deviceId 好念也好记。 */
  pairCode?: string;
  connectedAt: string;
  /**
   * 最后一次收到该设备存活证据的时间（ISO）。
   *
   * 「连着」和「活着」是两件事：连接可能还挂在服务端，但设备已经进 Doze、
   * 被 ROM 冻结、或者连着一条黑洞 TCP。控制端据此判断还能不能真的连上去。
   */
  lastSeenAt: string;
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
