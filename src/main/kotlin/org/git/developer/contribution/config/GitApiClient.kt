package org.git.developer.contribution.config

import org.git.developer.contribution.model.GitProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Centralized network layer for all GitHub / GitLab API calls.
 *
 * Every service that needs to talk to a Git provider should obtain a [WebClient]
 * through [createClient] and execute calls through [execute].
 *
 * This class is the **single place** that:
 *   • builds authenticated WebClients (GitHub Bearer / GitLab PRIVATE-TOKEN)
 *   • classifies HTTP errors (401 → [TokenExpiredException], 403+rateLimit → [RateLimitException], etc.)
 *   • logs every classified error with context
 *
 * No service should catch [WebClientResponseException] on its own — let it
 * bubble up; [GlobalExceptionHandler] will translate it into a proper JSON
 * response for the frontend.
 */
@Component
class GitApiClient {

    private val logger = LoggerFactory.getLogger(GitApiClient::class.java)

    companion object {
        const val GITHUB_API_URL = "https://api.github.com"
        const val GITHUB_GRAPHQL_URL = "https://api.github.com/graphql"
        const val GITLAB_API_URL = "https://gitlab.com/api/v4"
        private const val MAX_BUFFER_SIZE = 16 * 1024 * 1024 // 16 MB
    }

    // ─── Client factory ─────────────────────────────────────────

    /** Build an authenticated [WebClient] for the given provider. */
    fun createClient(
        token: String,
        provider: Provider,
        baseUrl: String? = null
    ): WebClient {
        val url = baseUrl ?: when (provider) {
            Provider.GITHUB -> GITHUB_API_URL
            Provider.GITLAB -> GITLAB_API_URL
        }

        val strategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE) }
            .build()

        val builder = WebClient.builder()
            .baseUrl(url)
            .exchangeStrategies(strategies)

        when (provider) {
            Provider.GITHUB -> builder
                .defaultHeader("Authorization", "Bearer $token")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            Provider.GITLAB -> builder
                .defaultHeader("PRIVATE-TOKEN", token)
        }

        return builder.build()
    }

    /** Shortcut: GitHub REST client. */
    fun github(token: String, baseUrl: String? = null): WebClient =
        createClient(token, Provider.GITHUB, baseUrl)

    /** Shortcut: GitHub GraphQL client (same auth, different Content-Type). */
    fun githubGraphQL(token: String): WebClient {
        val strategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE) }
            .build()

        return WebClient.builder()
            .baseUrl(GITHUB_GRAPHQL_URL)
            .exchangeStrategies(strategies)
            .defaultHeader("Authorization", "Bearer $token")
            .defaultHeader("Content-Type", "application/json")
            .build()
    }

    /** Shortcut: GitLab REST client. */
    fun gitlab(token: String, baseUrl: String? = null): WebClient =
        createClient(token, Provider.GITLAB, baseUrl)

    /**
     * Create a REST client based on the model [GitProvider] enum.
     * Services should prefer this over [github]/[gitlab] when they receive
     * a provider from a request object.
     */
    fun forProvider(token: String, provider: GitProvider, baseUrl: String? = null): WebClient =
        when (provider) {
            GitProvider.GITHUB -> github(token, baseUrl)
            GitProvider.GITLAB -> gitlab(token, baseUrl)
        }

    // ─── Execution with error classification ────────────────────

    /**
     * Execute a blocking call and translate HTTP errors into typed exceptions.
     *
     * Usage:
     * ```
     * val repos = gitApiClient.execute("fetch repos") {
     *     client.get().uri("/user/repos").retrieve().bodyToMono<List<…>>().block()
     * }
     * ```
     */
    fun <T> execute(context: String, block: () -> T?): T? {
        return try {
            block()
        } catch (ex: WebClientResponseException) {
            throw classifyAndLog(ex, context)
        }
    }

    /**
     * Same as [execute] but for non-nullable returns — throws on null.
     */
    fun <T : Any> executeRequired(context: String, block: () -> T?): T {
        return execute(context, block)
            ?: throw GitApiException("No response received ($context)")
    }

    // ─── Error classification ───────────────────────────────────

    /**
     * The single place that inspects a [WebClientResponseException],
     * logs meaningful information, and returns a typed exception.
     */
    fun classifyAndLog(ex: WebClientResponseException, context: String): GitApiException {
        val status = ex.statusCode.value()
        val body = ex.responseBodyAsString.take(300)

        return when (status) {
            401 -> {
                logger.error("🔑 [$context] Token rejected (401) — expired or invalid. Body: $body")
                TokenExpiredException("GitHub token expired or invalid. Please login again.", ex)
            }
            403 -> {
                val remaining = ex.headers.getFirst("X-RateLimit-Remaining")
                val reset = ex.headers.getFirst("X-RateLimit-Reset")
                if (remaining == "0") {
                    logger.error("⏱️ [$context] Rate limit exceeded (reset: $reset)")
                    RateLimitException("GitHub API rate limit exceeded. Please wait and try again.", reset, ex)
                } else {
                    logger.error("🚫 [$context] Forbidden (403): $body")
                    ForbiddenException("Access denied. Check token permissions.", ex)
                }
            }
            404 -> {
                logger.warn("🔍 [$context] Not found (404): $body")
                NotFoundException("Resource not found on GitHub.", ex)
            }
            else -> {
                logger.error("❌ [$context] HTTP $status: $body", ex)
                GitApiException("GitHub API error ($status): ${ex.statusText}", ex)
            }
        }
    }

    enum class Provider { GITHUB, GITLAB }
}

// ─── Exception hierarchy ────────────────────────────────────────

/** Base exception for all Git API errors. */
open class GitApiException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** 401 — token expired or invalid. */
class TokenExpiredException(
    message: String = "Token expired or invalid",
    cause: Throwable? = null
) : GitApiException(message, cause)

/** 403 + X-RateLimit-Remaining: 0 */
class RateLimitException(
    message: String = "Rate limit exceeded",
    val resetTimestamp: String? = null,
    cause: Throwable? = null
) : GitApiException(message, cause)

/** 403 — not rate-limit, just forbidden. */
class ForbiddenException(
    message: String = "Access denied",
    cause: Throwable? = null
) : GitApiException(message, cause)

/** 404 — resource not found. */
class NotFoundException(
    message: String = "Not found",
    cause: Throwable? = null
) : GitApiException(message, cause)

