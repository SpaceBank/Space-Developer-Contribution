package org.git.developer.contribution.controller

import org.git.developer.contribution.model.TrunkMetricsRequest
import org.git.developer.contribution.model.TrunkMetricsResponse
import org.git.developer.contribution.service.TrunkMetricsService
import org.git.developer.contribution.service.UserActivityLogger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/trunk-matrix")
class TrunkMetricsController(
    private val trunkMetricsService: TrunkMetricsService,
    private val userActivity: UserActivityLogger
) {
    private val logger = LoggerFactory.getLogger(TrunkMetricsController::class.java)

    @PostMapping("/analyze")
    fun analyze(@RequestBody request: TrunkMetricsRequest): TrunkMetricsResponse {
        val username = userActivity.resolveUser(request.token)
        userActivity.logAnalysisStart(
            username, "TRUNK MATRIX",
            listOf("${request.owner}/${request.repo}"),
            "branch=${request.branch}, workflow='${request.workflowName}', dates=${request.startDate}..${request.endDate}"
        )

        val startTime = System.currentTimeMillis()
        return try {
            val result = trunkMetricsService.analyze(request)
            val duration = System.currentTimeMillis() - startTime
            userActivity.logAnalysisComplete(
                username, "TRUNK MATRIX", duration,
                "commits=${result.totalCommits}, successRate=${result.deploySuccessRate}%, rating=${result.ratings.overall}"
            )
            result
        } catch (e: Exception) {
            userActivity.logAnalysisError(username, "TRUNK MATRIX", e)
            throw e
        }
    }

    @GetMapping("/workflows")
    fun listWorkflows(
        @RequestParam token: String,
        @RequestParam owner: String,
        @RequestParam repo: String,
        @RequestParam(defaultValue = "GITHUB") provider: String
    ): List<Map<String, String>> {
        val username = userActivity.resolveUser(token)
        userActivity.logApiCall(username, "LIST WORKFLOWS", "repo=$owner/$repo")
        logger.info("📥 [@$username] GET /api/trunk-matrix/workflows — repo=$owner/$repo provider=$provider")

        val startTime = System.currentTimeMillis()
        val result = trunkMetricsService.listWorkflows(token, owner, repo, provider)
        val duration = System.currentTimeMillis() - startTime

        userActivity.logAction(username, "WORKFLOWS LOADED", "${result.size} workflows found for $owner/$repo in ${duration}ms")
        return result
    }
}

