package org.git.developer.contribution.controller

import org.git.developer.contribution.model.*
import org.git.developer.contribution.service.EngagementService
import org.git.developer.contribution.service.UserActivityLogger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/engagement")
@CrossOrigin(origins = ["*"])
class EngagementController(
    private val engagementService: EngagementService,
    private val userActivity: UserActivityLogger
) {
    private val logger = LoggerFactory.getLogger(EngagementController::class.java)

    @PostMapping("/members")
    fun fetchOrgMembers(@RequestBody request: OrgMembersRequest): ContributorsResponse {
        val username = userActivity.resolveUser(request.token)
        userActivity.logApiCall(username, "FETCH ORG MEMBERS", "provider=${request.provider}")
        val startTime = System.currentTimeMillis()

        val result = engagementService.fetchOrgMembers(request)

        val duration = System.currentTimeMillis() - startTime
        userActivity.logAction(username, "ORG MEMBERS LOADED", "${result.totalCount} members from ${result.organizations.size} orgs in ${duration}ms")
        return result
    }

    @PostMapping("/contributors")
    fun fetchContributors(@RequestBody request: ContributorsRequest): ContributorsResponse {
        val username = userActivity.resolveUser(request.token)
        userActivity.logApiCall(username, "FETCH CONTRIBUTORS", "repos=${request.repositoryFullNames.size}")
        val startTime = System.currentTimeMillis()

        val result = engagementService.fetchContributors(request)

        val duration = System.currentTimeMillis() - startTime
        userActivity.logAction(username, "CONTRIBUTORS LOADED", "${result.totalCount} contributors in ${duration}ms")
        return result
    }

    @PostMapping("/analyze")
    fun analyzeEngagement(@RequestBody request: EngagementAnalyzeRequest): EngagementAnalysisResponse {
        val username = userActivity.resolveUser(request.token)
        userActivity.logAnalysisStart(
            username, "ENGAGEMENT",
            request.repositoryFullNames,
            "contributors=${request.contributorLogins.size} [${request.contributorLogins.joinToString(", ")}], period=${request.period}, dates=${request.startDate ?: "default"}..${request.endDate ?: "default"}"
        )

        val startTime = System.currentTimeMillis()
        return try {
            val result = engagementService.analyzeEngagement(request)
            val duration = System.currentTimeMillis() - startTime
            userActivity.logAnalysisComplete(
                username, "ENGAGEMENT", duration,
                "commits=${result.summary.totalCommits}, PRsMerged=${result.summary.totalPRsMerged}, PRsReviewed=${result.summary.totalPRsReviewed}, contributors=${result.contributors.size}"
            )
            result
        } catch (e: Exception) {
            userActivity.logAnalysisError(username, "ENGAGEMENT", e)
            throw e
        }
    }

    /**
     * Fast endpoint - only commits (no PR/review data)
     */
    @PostMapping("/analyze/commits")
    fun analyzeCommitsOnly(@RequestBody request: EngagementAnalyzeRequest): ResponseEntity<EngagementAnalysisResponse> {
        val username = userActivity.resolveUser(request.token)
        userActivity.logApiCall(username, "ENGAGEMENT COMMITS", "${request.contributorLogins.size} contributors, ${request.repositoryFullNames.size} repos")
        logger.info("📥 [@$username] /analyze/commits — contributors: [${request.contributorLogins.joinToString(", ")}]")

        val startTime = System.currentTimeMillis()
        return try {
            val result = engagementService.analyzeCommitsOnly(request)
            val duration = System.currentTimeMillis() - startTime
            userActivity.logAction(username, "ENGAGEMENT COMMITS DONE", "commits=${result.summary.totalCommits}, took=${duration}ms")
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            userActivity.logAnalysisError(username, "ENGAGEMENT COMMITS", e)
            throw e
        }
    }

    /**
     * Slower endpoint - PR and review data
     */
    @PostMapping("/analyze/prs")
    fun analyzePRsOnly(@RequestBody request: EngagementAnalyzeRequest): ResponseEntity<PRReviewResponse> {
        val username = userActivity.resolveUser(request.token)
        userActivity.logApiCall(username, "ENGAGEMENT PRs", "${request.contributorLogins.size} contributors, ${request.repositoryFullNames.size} repos")
        logger.info("📥 [@$username] /analyze/prs — contributors: [${request.contributorLogins.joinToString(", ")}], repos: [${request.repositoryFullNames.joinToString(", ")}]")

        val startTime = System.currentTimeMillis()
        return try {
            val result = engagementService.analyzePRsOnly(request)
            val duration = System.currentTimeMillis() - startTime
            userActivity.logAction(username, "ENGAGEMENT PRs DONE", "${result.contributors.size} contributors processed, took=${duration}ms")
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            userActivity.logAnalysisError(username, "ENGAGEMENT PRs", e)
            throw e
        }
    }
}

