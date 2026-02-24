package org.git.developer.contribution.service

import org.git.developer.contribution.config.GitApiClient
import org.git.developer.contribution.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.bodyToMono
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Service for analyzing remote repositories by cloning them temporarily
 */
@Service
class RemoteRepositoryAnalyzerService(
    private val gitProviderService: GitProviderService,
    private val gitDataExtractor: GitDataExtractorService,
    private val contributionAggregator: ContributionAggregatorService,
    private val api: GitApiClient
) {
    private val logger = LoggerFactory.getLogger(RemoteRepositoryAnalyzerService::class.java)

    /**
     * Analyze a single remote repository (for parallel processing from frontend)
     */
    fun analyzeSingleRepository(request: SingleRepoAnalyzeRequest): ContributionAnalysisResponse {
        val startTime = System.currentTimeMillis()
        val tempBaseDir = Files.createTempDirectory("git-analysis-single-").toFile()

        logger.info("═══════════════════════════════════════════════════════════")
        logger.info("📦 Starting analysis: ${request.repositoryFullName}")
        logger.info("═══════════════════════════════════════════════════════════")
        logger.info("  Provider: ${request.provider}")
        logger.info("  Date range: ${request.startDate ?: "default"} to ${request.endDate ?: "default"}")
        logger.info("  Period: ${request.period}")
        logger.info("  Branch: ${request.branch ?: "all"}")
        logger.info("  Exclude merges: ${request.excludeMerges}")

        try {
            // Step 1: Get repository info
            logger.info("📋 [${request.repositoryFullName}] Step 1: Fetching repository info...")
            val repoList = gitProviderService.listRepositories(
                token = request.token,
                provider = request.provider,
                baseUrl = request.baseUrl
            )

            val repo = repoList.repositories.find { it.fullName == request.repositoryFullName }
                ?: run {
                    logger.warn("❌ [${request.repositoryFullName}] Repository not found in accessible repos")
                    return emptySingleResponse(request)
                }
            logger.info("✅ [${request.repositoryFullName}] Repository found: ${repo.name} (${repo.defaultBranch})")

            // Step 2: Clone the repository
            val repoDir = File(tempBaseDir, repo.name)
            logger.info("📥 [${request.repositoryFullName}] Step 2: Cloning repository...")
            val cloneStartTime = System.currentTimeMillis()

            val success = gitProviderService.cloneRepository(
                cloneUrl = repo.cloneUrl,
                token = request.token,
                provider = request.provider,
                targetDir = repoDir
            )

            val cloneTime = System.currentTimeMillis() - cloneStartTime
            if (!success) {
                logger.error("❌ [${request.repositoryFullName}] Failed to clone after ${cloneTime}ms")
                return emptySingleResponse(request)
            }
            logger.info("✅ [${request.repositoryFullName}] Clone complete in ${cloneTime}ms")

            // Step 3: Analyze commits
            logger.info("🔍 [${request.repositoryFullName}] Step 3: Analyzing commits...")
            val analyzeStartTime = System.currentTimeMillis()

            val analyzeRequest = AnalyzeRequest(
                repositoryPaths = listOf(repoDir.absolutePath),
                startDate = request.startDate?.let { LocalDate.parse(it) },
                endDate = request.endDate?.let { LocalDate.parse(it) },
                period = request.period,
                branch = request.branch,
                excludeMerges = request.excludeMerges,
                repositoryFullNames = listOf(request.repositoryFullName)
            )

            val result = contributionAggregator.analyzeRepositories(analyzeRequest)
            val analyzeTime = System.currentTimeMillis() - analyzeStartTime
            logger.info("✅ [${request.repositoryFullName}] Commit analysis complete in ${analyzeTime}ms")
            logger.info("   └─ Found ${result.summary.totalCommits} commits from ${result.developers.size} developers")

            // Step 4: Fetch PRs
            logger.info("🔀 [${request.repositoryFullName}] Step 4: Fetching merged PRs...")
            val prStartTime = System.currentTimeMillis()

            val prs = gitProviderService.fetchMergedPullRequests(
                token = request.token,
                provider = request.provider,
                repositoryFullName = request.repositoryFullName,
                since = request.startDate,
                baseUrl = request.baseUrl
            )

            val prTime = System.currentTimeMillis() - prStartTime
            logger.info("✅ [${request.repositoryFullName}] PR fetch complete in ${prTime}ms")
            logger.info("   └─ Found ${prs.size} merged PRs")

            val prCountByPeriod = calculatePRCountByPeriod(prs, request.period, result.dateRange)

            // Calculate PR author stats
            val prAuthorStats = calculatePRAuthorStats(prs)
            logger.info("   └─ ${prAuthorStats.size} unique PR authors")

            // Attach PR details (authored + reviewed) to developer timelines via Search API
            val developersWithPRs = fetchAndAttachAllPRsToDevelopers(
                result.developers,
                request.token,
                request.provider,
                listOf(request.repositoryFullName),
                request.startDate,
                request.endDate
            )

            val totalTime = System.currentTimeMillis() - startTime
            logger.info("═══════════════════════════════════════════════════════════")
            logger.info("✅ [${request.repositoryFullName}] Analysis complete in ${totalTime}ms")
            logger.info("   └─ Commits: ${result.summary.totalCommits}, PRs: ${prs.size}, Developers: ${result.developers.size}")
            logger.info("═══════════════════════════════════════════════════════════")

            return result.copy(
                analyzedRepositories = listOf(request.repositoryFullName),
                prCountByPeriod = prCountByPeriod,
                prAuthorStats = prAuthorStats,
                developers = developersWithPRs,
                summary = result.summary.copy(totalPRs = prs.size)
            )

        } finally {
            try {
                tempBaseDir.deleteRecursively()
                logger.info("🧹 [${request.repositoryFullName}] Cleaned up temp directory")
            } catch (e: Exception) {
                logger.warn("⚠️ [${request.repositoryFullName}] Failed to clean up temp directory: ${e.message}")
            }
        }
    }

    private fun emptySingleResponse(request: SingleRepoAnalyzeRequest): ContributionAnalysisResponse {
        return ContributionAnalysisResponse(
            analyzedRepositories = listOf(request.repositoryFullName),
            dateRange = DateRange(
                startDate = request.startDate?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(3),
                endDate = request.endDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
            ),
            period = request.period,
            developers = emptyList(),
            summary = AnalysisSummary(
                totalDevelopers = 0,
                totalCommits = 0,
                totalLinesAdded = 0,
                totalLinesDeleted = 0,
                mostActiveAuthor = null,
                mostActiveRepository = null
            )
        )
    }

    /**
     * Analyze selected remote repositories
     */
    fun analyzeRemoteRepositories(request: RemoteAnalyzeRequest): ContributionAnalysisResponse {
        val tempBaseDir = Files.createTempDirectory("git-analysis-").toFile()
        logger.info("Created temp directory: ${tempBaseDir.absolutePath}")

        try {
            // First, get repository info to get clone URLs
            val repoList = gitProviderService.listRepositories(
                token = request.token,
                provider = request.provider,
                baseUrl = request.baseUrl
            )

            val selectedRepos = repoList.repositories.filter { repo ->
                request.repositoryFullNames.contains(repo.fullName)
            }

            if (selectedRepos.isEmpty()) {
                logger.warn("No matching repositories found for: ${request.repositoryFullNames}")
                return emptyResponse(request)
            }

            // Clone each repository
            val clonedPaths = mutableListOf<String>()
            for (repo in selectedRepos) {
                val repoDir = File(tempBaseDir, repo.name)
                logger.info("Cloning ${repo.fullName}...")

                val success = gitProviderService.cloneRepository(
                    cloneUrl = repo.cloneUrl,
                    token = request.token,
                    provider = request.provider,
                    targetDir = repoDir
                )

                if (success) {
                    clonedPaths.add(repoDir.absolutePath)
                } else {
                    logger.warn("Failed to clone ${repo.fullName}")
                }
            }

            if (clonedPaths.isEmpty()) {
                logger.error("Failed to clone any repositories")
                return emptyResponse(request)
            }

            // Now analyze all cloned repositories
            val analyzeRequest = AnalyzeRequest(
                repositoryPaths = clonedPaths,
                startDate = request.startDate?.let { LocalDate.parse(it) },
                endDate = request.endDate?.let { LocalDate.parse(it) },
                period = request.period,
                branch = request.branch,
                excludeMerges = request.excludeMerges
            )

            val result = contributionAggregator.analyzeRepositories(analyzeRequest)

            // Fetch PRs for all selected repositories
            logger.info("Fetching PRs for ${request.repositoryFullNames.size} repositories...")
            val allPRs = request.repositoryFullNames.flatMap { repoFullName ->
                gitProviderService.fetchMergedPullRequests(
                    token = request.token,
                    provider = request.provider,
                    repositoryFullName = repoFullName,
                    since = request.startDate,
                    baseUrl = request.baseUrl
                )
            }
            logger.info("Found ${allPRs.size} total merged PRs")

            // Calculate PR counts by period
            val prCountByPeriod = calculatePRCountByPeriod(
                allPRs,
                request.period,
                result.dateRange
            )

            // Calculate PR author stats
            val prAuthorStats = calculatePRAuthorStats(allPRs)

            // Attach PR details (authored + reviewed) to developer timelines via Search API
            val developersWithPRs = fetchAndAttachAllPRsToDevelopers(
                result.developers,
                request.token,
                request.provider,
                request.repositoryFullNames,
                request.startDate,
                request.endDate
            )

            // Return with original repo names and PR data
            return result.copy(
                analyzedRepositories = request.repositoryFullNames,
                prCountByPeriod = prCountByPeriod,
                prAuthorStats = prAuthorStats,
                developers = developersWithPRs,
                summary = result.summary.copy(totalPRs = allPRs.size)
            )

        } finally {
            // Clean up temp directory
            try {
                tempBaseDir.deleteRecursively()
                logger.info("Cleaned up temp directory")
            } catch (e: Exception) {
                logger.warn("Failed to clean up temp directory: ${e.message}")
            }
        }
    }

    /**
     * Calculate PR count by time period
     */
    private fun calculatePRCountByPeriod(
        prs: List<PullRequestInfo>,
        period: AggregationPeriod,
        dateRange: DateRange
    ): List<Int> {
        val periods = generateTimePeriods(dateRange, period)

        return periods.map { (_, start, end) ->
            prs.count { pr ->
                val mergedDate = pr.mergedAt?.let {
                    try { LocalDate.parse(it.substring(0, 10)) } catch (e: Exception) { null }
                }
                mergedDate != null && !mergedDate.isBefore(start) && !mergedDate.isAfter(end)
            }
        }
    }

    /**
     * Calculate PR counts by author
     */
    private fun calculatePRAuthorStats(prs: List<PullRequestInfo>): List<PRAuthorStats> {
        return prs.groupBy { it.authorName }
            .map { (author, authorPrs) -> PRAuthorStats(author, authorPrs.size) }
            .sortedByDescending { it.prCount }
    }

    /**
     * Attach individual PR details to developer timelines by matching PR author to developer nickname.
     * This uses the merged PRs already fetched (fallback for non-GitHub or when Search API fails).
     */
    private fun attachPRsToDevelopers(
        developers: List<DeveloperTimeline>,
        prs: List<PullRequestInfo>
    ): List<DeveloperTimeline> {
        if (prs.isEmpty()) return developers
        val prsByAuthor = prs.groupBy { it.authorName.lowercase() }
        return developers.map { dev ->
            val devPRs = prsByAuthor[dev.nickname.lowercase()] ?: emptyList()
            if (devPRs.isEmpty()) dev
            else dev.copy(
                prDetails = devPRs.map { pr ->
                    ContributionPRDetail(
                        number = pr.number,
                        title = pr.title,
                        state = pr.state,
                        createdAt = pr.createdAt,
                        mergedAt = pr.mergedAt,
                        repositoryFullName = pr.repositoryFullName,
                        url = "https://github.com/${pr.repositoryFullName}/pull/${pr.number}"
                    )
                }.sortedByDescending { it.createdAt }
            )
        }
    }

    /**
     * Fetch authored PRs (all states: open, merged, closed) and reviewed PRs
     * via GitHub Search API, then attach to developer timelines.
     */
    private fun fetchAndAttachAllPRsToDevelopers(
        developers: List<DeveloperTimeline>,
        token: String,
        provider: GitProvider,
        repositories: List<String>,
        since: String?,
        until: String?
    ): List<DeveloperTimeline> {
        if (developers.isEmpty() || repositories.isEmpty()) return developers

        val webClient = api.forProvider(token, provider)

        val startDate = since ?: LocalDate.now().minusMonths(6).toString()
        val endDate = until ?: LocalDate.now().toString()

        // Determine search scope
        val orgName = repositories.firstOrNull()?.split("/")?.firstOrNull() ?: ""
        val repoQueries = if (repositories.size > 5 && orgName.isNotEmpty()) {
            listOf("org:$orgName")
        } else {
            repositories.map { "repo:$it" }
        }

        return developers.map { dev ->
            val login = dev.nickname
            val authoredPRs = mutableListOf<ContributionPRDetail>()
            val reviewedPRs = mutableListOf<ContributionPRDetail>()

            try {
                // Fetch authored PRs (all states)
                for (repoQuery in repoQueries) {
                    var page = 1
                    var hasMore = true
                    while (hasMore && page <= 5) {
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

                            if (items.isEmpty()) {
                                hasMore = false
                            } else {
                                for (item in items) {
                                    val prNumber = (item["number"] as? Number)?.toInt() ?: continue
                                    val prTitle = item["title"] as? String ?: ""
                                    val prUrl = item["html_url"] as? String ?: ""
                                    val createdAt = item["created_at"] as? String ?: ""
                                    val prState = item["state"] as? String ?: ""

                                    @Suppress("UNCHECKED_CAST")
                                    val pullRequest = item["pull_request"] as? Map<String, Any?>
                                    val mergedAt = pullRequest?.get("merged_at") as? String

                                    val repoName = prUrl.replace("https://github.com/", "").split("/pull/").firstOrNull() ?: ""

                                    val state = when {
                                        mergedAt != null -> "merged"
                                        prState == "open" -> "open"
                                        else -> "closed"
                                    }

                                    authoredPRs.add(ContributionPRDetail(
                                        number = prNumber,
                                        title = prTitle,
                                        state = state,
                                        createdAt = createdAt,
                                        mergedAt = mergedAt,
                                        repositoryFullName = repoName,
                                        url = prUrl
                                    ))
                                }
                                if (items.size < 100 || (page * 100) >= totalCount) hasMore = false else page++
                            }
                        } catch (e: Exception) {
                            logger.warn("⚠️ Search authored PRs error for $login in $repoQuery: ${e.message}")
                            hasMore = false
                        }
                    }
                }

                // Fetch reviewed PRs
                for (repoQuery in repoQueries) {
                    var page = 1
                    var hasMore = true
                    while (hasMore && page <= 5) {
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

                            if (items.isEmpty()) {
                                hasMore = false
                            } else {
                                for (item in items) {
                                    val prNumber = (item["number"] as? Number)?.toInt() ?: continue
                                    val prTitle = item["title"] as? String ?: ""
                                    val prUrl = item["html_url"] as? String ?: ""
                                    val createdAt = item["created_at"] as? String ?: ""
                                    val prState = item["state"] as? String ?: ""

                                    @Suppress("UNCHECKED_CAST")
                                    val pullRequest = item["pull_request"] as? Map<String, Any?>
                                    val mergedAt = pullRequest?.get("merged_at") as? String

                                    val repoName = prUrl.replace("https://github.com/", "").split("/pull/").firstOrNull() ?: ""

                                    val state = when {
                                        mergedAt != null -> "merged"
                                        prState == "open" -> "open"
                                        else -> "closed"
                                    }

                                    reviewedPRs.add(ContributionPRDetail(
                                        number = prNumber,
                                        title = prTitle,
                                        state = state,
                                        createdAt = createdAt,
                                        mergedAt = mergedAt,
                                        repositoryFullName = repoName,
                                        url = prUrl
                                    ))
                                }
                                if (items.size < 100 || (page * 100) >= totalCount) hasMore = false else page++
                            }
                        } catch (e: Exception) {
                            logger.warn("⚠️ Search reviewed PRs error for $login in $repoQuery: ${e.message}")
                            hasMore = false
                        }
                    }
                }

                logger.info("📋 [$login] Authored: ${authoredPRs.size} PRs (all states), Reviewed: ${reviewedPRs.size} PRs")

            } catch (e: Exception) {
                logger.warn("⚠️ Failed to fetch PR details for $login: ${e.message}")
            }

            dev.copy(
                prDetails = authoredPRs.sortedByDescending { it.createdAt },
                reviewDetails = reviewedPRs.sortedByDescending { it.createdAt }
            )
        }
    }

    /**
     * Generate time periods for grouping
     */
    private fun generateTimePeriods(
        dateRange: DateRange,
        period: AggregationPeriod
    ): List<Triple<String, LocalDate, LocalDate>> {
        val periods = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        var current = dateRange.startDate

        when (period) {
            AggregationPeriod.DAILY -> {
                while (!current.isAfter(dateRange.endDate)) {
                    val label = current.toString()
                    periods.add(Triple(label, current, current))
                    current = current.plusDays(1)
                }
            }
            AggregationPeriod.WEEKLY -> {
                val weekFields = WeekFields.of(Locale.getDefault())
                current = current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

                while (!current.isAfter(dateRange.endDate)) {
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

                while (!current.isAfter(dateRange.endDate)) {
                    val label = "${current.year}-${current.monthValue.toString().padStart(2, '0')}"
                    val monthEnd = current.with(TemporalAdjusters.lastDayOfMonth())
                    periods.add(Triple(label, current, monthEnd))
                    current = current.plusMonths(1)
                }
            }
        }

        return periods
    }

    private fun emptyResponse(request: RemoteAnalyzeRequest): ContributionAnalysisResponse {
        return ContributionAnalysisResponse(
            analyzedRepositories = request.repositoryFullNames,
            dateRange = DateRange(
                startDate = request.startDate?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(3),
                endDate = request.endDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
            ),
            period = request.period,
            developers = emptyList(),
            summary = AnalysisSummary(
                totalDevelopers = 0,
                totalCommits = 0,
                totalLinesAdded = 0,
                totalLinesDeleted = 0,
                mostActiveAuthor = null,
                mostActiveRepository = null
            )
        )
    }
}

