package org.git.developer.contribution.model

import java.time.LocalDate

/**
 * Developer Engagement Models
 */

/**
 * Contributor info from GitHub/GitLab
 */
data class ContributorInfo(
    val login: String,
    val avatarUrl: String?,
    val contributions: Int,
    val profileUrl: String?,
    val name: String? = null,
    val publicRepos: Int? = null  // Number of public repos user has access to
)

/**
 * Request for fetching organization members/contributors
 */
data class OrgMembersRequest(
    val token: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val baseUrl: String? = null
)

/**
 * Request for fetching contributors (legacy - from repos)
 */
data class ContributorsRequest(
    val token: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val baseUrl: String? = null,
    val repositoryFullNames: List<String>
)

/**
 * Response with contributors list
 */
data class ContributorsResponse(
    val contributors: List<ContributorInfo>,
    val totalCount: Int,
    val organizations: List<String> = emptyList()
)

/**
 * Request for developer engagement analysis
 */
data class EngagementAnalyzeRequest(
    val token: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val baseUrl: String? = null,
    val repositoryFullNames: List<String> = emptyList(),  // Optional - empty means search all org repos
    val contributorLogins: List<String>,
    val startDate: String? = null,
    val endDate: String? = null,
    val period: AggregationPeriod = AggregationPeriod.WEEKLY,
    val excludeMergeCommits: Boolean = true  // Exclude merge commits by default
)

/**
 * Time series data point
 */
data class EngagementDataPoint(
    val period: String,
    val value: Int
)

/**
 * Engagement metrics for a single contributor
 */
data class ContributorEngagement(
    val login: String,
    val displayName: String? = null,  // Real display name from cache (e.g., "John Doe")
    val avatarUrl: String?,
    val totalCommits: Int,
    val totalLinesAdded: Int = 0,
    val totalLinesDeleted: Int = 0,
    // New GraphQL-based metrics
    val prsMerged: Int = 0,           // PR Merge Velocity - shows how many ideas were finished
    val prsReviewed: Int = 0,         // Review Responsiveness - team player metric
    val issuesClosed: Int = 0,        // Issue Resolution - problem solving metric
    val activeDays: Int = 0,          // Commit Consistency - active days in period
    // Time series data
    val commitsOverTime: List<EngagementDataPoint>,
    val prsMergedOverTime: List<EngagementDataPoint> = emptyList(),
    val prsReviewedOverTime: List<EngagementDataPoint> = emptyList(),
    val activeDaysOverTime: List<EngagementDataPoint> = emptyList(),
    // Legacy fields - kept for compatibility
    val linesAddedOverTime: List<EngagementDataPoint> = emptyList(),
    val linesDeletedOverTime: List<EngagementDataPoint> = emptyList(),
    val totalPRsReviewed: Int = 0,
    val reviewsOverTime: List<EngagementDataPoint> = emptyList()
)

/**
 * Complete engagement analysis response
 */
data class EngagementAnalysisResponse(
    val analyzedRepositories: List<String>,
    val dateRange: DateRange,
    val period: AggregationPeriod,
    val contributors: List<ContributorEngagement>,
    val periods: List<String>,  // List of period labels for charts
    val summary: EngagementSummary
)

/**
 * Summary of engagement metrics
 */
data class EngagementSummary(
    val totalContributors: Int,
    val totalCommits: Int,
    val totalLinesAdded: Int,
    val totalLinesDeleted: Int,
    val mostActiveCommitter: String?,
    val mostLinesChangedBy: String?,
    // New GraphQL-based metrics
    val totalPRsMerged: Int = 0,      // Total PRs merged by all contributors
    val totalPRsReviewed: Int = 0,    // Total PR reviews by all contributors
    val totalIssuesClosed: Int = 0,   // Total issues closed by all contributors
    val totalActiveDays: Int = 0,     // Sum of active days across contributors
    // Legacy fields
    val mostActiveReviewer: String? = null
)

/**
 * PR and Review data response for parallel loading
 */
data class PRReviewResponse(
    val contributors: List<ContributorPRData>
)

/**
 * PR data for a single contributor
 */
data class ContributorPRData(
    val login: String,
    val prsMerged: Int,
    val prsReviewed: Int,
    val activeDays: Int,
    val prsMergedOverTime: List<EngagementDataPoint>,
    val prsReviewedOverTime: List<EngagementDataPoint>,
    val activeDaysOverTime: List<EngagementDataPoint>
)

