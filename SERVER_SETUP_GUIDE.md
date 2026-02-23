# 🖥️ Server Setup Guide — What Was Installed & How

## Server Info
- **IP:** 10.154.238.42
- **OS:** Ubuntu 24.04 LTS (Noble Numbat)
- **Architecture:** x86_64 (amd64)
- **User:** sysadmin

---

## What Was Installed (Step by Step)

### Step 1: Docker GPG Key

This lets Ubuntu trust Docker's official packages.

```bash
# Create keyrings directory
sudo install -m 0755 -d /etc/apt/keyrings

# Download and install Docker's GPG key
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /tmp/docker.gpg
sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg < /tmp/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
```

### Step 2: Docker APT Repository

This tells Ubuntu where to download Docker from.

```bash
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu noble stable" | sudo tee /etc/apt/sources.list.d/docker.list
```

> ⚠️ If your server is Ubuntu 22.04, replace `noble` with `jammy`.
> If Ubuntu 20.04, replace with `focal`.

### Step 3: Install Docker

```bash
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin
```

### Step 4: Start Docker & Enable on Boot

```bash
sudo systemctl start docker
sudo systemctl enable docker
```

### Step 5: Verify Docker Works

```bash
docker --version
sudo docker ps
```

You should see: `Docker version 29.x.x` and an empty container list.

---

## What Was Deployed (Step by Step)

### Step 6: Create App Directory

```bash
sudo mkdir -p /opt/git-contribution
sudo chown sysadmin:sysadmin /opt/git-contribution
```

### Step 7: Copy Project to Server

**From your Mac:**
```bash
cd /Users/zkublashvili/Documents/GitDeveloperContribution

# Pack the project (excludes junk files)
tar czf /tmp/git-contribution-deploy.tar.gz \
  --exclude='.git' --exclude='build' --exclude='.gradle' \
  --exclude='*.log' --exclude='*.pem' --exclude='.idea' .

# Copy to server
scp /tmp/git-contribution-deploy.tar.gz sysadmin@10.154.238.42:/opt/git-contribution/deploy.tar.gz
```

### Step 8: Extract on Server

**On the server (via SSH):**
```bash
cd /opt/git-contribution
tar xzf deploy.tar.gz
rm -f deploy.tar.gz
```

### Step 9: Build Docker Image

```bash
cd /opt/git-contribution
sudo docker build -t git-contribution .
```

> ⏱️ This takes 3-5 minutes on first run (downloads JDK, compiles code).
> Subsequent builds are faster due to Docker layer caching.

### Step 10: Run the Container

```bash
sudo docker run -d \
  --name git-contribution \
  --restart always \
  -p 8081:8081 \
  git-contribution
```

### Step 11: Verify

```bash
# Check container is running
sudo docker ps

# Check app responds
curl http://localhost:8081
```

### Step 12: Open in Browser

```
http://10.154.238.42:8081
```

---

## 📋 Quick Reference — Server Commands

| Action | Command |
|--------|---------|
| **Check status** | `sudo docker ps` |
| **View live logs** | `sudo docker logs -f git-contribution` |
| **View last 100 log lines** | `sudo docker logs --tail 100 git-contribution` |
| **Restart app** | `sudo docker restart git-contribution` |
| **Stop app** | `sudo docker stop git-contribution` |
| **Start app** | `sudo docker start git-contribution` |
| **Remove container** | `sudo docker stop git-contribution && sudo docker rm git-contribution` |

---

## 🔄 How to Re-Deploy (Update App)

### Option A: One Command from Mac
```bash
cd /Users/zkublashvili/Documents/GitDeveloperContribution
./docker-deploy.sh 10.154.238.42 sysadmin 'Pass-here'
```

### Option B: Manual Update
```bash
# 1. From Mac — pack & copy
cd /Users/zkublashvili/Documents/GitDeveloperContribution
tar czf /tmp/git-contribution-deploy.tar.gz \
  --exclude='.git' --exclude='build' --exclude='.gradle' \
  --exclude='*.log' --exclude='*.pem' --exclude='.idea' .
scp /tmp/git-contribution-deploy.tar.gz sysadmin@10.154.238.42:/opt/git-contribution/deploy.tar.gz

# 2. SSH into server
ssh sysadmin@10.154.238.42

# 3. On server — extract, rebuild, restart
cd /opt/git-contribution
tar xzf deploy.tar.gz && rm -f deploy.tar.gz
sudo docker stop git-contribution
sudo docker rm git-contribution
sudo docker build -t git-contribution .
sudo docker run -d --name git-contribution --restart always -p 8081:8081 git-contribution
```

---

## 🔧 What's Installed on Your Mac (Prerequisites)

| Tool | What It Does | How to Install |
|------|-------------|----------------|
| **sshpass** | Lets scripts pass SSH password automatically | `brew install hudochenkov/sshpass/sshpass` |

That's the only extra tool installed on your Mac. Everything else (Java, Git, Docker) was already there or is only on the server.

---

## 📁 Files Created in Your Project

| File | Purpose |
|------|---------|
| `docker-deploy.sh` | One-command deploy script |
| `.dockerignore` | Tells Docker to skip .git, build, .gradle, etc. |
| `Dockerfile` | Multi-stage build (JDK to build → JRE to run) |
| `SERVER_DEPLOYMENT_GUIDE.md` | This guide |

---

## 🛑 Troubleshooting

| Problem | Fix |
|---------|-----|
| Can't open `http://10.154.238.42:8081` | Check firewall: `sudo ufw allow 8081/tcp` |
| Container keeps restarting | Check logs: `sudo docker logs git-contribution` |
| Docker build fails | Try clean build: `sudo docker build --no-cache -t git-contribution .` |
| "Permission denied" on docker | Run: `sudo usermod -aG docker sysadmin` then re-login |
| Out of memory | Increase in Dockerfile: change `-Xmx1024m` to `-Xmx2048m` |

