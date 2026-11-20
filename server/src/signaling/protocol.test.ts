import assert from "node:assert/strict";
import test from "node:test";
import { parseClientMessage } from "./protocol.js";

const deviceId = "4e9b8b87-2a62-4c34-99db-12c9f90f3b14";

test("parses a valid register message", () => {
  const message = parseClientMessage(
    JSON.stringify({ type: "register", deviceId, role: "agent", deviceName: "荣耀 70" }),
    4096,
  );
  assert.equal(message.type, "register");
});

test("rejects a payload larger than the limit", () => {
  assert.throws(
    () =>
      parseClientMessage(
        JSON.stringify({ type: "register", deviceId, role: "agent", deviceName: "x".repeat(200) }),
        32,
      ),
    /size limit/,
  );
});

test("rejects signal messages without a valid target id", () => {
  assert.throws(() =>
    parseClientMessage(
      JSON.stringify({ type: "signal", targetDeviceId: "not-a-uuid", payload: {} }),
      4096,
    ),
  );
});
