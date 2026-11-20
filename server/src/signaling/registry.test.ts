import assert from "node:assert/strict";
import test from "node:test";
import { DeviceRegistry, type DeviceSink } from "./registry.js";

class FakeSink implements DeviceSink {
  readonly sent: string[] = [];
  closedWith: { code: number; reason: string } | null = null;

  send(data: string): boolean {
    this.sent.push(data);
    return true;
  }

  close(code: number, reason: string): void {
    this.closedWith = { code, reason };
  }
}

test("registers devices and exposes only public fields", () => {
  const registry = new DeviceRegistry();
  const sink = new FakeSink();

  registry.register("4e9b8b87-2a62-4c34-99db-12c9f90f3b14", "agent", "荣耀 70", sink);

  const devices = registry.listPublic();
  assert.equal(devices.length, 1);
  assert.equal(devices[0]?.deviceName, "荣耀 70");
  assert.equal("sink" in (devices[0] ?? {}), false);
});

test("replacing a device closes the previous transport", () => {
  const registry = new DeviceRegistry();
  const first = new FakeSink();
  const second = new FakeSink();
  const id = "2eab4078-9c08-4a87-bca4-8754d6b9cd65";

  registry.register(id, "agent", "old", first);
  registry.register(id, "agent", "new", second);

  assert.deepEqual(first.closedWith, {
    code: 4001,
    reason: "Device replaced by a newer connection",
  });
  assert.equal(registry.find(id)?.deviceName, "new");
});

test("an old transport cannot unregister a replacement", () => {
  const registry = new DeviceRegistry();
  const first = new FakeSink();
  const second = new FakeSink();
  const id = "e12f58cf-692f-4934-a62c-2bf1c6487c72";

  registry.register(id, "agent", "old", first);
  registry.register(id, "agent", "new", second);

  assert.equal(registry.unregister(id, first), false);
  assert.equal(registry.find(id)?.deviceName, "new");
  assert.equal(registry.unregister(id, second), true);
  assert.equal(registry.size, 0);
});
