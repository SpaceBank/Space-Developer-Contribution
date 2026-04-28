#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  GitDeveloperContribution — Docker Deployment to Remote Server
# ═══════════════════════════════════════════════════════════════
#
#  USAGE:
#    ./docker-deploy.sh <SERVER_IP> <USERNAME> <PASSWORD>
#
#  EXAMPLE:
#    ./docker-deploy.sh 10.154.238.42 sysadmin 'MyP@ssword'
#
# ═══════════════════════════════════════════════════════════════

set -e

SERVER_IP="${1:?❌ Missing SERVER_IP. Usage: ./docker-deploy.sh <IP> <USER> <PASS>}"
USERNAME="${2:?❌ Missing USERNAME. Usage: ./docker-deploy.sh <IP> <USER> <PASS>}"
PASSWORD="${3:?❌ Missing PASSWORD. Usage: ./docker-deploy.sh <IP> <USER> <PASS>}"

APP_PORT=8081
CONTAINER_NAME="git-contribution"
IMAGE_NAME="git-contribution"
REMOTE_DIR="/opt/git-contribution"

SSH_OPTS="-o StrictHostKeyChecking=no -o ServerAliveInterval=10 -o ServerAliveCountMax=60 -o TCPKeepAlive=yes -o ConnectTimeout=15"

remote_cmd() {
    sshpass -p "$PASSWORD" ssh $SSH_OPTS "$USERNAME@$SERVER_IP" "$@"
}

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   🚀 GitDeveloperContribution — Docker Deploy           ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  Server:    $SERVER_IP"
echo "║  User:      $USERNAME"
echo "║  App Port:  $APP_PORT"
echo "║  Container: $CONTAINER_NAME"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── Check sshpass ──
if ! command -v sshpass &> /dev/null; then
    echo "❌ 'sshpass' is not installed."
    echo "   Run: brew install hudochenkov/sshpass/sshpass"
    exit 1
fi

# ── Step 1: Pack project (disable macOS xattrs) ──
echo "📦 [1/6] Packing project..."
TARBALL="/tmp/git-contribution-deploy.tar.gz"
COPYFILE_DISABLE=1 tar czf "$TARBALL" \
    --exclude='.git' \
    --exclude='build' \
    --exclude='.gradle' \
    --exclude='*.log' \
    --exclude='*.pem' \
    --exclude='.idea' \
    -C "$(pwd)" .
echo "   ✅ Created: $TARBALL ($(du -h "$TARBALL" | cut -f1))"
echo ""

# ── Step 2: Copy to server ──
echo "📤 [2/6] Copying to server..."
remote_cmd "echo '$PASSWORD' | sudo -S mkdir -p $REMOTE_DIR 2>/dev/null && echo '$PASSWORD' | sudo -S chown $USERNAME:$USERNAME $REMOTE_DIR 2>/dev/null"
sshpass -p "$PASSWORD" scp $SSH_OPTS "$TARBALL" "$USERNAME@$SERVER_IP:$REMOTE_DIR/deploy.tar.gz"
echo "   ✅ Files copied to $REMOTE_DIR"
echo ""

# ── Step 3: Check Docker ──
echo "🐳 [3/6] Checking Docker..."
DOCKER_VER=$(remote_cmd "docker --version 2>/dev/null || echo '$PASSWORD' | sudo -S docker --version 2>/dev/null" || echo "")
if [ -n "$DOCKER_VER" ]; then
    echo "   ✅ $DOCKER_VER"
else
    echo "   📥 Installing Docker..."
    remote_cmd "curl -fsSL https://get.docker.com | echo '$PASSWORD' | sudo -S sh 2>/dev/null && echo '$PASSWORD' | sudo -S systemctl enable --now docker 2>/dev/null"
    echo "   ✅ Docker installed"
fi
echo ""

# ── Step 4: Extract project ──
echo "📂 [4/6] Extracting project..."
remote_cmd "cd $REMOTE_DIR && rm -rf src gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts Dockerfile 2>/dev/null; tar xzf deploy.tar.gz 2>/dev/null; rm -f deploy.tar.gz && echo '   ✅ Extracted'"
echo ""

# ── Step 5: Build Docker image (nohup — survives SSH drops) ──
echo "🔨 [5/6] Building Docker image..."

# Clean markers, launch build in background with nohup
remote_cmd "echo '$PASSWORD' | sudo -S rm -f /tmp/docker-build.log /tmp/docker-build.done 2>/dev/null; cd $REMOTE_DIR && nohup bash -c 'echo \"$PASSWORD\" | sudo -S docker build --no-cache -t $IMAGE_NAME . > /tmp/docker-build.log 2>&1; echo \$? > /tmp/docker-build.done' > /dev/null 2>&1 &"

echo "   ⏳ Build started on server, polling for completion..."

# Poll every 10s, up to 10 min
DONE=""
for i in $(seq 1 60); do
    sleep 10
    DONE=$(remote_cmd "cat /tmp/docker-build.done 2>/dev/null" || echo "")
    if [ -n "$DONE" ]; then
        break
    fi
    LAST=$(remote_cmd "tail -1 /tmp/docker-build.log 2>/dev/null" || echo "...")
    printf "\r   ⏳ [%3ds] %s                    " $((i * 10)) "$(echo "$LAST" | cut -c1-55)"
done

echo ""
if [ -z "$DONE" ]; then
    echo "   ⚠️  Build timed out (10 min). Check: ssh $USERNAME@$SERVER_IP 'tail -f /tmp/docker-build.log'"
    exit 1
elif [ "$DONE" != "0" ]; then
    echo "   ❌ Build failed! Last 20 lines:"
    remote_cmd "tail -20 /tmp/docker-build.log"
    exit 1
else
    echo "   ✅ Image built: $IMAGE_NAME"
fi
echo ""

# ── Step 6: Run container ──
echo "🚀 [6/6] Starting container..."
remote_cmd "
P='$PASSWORD'
rs() { echo \"\$P\" | sudo -S \"\$@\" 2>/dev/null; }
if rs docker ps -a --format '{{.Names}}' | grep -q '^${CONTAINER_NAME}\$'; then
    echo '   🛑 Stopping old container...'
    rs docker stop $CONTAINER_NAME || true
    rs docker rm $CONTAINER_NAME || true
fi
rs docker run -d --name $CONTAINER_NAME --restart always -p $APP_PORT:$APP_PORT $IMAGE_NAME
echo '   ✅ Container started'
rs docker image prune -f || true
"
echo ""

# ── Wait & Verify ──
echo "⏳ Waiting for app to start..."
for i in $(seq 1 40); do
    HTTP_CODE=$(remote_cmd "curl -s -o /dev/null -w '%{http_code}' http://localhost:$APP_PORT 2>/dev/null" || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        echo ""
        echo "╔══════════════════════════════════════════════════════════╗"
        echo "║                                                          ║"
        echo "║   🎉  DEPLOYMENT SUCCESSFUL!                             ║"
        echo "║                                                          ║"
        echo "║   👉  http://$SERVER_IP:$APP_PORT                        ║"
        echo "║                                                          ║"
        echo "║   sudo docker logs -f $CONTAINER_NAME                   ║"
        echo "║   sudo docker restart $CONTAINER_NAME                   ║"
        echo "║                                                          ║"
        echo "╚══════════════════════════════════════════════════════════╝"
        exit 0
    fi
    printf "."
    sleep 3
done

echo ""
echo "⚠️  App may still be starting. Check:"
echo "    sudo docker logs -f $CONTAINER_NAME"
echo ""
echo "🎉 Open: http://$SERVER_IP:$APP_PORT"
