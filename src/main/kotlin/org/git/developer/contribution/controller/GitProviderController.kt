package org.git.developer.contribution.controller

import org.git.developer.contribution.model.*
import org.git.developer.contribution.service.ContributorCacheService
import org.git.developer.contribution.service.GitProviderService
import org.git.developer.contribution.service.RemoteRepositoryAnalyzerService
import org.git.developer.contribution.service.UserActivityLogger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST Controller for Git provider integration (GitHub/GitLab)
 */
@RestController
@RequestMapping("/api/git")
@CrossOrigin(origins = ["*"])
class GitProviderController(
    private val gitProviderService: GitProviderService,
    private val remoteAnalyzerService: RemoteRepositoryAnalyzerService,
    private val contributorCacheService: ContributorCacheService,
    private val userActivity: UserActivityLogger
) {
    private val logger = LoggerFactory.getLogger(GitProviderController::class.java)

    /**
     * Store session data (contributors) after frontend login
     */
    @PostMapping("/session")
    fun storeSessionData(@RequestBody request: SessionDataRequest): ResponseEntity<SessionDataResponse> {
        val contributorCount = request.contributors?.size ?: 0
        logger.info("📥 POST /api/git/session — storing $contributorCount contributors")

        if (!request.contributors.isNullOrEmpty()) {
            contributorCacheService.storeContributors(request.contributors)
            // Try to identify user from contributor list (first entry is often the logged-in user)
            val firstLogin = request.contributors.firstOrNull()?.get("login") as? String
            if (firstLogin != null) {
                userActivity.logSessionStore(firstLogin, contributorCount)
            }
        }
        return ResponseEntity.ok(SessionDataResponse(
            contributorsCached = contributorCacheService.size(),
            success = true
        ))
    }

    /**
     * List all repositories accessible with the provided token
     */
    @PostMapping("/repositories")
    fun listRepositories(@RequestBody request: AuthRequest): ResponseEntity<RepositoryListResponse> {
        val username = userActivity.resolveUser(request.token)
        userActivity.logAction(username, "LIST REPOSITORIES", "provider=${request.provider}")
        logger.info("📥 POST /api/git/repositories — user=@$username, provider=${request.provider}")

        val startTime = System.currentTimeMillis()
        val repos = gitProviderService.listRepositories(
            token = request.token,
            provider = request.provider,
            baseUrl = request.baseUrl
        )

        val duration = System.currentTimeMillis() - startTime
        userActivity.logAction(username, "REPOSITORIES LOADED", "${repos.totalCount} repos found in ${duration}ms")
        userActivity.registerUser(request.token, username)

        return ResponseEntity.ok(repos)
    }

    /**
     * Analyze selected remote repositories
     */
    @PostMapping("/analyze")
    fun analyzeRepositories(@RequestBody request: RemoteAnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
        val username = userActivity.resolveUser(request.token)
        userActivity.logAnalysisStart(
            username, "CONTRIBUTION (batch)",
            request.repositoryFullNames,
            "period=${request.period}, branch=${request.branch ?: "all"}, dates=${request.startDate ?: "default"}..${request.endDate ?: "default"}"
        )

        val startTime = System.currentTimeMillis()
        return try {
            val result = remoteAnalyzerService.analyzeRemoteRepositories(request)
            val duration = System.currentTimeMillis() - startTime
            userActivity.logAnalysisComplete(
                username, "CONTRIBUTION (batch)", duration,
                "commits=${result.summary.totalCommits}, developers=${result.summary.totalDevelopers}, PRs=${result.summary.totalPRs}"
            )
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            userActivity.logAnalysisError(username, "CONTRIBUTION (batch)", e)
            throw e
        }
    }

    /**
     * Analyze a single repository (for parallel processing)
     */
    @PostMapping("/analyze/single")
    fun analyzeSingleRepository(@RequestBody request: SingleRepoAnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
        val username = userActivity.resolveUser(request.token)
        userActivity.logApiCall(username, "ANALYZE SINGLE", "repo=${request.repositoryFullName}, branch=${request.branch ?: "all"}")

        val startTime = System.currentTimeMillis()
        return try {
            val result = remoteAnalyzerService.analyzeSingleRepository(request)
            val duration = System.currentTimeMillis() - startTime
            userActivity.logAction(
                username, "SINGLE REPO DONE: ${request.repositoryFullName}",
                "commits=${result.summary.totalCommits}, developers=${result.developers.size}, took=${duration}ms"
            )
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            userActivity.logAnalysisError(username, "SINGLE REPO: ${request.repositoryFullName}", e)
            throw e
        }
    }
}

