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

## 📄 License

MIT License

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

