package org.git.developer.contribution.service

import org.git.developer.contribution.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
    private val contributionAggregator: ContributionAggregatorService
) {
    private val logger = LoggerFactory.getLogger(RemoteRepositoryAnalyzerService::class.java)

    /**
     * Analyze a single remote repository (for parallel processing from frontend)
     */
    fun analyzeSingleRepository(request: SingleRepoAnalyzeRequest): ContributionAnalysisResponse {
        val tempBaseDir = Files.createTempDirectory("git-analysis-single-").toFile()
        logger.info("Analyzing single repo: ${request.repositoryFullName}")

        try {
            // Get repository info
            val repoList = gitProviderService.listRepositories(
                token = request.token,
                provider = request.provider,
                baseUrl = request.baseUrl
            )

            val repo = repoList.repositories.find { it.fullName == request.repositoryFullName }
                ?: run {
                    logger.warn("Repository not found: ${request.repositoryFullName}")
                    return emptySingleResponse(request)
                }

            // Clone the repository
            val repoDir = File(tempBaseDir, repo.name)
            logger.info("Cloning ${repo.fullName}...")

            val success = gitProviderService.cloneRepository(
                cloneUrl = repo.cloneUrl,
                token = request.token,
                provider = request.provider,
                targetDir = repoDir
            )

            if (!success) {
                logger.error("Failed to clone ${repo.fullName}")
                return emptySingleResponse(request)
            }

            // Analyze the repository
            val analyzeRequest = AnalyzeRequest(
                repositoryPaths = listOf(repoDir.absolutePath),
                startDate = request.startDate?.let { LocalDate.parse(it) },
                endDate = request.endDate?.let { LocalDate.parse(it) },
                period = request.period,
                branch = request.branch,
                excludeMerges = request.excludeMerges
            )

            val result = contributionAggregator.analyzeRepositories(analyzeRequest)

            // Fetch PRs
            val prs = gitProviderService.fetchMergedPullRequests(
                token = request.token,
                provider = request.provider,
                repositoryFullName = request.repositoryFullName,
                since = request.startDate,
                baseUrl = request.baseUrl
            )

            val prCountByPeriod = calculatePRCountByPeriod(prs, request.period, result.dateRange)

            return result.copy(
                analyzedRepositories = listOf(request.repositoryFullName),
                prCountByPeriod = prCountByPeriod,
                summary = result.summary.copy(totalPRs = prs.size)
            )

        } finally {
            try {
                tempBaseDir.deleteRecursively()
                logger.info("Cleaned up temp directory for ${request.repositoryFullName}")
            } catch (e: Exception) {
                logger.warn("Failed to clean up temp directory: ${e.message}")
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

            // Return with original repo names and PR data
            return result.copy(
                analyzedRepositories = request.repositoryFullNames,
                prCountByPeriod = prCountByPeriod,
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

