# ECS 部署说明

信令服务和 TURN 中继部署在同一台公网服务器上。**不依赖域名，直接用 IP**。

## 前置条件

- Ubuntu 20.04 / 22.04 / 24.04 或 Debian 11+（apt 系）
- 有公网 IPv4，能 SSH 登录
- 云控制台安全组放行：`TCP 22`、`TCP 8787`、`TCP/UDP 3478`、`UDP 49160-49200`

> 8787 是信令端口，3478 是 STUN/TURN，49160-49200 是 TURN 中继端口。
> 中继端口不放行的话，严格 NAT 下画面出不来，信令却是通的——很容易误判成“程序坏了”。

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

脚本会自动完成：安装 Node 20、构建服务端、写 systemd、安装并配置 coturn、放行 ufw。
执行完会打印手机端要用的**服务器地址**和**访问令牌**，这两项要填进两台手机。

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

## 更新版本

重新上传代码后执行：

```bash
cd /opt/remote-control-src
PUBLIC_IP=<公网IP> AUTH_TOKEN=<原令牌> TURN_USER=<原用户> TURN_PASS=<原密码> bash deploy/deploy.sh
```

务必带上原有令牌和 TURN 凭证，否则手机端配置会全部失效。
