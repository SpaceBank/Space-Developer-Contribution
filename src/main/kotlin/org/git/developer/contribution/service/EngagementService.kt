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
     * This fetches all members from all organizations the token has access to
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
            // First, get all organizations the user has access to
            val orgsResponse = webClient.get()
                .uri("/user/orgs?per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block()

            logger.info("Found ${orgsResponse?.size ?: 0} organizations")

            orgsResponse?.forEach { org ->
                val orgLogin = org["login"] as? String ?: return@forEach
                organizations.add(orgLogin)

                // Fetch members from each organization
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

            // If no org members found, try to get collaborators from user's repos
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

            // Fetch user details (name, public_repos) for each member
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
            // Get groups the user has access to
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

            // Merge contributors (same login across repos)
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
            var page = 1
            var hasMore = true

            while (hasMore && page <= 5) {
                val response = webClient.get()
                    .uri("/repos/$repoFullName/contributors?per_page=100&page=$page")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (response.isNullOrEmpty()) {
                    hasMore = false
                } else {
                    response.forEach { c ->
                        contributors.add(ContributorInfo(
                            login = c["login"] as? String ?: "",
                            avatarUrl = c["avatar_url"] as? String,
                            contributions = (c["contributions"] as? Number)?.toInt() ?: 0,
                            profileUrl = c["html_url"] as? String
                        ))
                    }
                    page++
                    if (response.size < 100) hasMore = false
                }
            }
        } catch (e: Exception) {
            logger.error("Error fetching GitHub contributors for $repoFullName: ${e.message}")
        }

        return contributors
    }

    private fun fetchGitLabContributors(token: String, repoFullName: String, baseUrl: String?): List<ContributorInfo> {
        val apiUrl = baseUrl ?: GITLAB_API_URL
        val webClient = createWebClient(apiUrl, token, GitProvider.GITLAB)
        val encodedPath = repoFullName.replace("/", "%2F")
        val contributors = mutableListOf<ContributorInfo>()

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
     * Analyze developer engagement
     * If no repositories are specified, fetches all accessible org repos automatically
     */
    fun analyzeEngagement(request: EngagementAnalyzeRequest): EngagementAnalysisResponse {
        val startDate = request.startDate?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(3)
        val endDate = request.endDate?.let { LocalDate.parse(it) } ?: LocalDate.now()

        // If no repositories specified, fetch all accessible repos
        val repositories = if (request.repositoryFullNames.isEmpty()) {
            logger.info("No repositories specified, fetching all accessible repos...")
            fetchAccessibleRepos(request.token, request.provider, request.baseUrl)
        } else {
            request.repositoryFullNames
        }

        logger.info("Analyzing engagement for ${request.contributorLogins.size} contributors in ${repositories.size} repositories")

        // Generate periods
        val periods = generatePeriods(startDate, endDate, request.period)
        val periodLabels = periods.map { it.first }

        // Fetch data for each contributor
        val contributorEngagements = request.contributorLogins.map { login ->
            analyzeContributorEngagement(
                token = request.token,
                provider = request.provider,
                baseUrl = request.baseUrl,
                repositories = repositories,
                login = login,
                startDate = startDate,
                endDate = endDate,
                periods = periods
            )
        }

        // Calculate summary
        val summary = EngagementSummary(
            totalContributors = contributorEngagements.size,
            totalCommits = contributorEngagements.sumOf { it.totalCommits },
            totalPRsReviewed = contributorEngagements.sumOf { it.totalPRsReviewed },
            totalComments = contributorEngagements.sumOf { it.totalComments },
            mostActiveCommitter = contributorEngagements.maxByOrNull { it.totalCommits }?.login,
            mostActiveReviewer = contributorEngagements.maxByOrNull { it.totalPRsReviewed }?.login,
            mostActiveCommenter = contributorEngagements.maxByOrNull { it.totalComments }?.login
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
     * Fetch all accessible repositories for the token
     */
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
            // Get org repos
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

            // Also get user's own repos if no org repos found
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

    private fun analyzeContributorEngagement(
        token: String,
        provider: GitProvider,
        baseUrl: String?,
        repositories: List<String>,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>
    ): ContributorEngagement {
        val apiUrl = when (provider) {
            GitProvider.GITHUB -> baseUrl ?: GITHUB_API_URL
            GitProvider.GITLAB -> baseUrl ?: GITLAB_API_URL
        }
        val webClient = createWebClient(apiUrl, token, provider)

        // Data containers
        val commitsPerPeriod = mutableMapOf<String, Int>()
        val reviewsPerPeriod = mutableMapOf<String, Int>()
        val commentsPerPeriod = mutableMapOf<String, Int>()
        var avatarUrl: String? = null

        // Initialize periods
        periods.forEach { (label, _, _) ->
            commitsPerPeriod[label] = 0
            reviewsPerPeriod[label] = 0
            commentsPerPeriod[label] = 0
        }

        for (repoFullName in repositories) {
            when (provider) {
                GitProvider.GITHUB -> {
                    // Fetch commits by author
                    fetchGitHubCommits(webClient, repoFullName, login, startDate, endDate, periods, commitsPerPeriod)

                    // Fetch PR reviews
                    fetchGitHubReviews(webClient, repoFullName, login, startDate, endDate, periods, reviewsPerPeriod)

                    // Fetch comments (issue comments + PR comments)
                    fetchGitHubComments(webClient, repoFullName, login, startDate, endDate, periods, commentsPerPeriod)

                    // Get avatar URL
                    if (avatarUrl == null) {
                        avatarUrl = fetchGitHubUserAvatar(webClient, login)
                    }
                }
                GitProvider.GITLAB -> {
                    // Simplified GitLab implementation
                    fetchGitLabCommits(webClient, repoFullName, login, startDate, endDate, periods, commitsPerPeriod)
                }
            }
        }

        return ContributorEngagement(
            login = login,
            avatarUrl = avatarUrl,
            totalCommits = commitsPerPeriod.values.sum(),
            totalPRsReviewed = reviewsPerPeriod.values.sum(),
            totalComments = commentsPerPeriod.values.sum(),
            commitsOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, commitsPerPeriod[label] ?: 0)
            },
            reviewsOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, reviewsPerPeriod[label] ?: 0)
            },
            commentsOverTime = periods.map { (label, _, _) ->
                EngagementDataPoint(label, commentsPerPeriod[label] ?: 0)
            }
        )
    }

    private fun fetchGitHubCommits(
        webClient: WebClient,
        repoFullName: String,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        commitsPerPeriod: MutableMap<String, Int>
    ) {
        try {
            var page = 1
            var hasMore = true

            while (hasMore && page <= 10) {
                val response = webClient.get()
                    .uri("/repos/$repoFullName/commits?author=$login&since=${startDate}T00:00:00Z&until=${endDate}T23:59:59Z&per_page=100&page=$page")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (response.isNullOrEmpty()) {
                    hasMore = false
                } else {
                    response.forEach { commit ->
                        val commitData = commit["commit"] as? Map<*, *>
                        val author = commitData?.get("author") as? Map<*, *>
                        val dateStr = author?.get("date") as? String

                        if (dateStr != null) {
                            val commitDate = LocalDate.parse(dateStr.substring(0, 10))
                            val period = findPeriod(commitDate, periods)
                            if (period != null) {
                                commitsPerPeriod[period] = (commitsPerPeriod[period] ?: 0) + 1
                            }
                        }
                    }
                    page++
                    if (response.size < 100) hasMore = false
                }
            }
        } catch (e: Exception) {
            logger.debug("Error fetching commits for $login in $repoFullName: ${e.message}")
        }
    }

    private fun fetchGitHubReviews(
        webClient: WebClient,
        repoFullName: String,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        reviewsPerPeriod: MutableMap<String, Int>
    ) {
        try {
            // Fetch closed PRs and check reviews
            var page = 1
            var hasMore = true

            while (hasMore && page <= 5) {
                val prs = webClient.get()
                    .uri("/repos/$repoFullName/pulls?state=all&per_page=100&page=$page&sort=updated&direction=desc")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (prs.isNullOrEmpty()) {
                    hasMore = false
                } else {
                    for (pr in prs) {
                        val prNumber = (pr["number"] as Number).toInt()
                        val updatedAt = pr["updated_at"] as? String

                        // Skip if PR was last updated before our start date
                        if (updatedAt != null && updatedAt.substring(0, 10) < startDate.toString()) {
                            continue
                        }

                        // Fetch reviews for this PR
                        try {
                            val reviews = webClient.get()
                                .uri("/repos/$repoFullName/pulls/$prNumber/reviews")
                                .retrieve()
                                .bodyToMono<List<Map<String, Any?>>>()
                                .block()

                            reviews?.forEach { review ->
                                val user = review["user"] as? Map<*, *>
                                val reviewerLogin = user?.get("login") as? String
                                val submittedAt = review["submitted_at"] as? String

                                if (reviewerLogin?.lowercase() == login.lowercase() && submittedAt != null) {
                                    val reviewDate = LocalDate.parse(submittedAt.substring(0, 10))
                                    if (!reviewDate.isBefore(startDate) && !reviewDate.isAfter(endDate)) {
                                        val period = findPeriod(reviewDate, periods)
                                        if (period != null) {
                                            reviewsPerPeriod[period] = (reviewsPerPeriod[period] ?: 0) + 1
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore individual PR review fetch errors
                        }
                    }
                    page++
                    if (prs.size < 100) hasMore = false
                }
            }
        } catch (e: Exception) {
            logger.debug("Error fetching reviews for $login in $repoFullName: ${e.message}")
        }
    }

    private fun fetchGitHubComments(
        webClient: WebClient,
        repoFullName: String,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        commentsPerPeriod: MutableMap<String, Int>
    ) {
        // Fetch issue comments
        try {
            var page = 1
            var hasMore = true

            while (hasMore && page <= 5) {
                val comments = webClient.get()
                    .uri("/repos/$repoFullName/issues/comments?since=${startDate}T00:00:00Z&per_page=100&page=$page&sort=created&direction=desc")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (comments.isNullOrEmpty()) {
                    hasMore = false
                } else {
                    comments.forEach { comment ->
                        val user = comment["user"] as? Map<*, *>
                        val commenterLogin = user?.get("login") as? String
                        val createdAt = comment["created_at"] as? String

                        if (commenterLogin?.lowercase() == login.lowercase() && createdAt != null) {
                            val commentDate = LocalDate.parse(createdAt.substring(0, 10))
                            if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                                val period = findPeriod(commentDate, periods)
                                if (period != null) {
                                    commentsPerPeriod[period] = (commentsPerPeriod[period] ?: 0) + 1
                                }
                            }
                        }
                    }
                    page++
                    if (comments.size < 100) hasMore = false
                }
            }
        } catch (e: Exception) {
            logger.debug("Error fetching issue comments for $login in $repoFullName: ${e.message}")
        }

        // Fetch PR review comments
        try {
            var page = 1
            var hasMore = true

            while (hasMore && page <= 5) {
                val comments = webClient.get()
                    .uri("/repos/$repoFullName/pulls/comments?since=${startDate}T00:00:00Z&per_page=100&page=$page&sort=created&direction=desc")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (comments.isNullOrEmpty()) {
                    hasMore = false
                } else {
                    comments.forEach { comment ->
                        val user = comment["user"] as? Map<*, *>
                        val commenterLogin = user?.get("login") as? String
                        val createdAt = comment["created_at"] as? String

                        if (commenterLogin?.lowercase() == login.lowercase() && createdAt != null) {
                            val commentDate = LocalDate.parse(createdAt.substring(0, 10))
                            if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                                val period = findPeriod(commentDate, periods)
                                if (period != null) {
                                    commentsPerPeriod[period] = (commentsPerPeriod[period] ?: 0) + 1
                                }
                            }
                        }
                    }
                    page++
                    if (comments.size < 100) hasMore = false
                }
            }
        } catch (e: Exception) {
            logger.debug("Error fetching PR comments for $login in $repoFullName: ${e.message}")
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

    private fun fetchGitLabCommits(
        webClient: WebClient,
        repoFullName: String,
        login: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<Triple<String, LocalDate, LocalDate>>,
        commitsPerPeriod: MutableMap<String, Int>
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

                if ((authorName?.lowercase() == login.lowercase() ||
                     authorEmail?.lowercase()?.contains(login.lowercase()) == true) &&
                    dateStr != null) {
                    val commitDate = LocalDate.parse(dateStr.substring(0, 10))
                    val period = findPeriod(commitDate, periods)
                    if (period != null) {
                        commitsPerPeriod[period] = (commitsPerPeriod[period] ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Error fetching GitLab commits for $login in $repoFullName: ${e.message}")
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

