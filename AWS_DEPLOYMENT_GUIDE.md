# 🚀 AWS Installation Guide

## You Have AWS Machine - What to Install

### Prerequisites on AWS:
- ✅ AWS EC2 or Lightsail instance running
- ✅ SSH access to the machine
- ✅ Port 8081 open in Security Group/Firewall

---

## 📋 Installation Steps (Copy & Paste)

### Step 1: Connect to Your Machine
```bash
ssh -i your-key.pem ec2-user@YOUR_IP
# or for Ubuntu:
ssh -i your-key.pem ubuntu@YOUR_IP
```

---

### Step 2: Install Java 17

**Amazon Linux / RHEL / CentOS:**
```bash
sudo yum install java-17-amazon-corretto -y
```

**Ubuntu / Debian:**
```bash
sudo apt update
sudo apt install openjdk-17-jdk -y
```

**Verify:**
```bash
java -version
```

---

### Step 3: Install Git

**Amazon Linux / RHEL / CentOS:**
```bash
sudo yum install git -y
```

**Ubuntu / Debian:**
```bash
sudo apt install git -y
```

---

### Step 4: Clone & Build

```bash
git clone https://github.com/SpaceBank/Space-Developer-Contribution.git
cd Space-Developer-Contribution
chmod +x gradlew
./gradlew build -x test
```

---

### Step 5: Run the App

```bash
nohup java -jar build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1 &
```

---

### Step 6: Open Port 8081 (if not already open)

**AWS Console:**
1. EC2 → Security Groups → Your SG → Edit Inbound Rules
2. Add Rule: Custom TCP | Port 8081 | Source 0.0.0.0/0
3. Save

**Or via CLI on the machine:**
```bash
# Amazon Linux
sudo firewall-cmd --add-port=8081/tcp --permanent
sudo firewall-cmd --reload

# Ubuntu
sudo ufw allow 8081
```

---

### Step 7: Access Your App
```
http://YOUR_AWS_IP:8081
```

---

## 🔄 Quick Commands Reference

| Action | Command |
|--------|---------|
| **View logs** | `tail -f app.log` |
| **Check if running** | `ps aux \| grep java` |
| **Stop app** | `pkill java` |
| **Restart app** | `pkill java && nohup java -jar build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1 &` |
| **Update app** | `cd Space-Developer-Contribution && git pull && ./gradlew build -x test && pkill java && nohup java -jar build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1 &` |

---

## 📦 One-Line Install (All in One)

**Amazon Linux:**
```bash
sudo yum install java-17-amazon-corretto git -y && git clone https://github.com/SpaceBank/Space-Developer-Contribution.git && cd Space-Developer-Contribution && chmod +x gradlew && ./gradlew build -x test && nohup java -jar build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1 &
```

**Ubuntu:**
```bash
sudo apt update && sudo apt install openjdk-17-jdk git -y && git clone https://github.com/SpaceBank/Space-Developer-Contribution.git && cd Space-Developer-Contribution && chmod +x gradlew && ./gradlew build -x test && nohup java -jar build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1 &
```

---

## 🆘 Troubleshooting

**"Connection refused" error:**
```bash
# Check if app is running
ps aux | grep java

# Check logs
tail -100 app.log
```

**Port not accessible:**
- Check AWS Security Group has port 8081 open
- Check instance firewall: `sudo firewall-cmd --list-all`

**Out of memory:**
```bash
# Add swap space
sudo dd if=/dev/zero of=/swapfile bs=128M count=16
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

---

## ✅ Summary - What You Need to Install

| Component | Amazon Linux | Ubuntu |
|-----------|--------------|--------|
| **Java 17** | `sudo yum install java-17-amazon-corretto -y` | `sudo apt install openjdk-17-jdk -y` |
| **Git** | `sudo yum install git -y` | `sudo apt install git -y` |
| **Port 8081** | Open in AWS Security Group | Open in AWS Security Group |

**That's it! Just Java + Git + Open Port**
