# 🐛 Debugging Guide for Git Developer Contribution Dashboard

## Quick Start - Running in Debug Mode

### Option 1: Using IntelliJ IDEA GUI (Easiest)

1. **Open the main application file:**
   - Navigate to: `src/main/kotlin/org/git/developer/contribution/GitDeveloperContributionApplication.kt`

2. **Start debugging:**
   - Click the green bug icon 🐛 next to `fun main()` (line 9)
   - OR right-click in the file → **Debug 'GitDeveloperContribution...'**
   - OR use the debug configuration dropdown at the top → Select **"Debug Application"** → Click the bug icon

3. **Access the application:**
   - Once running, open your browser to: `http://localhost:8080`

---

## Setting Breakpoints

### Where to Add Breakpoints (Most Useful Locations)

#### 1. **API Endpoint - Single Repository Analysis**
File: `src/main/kotlin/org/git/developer/contribution/controller/GitProviderController.kt`

```kotlin
@PostMapping("/analyze/single")
fun analyzeSingleRepository(@RequestBody request: SingleRepoAnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
    // 👈 SET BREAKPOINT HERE (line 56)
    val result = remoteAnalyzerService.analyzeSingleRepository(request)
    return ResponseEntity.ok(result)
}
```

#### 2. **Service Layer - Repository Analysis Logic**
File: `src/main/kotlin/org/git/developer/contribution/service/RemoteRepositoryAnalyzerService.kt`

```kotlin
fun analyzeSingleRepository(request: SingleRepoAnalyzeRequest): ContributionAnalysisResponse {
    // 👈 SET BREAKPOINT HERE (line 28)
    val startTime = System.currentTimeMillis()
    val tempBaseDir = Files.createTempDirectory("git-analysis-single-").toFile()
    
    logger.info("═══════════════════════════════════════════════════════════")
    logger.info("📦 Starting analysis: ${request.repositoryFullName}")
    // 👈 OR HERE to see the request details
```

#### 3. **PR Fetching**
File: `src/main/kotlin/org/git/developer/contribution/service/GitProviderService.kt`

```kotlin
fun fetchMergedPullRequests(
    token: String,
    provider: GitProvider,
    repositoryFullName: String,
    since: String? = null,
    baseUrl: String? = null
): List<PullRequestInfo> {
    // 👈 SET BREAKPOINT HERE (line 204)
    return when (provider) {
        GitProvider.GITHUB -> fetchGitHubPullRequests(token, repositoryFullName, since, baseUrl)
        GitProvider.GITLAB -> fetchGitLabMergeRequests(token, repositoryFullName, since, baseUrl)
    }
}
```

---

## How to Set Breakpoints

### Method 1: Click in the Gutter
1. Open any `.kt` file
2. Click in the left margin (gutter) next to the line number
3. A red dot 🔴 will appear

### Method 2: Keyboard Shortcut
1. Place cursor on the desired line
2. Press `Cmd + F8` (macOS) or `Ctrl + F8` (Windows/Linux)

### Method 3: Right-Click Menu
1. Right-click on a line number
2. Select **Toggle Line Breakpoint**

---

## Debugging Controls

Once you hit a breakpoint, use these controls:

| Icon | Action | Shortcut (Mac) | Description |
|------|--------|----------------|-------------|
| ▶️ | Resume Program | `Cmd + Option + R` | Continue to next breakpoint |
| ⏸️ | Pause | - | Pause execution |
| 🛑 | Stop | `Cmd + F2` | Stop debugging |
| ⤵️ | Step Over | `F8` | Execute current line, don't go into methods |
| ⬇️ | Step Into | `F7` | Go into method calls |
| ⬆️ | Step Out | `Shift + F8` | Exit current method |
| 🔄 | Run to Cursor | `Option + F9` | Run until cursor position |

---

## Debug Windows

### Variables Window
- Shows all variables in current scope
- Right-click variable → **Set Value** to change during debugging
- Expand objects to see their properties

### Watches Window
- Add custom expressions to monitor
- Click `+` to add a watch expression
- Example: `request.repositoryFullName`

### Console/Logs Window
- Shows all `logger.info()` output
- Shows exception stack traces
- Filter logs by level (INFO, DEBUG, ERROR)

---

## Advanced Debugging Tips

### 1. Conditional Breakpoints
Right-click on a breakpoint (red dot) → **Edit Breakpoint** → Add condition

Example:
```kotlin
// Only break when analyzing specific repo
request.repositoryFullName == "owner/repo-name"
```

### 2. Log Breakpoints (No Stop)
- Right-click breakpoint → Check **"Log message to console"**
- Uncheck **"Suspend"**
- Useful for tracking execution without stopping

### 3. Evaluate Expression
- While paused at a breakpoint
- Press `Option + F8` (Mac) or `Alt + F8` (Windows)
- Type any Kotlin expression
- Example: `repositories.size`, `data.summary.totalCommits`

### 4. Drop Frame (Go Back)
- Right-click in **Frames** window
- Select **Drop Frame**
- Re-executes the current method from the beginning

---

## Common Debugging Scenarios

### Scenario 1: Debug API Request Handling
```kotlin
// File: GitProviderController.kt
@PostMapping("/analyze/single")
fun analyzeSingleRepository(@RequestBody request: SingleRepoAnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
    // 🔴 Breakpoint here to inspect incoming request
    // Check: request.repositoryFullName, request.startDate, request.token
    
    val result = remoteAnalyzerService.analyzeSingleRepository(request)
    
    // 🔴 Breakpoint here to inspect result before sending response
    // Check: result.summary.totalCommits, result.developers.size
    
    return ResponseEntity.ok(result)
}
```

**What to inspect:**
- `request.token` - Is it valid?
- `request.repositoryFullName` - Is it correctly formatted?
- `result.developers` - How many developers found?
- `result.summary.totalCommits` - Total commits?

### Scenario 2: Debug Repository Cloning
```kotlin
// File: RemoteRepositoryAnalyzerService.kt
val success = gitProviderService.cloneRepository(
    cloneUrl = repo.cloneUrl,
    token = request.token,
    provider = request.provider,
    targetDir = repoDir
)

// 🔴 Breakpoint here to check if clone succeeded
if (!success) {
    logger.error("❌ Failed to clone ${repo.fullName}")
    return emptySingleResponse(request)
}
```

**What to check:**
- `repo.cloneUrl` - Is URL correct?
- `repoDir.absolutePath` - Where is it cloning to?
- `success` - Did clone succeed?

### Scenario 3: Debug PR Fetching
```kotlin
// File: GitProviderService.kt
private fun fetchGitHubPullRequests(...): List<PullRequestInfo> {
    val prs = mutableListOf<PullRequestInfo>()
    var page = 1
    var hasMore = true

    while (hasMore && page <= 10) {
        // 🔴 Breakpoint here to see API call details
        val response = webClient.get()
            .uri(uri)
            .retrieve()
            .bodyToMono<List<Map<String, Any?>>>()
            .block()

        // 🔴 Breakpoint here to inspect response
        if (response.isNullOrEmpty()) {
            hasMore = false
        } else {
            // Process PRs...
        }
    }
    
    return prs
}
```

**What to check:**
- `page` - Current page number
- `response.size` - How many PRs in this page?
- `prs.size` - Total PRs fetched so far

---

## Remote Debugging (Optional)

If you need to debug the app running on a server:

### 1. Start app with debug flags:
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar
```

### 2. In IntelliJ:
- Run → Edit Configurations → Add New → Remote JVM Debug
- Host: `localhost` (or server IP)
- Port: `5005`
- Click Debug

---

## Troubleshooting

### Breakpoint Not Hit?
- ✅ Make sure you're running in **Debug** mode (not Run)
- ✅ Check the breakpoint is enabled (red dot, not gray)
- ✅ Verify the code path is actually executed
- ✅ Try **Invalidate Caches** → Restart (File menu)

### Can't See Variable Values?
- ✅ Make sure you're paused at a breakpoint
- ✅ Check the variable is in scope
- ✅ Try adding it as a Watch expression

### Debug Too Slow?
- ✅ Use **Step Over** (F8) instead of **Step Into** (F7)
- ✅ Disable unused breakpoints
- ✅ Use conditional breakpoints to filter

---

## Quick Reference Card

```
🐛 START DEBUGGING
├── Click bug icon next to main() function
├── OR: Right-click file → Debug
└── OR: Top toolbar → Debug Application

🔴 SET BREAKPOINT
├── Click in left gutter next to line number
├── OR: Cmd+F8 (Mac) / Ctrl+F8 (Win)
└── Right-click for advanced options

⏯️ DEBUG CONTROLS
├── F8 - Step Over (skip method internals)
├── F7 - Step Into (enter method)
├── Shift+F8 - Step Out (exit method)
├── Cmd+Option+R - Resume to next breakpoint
└── Cmd+F2 - Stop debugging

🔍 INSPECT DATA
├── Variables window (automatic)
├── Watches window (custom expressions)
├── Option+F8 - Evaluate expression
└── Console window (logs)
```

---

## Most Useful Breakpoint Locations for Your App

1. **`GitProviderController.analyzeSingleRepository()`** - Line ~56
   - See incoming API requests from frontend

2. **`RemoteRepositoryAnalyzerService.analyzeSingleRepository()`** - Line ~28
   - See repository analysis logic

3. **`GitProviderService.cloneRepository()`** - Line ~151
   - Debug git cloning issues

4. **`GitProviderService.fetchMergedPullRequests()`** - Line ~204
   - Debug PR fetching

5. **`ContributionAggregatorService.analyzeRepositories()`** - Line ~22
   - Debug commit analysis

---

## Tips for Effective Debugging

1. **Start with the Controller** - Set breakpoint at the API endpoint first
2. **Use Conditional Breakpoints** - Only break for specific repos/conditions
3. **Watch Key Variables** - Add watches for important values
4. **Check Logs** - Console shows all logger.info() output
5. **Step Through Carefully** - Use F8 (Step Over) most of the time
6. **Evaluate Expressions** - Use Option+F8 to test code on-the-fly

---

Need help? Your IDE is now configured for debugging! 🚀

