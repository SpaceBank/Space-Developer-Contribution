package org.git.developer.contribution.controller

import org.git.developer.contribution.model.*
import org.git.developer.contribution.service.EngagementService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/engagement")
@CrossOrigin(origins = ["*"])
class EngagementController(
    private val engagementService: EngagementService
) {
    private val logger = LoggerFactory.getLogger(EngagementController::class.java)

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

    /**
     * Fast endpoint - only commits (no PR/review data)
     */
    @PostMapping("/analyze/commits")
    fun analyzeCommitsOnly(@RequestBody request: EngagementAnalyzeRequest): ResponseEntity<EngagementAnalysisResponse> {
        logger.info("📥 /analyze/commits request received: ${request.contributorLogins.size} contributors, ${request.repositoryFullNames.size} repos")
        return try {
            val result = engagementService.analyzeCommitsOnly(request)
            logger.info("✅ /analyze/commits completed successfully")
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            logger.error("❌ /analyze/commits failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Slower endpoint - PR and review data
     */
    @PostMapping("/analyze/prs")
    fun analyzePRsOnly(@RequestBody request: EngagementAnalyzeRequest): ResponseEntity<PRReviewResponse> {
        logger.info("📥 /analyze/prs request received: ${request.contributorLogins.size} contributors, ${request.repositoryFullNames.size} repos")
        return try {
            val result = engagementService.analyzePRsOnly(request)
            logger.info("✅ /analyze/prs completed successfully with ${result.contributors.size} contributors")
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            logger.error("❌ /analyze/prs failed: ${e.message}", e)
            throw e
        }
    }
}

