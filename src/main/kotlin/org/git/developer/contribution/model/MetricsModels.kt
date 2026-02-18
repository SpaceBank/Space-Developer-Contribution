package org.git.developer.contribution.model

/**
 * Engineering Metrics Models
 */

/**
 * Rating levels for metrics
 */
enum class MetricRating {
    ELITE,
    GOOD,
    FAIR,
    NEEDS_FOCUS
}

/**
 * Individual metric value with rating
 */
data class MetricValue(
    val value: Double,
    val unit: String,
    val rating: MetricRating,
    val displayValue: String
)

/**
 * PR Metrics for a single Pull Request
 */
data class PRMetrics(
    val prNumber: Int,
    val prTitle: String,
    val author: String,
    val repositoryFullName: String,    // Repository full name (owner/repo)
    val codingTimeHours: Double?,      // First commit to PR opened
    val pickupTimeHours: Double?,      // PR opened to first review
    val approveTimeHours: Double?,     // First comment to first approval
    val mergeTimeHours: Double?,       // First approval to merge
    val reviewTimeHours: Double?,      // PR opened to merge
    val cycleTimeHours: Double?,       // First commit to merge
    val prSize: Int,                   // Lines changed (additions + deletions)
    val additions: Int,                // Lines added
    val deletions: Int,                // Lines deleted
    val createdAt: String,
    val mergedAt: String?,
    val firstCommitTime: String?,      // First commit timestamp
    val firstReviewTime: String?,      // First review timestamp
    val firstApprovalTime: String?     // First approval timestamp
)

/**
 * Aggregated metrics for a developer
 */
data class DeveloperMetrics(
    val developerName: String,
    val nickname: String,
    val codingTime: MetricValue,
    val pickupTime: MetricValue,
    val approveTime: MetricValue,
    val mergeTime: MetricValue,
    val reviewTime: MetricValue,
    val cycleTime: MetricValue,
    val mergeFrequency: MetricValue,
    val prSize: MetricValue,
    val totalPRs: Int,
    val prDetails: List<PRMetrics>
)

/**
 * Team-level aggregated metrics
 */
data class TeamMetrics(
    val codingTime: MetricValue,
    val pickupTime: MetricValue,
    val approveTime: MetricValue,
    val mergeTime: MetricValue,
    val reviewTime: MetricValue,
    val cycleTime: MetricValue,
    val mergeFrequency: MetricValue,
    val prSize: MetricValue,
    val totalPRs: Int,
    val totalDevelopers: Int
)

/**
 * Request for metrics analysis
 */
data class MetricsAnalyzeRequest(
    val token: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val baseUrl: String? = null,
    val repositoryFullNames: List<String>,
    val startDate: String? = null,
    val endDate: String? = null,
    val branches: List<String>? = null  // Optional branch filter
)

/**
 * Complete metrics response
 */
data class MetricsAnalysisResponse(
    val analyzedRepositories: List<String>,
    val dateRange: DateRange,
    val teamMetrics: TeamMetrics,
    val developerMetrics: List<DeveloperMetrics>,
    val weeklyTrend: List<WeeklyMetricsTrend>
)

/**
 * Weekly trend data
 */
data class WeeklyMetricsTrend(
    val week: String,
    val startDate: String,
    val endDate: String,
    val cycleTime: Double,
    val reviewTime: Double,
    val mergeFrequency: Double,
    val prCount: Int
)

