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
class EngagementService {
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

        try {
            // GraphQL query for contribution data
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
                      commitContributionsByRepository(maxRepositories: 100) {
                        repository {
                          nameWithOwner
                        }
                        contributions {
                          totalCount
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
                    totalCommits = (contributionsCollection["totalCommitContributions"] as? Number)?.toInt() ?: 0
                    prsMerged = (contributionsCollection["totalPullRequestContributions"] as? Number)?.toInt() ?: 0
                    prsReviewed = (contributionsCollection["totalPullRequestReviewContributions"] as? Number)?.toInt() ?: 0
                    issuesClosed = (contributionsCollection["totalIssueContributions"] as? Number)?.toInt() ?: 0

                    // Get contribution calendar for active days and period breakdown
                    val calendar = contributionsCollection["contributionCalendar"] as? Map<*, *>
                    val weeks = calendar?.get("weeks") as? List<*>

                    weeks?.forEach { week ->
                        val weekMap = week as? Map<*, *>
                        val days = weekMap?.get("contributionDays") as? List<*>

                        days?.forEach { day ->
                            val dayMap = day as? Map<*, *>
                            val count = (dayMap?.get("contributionCount") as? Number)?.toInt() ?: 0
                            val dateStr = dayMap?.get("date") as? String

                            if (count > 0) {
                                activeDays++
                            }

                            if (dateStr != null && count > 0) {
                                val date = LocalDate.parse(dateStr)
                                val period = findPeriod(date, periods)
                                if (period != null) {
                                    commitsPerPeriod[period] = (commitsPerPeriod[period] ?: 0) + count
                                }
                            }
                        }
                    }

                    // Get commits by repository for filtering
                    val commitsByRepo = contributionsCollection["commitContributionsByRepository"] as? List<*>
                    logger.info("$login has contributions in ${commitsByRepo?.size ?: 0} repositories")
                }
            }

            // Now fetch line changes using REST API (GraphQL doesn't provide this)
            // Only fetch if we have commits
            if (totalCommits > 0) {
                val lineStats = fetchLineStatsForContributor(token, login, repositories, startDate, endDate, periods, excludeMergeCommits)
                totalLinesAdded = lineStats.first
                totalLinesDeleted = lineStats.second
                lineStats.third.forEach { (period, added) ->
                    linesAddedPerPeriod[period] = added
                }
                lineStats.fourth.forEach { (period, deleted) ->
                    linesDeletedPerPeriod[period] = deleted
                }
            }

        } catch (e: Exception) {
            logger.error("GraphQL error for $login: ${e.message}")
            // Fallback to REST if GraphQL fails
            return analyzeContributorViaREST(token, GitProvider.GITHUB, null, repositories, login, startDate, endDate, periods, excludeMergeCommits)
        }

        val result = ContributorEngagement(
            login = login,
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
            linesAddedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, linesAddedPerPeriod[label] ?: 0)
            },
            linesDeletedOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, linesDeletedPerPeriod[label] ?: 0)
            }
        )

        logger.info("Completed GraphQL analysis for $login: ${result.totalCommits} commits, ${result.prsMerged} PRs, ${result.prsReviewed} reviews, +${result.totalLinesAdded}/-${result.totalLinesDeleted} lines")
        return result
    }

    /**
     * Fetch line statistics using REST API (GraphQL doesn't provide line counts)
     * Optimized with parallel requests
     */
    private fun fetchLineStatsForContributor(
        token: String,
        login: String,
        repositories: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        excludeMergeCommits: Boolean
    ): Quadruple<Int, Int, Map<String, Int>, Map<String, Int>> {
        val webClient = createWebClient(GITHUB_API_URL, token, GitProvider.GITHUB)

        var totalAdded = 0
        var totalDeleted = 0
        val addedPerPeriod = mutableMapOf<String, Int>()
        val deletedPerPeriod = mutableMapOf<String, Int>()

        periods.forEach { (label, _, _) ->
            addedPerPeriod[label] = 0
            deletedPerPeriod[label] = 0
        }

        // Limit repos for line stats to avoid rate limiting
        val reposToCheck = repositories.take(30)

        try {
            // Fetch commits from all repos in parallel
            val allCommits = mutableListOf<Triple<String, String, LocalDate>>() // sha, repoName, date

            val commitLists = reactor.core.publisher.Flux.fromIterable(reposToCheck)
                .flatMap({ repoFullName ->
                    webClient.get()
                        .uri("/repos/$repoFullName/commits?author=$login&since=${startDate}T00:00:00Z&until=${endDate}T23:59:59Z&per_page=50")
                        .retrieve()
                        .bodyToMono<List<Map<String, Any?>>>()
                        .map { commits -> Pair(repoFullName, commits) }
                        .onErrorReturn(Pair(repoFullName, emptyList()))
                }, 15)
                .collectList()
                .block() ?: emptyList()

            for ((repoFullName, commits) in commitLists) {
                for (commit in commits) {
                    val sha = commit["sha"] as? String ?: continue

                    // Skip merge commits if requested
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

                    allCommits.add(Triple(sha, repoFullName, commitDate))
                }
            }

            // Limit commits for stats to avoid too many requests
            val commitsToFetch = allCommits.take(100)

            // Fetch commit stats in parallel
            val commitStats = reactor.core.publisher.Flux.fromIterable(commitsToFetch)
                .flatMap({ (sha, repoFullName, commitDate) ->
                    webClient.get()
                        .uri("/repos/$repoFullName/commits/$sha")
                        .retrieve()
                        .bodyToMono<Map<String, Any?>>()
                        .map { detail ->
                            val stats = detail["stats"] as? Map<*, *>
                            val additions = (stats?.get("additions") as? Number)?.toInt() ?: 0
                            val deletions = (stats?.get("deletions") as? Number)?.toInt() ?: 0
                            Triple(commitDate, additions, deletions)
                        }
                        .onErrorReturn(Triple(commitDate, 0, 0))
                }, 20)
                .collectList()
                .block() ?: emptyList()

            for ((commitDate, additions, deletions) in commitStats) {
                totalAdded += additions
                totalDeleted += deletions

                val period = findPeriod(commitDate, periods)
                if (period != null) {
                    addedPerPeriod[period] = (addedPerPeriod[period] ?: 0) + additions
                    deletedPerPeriod[period] = (deletedPerPeriod[period] ?: 0) + deletions
                }
            }

            logger.info("Line stats for $login: +$totalAdded/-$totalDeleted from ${commitsToFetch.size} commits")

        } catch (e: Exception) {
            logger.warn("Error fetching line stats for $login: ${e.message}")
        }

        return Quadruple(totalAdded, totalDeleted, addedPerPeriod, deletedPerPeriod)
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
                val lineStats = fetchLineStatsForContributor(token, login, repositories, startDate, endDate, periods, excludeMergeCommits)
                lineStats.third.forEach { (period, added) ->
                    linesAddedPerPeriod[period] = added
                    commitsPerPeriod[period] = (commitsPerPeriod[period] ?: 0) + 1
                }
                lineStats.fourth.forEach { (period, deleted) ->
                    linesDeletedPerPeriod[period] = deleted
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

        return ContributorEngagement(
            login = login,
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

// Helper class for returning 4 values
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

