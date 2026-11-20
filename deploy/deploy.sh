#!/usr/bin/env bash
# 在 Ubuntu / Debian 云服务器上部署信令服务 + coturn。
# 用法：
#   sudo PUBLIC_IP=1.2.3.4 bash deploy/deploy.sh
# 可选覆盖：AUTH_TOKEN / TURN_USER / TURN_PASS / SIGNAL_PORT
set -euo pipefail

PUBLIC_IP="${PUBLIC_IP:-}"
SIGNAL_PORT="${SIGNAL_PORT:-8787}"
TURN_PORT="${TURN_PORT:-3478}"
TURN_MIN_PORT="${TURN_MIN_PORT:-49160}"
TURN_MAX_PORT="${TURN_MAX_PORT:-49200}"
SERVICE_USER="remotecontrol"
INSTALL_DIR="/opt/remote-control"

if [[ -z "$PUBLIC_IP" ]]; then
  echo "ERROR: 必须提供 PUBLIC_IP，例如 PUBLIC_IP=1.2.3.4 bash deploy/deploy.sh" >&2
  exit 1
fi

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: 请用 root 或 sudo 运行" >&2
  exit 1
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "ERROR: 当前只支持 apt 系发行版（Ubuntu / Debian）" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_ROOT="$(dirname "$SCRIPT_DIR")"
if [[ ! -d "$SRC_ROOT/server" ]]; then
  echo "ERROR: 找不到 $SRC_ROOT/server，请在完整工程目录内执行" >&2
  exit 1
fi

gen_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

AUTH_TOKEN="${AUTH_TOKEN:-$(gen_secret)}"
TURN_USER="${TURN_USER:-rc$(gen_secret | head -c 8)}"
TURN_PASS="${TURN_PASS:-$(gen_secret)}"

echo "==> 安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates gnupg coturn rsync ufw >/dev/null

echo "==> 准备 Node.js 20"
if ! command -v node >/dev/null 2>&1 || [[ "$(node -p 'process.versions.node.split(".")[0]')" -lt 20 ]]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash - >/dev/null
  apt-get install -y -qq nodejs >/dev/null
fi
echo "    node $(node -v) / npm $(npm -v)"

echo "==> 创建服务账号与目录"
id -u "$SERVICE_USER" >/dev/null 2>&1 || useradd --system --home "$INSTALL_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
mkdir -p "$INSTALL_DIR"
rsync -a --delete --exclude node_modules --exclude dist "$SRC_ROOT/server/" "$INSTALL_DIR/server/"
mkdir -p "$INSTALL_DIR/server/dist"

echo "==> 构建服务端"
cd "$INSTALL_DIR/server"
npm ci --no-audit --no-fund >/dev/null
npm run build >/dev/null
npm prune --omit=dev >/dev/null

echo "==> 写入环境配置"
cat > "$INSTALL_DIR/server/.env" <<EOF
PORT=$SIGNAL_PORT
BIND_HOST=0.0.0.0
AUTH_TOKEN=$AUTH_TOKEN
MAX_WS_PAYLOAD_BYTES=262144
LOG_LEVEL=info
STUN_URLS=stun:$PUBLIC_IP:$TURN_PORT
TURN_URLS=turn:$PUBLIC_IP:$TURN_PORT?transport=udp,turn:$PUBLIC_IP:$TURN_PORT?transport=tcp
TURN_USERNAME=$TURN_USER
TURN_CREDENTIAL=$TURN_PASS
EOF
chmod 600 "$INSTALL_DIR/server/.env"
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

echo "==> 安装信令 systemd 服务"
install -m 644 "$SCRIPT_DIR/remote-control-signaling.service" /etc/systemd/system/remote-control-signaling.service
systemctl daemon-reload
systemctl enable --now remote-control-signaling.service
systemctl restart remote-control-signaling.service

echo "==> 配置 coturn"
mkdir -p /var/log/turnserver
sed -e "s/__PUBLIC_IP__/$PUBLIC_IP/g" \
    -e "s/__TURN_USER__/$TURN_USER/g" \
    -e "s/__TURN_PASS__/$TURN_PASS/g" \
    -e "s/min-port=.*/min-port=$TURN_MIN_PORT/" \
    -e "s/max-port=.*/max-port=$TURN_MAX_PORT/" \
    "$SCRIPT_DIR/turnserver.conf.template" > /etc/turnserver.conf
chmod 640 /etc/turnserver.conf

# Debian/Ubuntu 的 coturn 单元名可能不同，统一处理
if systemctl list-unit-files | grep -q '^coturn.service'; then
  systemctl enable --now coturn.service
  systemctl restart coturn.service
else
  systemctl enable --now turnserver.service
  systemctl restart turnserver.service
fi

echo "==> 放行防火墙"
ufw allow 22/tcp >/dev/null
ufw allow "$SIGNAL_PORT"/tcp >/dev/null
ufw allow "$TURN_PORT"/tcp >/dev/null
ufw allow "$TURN_PORT"/udp >/dev/null
ufw allow "$TURN_MIN_PORT:$TURN_MAX_PORT"/udp >/dev/null
yes | ufw enable >/dev/null || true

echo "==> 健康检查"
sleep 2
if curl -fsS "http://127.0.0.1:$SIGNAL_PORT/health" >/dev/null; then
  echo "    信令服务正常"
else
  echo "    信令服务未通过健康检查，请查看 journalctl -u remote-control-signaling -n 50" >&2
fi

cat <<EOF

================ 部署完成 ================
手机端服务器地址: http://$PUBLIC_IP:$SIGNAL_PORT
访问令牌:         $AUTH_TOKEN

TURN: turn:$PUBLIC_IP:$TURN_PORT
TURN 用户名: $TURN_USER
TURN 密码:   $TURN_PASS
中继端口:    $TURN_MIN_PORT-$TURN_MAX_PORT/udp

还需在云控制台安全组放行:
  TCP 22, TCP $SIGNAL_PORT, TCP/UDP $TURN_PORT, UDP $TURN_MIN_PORT-$TURN_MAX_PORT

常用命令:
  systemctl status remote-control-signaling
  journalctl -u remote-control-signaling -f
==========================================
EOF
