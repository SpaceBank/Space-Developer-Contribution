package org.git.developer.contribution.controller

import org.git.developer.contribution.model.*
import org.git.developer.contribution.service.ContributionAggregatorService
import org.git.developer.contribution.service.UserActivityLogger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * REST Controller for Git contribution analysis endpoints
 */
@RestController
@RequestMapping("/api/contributions")
@CrossOrigin(origins = ["*"])
class ContributionController(
    private val aggregatorService: ContributionAggregatorService,
    private val userActivity: UserActivityLogger
) {
    private val logger = LoggerFactory.getLogger(ContributionController::class.java)

    /**
     * Analyze multiple repositories and get time-series contribution data
     */
    @PostMapping("/analyze")
    fun analyzeRepositories(@RequestBody request: AnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
        logger.info("📥 POST /api/contributions/analyze — repos=${request.repositoryPaths.size}, period=${request.period}")
        logger.info("   Repos: ${request.repositoryPaths.joinToString(", ")}")

        val startTime = System.currentTimeMillis()
        val result = aggregatorService.analyzeRepositories(request)
        val duration = System.currentTimeMillis() - startTime

        logger.info("✅ Contribution analysis done in ${duration}ms — commits=${result.summary.totalCommits}, developers=${result.summary.totalDevelopers}")
        return ResponseEntity.ok(result)
    }

    /**
     * Get developer contribution summary
     *
     * Example: GET /api/contributions/developers?repos=/path/to/repo1,/path/to/repo2
     */
    @GetMapping("/developers")
    fun getDeveloperContributions(
        @RequestParam repos: List<String>,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?
    ): ResponseEntity<List<DeveloperContribution>> {
        logger.info("📥 GET /api/contributions/developers — repos=${repos.size}, dates=${startDate ?: "all"}..${endDate ?: "all"}")
        val contributions = aggregatorService.getDeveloperContributions(repos, startDate, endDate)
        logger.info("✅ Developer contributions: ${contributions.size} developers found")
        return ResponseEntity.ok(contributions)
    }

    /**
     * Get contribution timeline for chart visualization
     * Returns data formatted specifically for charting libraries
     *
     * Example: GET /api/contributions/timeline?repos=/path/to/repo1&period=WEEKLY
     */
    @GetMapping("/timeline")
    fun getContributionTimeline(
        @RequestParam repos: List<String>,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
        @RequestParam(required = false, defaultValue = "WEEKLY") period: AggregationPeriod
    ): ResponseEntity<ContributionAnalysisResponse> {
        logger.info("📥 GET /api/contributions/timeline — repos=${repos.size}, period=$period")
        val request = AnalyzeRequest(
            repositoryPaths = repos,
            startDate = startDate,
            endDate = endDate,
            period = period
        )
        val result = aggregatorService.analyzeRepositories(request)
        return ResponseEntity.ok(result)
    }

    /**
     * Get Chart.js compatible data format
     *
     * Example: GET /api/contributions/chart-data?repos=/path/to/repo1&period=MONTHLY
     */
    @GetMapping("/chart-data")
    fun getChartData(
        @RequestParam repos: List<String>,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
        @RequestParam(required = false, defaultValue = "WEEKLY") period: AggregationPeriod
    ): ResponseEntity<ChartDataResponse> {
        logger.info("📥 GET /api/contributions/chart-data — repos=${repos.size}, period=$period")
        val request = AnalyzeRequest(
            repositoryPaths = repos,
            startDate = startDate,
            endDate = endDate,
            period = period
        )
        val analysis = aggregatorService.analyzeRepositories(request)

        // Transform to Chart.js format
        val labels = analysis.developers.firstOrNull()?.dataPoints?.map { it.period } ?: emptyList()

        val datasets = analysis.developers.map { developer ->
            ChartDataset(
                label = developer.authorName,
                data = developer.dataPoints.map { it.commits }
            )
        }

        return ResponseEntity.ok(
            ChartDataResponse(
                labels = labels,
                datasets = datasets,
                summary = analysis.summary
            )
        )
    }
}

/**
 * Chart.js compatible response format
 */
data class ChartDataResponse(
    val labels: List<String>,
    val datasets: List<ChartDataset>,
    val summary: AnalysisSummary
)

data class ChartDataset(
    val label: String,
    val data: List<Int>
)

