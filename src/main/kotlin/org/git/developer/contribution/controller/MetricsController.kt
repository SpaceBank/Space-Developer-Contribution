package org.git.developer.contribution.controller

import org.git.developer.contribution.model.MetricsAnalyzeRequest
import org.git.developer.contribution.model.MetricsAnalysisResponse
import org.git.developer.contribution.service.MetricsService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/metrics")
class MetricsController(
    private val metricsService: MetricsService
) {

    @PostMapping("/analyze")
    fun analyzeMetrics(@RequestBody request: MetricsAnalyzeRequest): MetricsAnalysisResponse {
        return metricsService.analyzeMetrics(request)
    }
}

