import type { LogLevel } from "./shared/logger.js";

export interface AppConfig {
  port: number;
  bindHost: string;
  authToken: string;
  maxWebSocketPayloadBytes: number;
  logLevel: LogLevel;
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
  };
}
