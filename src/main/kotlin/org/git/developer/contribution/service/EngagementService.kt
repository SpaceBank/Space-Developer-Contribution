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
 * Simplified version: focuses on commits, lines added, lines deleted
 * Uses fast parallel fetching for better performance
 */
@Service
class EngagementService {
    private val logger = LoggerFactory.getLogger(EngagementService::class.java)

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com"
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

            if (allMembers.isEmpty()) {
                logger.info("No org members found, fetching from user's repositories...")
                val repos = webClient.get()
                    .uri("/user/repos?per_page=50&type=all")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                repos?.take(10)?.forEach { repo ->
                    val repoFullName = repo["full_name"] as? String ?: return@forEach
                    try {
                        val contributors = webClient.get()
                            .uri("/repos/$repoFullName/contributors?per_page=100")
                            .retrieve()
                            .bodyToMono<List<Map<String, Any?>>>()
                            .block()

                        contributors?.forEach { c ->
                            val login = c["login"] as? String ?: return@forEach
                            val existing = allMembers[login.lowercase()]
                            val contributions = (c["contributions"] as? Number)?.toInt() ?: 0

                            if (existing != null) {
                                allMembers[login.lowercase()] = existing.copy(
                                    contributions = existing.contributions + contributions
                                )
                            } else {
                                allMembers[login.lowercase()] = ContributorInfo(
                                    login = login,
                                    avatarUrl = c["avatar_url"] as? String,
                                    contributions = contributions,
                                    profileUrl = c["html_url"] as? String,
                                    name = null
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore individual repo errors
                    }
                }
            }

            // Fetch user details for each member
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
     * Analyze developer engagement - FAST version
     * Focuses on commits, lines added, lines deleted
     * Uses parallel fetching for speed
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

        logger.info("Analyzing engagement for ${request.contributorLogins.size} contributors in ${repositories.size} repositories")

        val periods = generatePeriods(startDate, endDate, request.period)
        val periodLabels = periods.map { it.first }

        // Process all contributors in parallel for speed
        val contributorEngagements = request.contributorLogins.mapIndexed { index, login ->
            logger.info("Processing contributor ${index + 1}/${request.contributorLogins.size}: $login")
            analyzeContributorFast(
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

        val summary = EngagementSummary(
            totalContributors = contributorEngagements.size,
            totalCommits = contributorEngagements.sumOf { it.totalCommits },
            totalLinesAdded = contributorEngagements.sumOf { it.totalLinesAdded },
            totalLinesDeleted = contributorEngagements.sumOf { it.totalLinesDeleted },
            mostActiveCommitter = contributorEngagements.maxByOrNull { it.totalCommits }?.login,
            mostLinesChangedBy = contributorEngagements.maxByOrNull { it.totalLinesAdded + it.totalLinesDeleted }?.login
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
     * FAST contributor analysis using parallel fetching
     */
    private fun analyzeContributorFast(
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

        // Initialize periods
        periods.forEach { (label, _, _) ->
            commitsPerPeriod[label] = 0
            linesAddedPerPeriod[label] = 0
            linesDeletedPerPeriod[label] = 0
        }

        when (provider) {
            GitProvider.GITHUB -> {
                logger.info("Fetching data for $login from ${repositories.size} repos using parallel requests...")

                // Use fast parallel fetching
                fetchCommitsAndLinesParallel(
                    webClient = webClient,
                    login = login,
                    startDate = startDate,
                    endDate = endDate,
                    periods = periods,
                    commitsPerPeriod = commitsPerPeriod,
                    linesAddedPerPeriod = linesAddedPerPeriod,
                    linesDeletedPerPeriod = linesDeletedPerPeriod,
                    repositories = repositories,
                    excludeMergeCommits = excludeMergeCommits
                )

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

        val result = ContributorEngagement(
            login = login,
            avatarUrl = avatarUrl,
            totalCommits = commitsPerPeriod.values.sum(),
            totalLinesAdded = linesAddedPerPeriod.values.sum(),
            totalLinesDeleted = linesDeletedPerPeriod.values.sum(),
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

        logger.info("Completed analysis for $login: ${result.totalCommits} commits, +${result.totalLinesAdded}/-${result.totalLinesDeleted} lines")
        return result
    }

    /**
     * FAST parallel fetching of commits and lines from repositories
     */
    private fun fetchCommitsAndLinesParallel(
        webClient: WebClient,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        commitsPerPeriod: MutableMap<String, Int>,
        linesAddedPerPeriod: MutableMap<String, Int>,
        linesDeletedPerPeriod: MutableMap<String, Int>,
        repositories: List<String>,
        excludeMergeCommits: Boolean
    ) {
        data class CommitInfo(val sha: String, val period: String, val repoFullName: String)
        val allCommits = mutableListOf<CommitInfo>()
        var skippedMergeCommits = 0

        logger.info("Step 1: Fetching commit lists from ${repositories.size} repos in parallel...")

        // Step 1: Fetch all commit lists in parallel
        val commitLists = reactor.core.publisher.Flux.fromIterable(repositories)
            .flatMap({ repoFullName ->
                webClient.get()
                    .uri("/repos/$repoFullName/commits?author=$login&since=${startDate}T00:00:00Z&until=${endDate}T23:59:59Z&per_page=100")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .map { commits -> Pair(repoFullName, commits) }
                    .onErrorReturn(Pair(repoFullName, emptyList()))
            }, 20) // 20 concurrent requests
            .collectList()
            .block() ?: emptyList()

        // Process commit lists
        for ((repoFullName, commits) in commitLists) {
            for (commit in commits) {
                val sha = commit["sha"] as? String ?: continue

                // Skip merge commits if requested
                if (excludeMergeCommits) {
                    @Suppress("UNCHECKED_CAST")
                    val parents = commit["parents"] as? List<Map<String, Any?>>
                    if (parents != null && parents.size > 1) {
                        skippedMergeCommits++
                        continue
                    }
                }

                // Get commit date
                val commitData = commit["commit"] as? Map<*, *>
                val author = commitData?.get("author") as? Map<*, *>
                val dateStr = author?.get("date") as? String ?: continue

                val commitDate = try {
                    LocalDate.parse(dateStr.substring(0, 10))
                } catch (e: Exception) { continue }

                val period = findPeriod(commitDate, periods) ?: continue
                allCommits.add(CommitInfo(sha, period, repoFullName))
            }
        }

        logger.info("Step 2: Found ${allCommits.size} commits (skipped $skippedMergeCommits merge commits), fetching stats in parallel...")

        // Step 2: Fetch commit details (for line stats) in parallel
        val commitDetails = reactor.core.publisher.Flux.fromIterable(allCommits)
            .flatMap({ commitInfo ->
                webClient.get()
                    .uri("/repos/${commitInfo.repoFullName}/commits/${commitInfo.sha}")
                    .retrieve()
                    .bodyToMono<Map<String, Any?>>()
                    .map { detail ->
                        val stats = detail["stats"] as? Map<*, *>
                        val additions = (stats?.get("additions") as? Number)?.toInt() ?: 0
                        val deletions = (stats?.get("deletions") as? Number)?.toInt() ?: 0
                        Triple(commitInfo.period, additions, deletions)
                    }
                    .onErrorReturn(Triple(commitInfo.period, 0, 0))
            }, 25) // 25 concurrent requests for commit details
            .collectList()
            .block() ?: emptyList()

        // Aggregate results
        var totalAdded = 0
        var totalDeleted = 0

        for ((period, additions, deletions) in commitDetails) {
            commitsPerPeriod[period] = (commitsPerPeriod[period] ?: 0) + 1
            linesAddedPerPeriod[period] = (linesAddedPerPeriod[period] ?: 0) + additions
            linesDeletedPerPeriod[period] = (linesDeletedPerPeriod[period] ?: 0) + deletions
            totalAdded += additions
            totalDeleted += deletions
        }

        logger.info("Done: ${allCommits.size} commits, +$totalAdded/-$totalDeleted lines")
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

                        // Fetch commit details for lines
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

    private fun fetchAccessibleRepos(token: String, provider: GitProvider, baseUrl: String?): List<String> {
        return when (provider) {
            GitProvider.GITHUB -> fetchGitHubAccessibleRepos(token, baseUrl)
            GitProvider.GITLAB -> fetchGitLabAccessibleRepos(token, baseUrl)
        }
    }

    private fun fetchGitHubAccessibleRepos(token: String, baseUrl: String?): List<String> {
        val apiUrl = baseUrl ?: GITHUB_API_URL
        val webClient = createWebClient(apiUrl, token, GitProvider.GITHUB)
        val repos = mutableListOf<String>()

        try {
            val orgs = webClient.get()
                .uri("/user/orgs?per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            orgs?.forEach { org ->
                val orgLogin = org["login"] as? String ?: return@forEach
                try {
                    var page = 1
                    var hasMore = true
                    while (hasMore && page <= 5) {
                        val orgRepos = webClient.get()
                            .uri("/orgs/$orgLogin/repos?per_page=100&page=$page")
                            .retrieve()
                            .bodyToMono<List<Map<String, Any?>>>()
                            .block()

                        if (orgRepos.isNullOrEmpty()) {
                            hasMore = false
                        } else {
                            orgRepos.forEach { repo ->
                                val fullName = repo["full_name"] as? String
                                if (fullName != null) repos.add(fullName)
                            }
                            page++
                            if (orgRepos.size < 100) hasMore = false
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Could not fetch repos from org $orgLogin: ${e.message}")
                }
            }

            if (repos.isEmpty()) {
                val userRepos = webClient.get()
                    .uri("/user/repos?per_page=100&type=all")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                userRepos?.forEach { repo ->
                    val fullName = repo["full_name"] as? String
                    if (fullName != null) repos.add(fullName)
                }
            }

            logger.info("Found ${repos.size} accessible repositories")
        } catch (e: Exception) {
            logger.error("Error fetching accessible repos: ${e.message}")
        }

        return repos
    }

    private fun fetchGitLabAccessibleRepos(token: String, baseUrl: String?): List<String> {
        val apiUrl = baseUrl ?: GITLAB_API_URL
        val webClient = createWebClient(apiUrl, token, GitProvider.GITLAB)
        val repos = mutableListOf<String>()

        try {
            val projects = webClient.get()
                .uri("/projects?membership=true&per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            projects?.forEach { project ->
                val pathWithNamespace = project["path_with_namespace"] as? String
                if (pathWithNamespace != null) repos.add(pathWithNamespace)
            }
        } catch (e: Exception) {
            logger.error("Error fetching GitLab projects: ${e.message}")
        }

        return repos
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

    private fun generatePeriods(
        startDate: LocalDate,
        endDate: LocalDate,
        period: AggregationPeriod
    ): List<Triple<String, LocalDate, LocalDate>> {
        val periods = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        var current = startDate

        when (period) {
            AggregationPeriod.DAILY -> {
                while (!current.isAfter(endDate)) {
                    val label = current.toString()
                    periods.add(Triple(label, current, current))
                    current = current.plusDays(1)
                }
            }
            AggregationPeriod.WEEKLY -> {
                val weekFields = WeekFields.of(Locale.getDefault())
                current = current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

                while (!current.isAfter(endDate)) {
                    val weekNum = current.get(weekFields.weekOfWeekBasedYear())
                    val year = current.get(weekFields.weekBasedYear())
                    val label = "$year-W${weekNum.toString().padStart(2, '0')}"
                    val weekEnd = current.plusDays(6)
                    periods.add(Triple(label, current, weekEnd))
                    current = current.plusWeeks(1)
                }
            }
            AggregationPeriod.MONTHLY -> {
                current = current.withDayOfMonth(1)

                while (!current.isAfter(endDate)) {
                    val label = "${current.year}-${current.monthValue.toString().padStart(2, '0')}"
                    val monthEnd = current.with(TemporalAdjusters.lastDayOfMonth())
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

        return when (provider) {
            GitProvider.GITHUB -> builder
                .defaultHeader("Authorization", "Bearer $token")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build()
            GitProvider.GITLAB -> builder
                .defaultHeader("PRIVATE-TOKEN", token)
                .build()
        }
    }
}
