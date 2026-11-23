import type { LogLevel } from "./shared/logger.js";

export interface IceServerSetting {
  urls: string[];
  username?: string;
  credential?: string;
}

export interface AppConfig {
  port: number;
  bindHost: string;
  authToken: string;
  maxWebSocketPayloadBytes: number;
  logLevel: LogLevel;
  iceServers: IceServerSetting[];
  /** 两位配对码分配表的落盘位置，重启后码必须保持不变。 */
  pairingCodeFile: string;
}

const validLogLevels = new Set<LogLevel>(["debug", "info", "warn", "error"]);

function readInteger(name: string, fallback: number, minimum: number, maximum: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === "") {
    return fallback;
  }

  const value = Number(raw);
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be an integer between ${minimum} and ${maximum}`);
  }
  return value;
}

function readRequired(name: string, minimumLength = 1): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  if (value.length < minimumLength) {
    throw new Error(`${name} must contain at least ${minimumLength} characters`);
  }
  return value;
}

function splitUrls(raw: string | undefined): string[] {
  if (!raw) {
    return [];
  }
  return raw
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

/**
 * ICE 服务器通过 ready 消息下发给客户端，这样换 TURN 地址不需要重新发 APK。
 * STUN 只用于发现公网映射；严格 NAT 下必须依赖 TURN 中继。
 */
function readIceServers(): IceServerSetting[] {
  const servers: IceServerSetting[] = [];

  const stunUrls = splitUrls(process.env.STUN_URLS);
  if (stunUrls.length > 0) {
    servers.push({ urls: stunUrls });
  }

  const turnUrls = splitUrls(process.env.TURN_URLS);
  if (turnUrls.length > 0) {
    const username = process.env.TURN_USERNAME?.trim();
    const credential = process.env.TURN_CREDENTIAL?.trim();
    if (!username || !credential) {
      throw new Error("TURN_USERNAME and TURN_CREDENTIAL are required when TURN_URLS is set");
    }
    servers.push({ urls: turnUrls, username, credential });
  }

  return servers;
}

export function loadConfig(): AppConfig {
  const authToken = readRequired("AUTH_TOKEN", 16);
  const logLevelValue = process.env.LOG_LEVEL?.trim() ?? "info";
  if (!validLogLevels.has(logLevelValue as LogLevel)) {
    throw new Error("LOG_LEVEL must be one of debug, info, warn, error");
  }

  return {
    port: readInteger("PORT", 8787, 1, 65535),
    bindHost: process.env.BIND_HOST?.trim() || "0.0.0.0",
    authToken,
    maxWebSocketPayloadBytes: readInteger("MAX_WS_PAYLOAD_BYTES", 262_144, 1024, 4_194_304),
    logLevel: logLevelValue as LogLevel,
    iceServers: readIceServers(),
    pairingCodeFile: process.env.PAIRING_CODE_FILE?.trim() || "data/pairing-codes.json",
  };
}
