import { randomInt } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

/**
 * 四位配对码分配表（大写字母 + 数字）。
 *
 * 为什么要「固定」：用户记不住 deviceId（UUID），也不可能每次连接都在列表里
 * 找手机名字。四位码是给人念的——「连 K7M2」比「连 FNE-AN00」快得多。
 * 固定则意味着同一台设备每次上线都是同一个码，写在纸上一劳永逸。
 *
 * 为什么随机：早期按 00–99 顺序发，码可预测、容易被猜到别人的设备；
 * 改成四位后码空间 31^4 ≈ 92 万，首次注册随机取一个未占用的。
 * 之后永久保留（即使设备离线、服务端重启也不回收）——否则用户的便签就失效了。
 *
 * 状态必须落盘：早期只在内存里分配，服务端一重启码就全变，用户按旧码连不上，
 * 会误以为是网络问题。
 */

/**
 * 手输字符集：大写字母与数字，剔除 0/O/1/I/L 这些在手机上极易看错的字符。
 * 保留 L 会让大写 L 与数字 1 混淆的风险上升，故一并剔除。
 */
export const PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

/** 四位码格式。协议层校验复用此正则，避免两处规则漂移。 */
export const PAIRING_CODE_PATTERN = /^[A-HJ-KM-NP-Z2-9]{4}$/;

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

  /** 按设备取码；没有就随机分配一个新的空闲码。 */
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

    // 随机取码，撞上已占用就重试。码空间 31^4，个人/小组场景下碰撞概率可忽略；
    // 真撞满 32 次说明配置异常，抛错让调用方告警，而不是静默发一个重复码。
    for (let attempt = 0; attempt < 32; attempt += 1) {
      const code = this.randomCode();
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

    throw new Error("配对码分配失败（连续 32 次随机碰撞）");
  }

  private randomCode(): string {
    let code = "";
    for (let index = 0; index < 4; index += 1) {
      // randomInt 不含上界，且不受 Math.random 精度偏差影响。
      code += PAIRING_CODE_ALPHABET[randomInt(PAIRING_CODE_ALPHABET.length)];
    }
    return code;
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
      let migrated = false;
      for (const item of parsed.devices) {
        if (
          typeof item.code !== "string" ||
          typeof item.deviceId !== "string" ||
          typeof item.deviceName !== "string"
        ) {
          continue;
        }
        // 迁移：旧版两位数字码不符合新格式，直接丢弃。设备下次注册会随机领新码。
        // 不兼容保留是有意的——同时支持两种格式会让「码不存在」的提示变得含糊。
        if (!PAIRING_CODE_PATTERN.test(item.code)) {
          migrated = true;
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
      // 迁移发生后立即回写，避免旧码在磁盘上一直残留、下次重启又被读进来。
      if (migrated) {
        this.persist();
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
