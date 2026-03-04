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

# ── Step 1: Pack project ──
echo "📦 [1/6] Packing project..."
TARBALL="/tmp/git-contribution-deploy.tar.gz"
tar czf "$TARBALL" \
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

# Create directory using sudo -S (reads password from stdin)
sshpass -p "$PASSWORD" ssh -o StrictHostKeyChecking=no "$USERNAME@$SERVER_IP" \
    "echo '$PASSWORD' | sudo -S mkdir -p $REMOTE_DIR 2>/dev/null && echo '$PASSWORD' | sudo -S chown $USERNAME:$USERNAME $REMOTE_DIR 2>/dev/null"

# Copy tarball
sshpass -p "$PASSWORD" scp -o StrictHostKeyChecking=no "$TARBALL" "$USERNAME@$SERVER_IP:$REMOTE_DIR/deploy.tar.gz"
echo "   ✅ Files copied to $REMOTE_DIR"
echo ""

# ── Step 3–6: Run everything on server ──
echo "🖥️  [3-6/6] Setting up on server..."
echo ""

sshpass -p "$PASSWORD" ssh -o StrictHostKeyChecking=no "$USERNAME@$SERVER_IP" bash -s << REMOTE_EOF
set -e

SUDO_PASS="$PASSWORD"

# Helper: run sudo with password piped via stdin
run_sudo() {
    echo "\$SUDO_PASS" | sudo -S "\$@" 2>/dev/null
}

# Suppress ALL interactive prompts from apt (kernel upgrade, restart services, etc.)
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=a
export NEEDRESTART_SUSPEND=1

cd $REMOTE_DIR

# ── Step 3: Install Docker ──
echo "🐳 [3/6] Checking Docker..."
if ! command -v docker &> /dev/null; then
    echo "   📥 Installing Docker..."

    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS_ID=\$ID
    else
        OS_ID="unknown"
    fi

    case \$OS_ID in
        ubuntu|debian)
            run_sudo apt-get update -qq
            run_sudo env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y -qq apt-transport-https ca-certificates curl gnupg lsb-release
            curl -fsSL https://download.docker.com/linux/\$OS_ID/gpg | run_sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg 2>/dev/null || true
            echo "deb [arch=\$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/\$OS_ID \$(lsb_release -cs) stable" | run_sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
            run_sudo apt-get update -qq
            run_sudo env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y -qq docker-ce docker-ce-cli containerd.io
            ;;
        centos|rhel|rocky|almalinux|fedora)
            run_sudo yum install -y yum-utils
            run_sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
            run_sudo yum install -y docker-ce docker-ce-cli containerd.io
            ;;
        amzn)
            run_sudo yum install -y docker
            ;;
        *)
            echo "   ⚠️  Unknown OS: \$OS_ID — trying generic install..."
            curl -fsSL https://get.docker.com | run_sudo sh
            ;;
    esac

    run_sudo systemctl start docker
    run_sudo systemctl enable docker
    run_sudo usermod -aG docker $USERNAME
    echo "   ✅ Docker installed"
else
    echo "   ✅ Docker already installed: \$(docker --version 2>/dev/null || run_sudo docker --version)"
fi
echo ""

# ── Step 4: Extract project ──
echo "📂 [4/6] Extracting project..."
# Remove old source files to ensure clean state
rm -rf src gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts Dockerfile 2>/dev/null || true
tar xzf deploy.tar.gz
rm -f deploy.tar.gz
echo "   ✅ Extracted (clean)"
echo ""

# ── Step 5: Build Docker image ──
echo "🔨 [5/6] Building Docker image (this takes 2-5 minutes)..."
run_sudo docker build --no-cache -t $IMAGE_NAME . 2>&1 | tail -5
echo "   ✅ Image built: $IMAGE_NAME"
echo ""

# ── Step 6: Run container ──
echo "🚀 [6/6] Starting container..."

# Stop and remove old container if exists
if run_sudo docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_NAME}\$"; then
    echo "   🛑 Stopping old container..."
    run_sudo docker stop $CONTAINER_NAME 2>/dev/null || true
    run_sudo docker rm $CONTAINER_NAME 2>/dev/null || true
fi

run_sudo docker run -d \
    --name $CONTAINER_NAME \
    --restart always \
    -p $APP_PORT:$APP_PORT \
    $IMAGE_NAME

echo "   ✅ Container started"

# Clean up old dangling images
run_sudo docker image prune -f 2>/dev/null || true
echo ""

# ── Wait & Verify ──
echo "⏳ Waiting for app to start..."
for i in \$(seq 1 40); do
    HTTP_CODE=\$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$APP_PORT 2>/dev/null || echo "000")
    if [ "\$HTTP_CODE" = "200" ]; then
        SERVER_IP_DETECTED=\$(hostname -I 2>/dev/null | awk '{print \$1}' || echo "$SERVER_IP")
        echo ""
        echo "╔══════════════════════════════════════════════════════════╗"
        echo "║                                                          ║"
        echo "║   🎉  DEPLOYMENT SUCCESSFUL!                             ║"
        echo "║                                                          ║"
        echo "║   Open in browser:                                       ║"
        echo "║   👉  http://\${SERVER_IP_DETECTED}:$APP_PORT             ║"
        echo "║                                                          ║"
        echo "║   Commands:                                              ║"
        echo "║     sudo docker logs -f $CONTAINER_NAME                 ║"
        echo "║     sudo docker restart $CONTAINER_NAME                 ║"
        echo "║     sudo docker stop $CONTAINER_NAME                    ║"
        echo "║                                                          ║"
        echo "╚══════════════════════════════════════════════════════════╝"
        exit 0
    fi
    printf "."
    sleep 3
done

echo ""
echo "⚠️  App may still be starting. Check logs:"
echo "    sudo docker logs -f $CONTAINER_NAME"

REMOTE_EOF

echo ""
echo "🎉 Done! Open: http://$SERVER_IP:$APP_PORT"

