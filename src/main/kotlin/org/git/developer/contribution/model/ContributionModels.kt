package org.git.developer.contribution.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Represents a single commit from a Git repository
 */
data class CommitInfo(
    val hash: String,
    val authorName: String,
    val authorEmail: String,
    val date: LocalDateTime,
    val message: String,
    val linesAdded: Int = 0,
    val linesDeleted: Int = 0,
    val filesChanged: Int = 0,
    val repositoryName: String,
    val isMerge: Boolean = false
)

/**
 * Aggregated contribution data for a developer
 */
data class DeveloperContribution(
    val authorName: String,
    val authorEmail: String,
    val totalCommits: Int,
    val totalLinesAdded: Int,
    val totalLinesDeleted: Int,
    val totalFilesChanged: Int,
    val repositories: Set<String>,
    val firstCommitDate: LocalDateTime?,
    val lastCommitDate: LocalDateTime?
)

/**
 * Time-series data point for charting
 */
data class ContributionDataPoint(
    val period: String,  // e.g., "2026-W01" for week or "2026-01" for month
    val startDate: LocalDate,
    val endDate: LocalDate,
    val commits: Int,
    val linesAdded: Int,
    val linesDeleted: Int,
    val filesChanged: Int
)

/**
 * Contribution timeline for a specific developer
 */
data class DeveloperTimeline(
    val authorName: String,
    val authorEmail: String,  // Primary email (first one found)
    val nickname: String,     // Extracted from email (e.g., "levan.karanadze" from email or GitHub username)
    val emails: Set<String>,  // All emails associated with this developer
    val dataPoints: List<ContributionDataPoint>,
    val repositories: Set<String>
)

/**
 * Aggregation period options
 */
enum class AggregationPeriod {
    DAILY,
    WEEKLY,
    MONTHLY
}

/**
 * Request body for analyzing repositories
 */
data class AnalyzeRequest(
    val repositoryPaths: List<String>,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val period: AggregationPeriod = AggregationPeriod.WEEKLY,
    val branch: String? = null,  // null means all branches
    val excludeMerges: Boolean = true  // Exclude merge commits by default
)

/**
 * Complete analysis response with all developers
 */
data class ContributionAnalysisResponse(
    val analyzedRepositories: List<String>,
    val dateRange: DateRange,
    val period: AggregationPeriod,
    val developers: List<DeveloperTimeline>,
    val summary: AnalysisSummary,
    val prCountByPeriod: List<Int> = emptyList(),  // Merged PRs per period for chart
    val prAuthorStats: List<PRAuthorStats> = emptyList()  // PRs by author
)

/**
 * PR statistics by author
 */
data class PRAuthorStats(
    val authorName: String,
    val prCount: Int
)

data class DateRange(
    val startDate: LocalDate,
    val endDate: LocalDate
)

data class AnalysisSummary(
    val totalDevelopers: Int,
    val totalCommits: Int,
    val totalLinesAdded: Int,
    val totalLinesDeleted: Int,
    val mostActiveAuthor: String?,
    val mostActiveRepository: String?,
    val totalPRs: Int = 0
)

