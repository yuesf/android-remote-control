export type LogLevel = "debug" | "info" | "warn" | "error";

const priorities: Record<LogLevel, number> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
};

export type LogFields = Record<string, unknown>;

export interface Logger {
  debug(message: string, fields?: LogFields): void;
  info(message: string, fields?: LogFields): void;
  warn(message: string, fields?: LogFields): void;
  error(message: string, fields?: LogFields): void;
}

export function createLogger(level: LogLevel): Logger {
  const threshold = priorities[level];

  function write(entryLevel: LogLevel, message: string, fields: LogFields = {}): void {
    if (priorities[entryLevel] < threshold) {
      return;
    }

    const entry = {
      timestamp: new Date().toISOString(),
      level: entryLevel,
      message,
      ...fields,
    };
    const line = JSON.stringify(entry);

    if (entryLevel === "error") {
      console.error(line);
      return;
    }
    console.log(line);
  }

  return {
    debug: (message, fields) => write("debug", message, fields),
    info: (message, fields) => write("info", message, fields),
    warn: (message, fields) => write("warn", message, fields),
    error: (message, fields) => write("error", message, fields),
  };
}
