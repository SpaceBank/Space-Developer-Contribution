package org.git.developer.contribution.model

/**
 * Git provider type
 */
enum class GitProvider {
    GITHUB,
    GITLAB
}

/**
 * Repository information from Git provider API
 */
data class RepositoryInfo(
    val id: Long,
    val name: String,
    val fullName: String,
    val description: String?,
    val cloneUrl: String,
    val sshUrl: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val owner: String,
    val provider: GitProvider
)

/**
 * Request to authenticate and list repositories
 */
data class AuthRequest(
    val token: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val baseUrl: String? = null  // For self-hosted GitLab/GitHub Enterprise
)

/**
 * Request to analyze selected repositories by cloning them
 */
data class RemoteAnalyzeRequest(
    val token: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val baseUrl: String? = null,
    val repositoryFullNames: List<String>,  // e.g., ["owner/repo1", "owner/repo2"]
    val startDate: String? = null,
    val endDate: String? = null,
    val period: AggregationPeriod = AggregationPeriod.WEEKLY,
    val branch: String? = null,  // null means all branches, "master" or "main" for default branch
    val excludeMerges: Boolean = true  // Exclude merge commits by default (like GitHub)
)

/**
 * Request to analyze a single repository (for parallel processing)
 */
data class SingleRepoAnalyzeRequest(
    val token: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val baseUrl: String? = null,
    val repositoryFullName: String,  // e.g., "owner/repo"
    val startDate: String? = null,
    val endDate: String? = null,
    val period: AggregationPeriod = AggregationPeriod.WEEKLY,
    val branch: String? = null,
    val excludeMerges: Boolean = true
)

/**
 * Response with list of available repositories
 */
data class RepositoryListResponse(
    val repositories: List<RepositoryInfo>,
    val totalCount: Int
)

/**
 * Pull Request info
 */
data class PullRequestInfo(
    val id: Long,
    val number: Int,
    val title: String,
    val state: String,  // open, closed, merged
    val createdAt: String,
    val mergedAt: String?,
    val authorName: String,
    val repositoryFullName: String
)

/**
 * Request to store session data (contributors) after login
 */
data class SessionDataRequest(
    val contributors: List<Map<String, Any?>>? = null
)

/**
 * Response after storing session data
 */
data class SessionDataResponse(
    val contributorsCached: Int,
    val success: Boolean
)

