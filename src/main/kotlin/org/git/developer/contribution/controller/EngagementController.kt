package org.git.developer.contribution.controller

import org.git.developer.contribution.model.*
import org.git.developer.contribution.service.EngagementService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/engagement")
class EngagementController(
    private val engagementService: EngagementService
) {

    @PostMapping("/members")
    fun fetchOrgMembers(@RequestBody request: OrgMembersRequest): ContributorsResponse {
        return engagementService.fetchOrgMembers(request)
    }

    @PostMapping("/contributors")
    fun fetchContributors(@RequestBody request: ContributorsRequest): ContributorsResponse {
        return engagementService.fetchContributors(request)
    }

    @PostMapping("/analyze")
    fun analyzeEngagement(@RequestBody request: EngagementAnalyzeRequest): EngagementAnalysisResponse {
        return engagementService.analyzeEngagement(request)
    }
}

