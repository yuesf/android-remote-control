import type { DeviceRole, PublicDevice } from "./protocol.js";

export interface DeviceSink {
  /**
   * 投递一条消息。
   * 返回 false 表示这条连接当前不可写（已关闭或正在关闭），
   * 调用方据此向发送方回 TARGET_UNAVAILABLE；
   * 早先的实现在这里静默丢弃，导致发送方永远等不到任何结果。
   */
  send(data: string): boolean;
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
    pairCode?: string,
  ): RegisteredDevice {
    const previous = this.devices.get(deviceId);
    if (previous && previous.sink !== sink) {
      try {
        previous.sink.close(4001, "Device replaced by a newer connection");
      } catch {
        // The stale transport may already be closed.
      }
    }

    const now = new Date().toISOString();
    const device: RegisteredDevice = {
      deviceId,
      role,
      deviceName,
      pairCode,
      connectedAt: now,
      lastSeenAt: now,
      sink,
    };
    this.devices.set(deviceId, device);
    return device;
  }

  /**
   * 记录一次「设备还活着」的证据：收到它的任何消息，或者 pong 回来了。
   *
   * 只在注册表里记录，不改连接本身——连接死活由 WebSocket 层判断，
   * 这里只回答「最近有没有动静」。
   */
  touch(deviceId: string): void {
    const device = this.devices.get(deviceId);
    if (!device) {
      return;
    }
    device.lastSeenAt = new Date().toISOString();
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

  /** 按四位配对码找在线设备。码可能属于一台离线设备，这里只回答「在线的是哪台」。 */
  findByPairCode(pairCode: string): RegisteredDevice | undefined {
    for (const device of this.devices.values()) {
      if (device.pairCode === pairCode) {
        return device;
      }
    }
    return undefined;
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
