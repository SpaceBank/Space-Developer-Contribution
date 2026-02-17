package org.git.developer.contribution.service

import org.git.developer.contribution.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.*

/**
 * Service for analyzing developer engagement metrics
 * Uses GitHub GraphQL API for fast, comprehensive data fetching
 */
@Service
class EngagementService(
    private val contributorCacheService: ContributorCacheService
) {
    private val logger = LoggerFactory.getLogger(EngagementService::class.java)

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com"
        private const val GITHUB_GRAPHQL_URL = "https://api.github.com/graphql"
        private const val GITLAB_API_URL = "https://gitlab.com/api/v4"
    }

    /**
     * Fetch organization members using the token
     */
    fun fetchOrgMembers(request: OrgMembersRequest): ContributorsResponse {
        return when (request.provider) {
            GitProvider.GITHUB -> fetchGitHubOrgMembers(request.token, request.baseUrl)
            GitProvider.GITLAB -> fetchGitLabGroupMembers(request.token, request.baseUrl)
        }
    }

    private fun fetchGitHubOrgMembers(token: String, baseUrl: String?): ContributorsResponse {
        val apiUrl = baseUrl ?: GITHUB_API_URL
        val webClient = createWebClient(apiUrl, token, GitProvider.GITHUB)
        val allMembers = mutableMapOf<String, ContributorInfo>()
        val organizations = mutableListOf<String>()

        try {
            val orgsResponse = webClient.get()
                .uri("/user/orgs?per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            logger.info("Found ${orgsResponse?.size ?: 0} organizations")

            orgsResponse?.forEach { org ->
                val orgLogin = org["login"] as? String ?: return@forEach
                organizations.add(orgLogin)

                try {
                    var page = 1
                    var hasMore = true

                    while (hasMore && page <= 10) {
                        val members = webClient.get()
                            .uri("/orgs/$orgLogin/members?per_page=100&page=$page")
                            .retrieve()
                            .bodyToMono<List<Map<String, Any?>>>()
                            .block()

                        if (members.isNullOrEmpty()) {
                            hasMore = false
                        } else {
                            members.forEach { member ->
                                val login = member["login"] as? String ?: return@forEach
                                if (!allMembers.containsKey(login.lowercase())) {
                                    allMembers[login.lowercase()] = ContributorInfo(
                                        login = login,
                                        avatarUrl = member["avatar_url"] as? String,
                                        contributions = 0,
                                        profileUrl = member["html_url"] as? String,
                                        name = null
                                    )
                                }
                            }
                            page++
                            if (members.size < 100) hasMore = false
                        }
                    }
                    logger.info("Fetched members from org: $orgLogin")
                } catch (e: Exception) {
                    logger.warn("Could not fetch members from org $orgLogin: ${e.message}")
                }
            }

            // Fetch user details for first 50 members
            allMembers.values.take(50).forEach { member ->
                try {
                    val userDetails = webClient.get()
                        .uri("/users/${member.login}")
                        .retrieve()
                        .bodyToMono<Map<String, Any?>>()
                        .block()

                    val name = userDetails?.get("name") as? String
                    val publicRepos = (userDetails?.get("public_repos") as? Number)?.toInt()

                    allMembers[member.login.lowercase()] = member.copy(
                        name = name ?: member.name,
                        publicRepos = publicRepos
                    )
                } catch (e: Exception) {
                    // Ignore
                }
            }

        } catch (e: Exception) {
            logger.error("Error fetching GitHub org members: ${e.message}", e)
        }

        val sortedMembers = allMembers.values.sortedByDescending { it.contributions }

        return ContributorsResponse(
            contributors = sortedMembers,
            totalCount = sortedMembers.size,
            organizations = organizations
        )
    }

    private fun fetchGitLabGroupMembers(token: String, baseUrl: String?): ContributorsResponse {
        val apiUrl = baseUrl ?: GITLAB_API_URL
        val webClient = createWebClient(apiUrl, token, GitProvider.GITLAB)
        val allMembers = mutableMapOf<String, ContributorInfo>()
        val groups = mutableListOf<String>()

        try {
            val groupsResponse = webClient.get()
                .uri("/groups?per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            groupsResponse?.forEach { group ->
                val groupId = (group["id"] as? Number)?.toLong() ?: return@forEach
                val groupName = group["name"] as? String ?: ""
                groups.add(groupName)

                try {
                    val members = webClient.get()
                        .uri("/groups/$groupId/members?per_page=100")
                        .retrieve()
                        .bodyToMono<List<Map<String, Any?>>>()
                        .block()

                    members?.forEach { member ->
                        val username = member["username"] as? String ?: return@forEach
                        if (!allMembers.containsKey(username.lowercase())) {
                            allMembers[username.lowercase()] = ContributorInfo(
                                login = username,
                                avatarUrl = member["avatar_url"] as? String,
                                contributions = 0,
                                profileUrl = member["web_url"] as? String,
                                name = member["name"] as? String
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Could not fetch members from group $groupName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error fetching GitLab group members: ${e.message}", e)
        }

        return ContributorsResponse(
            contributors = allMembers.values.toList(),
            totalCount = allMembers.size,
            organizations = groups
        )
    }

    /**
     * Fetch contributors for given repositories (legacy method)
     */
    fun fetchContributors(request: ContributorsRequest): ContributorsResponse {
        val allContributors = mutableMapOf<String, ContributorInfo>()

        for (repoFullName in request.repositoryFullNames) {
            val contributors = when (request.provider) {
                GitProvider.GITHUB -> fetchGitHubContributors(request.token, repoFullName, request.baseUrl)
                GitProvider.GITLAB -> fetchGitLabContributors(request.token, repoFullName, request.baseUrl)
            }

            contributors.forEach { contributor ->
                val existing = allContributors[contributor.login.lowercase()]
                if (existing != null) {
                    allContributors[contributor.login.lowercase()] = existing.copy(
                        contributions = existing.contributions + contributor.contributions
                    )
                } else {
                    allContributors[contributor.login.lowercase()] = contributor
                }
            }
        }

        val sortedContributors = allContributors.values.sortedByDescending { it.contributions }

        return ContributorsResponse(
            contributors = sortedContributors,
            totalCount = sortedContributors.size
        )
    }

    private fun fetchGitHubContributors(token: String, repoFullName: String, baseUrl: String?): List<ContributorInfo> {
        val apiUrl = baseUrl ?: GITHUB_API_URL
        val webClient = createWebClient(apiUrl, token, GitProvider.GITHUB)
        val contributors = mutableListOf<ContributorInfo>()

        try {
            val response = webClient.get()
                .uri("/repos/$repoFullName/contributors?per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            response?.forEach { c ->
                contributors.add(ContributorInfo(
                    login = c["login"] as? String ?: "",
                    avatarUrl = c["avatar_url"] as? String,
                    contributions = (c["contributions"] as? Number)?.toInt() ?: 0,
                    profileUrl = c["html_url"] as? String
                ))
            }
        } catch (e: Exception) {
            logger.error("Error fetching GitHub contributors for $repoFullName: ${e.message}")
        }

        return contributors
    }

    private fun fetchGitLabContributors(token: String, repoFullName: String, baseUrl: String?): List<ContributorInfo> {
        val apiUrl = baseUrl ?: GITLAB_API_URL
        val webClient = createWebClient(apiUrl, token, GitProvider.GITLAB)
        val contributors = mutableListOf<ContributorInfo>()
        val encodedPath = repoFullName.replace("/", "%2F")

        try {
            val response = webClient.get()
                .uri("/projects/$encodedPath/repository/contributors")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            response?.forEach { c ->
                contributors.add(ContributorInfo(
                    login = c["name"] as? String ?: c["email"] as? String ?: "",
                    avatarUrl = null,
                    contributions = (c["commits"] as? Number)?.toInt() ?: 0,
                    profileUrl = null
                ))
            }
        } catch (e: Exception) {
            logger.error("Error fetching GitLab contributors for $repoFullName: ${e.message}")
        }

        return contributors
    }

    /**
     * FAST: Analyze only commits (no PR/review data)
     * Returns quickly for immediate display
     */
    fun analyzeCommitsOnly(request: EngagementAnalyzeRequest): EngagementAnalysisResponse {
        logger.info("🚀 analyzeCommitsOnly() started")
        logger.info("📊 Request: provider=${request.provider}, repos=${request.repositoryFullNames.size}, contributors=${request.contributorLogins.size}")
        logger.info("📅 Date range: ${request.startDate} to ${request.endDate}, period=${request.period}")
        logger.info("🔀 Exclude merge commits: ${request.excludeMergeCommits}")

        try {
            val startDate = request.startDate?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(3)
            val endDate = request.endDate?.let { LocalDate.parse(it) } ?: LocalDate.now()

            val repositories = if (request.repositoryFullNames.isEmpty()) {
                fetchAccessibleRepos(request.token, request.provider, request.baseUrl)
            } else {
                request.repositoryFullNames
            }

            logger.info("📁 Analyzing ${repositories.size} repositories")
            logger.info("FAST: Analyzing commits only for ${request.contributorLogins.size} contributors")

            val periods = generatePeriods(startDate, endDate, request.period.name)
            val periodLabels = periods.map { it.first }

            val contributorEngagements = request.contributorLogins.mapIndexed { index, login ->
                logger.info("Processing contributor ${index + 1}/${request.contributorLogins.size}: $login")
                analyzeCommitsOnlyForContributor(
                    token = request.token,
                    login = login,
                    startDate = startDate,
                    endDate = endDate,
                    periods = periods,
                    repositories = repositories,
                    excludeMergeCommits = request.excludeMergeCommits
                )
            }

            val summary = EngagementSummary(
                totalContributors = contributorEngagements.size,
                totalCommits = contributorEngagements.sumOf { it.totalCommits },
                totalLinesAdded = 0,
                totalLinesDeleted = 0,
                mostActiveCommitter = contributorEngagements.maxByOrNull { it.totalCommits }?.login,
                mostLinesChangedBy = null,
                totalPRsMerged = 0,
                totalPRsReviewed = 0,
                totalIssuesClosed = 0,
                totalActiveDays = 0
            )

            val result = EngagementAnalysisResponse(
                analyzedRepositories = repositories,
                dateRange = DateRange(startDate, endDate),
                period = request.period,
                contributors = contributorEngagements.sortedByDescending { it.totalCommits },
                periods = periodLabels,
                summary = summary
            )

            logger.info("✅ analyzeCommitsOnly() completed successfully")
            logger.info("📈 Result: ${contributorEngagements.size} contributors, ${summary.totalCommits} total commits")
            return result

        } catch (e: Exception) {
            logger.error("❌ Error in analyzeCommitsOnly(): ${e.message}", e)
            throw e
        }
    }

    /**
     * SLOW: Analyze PRs and reviews only
     * Called separately for progressive loading
     */
    fun analyzePRsOnly(request: EngagementAnalyzeRequest): PRReviewResponse {
        val startDate = request.startDate?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(3)
        val endDate = request.endDate?.let { LocalDate.parse(it) } ?: LocalDate.now()

        val repositories = if (request.repositoryFullNames.isEmpty()) {
            fetchAccessibleRepos(request.token, request.provider, request.baseUrl)
        } else {
            request.repositoryFullNames
        }

        logger.info("SLOW: Analyzing PRs/reviews for ${request.contributorLogins.size} contributors")

        val periods = generatePeriods(startDate, endDate, request.period.name)

        val contributorPRData = request.contributorLogins.map { login ->
            val stats = fetchPRAndReviewStats(request.token, login, repositories, startDate, endDate, periods)
            ContributorPRData(
                login = login,
                prsMerged = stats.totalPRs,
                prsReviewed = stats.totalReviews,
                activeDays = stats.activeDays,
                prsMergedOverTime = periods.map { (label, _, _) ->
                    EngagementDataPoint(label, stats.prsPerPeriod[label] ?: 0)
                },
                prsReviewedOverTime = periods.map { (label, _, _) ->
                    EngagementDataPoint(label, stats.reviewsPerPeriod[label] ?: 0)
                },
                activeDaysOverTime = periods.map { (label, _, _) ->
                    EngagementDataPoint(label, stats.activeDaysPerPeriod[label] ?: 0)
                }
            )
        }

        return PRReviewResponse(contributors = contributorPRData)
    }

    /**
     * Fast commit-only analysis for a single contributor
     */
    private fun analyzeCommitsOnlyForContributor(
        token: String,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        repositories: List<String>,
        excludeMergeCommits: Boolean
    ): ContributorEngagement {
        val stats = fetchCommitAndLineStatsForContributor(token, login, repositories, startDate, endDate, periods, excludeMergeCommits)

        // Get display name from cache
        val displayName = contributorCacheService.getDisplayNameByUsername(login)

        return ContributorEngagement(
            login = login,
            displayName = displayName,
            avatarUrl = null,
            totalCommits = stats.commitsPerPeriod.values.sum(),
            totalLinesAdded = 0,
            totalLinesDeleted = 0,
            prsMerged = 0,
            prsReviewed = 0,
            issuesClosed = 0,
            activeDays = 0,
            commitsOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, stats.commitsPerPeriod[label] ?: 0)
            }
        )
    }

    /**
     * Analyze developer engagement using GitHub GraphQL API
     * Fast single-query approach for comprehensive metrics
     */
    fun analyzeEngagement(request: EngagementAnalyzeRequest): EngagementAnalysisResponse {
        val startDate = request.startDate?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(3)
        val endDate = request.endDate?.let { LocalDate.parse(it) } ?: LocalDate.now()

        val repositories = if (request.repositoryFullNames.isEmpty()) {
            logger.info("No repositories specified, fetching all accessible repos...")
            fetchAccessibleRepos(request.token, request.provider, request.baseUrl)
        } else {
            request.repositoryFullNames
        }

        logger.info("Analyzing engagement for ${request.contributorLogins.size} contributors")

        val periods = generatePeriods(startDate, endDate, request.period.name)
        val periodLabels = periods.map { it.first }

        // Use GraphQL for GitHub, REST for GitLab
        val contributorEngagements = when (request.provider) {
            GitProvider.GITHUB -> {
                request.contributorLogins.mapIndexed { index, login ->
                    logger.info("Processing contributor ${index + 1}/${request.contributorLogins.size}: $login via GraphQL")
                    analyzeContributorViaGraphQL(
                        token = request.token,
                        login = login,
                        startDate = startDate,
                        endDate = endDate,
                        periods = periods,
                        repositories = repositories,
                        excludeMergeCommits = request.excludeMergeCommits
                    )
                }
            }
            GitProvider.GITLAB -> {
                request.contributorLogins.mapIndexed { index, login ->
                    logger.info("Processing contributor ${index + 1}/${request.contributorLogins.size}: $login via REST")
                    analyzeContributorViaREST(
                        token = request.token,
                        provider = request.provider,
                        baseUrl = request.baseUrl,
                        repositories = repositories,
                        login = login,
                        startDate = startDate,
                        endDate = endDate,
                        periods = periods,
                        excludeMergeCommits = request.excludeMergeCommits
                    )
                }
            }
        }

        val summary = EngagementSummary(
            totalContributors = contributorEngagements.size,
            totalCommits = contributorEngagements.sumOf { it.totalCommits },
            totalLinesAdded = contributorEngagements.sumOf { it.totalLinesAdded },
            totalLinesDeleted = contributorEngagements.sumOf { it.totalLinesDeleted },
            mostActiveCommitter = contributorEngagements.maxByOrNull { it.totalCommits }?.login,
            mostLinesChangedBy = contributorEngagements.maxByOrNull { it.totalLinesAdded + it.totalLinesDeleted }?.login,
            // New GraphQL metrics
            totalPRsMerged = contributorEngagements.sumOf { it.prsMerged },
            totalPRsReviewed = contributorEngagements.sumOf { it.prsReviewed },
            totalIssuesClosed = contributorEngagements.sumOf { it.issuesClosed },
            totalActiveDays = contributorEngagements.sumOf { it.activeDays }
        )

        return EngagementAnalysisResponse(
            analyzedRepositories = repositories,
            dateRange = DateRange(startDate, endDate),
            period = request.period,
            contributors = contributorEngagements.sortedByDescending { it.totalCommits },
            periods = periodLabels,
            summary = summary
        )
    }

    /**
     * FAST GraphQL-based contributor analysis
     * Single query gets all contribution data for a user
     */
    private fun analyzeContributorViaGraphQL(
        token: String,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        repositories: List<String>,
        excludeMergeCommits: Boolean
    ): ContributorEngagement {
        val exchangeStrategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()

        val webClient = WebClient.builder()
            .baseUrl(GITHUB_GRAPHQL_URL)
            .exchangeStrategies(exchangeStrategies)
            .defaultHeader("Authorization", "Bearer $token")
            .defaultHeader("Content-Type", "application/json")
            .build()

        val commitsPerPeriod = mutableMapOf<String, Int>()
        val linesAddedPerPeriod = mutableMapOf<String, Int>()
        val linesDeletedPerPeriod = mutableMapOf<String, Int>()

        // Initialize periods
        periods.forEach { (label, _, _) ->
            commitsPerPeriod[label] = 0
            linesAddedPerPeriod[label] = 0
            linesDeletedPerPeriod[label] = 0
        }

        var avatarUrl: String? = null
        var prsMerged = 0
        var prsReviewed = 0
        var issuesClosed = 0
        var activeDays = 0
        var totalCommits = 0
        var totalLinesAdded = 0
        var totalLinesDeleted = 0

        // Maps for per-period tracking
        val prsMergedPerPeriod = mutableMapOf<String, Int>()
        val prsReviewedPerPeriod = mutableMapOf<String, Int>()
        val activeDaysPerPeriod = mutableMapOf<String, Int>()

        // Initialize all period maps
        periods.forEach { (label, _, _) ->
            commitsPerPeriod[label] = 0
            linesAddedPerPeriod[label] = 0
            linesDeletedPerPeriod[label] = 0
            prsMergedPerPeriod[label] = 0
            prsReviewedPerPeriod[label] = 0
            activeDaysPerPeriod[label] = 0
        }

        try {
            // GraphQL query for contribution data with calendar for per-day breakdown
            val query = """
                query(${"$"}username: String!, ${"$"}from: DateTime!, ${"$"}to: DateTime!) {
                  user(login: ${"$"}username) {
                    avatarUrl
                    name
                    contributionsCollection(from: ${"$"}from, to: ${"$"}to) {
                      totalCommitContributions
                      totalPullRequestContributions
                      totalPullRequestReviewContributions
                      totalIssueContributions
                      restrictedContributionsCount
                      contributionCalendar {
                        totalContributions
                        weeks {
                          contributionDays {
                            contributionCount
                            date
                          }
                        }
                      }
                      pullRequestContributionsByRepository(maxRepositories: 100) {
                        repository {
                          nameWithOwner
                        }
                        contributions(first: 100) {
                          nodes {
                            pullRequest {
                              createdAt
                            }
                          }
                        }
                      }
                      pullRequestReviewContributionsByRepository(maxRepositories: 100) {
                        repository {
                          nameWithOwner
                        }
                        contributions(first: 100) {
                          nodes {
                            pullRequestReview {
                              createdAt
                            }
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val variables = mapOf(
                "username" to login,
                "from" to "${startDate}T00:00:00Z",
                "to" to "${endDate}T23:59:59Z"
            )

            val response = webClient.post()
                .bodyValue(mapOf("query" to query, "variables" to variables))
                .retrieve()
                .bodyToMono<Map<String, Any?>>()
                .block()

            val data = response?.get("data") as? Map<*, *>
            val user = data?.get("user") as? Map<*, *>

            if (user != null) {
                avatarUrl = user["avatarUrl"] as? String
                val contributionsCollection = user["contributionsCollection"] as? Map<*, *>

                if (contributionsCollection != null) {
                    // Get totals from GraphQL
                    prsMerged = (contributionsCollection["totalPullRequestContributions"] as? Number)?.toInt() ?: 0
                    prsReviewed = (contributionsCollection["totalPullRequestReviewContributions"] as? Number)?.toInt() ?: 0
                    issuesClosed = (contributionsCollection["totalIssueContributions"] as? Number)?.toInt() ?: 0

                    // Get contribution calendar for active days per period
                    val calendar = contributionsCollection["contributionCalendar"] as? Map<*, *>
                    val weeks = calendar?.get("weeks") as? List<*>

                    weeks?.forEach { week ->
                        val weekMap = week as? Map<*, *>
                        val days = weekMap?.get("contributionDays") as? List<*>

                        days?.forEach { day ->
                            val dayMap = day as? Map<*, *>
                            val count = (dayMap?.get("contributionCount") as? Number)?.toInt() ?: 0
                            val dateStr = dayMap?.get("date") as? String

                            if (count > 0 && dateStr != null) {
                                activeDays++
                                val date = LocalDate.parse(dateStr)
                                val period = findPeriod(date, periods)
                                if (period != null) {
                                    activeDaysPerPeriod[period] = (activeDaysPerPeriod[period] ?: 0) + 1
                                }
                            }
                        }
                    }

                    // Get PRs merged per period
                    val prContribsByRepo = contributionsCollection["pullRequestContributionsByRepository"] as? List<*>
                    prContribsByRepo?.forEach { repoContrib ->
                        val repoContribMap = repoContrib as? Map<*, *>
                        val contributions = repoContribMap?.get("contributions") as? Map<*, *>
                        val nodes = contributions?.get("nodes") as? List<*>
                        nodes?.forEach { node ->
                            val nodeMap = node as? Map<*, *>
                            val pr = nodeMap?.get("pullRequest") as? Map<*, *>
                            val createdAt = pr?.get("createdAt") as? String
                            if (createdAt != null) {
                                try {
                                    val date = LocalDate.parse(createdAt.substring(0, 10))
                                    val period = findPeriod(date, periods)
                                    if (period != null) {
                                        prsMergedPerPeriod[period] = (prsMergedPerPeriod[period] ?: 0) + 1
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }

                    // Get PRs reviewed per period
                    val reviewContribsByRepo = contributionsCollection["pullRequestReviewContributionsByRepository"] as? List<*>
                    reviewContribsByRepo?.forEach { repoContrib ->
                        val repoContribMap = repoContrib as? Map<*, *>
                        val contributions = repoContribMap?.get("contributions") as? Map<*, *>
                        val nodes = contributions?.get("nodes") as? List<*>
                        nodes?.forEach { node ->
                            val nodeMap = node as? Map<*, *>
                            val review = nodeMap?.get("pullRequestReview") as? Map<*, *>
                            val createdAt = review?.get("createdAt") as? String
                            if (createdAt != null) {
                                try {
                                    val date = LocalDate.parse(createdAt.substring(0, 10))
                                    val period = findPeriod(date, periods)
                                    if (period != null) {
                                        prsReviewedPerPeriod[period] = (prsReviewedPerPeriod[period] ?: 0) + 1
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }

                    logger.info("$login: GraphQL prsMerged=$prsMerged, prsReviewed=$prsReviewed, activeDays=$activeDays")
                }
            }

            // Now fetch commit counts using REST API (more accurate for selected repos)
            val stats = fetchCommitAndLineStatsForContributor(token, login, repositories, startDate, endDate, periods, excludeMergeCommits)

            // Use REST API commit count (accurate for selected repos)
            val actualCommitCount = stats.commitsPerPeriod.values.sum()
            totalCommits = actualCommitCount

            totalLinesAdded = stats.totalLinesAdded
            totalLinesDeleted = stats.totalLinesDeleted
            stats.linesAddedPerPeriod.forEach { (period, added) ->
                linesAddedPerPeriod[period] = added
            }
            stats.linesDeletedPerPeriod.forEach { (period, deleted) ->
                linesDeletedPerPeriod[period] = deleted
            }
            stats.commitsPerPeriod.forEach { (period, count) ->
                commitsPerPeriod[period] = count
            }

            // Fetch PRs and Reviews using REST API (more accurate for date ranges > 1 year)
            val prStats = fetchPRAndReviewStats(token, login, repositories, startDate, endDate, periods)
            prsMerged = prStats.totalPRs
            prsReviewed = prStats.totalReviews
            activeDays = prStats.activeDays
            prStats.prsPerPeriod.forEach { (period, count) ->
                prsMergedPerPeriod[period] = count
            }
            prStats.reviewsPerPeriod.forEach { (period, count) ->
                prsReviewedPerPeriod[period] = count
            }
            prStats.activeDaysPerPeriod.forEach { (period, count) ->
                activeDaysPerPeriod[period] = count
            }

            logger.info("$login: REST API found $actualCommitCount commits, $prsMerged PRs, $prsReviewed reviews, $activeDays active days")

        } catch (e: Exception) {
            logger.error("GraphQL error for $login: ${e.message}")
            // Fallback to REST if GraphQL fails
            return analyzeContributorViaREST(token, GitProvider.GITHUB, null, repositories, login, startDate, endDate, periods, excludeMergeCommits)
        }

        // Create Over Time data using actual per-period data from GraphQL
        val prsMergedOverTime = periods.map { (label, _, _) ->
            EngagementDataPoint(label, prsMergedPerPeriod[label] ?: 0)
        }
        val prsReviewedOverTime = periods.map { (label, _, _) ->
            EngagementDataPoint(label, prsReviewedPerPeriod[label] ?: 0)
        }
        val activeDaysOverTime = periods.map { (label, _, _) ->
            EngagementDataPoint(label, activeDaysPerPeriod[label] ?: 0)
        }

        // Get display name from cache
        val displayName = contributorCacheService.getDisplayNameByUsername(login)

        val result = ContributorEngagement(
            login = login,
            displayName = displayName,
            avatarUrl = avatarUrl,
            totalCommits = totalCommits,
            totalLinesAdded = totalLinesAdded,
            totalLinesDeleted = totalLinesDeleted,
            prsMerged = prsMerged,
            prsReviewed = prsReviewed,
            issuesClosed = issuesClosed,
            activeDays = activeDays,
            commitsOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, commitsPerPeriod[label] ?: 0)
            },
            prsMergedOverTime = prsMergedOverTime,
            prsReviewedOverTime = prsReviewedOverTime,
            activeDaysOverTime = activeDaysOverTime,
            linesAddedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, linesAddedPerPeriod[label] ?: 0)
            },
            linesDeletedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, linesDeletedPerPeriod[label] ?: 0)
            }
        )

        logger.info("Completed GraphQL analysis for $login: ${result.totalCommits} commits, ${result.prsMerged} PRs, ${result.prsReviewed} reviews")
        return result
    }

    /**
     * Fetch commit and line statistics using REST API
     * Fetches from default branch (master/main) only to match GitHub's contribution view
     * "Contributions per week to master, excluding merge commits"
     * Uses pagination to get ALL commits (up to 1000 per repo)
     */
    private fun fetchCommitAndLineStatsForContributor(
        token: String,
        login: String,
        repositories: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        excludeMergeCommits: Boolean
    ): CommitLineStats {
        val webClient = WebClient.builder()
            .baseUrl(GITHUB_API_URL)
            .defaultHeader("Authorization", "Bearer $token")
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()

        val totalAdded = 0
        val totalDeleted = 0
        val addedPerPeriod = mutableMapOf<String, Int>()
        val deletedPerPeriod = mutableMapOf<String, Int>()
        val commitsPerPeriod = mutableMapOf<String, Int>()

        periods.forEach { (label, _, _) ->
            addedPerPeriod[label] = 0
            deletedPerPeriod[label] = 0
            commitsPerPeriod[label] = 0
        }

        val reposToCheck = repositories.take(200) // Check up to 200 repos
        val processedShas = mutableSetOf<String>()
        var totalCommitCount = 0

        try {
            // For each repo, fetch ALL pages of commits (with pagination)
            for (repoFullName in reposToCheck) {
                var page = 1
                var hasMore = true

                while (hasMore && page <= 15) { // Max 15 pages = 1500 commits per repo
                    try {
                        val commits = webClient.get()
                            .uri("/repos/$repoFullName/commits?author=$login&since=${startDate}T00:00:00Z&until=${endDate}T23:59:59Z&per_page=100&page=$page")
                            .retrieve()
                            .bodyToMono<List<Map<String, Any?>>>()
                            .block() ?: emptyList()

                        if (commits.isEmpty()) {
                            hasMore = false
                        } else {
                            for (commit in commits) {
                                val sha = commit["sha"] as? String ?: continue

                                // Skip if already processed (avoid duplicates)
                                if (processedShas.contains(sha)) continue
                                processedShas.add(sha)

                                // Skip merge commits if requested (GitHub excludes these by default)
                                if (excludeMergeCommits) {
                                    @Suppress("UNCHECKED_CAST")
                                    val parents = commit["parents"] as? List<Map<String, Any?>>
                                    if (parents != null && parents.size > 1) continue
                                }

                                val commitData = commit["commit"] as? Map<*, *>
                                val author = commitData?.get("author") as? Map<*, *>
                                val dateStr = author?.get("date") as? String ?: continue

                                val commitDate = try {
                                    LocalDate.parse(dateStr.substring(0, 10))
                                } catch (e: Exception) { continue }

                                totalCommitCount++

                                // Count commits per period
                                val period = findPeriod(commitDate, periods)
                                if (period != null) {
                                    commitsPerPeriod[period] = (commitsPerPeriod[period] ?: 0) + 1
                                }
                            }

                            // Check if there are more pages
                            if (commits.size < 100) {
                                hasMore = false
                            } else {
                                page++
                            }
                        }
                    } catch (e: Exception) {
                        hasMore = false
                    }
                }
            }

            logger.info("Found $totalCommitCount commits for $login across ${reposToCheck.size} repos (with pagination)")

        } catch (e: Exception) {
            logger.warn("Error fetching commits for $login: ${e.message}")
        }

        return CommitLineStats(
            totalLinesAdded = totalAdded,
            totalLinesDeleted = totalDeleted,
            linesAddedPerPeriod = addedPerPeriod,
            linesDeletedPerPeriod = deletedPerPeriod,
            commitsPerPeriod = commitsPerPeriod
        )
    }

    // Data class to hold commit and line stats
    data class CommitLineStats(
        val totalLinesAdded: Int,
        val totalLinesDeleted: Int,
        val linesAddedPerPeriod: Map<String, Int>,
        val linesDeletedPerPeriod: Map<String, Int>,
        val commitsPerPeriod: Map<String, Int>
    )

    // Data class to hold PR and review stats
    data class PRReviewStats(
        val totalPRs: Int,
        val totalReviews: Int,
        val activeDays: Int,
        val prsPerPeriod: Map<String, Int>,
        val reviewsPerPeriod: Map<String, Int>,
        val activeDaysPerPeriod: Map<String, Int>
    )

    /**
     * FAST: Fetch PR and Review statistics using GitHub Search API
     * Search API is much faster than iterating through each repo's PRs
     */
    private fun fetchPRAndReviewStats(
        token: String,
        login: String,
        repositories: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>
    ): PRReviewStats {
        val webClient = WebClient.builder()
            .baseUrl(GITHUB_API_URL)
            .defaultHeader("Authorization", "Bearer $token")
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()

        val prsPerPeriod = mutableMapOf<String, Int>()
        val reviewsPerPeriod = mutableMapOf<String, Int>()
        val activeDaysPerPeriod = mutableMapOf<String, Int>()
        val activeDatesSet = mutableSetOf<LocalDate>()

        periods.forEach { (label, _, _) ->
            prsPerPeriod[label] = 0
            reviewsPerPeriod[label] = 0
            activeDaysPerPeriod[label] = 0
        }

        var totalPRs = 0
        var totalReviews = 0

        try {
            // Determine search strategy based on number of repos
            val useOrgSearch = repositories.size > 5
            val orgName = repositories.firstOrNull()?.split("/")?.firstOrNull() ?: ""

            if (orgName.isEmpty()) {
                return PRReviewStats(0, 0, 0, prsPerPeriod, reviewsPerPeriod, activeDaysPerPeriod)
            }

            // Get list of repos to search
            val reposToSearch = if (useOrgSearch) {
                listOf("org:$orgName")  // Search entire org
            } else {
                repositories.map { "repo:$it" }  // Search each repo individually
            }

            // FAST: Use Search API to find PRs created by user
            for (repoQuery in reposToSearch) {
                var page = 1
                var hasMore = true
                while (hasMore && page <= 10) {
                    try {
                        val searchQuery = "$repoQuery+type:pr+author:$login+created:$startDate..$endDate"
                        val response = webClient.get()
                            .uri("/search/issues?q=$searchQuery&per_page=100&page=$page&sort=created&order=desc")
                            .retrieve()
                            .bodyToMono<Map<String, Any?>>()
                            .block()

                        @Suppress("UNCHECKED_CAST")
                        val items = response?.get("items") as? List<Map<String, Any?>> ?: emptyList()
                        val totalCount = (response?.get("total_count") as? Number)?.toInt() ?: 0

                        if (page == 1 && reposToSearch.size == 1) {
                            logger.info("Search found $totalCount PRs created by $login")
                        }

                        if (items.isEmpty()) {
                            hasMore = false
                        } else {
                            for (item in items) {
                                val createdAt = item["created_at"] as? String ?: continue
                                try {
                                    val prDate = LocalDate.parse(createdAt.substring(0, 10))
                                    totalPRs++
                                    activeDatesSet.add(prDate)
                                    val period = findPeriod(prDate, periods)
                                    if (period != null) {
                                        prsPerPeriod[period] = (prsPerPeriod[period] ?: 0) + 1
                                    }
                                } catch (e: Exception) { }
                            }

                            if (items.size < 100 || (page * 100) >= totalCount) {
                                hasMore = false
                            } else {
                                page++
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Search PRs error for $repoQuery: ${e.message}")
                        hasMore = false
                    }
                }
            }

            // FAST: Use Search API to find PRs reviewed by user
            for (repoQuery in reposToSearch) {
                var page = 1
                var hasMore = true
                while (hasMore && page <= 10) {
                    try {
                        val searchQuery = "$repoQuery+type:pr+reviewed-by:$login+created:$startDate..$endDate"
                        val response = webClient.get()
                            .uri("/search/issues?q=$searchQuery&per_page=100&page=$page&sort=created&order=desc")
                            .retrieve()
                            .bodyToMono<Map<String, Any?>>()
                            .block()

                        @Suppress("UNCHECKED_CAST")
                        val items = response?.get("items") as? List<Map<String, Any?>> ?: emptyList()
                        val totalCount = (response?.get("total_count") as? Number)?.toInt() ?: 0

                        if (page == 1 && reposToSearch.size == 1) {
                            logger.info("Search found $totalCount PRs reviewed by $login")
                        }

                        if (items.isEmpty()) {
                            hasMore = false
                        } else {
                            for (item in items) {
                                val createdAt = item["created_at"] as? String ?: continue
                                try {
                                    val reviewDate = LocalDate.parse(createdAt.substring(0, 10))
                                    totalReviews++
                                    activeDatesSet.add(reviewDate)
                                    val period = findPeriod(reviewDate, periods)
                                    if (period != null) {
                                        reviewsPerPeriod[period] = (reviewsPerPeriod[period] ?: 0) + 1
                                    }
                                } catch (e: Exception) { }
                            }

                            if (items.size < 100 || (page * 100) >= totalCount) {
                                hasMore = false
                            } else {
                                page++
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Search reviews error for $repoQuery: ${e.message}")
                        hasMore = false
                    }
                }
            }

            // Calculate active days per period
            for (date in activeDatesSet) {
                val period = findPeriod(date, periods)
                if (period != null) {
                    activeDaysPerPeriod[period] = (activeDaysPerPeriod[period] ?: 0) + 1
                }
            }

            logger.info("FAST PR/Review stats for $login: $totalPRs PRs, $totalReviews reviews, ${activeDatesSet.size} active days")

        } catch (e: Exception) {
            logger.warn("Error fetching PR/review stats for $login: ${e.message}")
        }

        return PRReviewStats(
            totalPRs = totalPRs,
            totalReviews = totalReviews,
            activeDays = activeDatesSet.size,
            prsPerPeriod = prsPerPeriod,
            reviewsPerPeriod = reviewsPerPeriod,
            activeDaysPerPeriod = activeDaysPerPeriod
        )
    }

    /**
     * Fallback REST-based contributor analysis
     */
    private fun analyzeContributorViaREST(
        token: String,
        provider: GitProvider,
        baseUrl: String?,
        repositories: List<String>,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        excludeMergeCommits: Boolean
    ): ContributorEngagement {
        val apiUrl = when (provider) {
            GitProvider.GITHUB -> baseUrl ?: GITHUB_API_URL
            GitProvider.GITLAB -> baseUrl ?: GITLAB_API_URL
        }
        val webClient = createWebClient(apiUrl, token, provider)

        val commitsPerPeriod = mutableMapOf<String, Int>()
        val linesAddedPerPeriod = mutableMapOf<String, Int>()
        val linesDeletedPerPeriod = mutableMapOf<String, Int>()
        var avatarUrl: String? = null

        periods.forEach { (label, _, _) ->
            commitsPerPeriod[label] = 0
            linesAddedPerPeriod[label] = 0
            linesDeletedPerPeriod[label] = 0
        }

        when (provider) {
            GitProvider.GITHUB -> {
                // Use parallel fetching for GitHub REST
                val stats = fetchCommitAndLineStatsForContributor(token, login, repositories, startDate, endDate, periods, excludeMergeCommits)
                stats.linesAddedPerPeriod.forEach { (period, added) ->
                    linesAddedPerPeriod[period] = added
                }
                stats.linesDeletedPerPeriod.forEach { (period, deleted) ->
                    linesDeletedPerPeriod[period] = deleted
                }
                stats.commitsPerPeriod.forEach { (period, count) ->
                    commitsPerPeriod[period] = count
                }

                avatarUrl = fetchGitHubUserAvatar(webClient, login)
            }
            GitProvider.GITLAB -> {
                for (repoFullName in repositories.take(20)) {
                    fetchGitLabCommitsAndLines(
                        webClient, repoFullName, login, startDate, endDate, periods,
                        commitsPerPeriod, linesAddedPerPeriod, linesDeletedPerPeriod
                    )
                }
            }
        }

        // Get display name from cache
        val displayName = contributorCacheService.getDisplayNameByUsername(login)

        return ContributorEngagement(
            login = login,
            displayName = displayName,
            avatarUrl = avatarUrl,
            totalCommits = commitsPerPeriod.values.sum(),
            totalLinesAdded = linesAddedPerPeriod.values.sum(),
            totalLinesDeleted = linesDeletedPerPeriod.values.sum(),
            prsMerged = 0,
            prsReviewed = 0,
            issuesClosed = 0,
            activeDays = 0,
            commitsOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, commitsPerPeriod[label] ?: 0)
            },
            prsMergedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, 0)
            },
            prsReviewedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, 0)
            },
            activeDaysOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, 0)
            },
            linesAddedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, linesAddedPerPeriod[label] ?: 0)
            },
            linesDeletedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, linesDeletedPerPeriod[label] ?: 0)
            }
        )
    }

    private fun fetchGitLabCommitsAndLines(
        webClient: WebClient,
        repoFullName: String,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        commitsPerPeriod: MutableMap<String, Int>,
        linesAddedPerPeriod: MutableMap<String, Int>,
        linesDeletedPerPeriod: MutableMap<String, Int>
    ) {
        val encodedPath = repoFullName.replace("/", "%2F")
        try {
            val response = webClient.get()
                .uri("/projects/$encodedPath/repository/commits?since=${startDate}T00:00:00Z&until=${endDate}T23:59:59Z&per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            response?.forEach { commit ->
                val authorName = commit["author_name"] as? String
                val authorEmail = commit["author_email"] as? String
                val dateStr = commit["created_at"] as? String
                val sha = commit["id"] as? String

                if ((authorName?.lowercase() == login.lowercase() ||
                            authorEmail?.lowercase()?.contains(login.lowercase()) == true) &&
                    dateStr != null) {
                    val commitDate = LocalDate.parse(dateStr.substring(0, 10))
                    val period = findPeriod(commitDate, periods)
                    if (period != null && sha != null) {
                        commitsPerPeriod[period] = (commitsPerPeriod[period] ?: 0) + 1

                        try {
                            val detail = webClient.get()
                                .uri("/projects/$encodedPath/repository/commits/$sha")
                                .retrieve()
                                .bodyToMono<Map<String, Any?>>()
                                .block()

                            val stats = detail?.get("stats") as? Map<*, *>
                            val additions = (stats?.get("additions") as? Number)?.toInt() ?: 0
                            val deletions = (stats?.get("deletions") as? Number)?.toInt() ?: 0

                            linesAddedPerPeriod[period] = (linesAddedPerPeriod[period] ?: 0) + additions
                            linesDeletedPerPeriod[period] = (linesDeletedPerPeriod[period] ?: 0) + deletions
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Error fetching GitLab commits for $login in $repoFullName: ${e.message}")
        }
    }

    private fun fetchGitHubUserAvatar(webClient: WebClient, login: String): String? {
        return try {
            val user = webClient.get()
                .uri("/users/$login")
                .retrieve()
                .bodyToMono<Map<String, Any?>>()
                .block()
            user?.get("avatar_url") as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchAccessibleRepos(token: String, provider: GitProvider, baseUrl: String?): List<String> {
        val apiUrl = when (provider) {
            GitProvider.GITHUB -> baseUrl ?: GITHUB_API_URL
            GitProvider.GITLAB -> baseUrl ?: GITLAB_API_URL
        }
        val webClient = createWebClient(apiUrl, token, provider)
        val repos = mutableListOf<String>()

        try {
            when (provider) {
                GitProvider.GITHUB -> {
                    var page = 1
                    var hasMore = true
                    while (hasMore && page <= 10) {
                        val response = webClient.get()
                            .uri("/user/repos?per_page=100&page=$page&type=all")
                            .retrieve()
                            .bodyToMono<List<Map<String, Any?>>>()
                            .block()

                        if (response.isNullOrEmpty()) {
                            hasMore = false
                        } else {
                            response.forEach { repo ->
                                val fullName = repo["full_name"] as? String
                                if (fullName != null) repos.add(fullName)
                            }
                            page++
                            if (response.size < 100) hasMore = false
                        }
                    }
                }
                GitProvider.GITLAB -> {
                    val response = webClient.get()
                        .uri("/projects?membership=true&per_page=100")
                        .retrieve()
                        .bodyToMono<List<Map<String, Any?>>>()
                        .block()

                    response?.forEach { project ->
                        val fullPath = project["path_with_namespace"] as? String
                        if (fullPath != null) repos.add(fullPath)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error fetching accessible repos: ${e.message}")
        }

        logger.info("Found ${repos.size} accessible repositories")
        return repos
    }

    private fun generatePeriods(startDate: LocalDate, endDate: LocalDate, period: String): List<Triple<String, LocalDate, LocalDate>> {
        val periods = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        var current = startDate

        when (period) {
            "DAILY" -> {
                while (!current.isAfter(endDate)) {
                    val label = current.toString()
                    periods.add(Triple(label, current, current))
                    current = current.plusDays(1)
                }
            }
            "WEEKLY" -> {
                val weekFields = WeekFields.of(Locale.getDefault())
                current = current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                while (!current.isAfter(endDate)) {
                    val weekEnd = current.plusDays(6).let { if (it.isAfter(endDate)) endDate else it }
                    val weekNum = current.get(weekFields.weekOfWeekBasedYear())
                    val label = "W${weekNum} ${current.month.toString().take(3)}"
                    periods.add(Triple(label, current, weekEnd))
                    current = current.plusWeeks(1)
                }
            }
            "MONTHLY" -> {
                current = current.withDayOfMonth(1)
                while (!current.isAfter(endDate)) {
                    val monthEnd = current.plusMonths(1).minusDays(1).let { if (it.isAfter(endDate)) endDate else it }
                    val label = "${current.month.toString().take(3)} ${current.year}"
                    periods.add(Triple(label, current, monthEnd))
                    current = current.plusMonths(1)
                }
            }
        }

        return periods
    }

    private fun findPeriod(date: LocalDate, periods: List<Triple<String, LocalDate, LocalDate>>): String? {
        return periods.find { (_, start, end) ->
            !date.isBefore(start) && !date.isAfter(end)
        }?.first
    }

    private fun createWebClient(baseUrl: String, token: String, provider: GitProvider): WebClient {
        val exchangeStrategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()

        val builder = WebClient.builder()
            .baseUrl(baseUrl)
            .exchangeStrategies(exchangeStrategies)

        when (provider) {
            GitProvider.GITHUB -> builder.defaultHeader("Authorization", "Bearer $token")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
            GitProvider.GITLAB -> builder.defaultHeader("PRIVATE-TOKEN", token)
        }

        return builder.build()
    }
}

