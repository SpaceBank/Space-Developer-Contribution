package org.git.developer.contribution.controller

import org.git.developer.contribution.model.TrunkMetricsRequest
import org.git.developer.contribution.model.TrunkMetricsResponse
import org.git.developer.contribution.service.TrunkMetricsService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/trunk-matrix")
class TrunkMetricsController(
    private val trunkMetricsService: TrunkMetricsService
) {

    @PostMapping("/analyze")
    fun analyze(@RequestBody request: TrunkMetricsRequest): TrunkMetricsResponse {
        return trunkMetricsService.analyze(request)
    }

    @GetMapping("/workflows")
    fun listWorkflows(
        @RequestParam token: String,
        @RequestParam owner: String,
        @RequestParam repo: String
    ): List<Map<String, String>> {
        return trunkMetricsService.listWorkflows(token, owner, repo)
    }
}

