import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AppConfig } from "../config.js";
import type { Logger } from "../shared/logger.js";
import type { DeviceRegistry } from "../signaling/registry.js";
import { isBearerAuthorized } from "./auth.js";

interface HttpDependencies {
  config: AppConfig;
  registry: DeviceRegistry;
  logger: Logger;
}

function sendJson(
  response: ServerResponse,
  statusCode: number,
  body: unknown,
  requestId: string,
): void {
  const payload = JSON.stringify(body);
  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "X-Request-Id": requestId,
  });
  response.end(payload);
}

function sendError(
  response: ServerResponse,
  statusCode: number,
  title: string,
  detail: string,
  requestId: string,
  extraHeaders: Record<string, string> = {},
): void {
  for (const [name, value] of Object.entries(extraHeaders)) {
    response.setHeader(name, value);
  }
  sendJson(response, statusCode, { title, status: statusCode, detail, request_id: requestId }, requestId);
}

export function createHttpServer(dependencies: HttpDependencies): Server {
  const { config, registry, logger } = dependencies;

  return createServer((request: IncomingMessage, response: ServerResponse) => {
    const requestId = randomUUID();
    const method = request.method ?? "GET";
    const url = new URL(request.url ?? "/", "http://localhost");
    const startedAt = Date.now();

    response.on("finish", () => {
      logger.info("HTTP request completed", {
        requestId,
        method,
        path: url.pathname,
        statusCode: response.statusCode,
        durationMs: Date.now() - startedAt,
      });
    });

    if (url.pathname === "/health" || url.pathname === "/ready") {
      if (method !== "GET") {
        response.setHeader("Allow", "GET");
        sendError(response, 405, "METHOD_NOT_ALLOWED", "Use GET for this endpoint", requestId);
        return;
      }
      sendJson(
        response,
        200,
        {
          status: "ok",
          uptimeSeconds: Math.round(process.uptime()),
          onlineDevices: registry.size,
        },
        requestId,
      );
      return;
    }

    if (url.pathname === "/api/devices") {
      if (method !== "GET") {
        response.setHeader("Allow", "GET");
        sendError(response, 405, "METHOD_NOT_ALLOWED", "Use GET for this endpoint", requestId);
        return;
      }
      if (!isBearerAuthorized(request.headers.authorization, config.authToken)) {
        sendError(
          response,
          401,
          "UNAUTHORIZED",
          "A valid bearer token is required",
          requestId,
          { "WWW-Authenticate": "Bearer" },
        );
        return;
      }

      sendJson(response, 200, { devices: registry.listPublic() }, requestId);
      return;
    }

    sendError(response, 404, "NOT_FOUND", "Endpoint does not exist", requestId);
  });
}
