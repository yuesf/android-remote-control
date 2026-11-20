import type { DeviceRole, PublicDevice } from "./protocol.js";

export interface DeviceSink {
  send(data: string): void;
  close(code: number, reason: string): void;
}

export interface RegisteredDevice extends PublicDevice {
  sink: DeviceSink;
}

export class DeviceRegistry {
  private readonly devices = new Map<string, RegisteredDevice>();

  register(
    deviceId: string,
    role: DeviceRole,
    deviceName: string,
    sink: DeviceSink,
  ): RegisteredDevice {
    const previous = this.devices.get(deviceId);
    if (previous && previous.sink !== sink) {
      try {
        previous.sink.close(4001, "Device replaced by a newer connection");
      } catch {
        // The stale transport may already be closed.
      }
    }

    const device: RegisteredDevice = {
      deviceId,
      role,
      deviceName,
      connectedAt: new Date().toISOString(),
      sink,
    };
    this.devices.set(deviceId, device);
    return device;
  }

  unregister(deviceId: string, sink: DeviceSink): boolean {
    const current = this.devices.get(deviceId);
    if (!current || current.sink !== sink) {
      return false;
    }
    return this.devices.delete(deviceId);
  }

  find(deviceId: string): RegisteredDevice | undefined {
    return this.devices.get(deviceId);
  }

  listPublic(): PublicDevice[] {
    return [...this.devices.values()]
      .map(({ sink: _sink, ...device }) => device)
      .sort((left, right) => left.deviceName.localeCompare(right.deviceName, "zh-CN"));
  }

  forEachSink(action: (sink: DeviceSink) => void): void {
    for (const device of this.devices.values()) {
      action(device.sink);
    }
  }

  get size(): number {
    return this.devices.size;
  }
}
