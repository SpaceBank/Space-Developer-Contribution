package org.git.developer.contribution.service

import org.git.developer.contribution.config.GitApiClient
import org.git.developer.contribution.model.GitProvider
import org.git.developer.contribution.model.PullRequestInfo
import org.git.developer.contribution.model.RepositoryInfo
import org.git.developer.contribution.model.RepositoryListResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Service for interacting with GitHub/GitLab APIs.
 * All HTTP calls go through [GitApiClient] which handles error classification centrally.
 */
@Service
class GitProviderService(
    private val api: GitApiClient
) {
    private val logger = LoggerFactory.getLogger(GitProviderService::class.java)

    fun listRepositories(
        token: String,
        provider: GitProvider,
        baseUrl: String? = null
    ): RepositoryListResponse {
        return when (provider) {
            GitProvider.GITHUB -> listGitHubRepositories(token, baseUrl)
            GitProvider.GITLAB -> listGitLabRepositories(token, baseUrl)
        }
    }

    private fun listGitHubRepositories(token: String, baseUrl: String?): RepositoryListResponse {
        val client = api.github(token, baseUrl)
        val repos = mutableListOf<RepositoryInfo>()
        var page = 1
        var hasMore = true

        while (hasMore && page <= 10) {
            val response = api.execute("fetch GitHub repos page $page") {
                client.get()
                    .uri("/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()
            }

            if (response.isNullOrEmpty()) {
                hasMore = false
            } else {
                repos.addAll(response.map { repo ->
                    val owner = repo["owner"] as? Map<*, *>
                    RepositoryInfo(
                        id = (repo["id"] as Number).toLong(),
                        name = repo["name"] as String,
                        fullName = repo["full_name"] as String,
                        description = repo["description"] as? String,
                        cloneUrl = repo["clone_url"] as String,
                        sshUrl = repo["ssh_url"] as String,
                        defaultBranch = repo["default_branch"] as? String ?: "main",
                        isPrivate = repo["private"] as? Boolean ?: false,
                        owner = owner?.get("login") as? String ?: "",
                        provider = GitProvider.GITHUB
                    )
                })
                page++
                if (response.size < 100) hasMore = false
            }
        }

        logger.info("Found ${repos.size} GitHub repositories")
        return RepositoryListResponse(
            repositories = repos.sortedByDescending { it.name },
            totalCount = repos.size
        )
    }

    private fun listGitLabRepositories(token: String, baseUrl: String?): RepositoryListResponse {
        val client = api.gitlab(token, baseUrl)
        val repos = mutableListOf<RepositoryInfo>()
        var page = 1
        var hasMore = true

        while (hasMore && page <= 10) {
            val response = api.execute("fetch GitLab repos page $page") {
                client.get()
                    .uri("/projects?membership=true&per_page=100&page=$page&order_by=updated_at")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()
            }

            if (response.isNullOrEmpty()) {
                hasMore = false
            } else {
                repos.addAll(response.map { repo ->
                    val namespace = repo["namespace"] as? Map<*, *>
                    RepositoryInfo(
                        id = (repo["id"] as Number).toLong(),
                        name = repo["name"] as String,
                        fullName = repo["path_with_namespace"] as String,
                        description = repo["description"] as? String,
                        cloneUrl = repo["http_url_to_repo"] as String,
                        sshUrl = repo["ssh_url_to_repo"] as String,
                        defaultBranch = repo["default_branch"] as? String ?: "main",
                        isPrivate = (repo["visibility"] as? String) == "private",
                        owner = namespace?.get("path") as? String ?: "",
                        provider = GitProvider.GITLAB
                    )
                })
                page++
                if (response.size < 100) hasMore = false
            }
        }

        logger.info("Found ${repos.size} GitLab repositories")
        return RepositoryListResponse(
            repositories = repos.sortedByDescending { it.name },
            totalCount = repos.size
        )
    }

    fun cloneRepository(
        cloneUrl: String,
        token: String,
        provider: GitProvider,
        targetDir: java.io.File
    ): Boolean {
        return try {
            val authenticatedUrl = when (provider) {
                GitProvider.GITHUB -> cloneUrl.replace("https://", "https://$token@")
                GitProvider.GITLAB -> cloneUrl.replace("https://", "https://oauth2:$token@")
            }
            logger.info("Cloning repository from $cloneUrl to ${targetDir.absolutePath}")
            val processBuilder = ProcessBuilder(
                "git", "clone", "--no-checkout", authenticatedUrl, targetDir.absolutePath
            ).redirectErrorStream(true)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.error("Git clone failed with exit code $exitCode: $output")
                false
            } else {
                logger.info("Successfully cloned to ${targetDir.absolutePath}")
                true
            }
        } catch (e: Exception) {
            logger.error("Error cloning repository: ${e.message}", e)
            false
        }
    }

    fun fetchMergedPullRequests(
        token: String,
        provider: GitProvider,
        repositoryFullName: String,
        since: String? = null,
        baseUrl: String? = null
    ): List<PullRequestInfo> {
        return when (provider) {
            GitProvider.GITHUB -> fetchGitHubPullRequests(token, repositoryFullName, since, baseUrl)
            GitProvider.GITLAB -> fetchGitLabMergeRequests(token, repositoryFullName, since, baseUrl)
        }
    }

    private fun fetchGitHubPullRequests(
        token: String,
        repositoryFullName: String,
        since: String?,
        baseUrl: String?
    ): List<PullRequestInfo> {
        val client = api.github(token, baseUrl)
        val prs = mutableListOf<PullRequestInfo>()
        var page = 1
        var hasMore = true

        while (hasMore && page <= 10) {
            val response = api.execute("fetch GitHub PRs for $repositoryFullName page $page") {
                client.get()
                    .uri("/repos/$repositoryFullName/pulls?state=closed&per_page=100&page=$page&sort=updated&direction=desc")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()
            }

            if (response.isNullOrEmpty()) {
                hasMore = false
            } else {
                response.forEach { pr ->
                    val mergedAt = pr["merged_at"] as? String
                    if (mergedAt != null && (since == null || mergedAt >= since)) {
                        val user = pr["user"] as? Map<*, *>
                        prs.add(
                            PullRequestInfo(
                                id = (pr["id"] as Number).toLong(),
                                number = (pr["number"] as Number).toInt(),
                                title = pr["title"] as? String ?: "",
                                state = "merged",
                                createdAt = pr["created_at"] as? String ?: "",
                                mergedAt = mergedAt,
                                authorName = user?.get("login") as? String ?: "unknown",
                                repositoryFullName = repositoryFullName
                            )
                        )
                    }
                }
                page++
                if (response.size < 100) hasMore = false
            }
        }

        logger.info("Found ${prs.size} merged PRs for $repositoryFullName")
        return prs
    }

    private fun fetchGitLabMergeRequests(
        token: String,
        repositoryFullName: String,
        since: String?,
        baseUrl: String?
    ): List<PullRequestInfo> {
        val client = api.gitlab(token, baseUrl)
        val prs = mutableListOf<PullRequestInfo>()
        val encodedPath = repositoryFullName.replace("/", "%2F")
        var page = 1
        var hasMore = true

        while (hasMore && page <= 10) {
            val response = api.execute("fetch GitLab MRs for $repositoryFullName page $page") {
                client.get()
                    .uri("/projects/$encodedPath/merge_requests?state=merged&per_page=100&page=$page")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()
            }

            if (response.isNullOrEmpty()) {
                hasMore = false
            } else {
                response.forEach { mr ->
                    val mergedAt = mr["merged_at"] as? String
                    if (mergedAt != null && (since == null || mergedAt >= since)) {
                        val author = mr["author"] as? Map<*, *>
                        prs.add(
                            PullRequestInfo(
                                id = (mr["id"] as Number).toLong(),
                                number = (mr["iid"] as Number).toInt(),
                                title = mr["title"] as? String ?: "",
                                state = "merged",
                                createdAt = mr["created_at"] as? String ?: "",
                                mergedAt = mergedAt,
                                authorName = author?.get("username") as? String ?: "unknown",
                                repositoryFullName = repositoryFullName
                            )
                        )
                    }
                }
                page++
                if (response.size < 100) hasMore = false
            }
        }

        logger.info("Found ${prs.size} merged MRs for $repositoryFullName")
        return prs
    }
}
