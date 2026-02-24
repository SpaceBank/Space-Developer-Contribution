package org.git.developer.contribution.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for tracking user activity and providing structured logging.
 * Resolves GitHub token → username using a local cache (no external API calls).
 * The username is registered during login/session-store and then reused for all subsequent requests.
 */
@Service
class UserActivityLogger {

    private val logger = LoggerFactory.getLogger(UserActivityLogger::class.java)

    // Cache: token-prefix → username (never log full tokens)
    private val tokenToUser = ConcurrentHashMap<String, String>()

    /**
     * Resolve a GitHub token to a username from local cache only.
     * Does NOT make any external API calls.
     * Returns "unknown" if the token hasn't been registered yet.
     */
    fun resolveUser(token: String?): String {
        if (token.isNullOrBlank()) return "anonymous"
        return tokenToUser[tokenPrefix(token)] ?: "unknown"
    }

    /**
     * Register a known user for a token (called after successful login or session restore)
     */
    fun registerUser(token: String, username: String) {
        tokenToUser[tokenPrefix(token)] = username
        logger.info("🔐 Registered user @$username for token ${tokenPrefix(token)}***")
    }

    // ─── Structured log methods ───

    fun logLogin(username: String) {
        logger.info("🔐 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        logger.info("🔐 USER LOGIN: @$username")
        logger.info("🔐 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    fun logAction(username: String, action: String, details: String = "") {
        val detailStr = if (details.isNotBlank()) " | $details" else ""
        logger.info("👤 [@$username] $action$detailStr")
    }

    fun logAnalysisStart(username: String, analysisType: String, repos: List<String>, extraInfo: String = "") {
        logger.info("📊 ═══════════════════════════════════════════════")
        logger.info("📊 [@$username] ANALYSIS START: $analysisType")
        logger.info("📊   Repositories (${repos.size}): ${repos.joinToString(", ")}")
        if (extraInfo.isNotBlank()) logger.info("📊   $extraInfo")
        logger.info("📊 ═══════════════════════════════════════════════")
    }

    fun logAnalysisComplete(username: String, analysisType: String, durationMs: Long, summary: String = "") {
        logger.info("✅ ═══════════════════════════════════════════════")
        logger.info("✅ [@$username] ANALYSIS COMPLETE: $analysisType")
        logger.info("✅   Duration: ${formatDuration(durationMs)}")
        if (summary.isNotBlank()) logger.info("✅   $summary")
        logger.info("✅ ═══════════════════════════════════════════════")
    }

    fun logAnalysisError(username: String, analysisType: String, error: Exception) {
        logger.error("❌ ═══════════════════════════════════════════════")
        logger.error("❌ [@$username] ANALYSIS FAILED: $analysisType")
        logger.error("❌   Error: ${error.message}")
        logger.error("❌ ═══════════════════════════════════════════════", error)
    }

    fun logApiCall(username: String, endpoint: String, details: String = "") {
        val detailStr = if (details.isNotBlank()) " | $details" else ""
        logger.info("🌐 [@$username] API: $endpoint$detailStr")
    }

    fun logSessionStore(username: String, contributorCount: Int) {
        logger.info("💾 [@$username] Session stored: $contributorCount contributors cached")
    }

    // ─── Helpers ───

    private fun tokenPrefix(token: String): String {
        return if (token.length > 8) token.substring(0, 8) else token
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
            else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
        }
    }
}

