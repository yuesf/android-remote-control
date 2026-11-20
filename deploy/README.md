# ECS 部署说明

信令服务和 TURN 中继部署在同一台公网服务器上。**不依赖域名，直接用 IP**。

## 前置条件

- 以下任一发行版（脚本自动识别）：
  - apt 系：Ubuntu 20.04 / 22.04 / 24.04、Debian 11+
  - dnf/yum 系：Alibaba Cloud Linux 3、RHEL / CentOS / Rocky 8+
- 有公网 IPv4，能 SSH 登录，能访问 npm 公共源
- 云控制台安全组放行：`TCP 22`、`TCP 8787`、`TCP/UDP 3478`、`UDP 49160-49200`

> 8787 是信令端口，3478 是 STUN/TURN，49160-49200 是 TURN 中继端口。
> 中继端口不放行的话，严格 NAT 下画面出不来，信令却是通的——很容易误判成“程序坏了”。
>
> dnf 系上 coturn 来自 EPEL 仓库，脚本会自动启用 `epel-release`。

## 部署

1. 把整个工程传到服务器（在本地工程根目录执行）：

```bash
scp -r . root@<服务器IP>:/opt/remote-control-src
```

2. 登录服务器执行：

```bash
ssh root@<服务器IP>
cd /opt/remote-control-src
PUBLIC_IP=<服务器公网IP> bash deploy/deploy.sh
```

脚本会自动完成：安装依赖（Node 20+ / coturn）、构建服务端、写 systemd、配置 coturn、放行系统防火墙。
执行完会打印手机端要用的**服务器地址**和**访问令牌**，这两项要填进两台手机。

## 与既有业务共存

脚本只做加法，不改动服务器上已有的服务：

| 项目 | 值 |
|---|---|
| 代码目录 | `/opt/remote-control` |
| 运行账号 | `remotecontrol`（系统账号，无登录权限） |
| systemd 单元 | `remote-control-signaling.service` |
| 监听端口 | `8787`（信令）、`3478`（STUN/TURN）、`49160-49200`（中继） |
| 配置文件 | `/opt/remote-control/server/.env`、`/etc/turnserver.conf` 或 `/etc/coturn/turnserver.conf` |

不会碰 nginx、不会占用 80/443、不会修改已有站点配置。

## 验证

```bash
curl http://127.0.0.1:8787/health
systemctl status remote-control-signaling
journalctl -u remote-control-signaling -n 50
```

从手机浏览器访问 `http://<公网IP>:8787/health` 也应当返回正常，否则先查安全组。

## 手机端配置

两台手机都填同一组：

- 服务器地址：`http://<公网IP>:8787`
- 访问令牌：脚本输出的 `AUTH_TOKEN`

STUN/TURN 由服务端在连接建立时自动下发，**不需要在手机上填**。

## 已知风险

当前信令走明文 HTTP/WS，令牌在公网上明文传输。因为域名已失效、手机也不信任自签证书，暂时不加 TLS。
缓解措施：令牌用随机长串（脚本自动生成）、只在内网或短期联调期开放、正式使用前补 TLS 或 VPN。

## 排错

**`npm error code EALLOWREMOTE`**
`package-lock.json` 里锁了内网 npm 源（如 `npm.midea.com`），服务器拉不到。仓库里已统一改成 `registry.npmjs.org`；
若以后用内网源装过依赖，提交前先检查：

```bash
grep -o 'https://[^/"]*' server/package-lock.json | sort -u
```

**手机能连信令但看不到画面**
几乎都是 TURN 中继端口没放行。确认云安全组放行 `UDP 49160-49200`，且 `/etc/turnserver.conf` 里的
`external-ip` 等于服务器公网 IP。

**`systemctl status coturn` 起来了但配置不生效**
不同发行版配置路径不同：dnf 系读 `/etc/coturn/turnserver.conf`，apt 系读 `/etc/turnserver.conf`。
脚本会按单元里 `-c` 参数自动选择，手工排查时以 `systemctl cat coturn` 里的路径为准。

## 更新版本

重新上传代码后执行：

```bash
cd /opt/remote-control-src
PUBLIC_IP=<公网IP> AUTH_TOKEN=<原令牌> TURN_USER=<原用户> TURN_PASS=<原密码> bash deploy/deploy.sh
```

务必带上原有令牌和 TURN 凭证，否则手机端配置会全部失效。
