# 🐳 Docker Deploy Guide — GitDeveloperContribution

## 🚀 One-Command Deploy (From Your Mac)

```bash
cd /Users/zkublashvili/Documents/GitDeveloperContribution
./docker-deploy.sh  10.154.238.42  sysadmin  'YOUR_PASSWORD'
```

This automatically: packs → copies → rebuilds → restarts. That's it!

---

## 🔧 Manual Deploy (If You're Already SSH'd Into the Server)

### Option A: Full Rebuild (You Updated Source Code)

```bash
cd /opt/git-contribution
sudo docker stop git-contribution
sudo docker rm git-contribution
sudo docker build -t git-contribution .
sudo docker run -d --name git-contribution --restart always -p 8081:8081 git-contribution
```

### Option B: One-Liner (Copy-Paste)

```bash
cd /opt/git-contribution && sudo docker stop git-contribution && sudo docker rm git-contribution && sudo docker build -t git-contribution . && sudo docker run -d --name git-contribution --restart always -p 8081:8081 git-contribution
```

Wait ~15 seconds, then open: **http://10.154.238.42:8081**

---

## 📋 Server Commands Cheat Sheet

### 🔍 Check Container Status
```bash
sudo docker ps
```
Shows running containers — you'll see `git-contribution` with its status, uptime, and port mapping (8081).

### 📜 View Real-Time Application Logs
```bash
sudo docker logs -f git-contribution
```
Streams live logs from the application. You'll see:
- 🔐 Who logged in and when
- 📊 Which analysis was triggered and by whom
- ⏱️ How long each operation took
- ❌ Any errors that occurred

Press `Ctrl+C` to stop watching.

### Other Useful Log Commands

| Action | Command |
|--------|---------|
| **Live logs (follow)** | `sudo docker logs -f git-contribution` |
| **Last 200 lines + follow** | `sudo docker logs --tail 200 -f git-contribution` |
| **Last 100 lines** | `sudo docker logs --tail 100 git-contribution` |
| **Logs from last 1 hour** | `sudo docker logs --since 1h git-contribution` |
| **Logs from last 30 min** | `sudo docker logs --since 30m -f git-contribution` |
| **Only ERROR lines** | `sudo docker logs git-contribution 2>&1 \| grep ERROR` |
| **Search logs** | `sudo docker logs git-contribution 2>&1 \| grep "keyword"` |

### Container Management

| Action | Command |
|--------|---------|
| **Check status** | `sudo docker ps` |
| **Restart** | `sudo docker restart git-contribution` |
| **Stop** | `sudo docker stop git-contribution` |
| **Start** | `sudo docker start git-contribution` |
| **Remove container** | `sudo docker stop git-contribution && sudo docker rm git-contribution` |

---

## 🔄 How to Update (From Mac to Server)

### Step 1: Copy new files to server
```bash
cd /Users/zkublashvili/Documents/GitDeveloperContribution
tar czf /tmp/git-contribution-deploy.tar.gz \
  --exclude='.git' --exclude='build' --exclude='.gradle' \
  --exclude='*.log' --exclude='*.pem' --exclude='.idea' .
scp /tmp/git-contribution-deploy.tar.gz sysadmin@10.154.238.42:/opt/git-contribution/deploy.tar.gz
```

### Step 2: SSH into server & redeploy
```bash
ssh sysadmin@10.154.238.42
cd /opt/git-contribution
tar xzf deploy.tar.gz && rm -f deploy.tar.gz
sudo docker stop git-contribution && sudo docker rm git-contribution
sudo docker build -t git-contribution .
sudo docker run -d --name git-contribution --restart always -p 8081:8081 git-contribution
```

---

## 🛑 Troubleshooting

| Problem | Fix |
|---------|-----|
| Can't open in browser | `sudo ufw allow 8081/tcp` |
| Container keeps restarting | `sudo docker logs git-contribution` |
| Build fails | `sudo docker build --no-cache -t git-contribution .` |
| Out of memory | Change `-Xmx1024m` to `-Xmx2048m` in Dockerfile |

