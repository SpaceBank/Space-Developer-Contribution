package org.git.developer.contribution.controller

import org.git.developer.contribution.model.*
import org.git.developer.contribution.service.ContributionAggregatorService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * REST Controller for Git contribution analysis endpoints
 */
@RestController
@RequestMapping("/api/contributions")
@CrossOrigin(origins = ["*"]) // Allow CORS for frontend charting libraries
class ContributionController(
    private val aggregatorService: ContributionAggregatorService
) {

    /**
     * Analyze multiple repositories and get time-series contribution data
     *
     * Example request:
     * POST /api/contributions/analyze
     * {
     *   "repositoryPaths": ["/path/to/repo1", "/path/to/repo2"],
     *   "startDate": "2025-01-01",
     *   "endDate": "2025-12-31",
     *   "period": "WEEKLY"
     * }
     */
    @PostMapping("/analyze")
    fun analyzeRepositories(@RequestBody request: AnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
        val result = aggregatorService.analyzeRepositories(request)
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
        val contributions = aggregatorService.getDeveloperContributions(repos, startDate, endDate)
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

