package org.git.developer.contribution.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Global exception handler for all REST controllers.
 *
 * Catches the typed exceptions produced by [GitApiClient] (and any
 * raw [WebClientResponseException] that slipped through) and returns
 * structured JSON so the frontend can react appropriately.
 */
@ControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    // ── Typed exceptions from GitApiClient ──────────────────────

    @ExceptionHandler(TokenExpiredException::class)
    fun handleTokenExpired(ex: TokenExpiredException): ResponseEntity<Map<String, Any>> {
        logger.warn("🔑 ${ex.message}")
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            mapOf("error" to "token_expired", "message" to (ex.message ?: "Token expired"), "status" to 401)
        )
    }

    @ExceptionHandler(RateLimitException::class)
    fun handleRateLimit(ex: RateLimitException): ResponseEntity<Map<String, Any>> {
        logger.warn("⏱️ ${ex.message} (reset: ${ex.resetTimestamp})")
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
            mapOf(
                "error" to "rate_limited",
                "message" to (ex.message ?: "Rate limit exceeded"),
                "status" to 429,
                "rateLimitReset" to (ex.resetTimestamp ?: "unknown")
            )
        )
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException): ResponseEntity<Map<String, Any>> {
        logger.warn("🚫 ${ex.message}")
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            mapOf("error" to "forbidden", "message" to (ex.message ?: "Access denied"), "status" to 403)
        )
    }

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<Map<String, Any>> {
        logger.warn("🔍 ${ex.message}")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf("error" to "not_found", "message" to (ex.message ?: "Not found"), "status" to 404)
        )
    }

    @ExceptionHandler(GitApiException::class)
    fun handleGitApi(ex: GitApiException): ResponseEntity<Map<String, Any>> {
        logger.error("❌ Git API error: ${ex.message}", ex)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            mapOf("error" to "github_api_error", "message" to (ex.message ?: "Git API error"), "status" to 502)
        )
    }

    // ── Fallback: raw WebClientResponseException (shouldn't happen often) ──

    @ExceptionHandler(WebClientResponseException::class)
    fun handleWebClientResponse(ex: WebClientResponseException): ResponseEntity<Map<String, Any>> {
        // Delegate to GitApiClient's classifier so we get the same typed mapping
        val classified = GitApiClient().classifyAndLog(ex, "unhandled")
        return when (classified) {
            is TokenExpiredException -> handleTokenExpired(classified)
            is RateLimitException    -> handleRateLimit(classified)
            is ForbiddenException    -> handleForbidden(classified)
            is NotFoundException     -> handleNotFound(classified)
            else                     -> handleGitApi(classified)
        }
    }

    // ── Catch-all ───────────────────────────────────────────────

    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception): ResponseEntity<Map<String, Any>> {
        // Walk the cause chain looking for a WebClient or GitApi exception
        val root = generateSequence(ex as Throwable) { it.cause }
            .firstOrNull { it is GitApiException || it is WebClientResponseException }

        if (root is GitApiException) return handleGitApi(root)
        if (root is WebClientResponseException) return handleWebClientResponse(root)

        logger.error("❌ Unhandled: ${ex.message}", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            mapOf("error" to "internal_error", "message" to (ex.message ?: "Unexpected error"), "status" to 500)
        )
    }
}
