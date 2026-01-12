# Git Developer Contribution Dashboard

A comprehensive web application for analyzing Git repository contributions, engineering metrics, and developer engagement across GitHub and GitLab organizations.

## 🚀 Features

- **Developer Contribution Dashboard** - Analyze commits, lines of code, and contributor statistics
- **Engineering Matrix** - Track DORA-like metrics (Cycle Time, Pickup Time, Review Time, etc.)
- **Developer Engagement** - Monitor individual contributor activity and performance

## 📋 Prerequisites

- **Java 17** or higher
- **Gradle** (wrapper included)
- **GitHub/GitLab Personal Access Token** with appropriate permissions

## 🔧 Installation & Running

### Quick Start

```bash
# Clone the repository
git clone <repository-url>
cd GitDeveloperContribution

# Run the application
./gradlew bootRun
```

The application will start at `http://localhost:8081`

### Build Only

```bash
# Compile the project
./gradlew compileKotlin

# Build the JAR file
./gradlew build

# Clean build artifacts
./gradlew clean

# Clean and rebuild
./gradlew clean build
```

## ⚙️ Configuration

### Changing the Port

#### Option 1: Edit application.properties

Edit `src/main/resources/application.properties`:

```properties
server.port=8082
```

#### Option 2: Command Line Argument

```bash
./gradlew bootRun --args='--server.port=8082'
```

#### Option 3: Environment Variable

```bash
SERVER_PORT=8082 ./gradlew bootRun
```

## 🔥 Troubleshooting

### Port Already in Use

If you see the error:
```
Web server failed to start. Port 8081 was already in use.
```

#### Find and Kill the Process on Port 8081

**macOS/Linux:**
```bash
# Find the process using port 8081
lsof -i :8081

# Kill the process by PID
kill -9 <PID>

# Or kill directly in one command
lsof -ti:8081 | xargs kill -9
```

**Windows:**
```cmd
# Find the process using port 8081
netstat -ano | findstr :8081

# Kill the process by PID
taskkill /PID <PID> /F
```

#### Or Simply Change the Port

```bash
./gradlew bootRun --args='--server.port=8082'
```

### Build Stuck at 85% EXECUTING

This is normal behavior - the application is running. The Gradle task doesn't complete because bootRun keeps the server alive. Access the app at `http://localhost:8081`.

### Browser Cache Issues

If changes aren't reflected:

**Chrome:**
- Press `Cmd+Shift+R` (macOS) or `Ctrl+Shift+R` (Windows/Linux) for hard refresh
- Or open DevTools (F12) → Right-click refresh button → "Empty Cache and Hard Reload"

**Safari:**
- Press `Cmd+Option+E` to empty cache, then `Cmd+R` to reload

### Clear All Gradle Caches

```bash
./gradlew clean
rm -rf ~/.gradle/caches/
./gradlew build
```

### Stop All Running Instances

```bash
# Kill all Java processes (use with caution)
pkill -f 'java.*GitDeveloperContribution'

# Or kill by port
lsof -ti:8081 | xargs kill -9 2>/dev/null
lsof -ti:8080 | xargs kill -9 2>/dev/null
```

## 🔑 GitHub Token Permissions

When creating a GitHub Personal Access Token, ensure these permissions:

- `repo` - Full control of private repositories
- `read:org` - Read organization membership
- `read:user` - Read user profile data

### Creating a Token

1. Go to GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Click "Generate new token (classic)"
3. Select the required scopes
4. Copy and save the token securely

## 📊 Usage

### 1. Developer Contribution

- Enter your GitHub/GitLab token
- Select repositories to analyze
- Choose date range and aggregation period (daily/weekly/monthly)
- View commit statistics, lines changed, and contributor charts

### 2. Engineering Matrix

- Analyze DORA metrics:
  - **Coding Time** - Time from first commit to PR creation
  - **Pickup Time** - Time for PR to get first review
  - **Approve Time** - Time from first review to approval
  - **Merge Time** - Time from approval to merge
  - **Review Time** - Total time from PR creation to merge
  - **Cycle Time** - Total time from first commit to merge
  - **Merge Frequency** - PRs merged per developer per week
  - **PR Size** - Average lines changed per PR

### 3. Developer Engagement

- Track individual contributor metrics
- Monitor commits, PRs reviewed, and lines changed
- View trends over time

## 📁 Project Structure

```
GitDeveloperContribution/
├── src/
│   ├── main/
│   │   ├── kotlin/org/git/developer/contribution/
│   │   │   ├── controller/       # REST API endpoints
│   │   │   ├── model/            # Data models
│   │   │   └── service/          # Business logic
│   │   └── resources/
│   │       ├── static/           # HTML, CSS, JS files
│   │       └── application.properties
│   └── test/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 🛠️ Tech Stack

- **Backend:** Kotlin, Spring Boot 4.0.1
- **Frontend:** HTML5, CSS3, JavaScript, Chart.js
- **Build Tool:** Gradle (Kotlin DSL)
- **APIs:** GitHub REST API, GitHub GraphQL API

## 📝 API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/git/repositories` | Fetch repositories from Git provider |
| POST | `/api/git/analyze` | Analyze contribution data |
| POST | `/api/metrics/analyze` | Calculate engineering metrics |
| POST | `/api/engagement/contributors` | Fetch contributors list |
| POST | `/api/engagement/analyze` | Analyze developer engagement |

## ⚠️ Known Issues

1. **Large organizations** - Fetching 500+ repos may take time
2. **Rate limiting** - GitHub API has rate limits; use GraphQL where possible
3. **Merge commits** - Enable "Exclude merge commits" for accurate stats

---

## ☁️ AWS Deployment Guide

### Option 1: EC2 Instance (Recommended for Beginners)

#### What You Need from AWS:
- AWS Account
- EC2 Instance (t2.micro for free tier, t2.small/medium for production)
- Security Group with ports 22 (SSH), 80 (HTTP), 443 (HTTPS), 8081 (App)
- Elastic IP (optional, for static public IP)

#### Step-by-Step Deployment:

**1. Launch EC2 Instance**
```bash
# AWS Console → EC2 → Launch Instance
# - Choose: Amazon Linux 2023 or Ubuntu 22.04
# - Instance type: t2.small (2GB RAM recommended)
# - Create/select key pair for SSH
# - Configure Security Group (see below)
```

**2. Configure Security Group**
```
Inbound Rules:
- SSH (22) → Your IP or 0.0.0.0/0
- HTTP (80) → 0.0.0.0/0
- HTTPS (443) → 0.0.0.0/0
- Custom TCP (8081) → 0.0.0.0/0
```

**3. Connect to EC2**
```bash
# Download your .pem key file and connect
chmod 400 your-key.pem
ssh -i your-key.pem ec2-user@<your-ec2-public-ip>
# For Ubuntu: ssh -i your-key.pem ubuntu@<your-ec2-public-ip>
```

**4. Install Java 17**
```bash
# Amazon Linux 2023
sudo yum install java-17-amazon-corretto -y

# Ubuntu
sudo apt update
sudo apt install openjdk-17-jdk -y

# Verify installation
java -version
```

**5. Install Git and Clone Project**
```bash
sudo yum install git -y  # Amazon Linux
# OR
sudo apt install git -y  # Ubuntu

git clone https://github.com/SpaceBank/Space-Developer-Contribution.git
cd Space-Developer-Contribution
```

**6. Build and Run**
```bash
# Make gradlew executable
chmod +x gradlew

# Build the JAR
./gradlew build -x test

# Run on port 80 (requires sudo) or 8081
sudo ./gradlew bootRun --args='--server.port=80'
# OR
./gradlew bootRun --args='--server.port=8081'
```

**7. Run as Background Service**
```bash
# Create a systemd service file
sudo nano /etc/systemd/system/gitcontribution.service
```

Add this content:
```ini
[Unit]
Description=Git Developer Contribution Dashboard
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/Space-Developer-Contribution
ExecStart=/home/ec2-user/Space-Developer-Contribution/gradlew bootRun --args='--server.port=8081'
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable gitcontribution
sudo systemctl start gitcontribution
sudo systemctl status gitcontribution
```

**8. Access Your App**
```
http://<your-ec2-public-ip>:8081
```

---

### Option 2: Run as JAR with Nginx Reverse Proxy

**1. Build JAR file**
```bash
./gradlew build -x test
# JAR will be in: build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar
```

**2. Run JAR directly**
```bash
# Run in background
nohup java -jar build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1 &

# Check if running
ps aux | grep java
```

**3. Install and Configure Nginx**
```bash
# Amazon Linux
sudo yum install nginx -y

# Ubuntu
sudo apt install nginx -y

# Start Nginx
sudo systemctl start nginx
sudo systemctl enable nginx
```

**4. Configure Nginx as Reverse Proxy**
```bash
sudo nano /etc/nginx/conf.d/gitcontribution.conf
```

Add:
```nginx
server {
    listen 80;
    server_name your-domain.com;  # or use _ for any

    location / {
        proxy_pass http://localhost:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_cache_bypass $http_upgrade;
        proxy_read_timeout 300s;
        proxy_connect_timeout 75s;
    }
}
```

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Now access via: `http://<your-ec2-public-ip>` (port 80)

---

### Option 3: AWS Elastic Beanstalk (Easy Auto-scaling)

**1. Install EB CLI**
```bash
pip install awsebcli
```

**2. Build JAR**
```bash
./gradlew build -x test
```

**3. Initialize and Deploy**
```bash
eb init -p java-17 git-contribution-app
eb create git-contribution-env
eb deploy
```

**4. Open App**
```bash
eb open
```

---

### Option 4: Docker + ECS/ECR

**1. Create Dockerfile**
```dockerfile
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=8081"]
```

**2. Build and Push to ECR**
```bash
# Build JAR first
./gradlew build -x test

# Build Docker image
docker build -t git-contribution .

# Create ECR repository (AWS Console or CLI)
aws ecr create-repository --repository-name git-contribution

# Login to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com

# Tag and push
docker tag git-contribution:latest <account-id>.dkr.ecr.us-east-1.amazonaws.com/git-contribution:latest
docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/git-contribution:latest
```

**3. Deploy to ECS**
- Create ECS Cluster
- Create Task Definition with your ECR image
- Create Service with desired count

---

### 🔐 SSL/HTTPS Setup (Recommended for Production)

**Option A: AWS Certificate Manager + Load Balancer**
1. Request free SSL certificate in ACM
2. Create Application Load Balancer
3. Add HTTPS listener with ACM certificate
4. Point to your EC2 instance

**Option B: Let's Encrypt with Certbot**
```bash
# Install Certbot
sudo yum install certbot python3-certbot-nginx -y  # Amazon Linux
# OR
sudo apt install certbot python3-certbot-nginx -y  # Ubuntu

# Get certificate (requires domain pointing to your server)
sudo certbot --nginx -d your-domain.com
```

---

### 💰 AWS Cost Estimation

| Service | Free Tier | Production |
|---------|-----------|------------|
| EC2 t2.micro | 750 hrs/month free (1 year) | ~$8/month |
| EC2 t2.small | - | ~$17/month |
| Elastic IP | Free if attached | $3.6/month if unused |
| Data Transfer | 100GB free | $0.09/GB after |
| Route 53 (DNS) | - | $0.50/zone/month |

---

### 🚀 Quick Deploy Script

Create `deploy.sh` on your EC2:
```bash
#!/bin/bash
cd /home/ec2-user/Space-Developer-Contribution
git pull origin main
./gradlew build -x test
sudo systemctl restart gitcontribution
echo "Deployment complete!"
```

Make executable and run:
```bash
chmod +x deploy.sh
./deploy.sh
```

---

### 📋 AWS Checklist

- [ ] AWS Account created
- [ ] EC2 instance launched
- [ ] Security Group configured (ports 22, 80, 443, 8081)
- [ ] Java 17 installed
- [ ] Git installed and repo cloned
- [ ] Application built and running
- [ ] Systemd service configured (for auto-restart)
- [ ] Nginx configured (optional, for port 80)
- [ ] Domain configured (optional)
- [ ] SSL certificate installed (optional)

## 📄 License

MIT License

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

