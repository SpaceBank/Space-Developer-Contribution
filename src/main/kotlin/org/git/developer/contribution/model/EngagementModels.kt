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
    val repositoryFullNames: List<String> = emptyList(),  // Optional - can be empty to search all org repos
    val contributorLogins: List<String>,
    val startDate: String? = null,
    val endDate: String? = null,
    val period: AggregationPeriod = AggregationPeriod.WEEKLY
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
    val avatarUrl: String?,
    val totalCommits: Int,
    val totalPRsReviewed: Int,
    val totalComments: Int,
    val commitsOverTime: List<EngagementDataPoint>,
    val reviewsOverTime: List<EngagementDataPoint>,
    val commentsOverTime: List<EngagementDataPoint>
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
    val totalPRsReviewed: Int,
    val totalComments: Int,
    val mostActiveCommitter: String?,
    val mostActiveReviewer: String?,
    val mostActiveCommenter: String?
)

