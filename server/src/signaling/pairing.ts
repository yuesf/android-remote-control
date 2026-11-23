import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

/**
 * 两位配对码分配表。
 *
 * 为什么要「固定」：用户记不住 deviceId（UUID），也不可能每次连接都在列表里
 * 找手机名字。两位码是给人念的——「连 35」比「连 FNE-AN00」快得多。
 * 固定则意味着同一台设备每次上线都是同一个码，写在纸上一劳永逸。
 *
 * 码池 00–99 共 100 个，够个人与小组使用。分配是首次注册时领取，
 * 之后永久保留（即使设备离线、服务端重启也不回收）——否则用户的便签就失效了。
 *
 * 状态必须落盘：早期只在内存里分配，服务端一重启码就全变，用户按旧码连不上，
 * 会误以为是网络问题。
 */

export interface PairingAssignment {
  code: string;
  deviceId: string;
  deviceName: string;
  assignedAt: string;
}

interface PersistedShape {
  version: 1;
  devices: PairingAssignment[];
}

export class PairingCodeStore {
  private readonly byDevice = new Map<string, PairingAssignment>();
  private readonly byCode = new Map<string, PairingAssignment>();

  constructor(private readonly filePath: string) {
    this.load();
  }

  /** 按设备取码；没有就分配一个新的空闲码。码池满时抛错（由调用方转成服务端错误）。 */
  assign(deviceId: string, deviceName: string): PairingAssignment {
    const existing = this.byDevice.get(deviceId);
    if (existing) {
      // 设备改名很常见（换手机名/恢复出厂），码保持不变，只更新展示名。
      if (existing.deviceName !== deviceName) {
        existing.deviceName = deviceName;
        this.persist();
      }
      return existing;
    }

    for (let index = 0; index < 100; index += 1) {
      const code = index.toString().padStart(2, "0");
      if (this.byCode.has(code)) {
        continue;
      }
      const assignment: PairingAssignment = {
        code,
        deviceId,
        deviceName,
        assignedAt: new Date().toISOString(),
      };
      this.byDevice.set(deviceId, assignment);
      this.byCode.set(code, assignment);
      this.persist();
      return assignment;
    }

    throw new Error("配对码已用完（最多 100 台设备）");
  }

  /** 按码反查设备。码是分配过的就一定查得到，无论那台设备当前是否在线。 */
  resolve(code: string): PairingAssignment | undefined {
    return this.byCode.get(code);
  }

  codeOf(deviceId: string): string | undefined {
    return this.byDevice.get(deviceId)?.code;
  }

  get size(): number {
    return this.byCode.size;
  }

  private load(): void {
    let raw: string;
    try {
      raw = readFileSync(this.filePath, "utf8");
    } catch {
      // 首次运行没有文件，正常。
      return;
    }

    try {
      const parsed = JSON.parse(raw) as PersistedShape;
      if (parsed.version !== 1 || !Array.isArray(parsed.devices)) {
        return;
      }
      for (const item of parsed.devices) {
        if (
          typeof item.code !== "string" ||
          typeof item.deviceId !== "string" ||
          typeof item.deviceName !== "string"
        ) {
          continue;
        }
        const assignment: PairingAssignment = {
          code: item.code,
          deviceId: item.deviceId,
          deviceName: item.deviceName,
          assignedAt: typeof item.assignedAt === "string" ? item.assignedAt : "",
        };
        // 文件冲突（手工编辑过）时以设备侧为准，码侧跳过重复。
        if (this.byCode.has(assignment.code)) {
          continue;
        }
        this.byDevice.set(assignment.deviceId, assignment);
        this.byCode.set(assignment.code, assignment);
      }
    } catch {
      // 文件损坏时宁可空着重新分配，也不要让整个服务起不来。
    }
  }

  private persist(): void {
    const body: PersistedShape = {
      version: 1,
      devices: [...this.byDevice.values()],
    };
    try {
      mkdirSync(dirname(this.filePath), { recursive: true });
      const temporary = `${this.filePath}.tmp`;
      writeFileSync(temporary, `${JSON.stringify(body, null, 2)}\n`, "utf8");
      // 先写临时文件再 rename：写到一半崩溃也不会留下半个 JSON。
      renameSync(temporary, this.filePath);
    } catch {
      // 落盘失败不能让注册流程挂掉——码在内存里仍然有效，只是重启会丢。
    }
  }
}
