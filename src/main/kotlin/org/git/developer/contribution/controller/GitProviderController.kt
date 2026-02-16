package org.git.developer.contribution.controller

import org.git.developer.contribution.model.*
import org.git.developer.contribution.service.GitProviderService
import org.git.developer.contribution.service.RemoteRepositoryAnalyzerService
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
    private val remoteAnalyzerService: RemoteRepositoryAnalyzerService
) {

    /**
     * List all repositories accessible with the provided token
     *
     * POST /api/git/repositories
     * {
     *   "token": "ghp_xxxx",
     *   "provider": "GITHUB",
     *   "baseUrl": null
     * }
     */
    @PostMapping("/repositories")
    fun listRepositories(@RequestBody request: AuthRequest): ResponseEntity<RepositoryListResponse> {
        val repos = gitProviderService.listRepositories(
            token = request.token,
            provider = request.provider,
            baseUrl = request.baseUrl
        )
        return ResponseEntity.ok(repos)
    }

    /**
     * Analyze selected remote repositories
     *
     * POST /api/git/analyze
     * {
     *   "token": "ghp_xxxx",
     *   "provider": "GITHUB",
     *   "repositoryFullNames": ["owner/repo1", "owner/repo2"],
     *   "startDate": "2025-01-01",
     *   "endDate": "2025-12-31",
     *   "period": "WEEKLY"
     * }
     */
    @PostMapping("/analyze")
    fun analyzeRepositories(@RequestBody request: RemoteAnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
        val result = remoteAnalyzerService.analyzeRemoteRepositories(request)
        return ResponseEntity.ok(result)
    }

    /**
     * Analyze a single repository (for parallel processing)
     *
     * POST /api/git/analyze/single
     * {
     *   "token": "ghp_xxxx",
     *   "provider": "GITHUB",
     *   "repositoryFullName": "owner/repo",
     *   "startDate": "2025-01-01",
     *   "endDate": "2025-12-31",
     *   "period": "WEEKLY"
     * }
     */
    @PostMapping("/analyze/single")
    fun analyzeSingleRepository(@RequestBody request: SingleRepoAnalyzeRequest): ResponseEntity<ContributionAnalysisResponse> {
        val result = remoteAnalyzerService.analyzeSingleRepository(request)
        return ResponseEntity.ok(result)
    }
}

