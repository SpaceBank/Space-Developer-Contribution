package org.git.developer.contribution.controller

import org.git.developer.contribution.config.GitApiClient
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
    private val userActivity: UserActivityLogger,
    private val gitApiClient: GitApiClient
) {
    private val logger = LoggerFactory.getLogger(GitProviderController::class.java)

    /**
     * Store session data (contributors) after frontend login
     */
    @PostMapping("/session")
    fun storeSessionData(@RequestBody request: SessionDataRequest): ResponseEntity<SessionDataResponse> {
        val contributorCount = request.contributors?.size ?: 0
        logger.info("📥 POST /api/git/session — storing $contributorCount contributors")

        // Register the user for activity logging
        if (!request.token.isNullOrBlank() && !request.username.isNullOrBlank()) {
            userActivity.registerUser(request.token, request.username)
            userActivity.logLogin(request.username)
        }

        if (!request.contributors.isNullOrEmpty()) {
            contributorCacheService.storeContributors(request.contributors)
            val username = request.username ?: (request.contributors.firstOrNull()?.get("login") as? String) ?: "unknown"
            userActivity.logSessionStore(username, contributorCount)
        }
        return ResponseEntity.ok(SessionDataResponse(
            contributorsCached = contributorCacheService.size(),
            success = true
        ))
    }

    /**
     * Validate a stored token — called by frontend when restoring session from localStorage.
     * Also registers the user for activity logging.
     */
    @PostMapping("/validate-token")
    fun validateToken(@RequestBody request: ValidateTokenRequest): ResponseEntity<ValidateTokenResponse> {
        logger.info("🔑 POST /api/git/validate-token — checking token validity")
        return try {
            val webClient = gitApiClient.forProvider(request.token, request.provider, request.baseUrl)

            val response = webClient.get()
                .uri("/user")
                .exchangeToMono { clientResponse ->
                    val statusCode = clientResponse.statusCode().value()
                    val rateLimitRemaining = clientResponse.headers().asHttpHeaders().getFirst("X-RateLimit-Remaining")
                    val tokenExpiry = clientResponse.headers().asHttpHeaders().getFirst("github-authentication-token-expiration")

                    if (statusCode == 401) {
                        logger.warn("🔑 Token is invalid or expired (401)")
                        reactor.core.publisher.Mono.just(ValidateTokenResponse(
                            valid = false,
                            message = "Token expired or invalid. Please login again.",
                            reason = "expired"
                        ))
                    } else if (statusCode == 403 && rateLimitRemaining == "0") {
                        logger.warn("⏱️ Token valid but rate limited (403)")
                        reactor.core.publisher.Mono.just(ValidateTokenResponse(
                            valid = true,
                            message = "Rate limited — try again later",
                            reason = "rate_limited"
                        ))
                    } else if (statusCode in 200..299) {
                        clientResponse.bodyToMono(Map::class.java).map { user ->
                            val login = user?.get("login") as? String
                            if (login != null) {
                                userActivity.registerUser(request.token, login)
                                logger.info("✅ Token valid for user @$login (expires: ${tokenExpiry ?: "never"})")
                            }
                            ValidateTokenResponse(
                                valid = login != null,
                                username = login,
                                message = if (login != null) "Token valid" else "Could not identify user",
                                tokenExpiry = tokenExpiry
                            )
                        }
                    } else {
                        logger.warn("⚠️ Unexpected status from GitHub: $statusCode")
                        reactor.core.publisher.Mono.just(ValidateTokenResponse(
                            valid = false,
                            message = "Unexpected response from GitHub (status $statusCode)"
                        ))
                    }
                }
                .block()

            ResponseEntity.ok(response ?: ValidateTokenResponse(valid = false, message = "No response from GitHub"))
        } catch (e: Exception) {
            logger.warn("❌ Token validation error: ${e.message}")
            ResponseEntity.ok(ValidateTokenResponse(
                valid = false,
                message = "Token validation failed: ${e.message?.take(100) ?: "unknown error"}",
                reason = "error"
            ))
        }
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

