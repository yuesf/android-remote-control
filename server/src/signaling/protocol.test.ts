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

test("accepts four-character alphanumeric pairing codes and rejects legacy or ambiguous ones", () => {
  const resolved = parseClientMessage(
    JSON.stringify({ type: "resolve_code", code: "K7M2" }),
    4096,
  );
  assert.equal(resolved.type, "resolve_code");
  assert.equal(resolved.code, "K7M2");

  // 旧两位码、含易混字符的码、小写码都必须被拒绝——生成端只发大写。
  for (const code of ["37", "0000", "K7M0", "K7MI", "k7m2", "K7M"]) {
    assert.throws(() =>
      parseClientMessage(JSON.stringify({ type: "resolve_code", code }), 4096),
    );
  }
});
