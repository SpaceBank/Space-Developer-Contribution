package org.git.developer.contribution.model

/**
 * Trunk-Based Matrix Models
 *
 * In trunk-based development commits go directly to main/master.
 * A configurable GitHub Actions workflow determines deployment success.
 *
 * For each commit we find the FIRST run of the named action AFTER the commit time:
 *   - If that first run succeeded → commit is "successfully deployed"
 *   - If that first run failed   → commit is a "failed deployment"
 *   - If no run found            → commit is "pending"
 *
 * Lead Time = action_completed_at − commit_authored_at
 * Cycle Time = same as lead time in trunk-based flow
 * MTTR = avg(next_success_completed_at − failure_completed_at)
 * Change Failure Rate = failed_commits / (failed + successful) * 100
 */

// ─── Request / Response ────────────────────────────────────────

data class TrunkMetricsRequest(
    val token: String,
    val provider: String = "GITHUB",
    val owner: String,
    val repo: String,
    val branch: String = "main",
    val workflowName: String,
    val startDate: String,
    val endDate: String
)

data class TrunkMetricsResponse(
    val owner: String,
    val repo: String,
    val branch: String,
    val workflowName: String,
    val startDate: String,
    val endDate: String,

    // Counts
    val totalCommits: Int,
    val totalActionRuns: Int,
    val successfulCommits: Int,
    val failedCommits: Int,
    val pendingCommits: Int,
    val successfulDeploys: Int,          // unique successful workflow runs
    val failedDeploys: Int,              // unique failed workflow runs

    // DORA metrics
    val deploymentFrequency: Double,        // successful deploys / day
    val leadTimeForChangesHours: Double,     // avg hours commit → action completed
    val cycleTimeHours: Double,             // same as lead time in trunk
    val changeFailureRate: Double,          // percentage
    val meanTimeToRecoveryHours: Double,    // avg hours failure → next success

    // Secondary metrics
    val commitFrequency: Double,            // commits / day
    val avgBatchSize: Double,               // avg lines changed per commit
    val avgPipelineDurationMinutes: Double,  // avg action run duration
    val deploySuccessRate: Double,          // 100 − changeFailureRate

    // Team-level metrics (same structure as matrix page)
    val teamMetrics: TrunkTeamMetrics,

    // Detail data
    val commits: List<TrunkCommitInfo>,
    val actionRuns: List<TrunkActionRun>,
    val dailyStats: List<DailyTrunkStat>,
    val weeklyTrend: List<WeeklyTrunkTrend>,
    val authorStats: List<TrunkAuthorStats>,
    val ratings: TrunkRatings
)

// ─── Commit info ───────────────────────────────────────────────

data class TrunkCommitInfo(
    val sha: String,
    val shortSha: String,
    val message: String,
    val authorLogin: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val committedAt: String,          // ISO-8601

    // Size
    val linesAdded: Int,
    val linesDeleted: Int,
    val filesChanged: Int,

    // First action run after this commit
    val firstActionRunId: Long?,
    val firstActionStatus: String?,   // SUCCESS / FAILURE / null
    val firstActionConclusion: String?,
    val firstActionStartedAt: String?,
    val firstActionCompletedAt: String?,
    val firstActionUrl: String?,

    // Derived
    val leadTimeMinutes: Double?,
    val cycleTimeMinutes: Double?,
    val deploymentResult: String      // SUCCESS / FAILURE / PENDING
)

// ─── Action run info ───────────────────────────────────────────

data class TrunkActionRun(
    val runId: Long,
    val workflowName: String,
    val status: String,               // SUCCESS / FAILURE / IN_PROGRESS
    val conclusion: String?,
    val startedAt: String,
    val completedAt: String?,
    val durationMinutes: Double,
    val headSha: String,
    val htmlUrl: String,
    val event: String?
)

// ─── Daily aggregation ─────────────────────────────────────────

data class DailyTrunkStat(
    val date: String,
    val commits: Int,
    val actionRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val avgLeadTimeMinutes: Double?,
    val avgCycleTimeMinutes: Double?
)

// ─── Per-author aggregation ────────────────────────────────────

data class TrunkAuthorStats(
    val authorLogin: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val totalCommits: Int,
    val successfulCommits: Int,
    val failedCommits: Int,
    val pendingCommits: Int,
    val commitSuccessRate: Double,
    val avgLeadTimeMinutes: Double?,
    val avgCycleTimeMinutes: Double?,
    val avgTimeToDeployMinutes: Double?,
    val avgBatchSize: Double,
    val totalLinesAdded: Int,
    val totalLinesDeleted: Int,
    val deploymentFrequency: Double,
    val commits: List<TrunkCommitInfo>
)

// ─── Team-level metrics ────────────────────────────────────────

data class TrunkTeamMetrics(
    val deploymentFrequency: TrunkMetricValue,
    val leadTime: TrunkMetricValue,
    val cycleTime: TrunkMetricValue,
    val changeFailureRate: TrunkMetricValue,
    val mttr: TrunkMetricValue,
    val commitFrequency: TrunkMetricValue,
    val batchSize: TrunkMetricValue,
    val timeToDeploy: TrunkMetricValue,
    val deploySuccessRate: TrunkMetricValue,
    val totalCommits: Int,
    val totalDevelopers: Int
)

data class TrunkMetricValue(
    val value: Double,
    val unit: String,
    val displayValue: String,
    val rating: String       // ELITE / GOOD / FAIR / NEEDS_FOCUS
)

// ─── Weekly trend ──────────────────────────────────────────────

data class WeeklyTrunkTrend(
    val week: String,
    val startDate: String,
    val endDate: String,
    val commits: Int,
    val deployments: Int,
    val successfulDeploys: Int,
    val failedDeploys: Int,
    val avgLeadTimeHours: Double?,
    val avgCycleTimeHours: Double?,
    val deploymentFrequency: Double,
    val changeFailureRate: Double
)

// ─── DORA ratings ──────────────────────────────────────────────

data class TrunkRatings(
    val deploymentFrequency: String,   // Elite / High / Medium / Low
    val leadTimeForChanges: String,
    val cycleTime: String,
    val changeFailureRate: String,
    val meanTimeToRecovery: String,
    val commitFrequency: String,
    val batchSize: String,
    val timeToDeploy: String,
    val overall: String
)

