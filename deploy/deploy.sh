#!/usr/bin/env bash
# 在 Linux 云服务器上部署信令服务 + coturn。
# 支持 apt 系（Ubuntu/Debian）与 dnf/yum 系（Alibaba Cloud Linux / RHEL / CentOS）。
#
# 用法：
#   sudo PUBLIC_IP=1.2.3.4 bash deploy/deploy.sh
# 可选覆盖：AUTH_TOKEN / TURN_USER / TURN_PASS / SIGNAL_PORT / TURN_PORT
#
# 幂等：可重复执行。重跑时请带上原有的 AUTH_TOKEN / TURN_USER / TURN_PASS，
#       否则手机端已保存的配置会全部失效。
set -euo pipefail

PUBLIC_IP="${PUBLIC_IP:-}"
SIGNAL_PORT="${SIGNAL_PORT:-8787}"
TURN_PORT="${TURN_PORT:-3478}"
TURN_MIN_PORT="${TURN_MIN_PORT:-49160}"
TURN_MAX_PORT="${TURN_MAX_PORT:-49200}"
SERVICE_USER="remotecontrol"
INSTALL_DIR="/opt/remote-control"

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ -n "$PUBLIC_IP" ]] || die "必须提供 PUBLIC_IP，例如 PUBLIC_IP=1.2.3.4 bash deploy/deploy.sh"
[[ $EUID -eq 0 ]] || die "请用 root 或 sudo 运行"

# ---------- 识别包管理器 ----------
if command -v apt-get >/dev/null 2>&1; then
  PKG_FAMILY="apt"
elif command -v dnf >/dev/null 2>&1; then
  PKG_FAMILY="dnf"
elif command -v yum >/dev/null 2>&1; then
  PKG_FAMILY="yum"
else
  die "未识别的发行版：既没有 apt-get 也没有 dnf/yum"
fi
log "包管理器: $PKG_FAMILY"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_ROOT="$(dirname "$SCRIPT_DIR")"
[[ -d "$SRC_ROOT/server" ]] || die "找不到 $SRC_ROOT/server，请在完整工程目录内执行"

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

# ---------- 安装依赖 ----------
log "安装系统依赖"
if [[ "$PKG_FAMILY" == "apt" ]]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq curl ca-certificates gnupg coturn rsync >/dev/null
else
  # coturn 在 EPEL 仓库，Alibaba Cloud Linux / RHEL 系需要先启用
  if ! dnf repolist 2>/dev/null | grep -q '^epel'; then
    log "启用 EPEL 仓库（coturn 来源）"
    dnf install -y epel-release >/dev/null || true
  fi
  dnf install -y curl rsync coturn >/dev/null
fi
command -v rsync >/dev/null 2>&1 || die "rsync 安装失败"
command -v turnserver >/dev/null 2>&1 || die "coturn 安装失败（检查 EPEL 仓库是否可用）"

# ---------- Node.js ----------
log "检查 Node.js"
node_ok() {
  command -v node >/dev/null 2>&1 || return 1
  [[ "$(node -p 'process.versions.node.split(".")[0]')" -ge 20 ]] || return 1
}
if node_ok; then
  log "    已满足: node $(node -v)"
else
  log "    安装 Node.js 20"
  if [[ "$PKG_FAMILY" == "apt" ]]; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - >/dev/null
    apt-get install -y -qq nodejs >/dev/null
  else
    curl -fsSL https://rpm.nodesource.com/setup_20.x | bash - >/dev/null
    dnf install -y nodejs >/dev/null
  fi
  node_ok || die "Node.js 安装后仍不满足 >= 20"
  log "    已安装: node $(node -v)"
fi
NODE_BIN="$(command -v node)"

# ---------- 服务账号与代码 ----------
log "准备服务账号与目录"
id -u "$SERVICE_USER" >/dev/null 2>&1 || \
  useradd --system --home "$INSTALL_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
mkdir -p "$INSTALL_DIR"
rsync -a --delete --exclude node_modules --exclude dist "$SRC_ROOT/server/" "$INSTALL_DIR/server/"

log "构建服务端"
cd "$INSTALL_DIR/server"
npm ci --no-audit --no-fund >/dev/null
npm run build >/dev/null
npm prune --omit=dev >/dev/null

# ---------- 环境配置 ----------
log "写入环境配置"
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

# ---------- systemd ----------
log "安装信令 systemd 服务"
sed "s#/usr/bin/node#$NODE_BIN#g" \
  "$SCRIPT_DIR/remote-control-signaling.service" \
  > /etc/systemd/system/remote-control-signaling.service
systemctl daemon-reload
systemctl enable remote-control-signaling.service >/dev/null 2>&1
systemctl restart remote-control-signaling.service

# ---------- coturn ----------
log "配置 coturn"
mkdir -p /var/log/turnserver

# 单元名：EPEL/RHEL 系是 coturn.service，Debian/Ubuntu 两者皆有
if [[ -f /usr/lib/systemd/system/coturn.service ]] || [[ -f /etc/systemd/system/coturn.service ]]; then
  TURN_UNIT="coturn"
else
  TURN_UNIT="turnserver"
fi

# 配置文件路径必须跟单元里 -c 指向的一致，否则写了也不生效
TURN_CONF="/etc/turnserver.conf"
if [[ -d /etc/coturn ]]; then
  TURN_CONF="/etc/coturn/turnserver.conf"
fi
log "    单元: $TURN_UNIT.service   配置: $TURN_CONF"

sed -e "s/__PUBLIC_IP__/$PUBLIC_IP/g" \
    -e "s/__TURN_USER__/$TURN_USER/g" \
    -e "s/__TURN_PASS__/$TURN_PASS/g" \
    -e "s/min-port=.*/min-port=$TURN_MIN_PORT/" \
    -e "s/max-port=.*/max-port=$TURN_MAX_PORT/" \
    "$SCRIPT_DIR/turnserver.conf.template" > "$TURN_CONF"
chmod 640 "$TURN_CONF"
for grp in turnserver coturn; do
  if getent group "$grp" >/dev/null 2>&1; then
    chown "root:$grp" "$TURN_CONF"
    chown "$grp:$grp" /var/log/turnserver 2>/dev/null || true
    break
  fi
done

# Debian/Ubuntu 的 coturn 需要 /etc/default/coturn 里显式开启
if [[ -f /etc/default/coturn ]]; then
  if grep -q '^#\?TURNSERVER_ENABLED=' /etc/default/coturn; then
    sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
  else
    echo 'TURNSERVER_ENABLED=1' >> /etc/default/coturn
  fi
fi

systemctl daemon-reload
systemctl enable "$TURN_UNIT.service" >/dev/null 2>&1 || true
if ! systemctl restart "$TURN_UNIT.service"; then
  journalctl -u "$TURN_UNIT.service" -n 30 --no-pager || true
  die "coturn 启动失败"
fi

# ---------- 防火墙 ----------
log "放行防火墙"
if [[ "$PKG_FAMILY" == "apt" ]]; then
  if command -v ufw >/dev/null 2>&1; then
    ufw allow 22/tcp >/dev/null
    ufw allow "$SIGNAL_PORT"/tcp >/dev/null
    ufw allow "$TURN_PORT"/tcp >/dev/null
    ufw allow "$TURN_PORT"/udp >/dev/null
    ufw allow "$TURN_MIN_PORT:$TURN_MAX_PORT"/udp >/dev/null
    yes | ufw enable >/dev/null 2>&1 || true
  fi
else
  if systemctl is-active --quiet firewalld; then
    for spec in "22/tcp" "$SIGNAL_PORT/tcp" "$TURN_PORT/tcp" "$TURN_PORT/udp" \
                "$TURN_MIN_PORT-$TURN_MAX_PORT/udp"; do
      firewall-cmd --permanent --add-port="$spec" >/dev/null
    done
    firewall-cmd --reload >/dev/null
  else
    log "    firewalld 未运行，跳过（请确认云安全组已放行）"
  fi
fi

# ---------- 自检 ----------
log "自检"
sleep 2
SIGNAL_OK="no"
if curl -fsS "http://127.0.0.1:$SIGNAL_PORT/health" >/dev/null 2>&1; then
  SIGNAL_OK="yes"
  log "    信令服务健康检查通过"
else
  log "    信令服务未通过健康检查"
fi
TURN_OK="no"
if systemctl is-active --quiet "$TURN_UNIT.service"; then TURN_OK="yes"; fi
log "    coturn ($TURN_UNIT) 状态: $(systemctl is-active "$TURN_UNIT.service" 2>/dev/null || echo unknown)"

cat <<EOF

================ 部署完成 ================
信令健康:   $SIGNAL_OK      TURN 状态: $TURN_OK
手机端服务器地址: http://$PUBLIC_IP:$SIGNAL_PORT
访问令牌:         $AUTH_TOKEN

TURN: turn:$PUBLIC_IP:$TURN_PORT
TURN 用户名: $TURN_USER
TURN 密码:   $TURN_PASS
中继端口:    $TURN_MIN_PORT-$TURN_MAX_PORT/udp

【必做】云控制台安全组放行:
  TCP $SIGNAL_PORT
  TCP/UDP $TURN_PORT
  UDP $TURN_MIN_PORT-$TURN_MAX_PORT

常用命令:
  systemctl status remote-control-signaling
  journalctl -u remote-control-signaling -n 50
  journalctl -u $TURN_UNIT -n 50
==========================================
EOF
