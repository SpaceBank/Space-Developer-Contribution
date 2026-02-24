package org.git.developer.contribution.controller

import org.git.developer.contribution.model.MetricsAnalyzeRequest
import org.git.developer.contribution.model.MetricsAnalysisResponse
import org.git.developer.contribution.service.MetricsService
import org.git.developer.contribution.service.UserActivityLogger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/metrics")
class MetricsController(
    private val metricsService: MetricsService,
    private val userActivity: UserActivityLogger
) {
    private val logger = LoggerFactory.getLogger(MetricsController::class.java)

    @PostMapping("/analyze")
    fun analyzeMetrics(@RequestBody request: MetricsAnalyzeRequest): MetricsAnalysisResponse {
        val username = userActivity.resolveUser(request.token)
        userActivity.logAnalysisStart(
            username, "FLOW(GIT) MATRIX",
            request.repositoryFullNames,
            "dates=${request.startDate ?: "default"}..${request.endDate ?: "default"}, branches=${request.branches ?: "default"}"
        )

        val startTime = System.currentTimeMillis()
        return try {
            val result = metricsService.analyzeMetrics(request)
            val duration = System.currentTimeMillis() - startTime
            userActivity.logAnalysisComplete(
                username, "FLOW(GIT) MATRIX", duration,
                "PRs analyzed=${result.teamMetrics.totalPRs}, developers=${result.developerMetrics.size}"
            )
            result
        } catch (e: Exception) {
            userActivity.logAnalysisError(username, "FLOW(GIT) MATRIX", e)
            throw e
        }
    }
}

