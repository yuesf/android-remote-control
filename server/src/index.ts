import { loadConfig } from "./config.js";
import { createHttpServer } from "./http/app.js";
import { createLogger } from "./shared/logger.js";
import { DeviceRegistry } from "./signaling/registry.js";
import { attachSignalingServer } from "./signaling/server.js";

let config;
try {
  config = loadConfig();
} catch (error) {
  console.error(
    JSON.stringify({
      timestamp: new Date().toISOString(),
      level: "error",
      message: "Configuration is invalid",
      error: error instanceof Error ? error.message : "unknown error",
    }),
  );
  process.exit(1);
}

const logger = createLogger(config.logLevel);
const registry = new DeviceRegistry();
const server = createHttpServer({ config, registry, logger });
const signaling = attachSignalingServer(server, config, registry, logger);

server.listen(config.port, config.bindHost, () => {
  logger.info("Remote control signaling server started", {
    host: config.bindHost,
    port: config.port,
    webSocketPath: "/ws",
  });
});

server.on("error", (error) => {
  logger.error("HTTP server failed", { error: error.message });
});

let shuttingDown = false;

async function shutdown(signalName: string): Promise<void> {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;
  logger.info("Shutting down", { signal: signalName });

  const forceExitTimer = setTimeout(() => {
    logger.error("Graceful shutdown timed out");
    process.exit(1);
  }, 10_000);
  forceExitTimer.unref();

  await signaling.close();
  await new Promise<void>((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
  clearTimeout(forceExitTimer);
  logger.info("Shutdown complete");
}

for (const signalName of ["SIGINT", "SIGTERM"] as const) {
  process.on(signalName, () => {
    void shutdown(signalName)
      .then(() => process.exit(0))
      .catch((error: unknown) => {
        logger.error("Graceful shutdown failed", {
          error: error instanceof Error ? error.message : "unknown error",
        });
        process.exit(1);
      });
  });
}
