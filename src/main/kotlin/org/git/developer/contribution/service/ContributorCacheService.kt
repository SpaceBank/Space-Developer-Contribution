package org.git.developer.contribution.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache service to store contributor information from login session.
 * This allows services to look up contributor display names without
 * needing to pass the data through every method call.
 *
 * Cache is persisted to file so it survives server restarts.
 */
@Service
class ContributorCacheService {

    private val logger = LoggerFactory.getLogger(ContributorCacheService::class.java)

    // ObjectMapper for JSON serialization
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    // Cache file location
    private val cacheFile = File(System.getProperty("user.home"), ".git-contributor-cache.json")

    // Map of login (lowercase) to ContributorInfo
    private val contributorsByLogin = ConcurrentHashMap<String, CachedContributor>()

    // Map of email username (lowercase) to login
    private val emailToLogin = ConcurrentHashMap<String, String>()

    /**
     * Load cache from file on startup
     */
    @PostConstruct
    fun loadFromFile() {
        try {
            if (cacheFile.exists()) {
                val data: List<CachedContributor> = objectMapper.readValue(cacheFile)
                data.forEach { cached ->
                    contributorsByLogin[cached.login.lowercase()] = cached
                    if (!cached.email.isNullOrBlank()) {
                        emailToLogin[cached.email.substringBefore("@").lowercase()] = cached.login.lowercase()
                    }
                }
                logger.info("✅ Loaded ${contributorsByLogin.size} contributors from cache file")
            } else {
                logger.info("No cache file found, starting with empty cache")
            }
        } catch (e: Exception) {
            logger.warn("Could not load cache from file: ${e.message}")
        }
    }

    /**
     * Save cache to file
     */
    private fun saveToFile() {
        try {
            val data = contributorsByLogin.values.toList()
            objectMapper.writeValue(cacheFile, data)
            logger.info("💾 Saved ${data.size} contributors to cache file")
        } catch (e: Exception) {
            logger.warn("Could not save cache to file: ${e.message}")
        }
    }

    /**
     * Store contributors from session data
     */
    fun storeContributors(contributors: List<Map<String, Any?>>) {
        logger.info("Storing ${contributors.size} contributors in cache")

        var storedCount = 0
        var withNameCount = 0

        contributors.forEach { contributor ->
            val login = (contributor["login"] as? String)?.lowercase() ?: return@forEach
            val name = contributor["name"] as? String

            val cached = CachedContributor(
                login = login,
                displayName = name,
                avatarUrl = contributor["avatarUrl"] as? String ?: contributor["avatar_url"] as? String,
                profileUrl = contributor["profileUrl"] as? String ?: contributor["html_url"] as? String,
                // Extended fields from GitHub API
                id = (contributor["id"] as? Number)?.toLong(),
                nodeId = contributor["node_id"] as? String,
                gravatarId = contributor["gravatar_id"] as? String,
                type = contributor["type"] as? String,
                siteAdmin = contributor["site_admin"] as? Boolean,
                company = contributor["company"] as? String,
                blog = contributor["blog"] as? String,
                location = contributor["location"] as? String,
                email = contributor["email"] as? String,
                hireable = contributor["hireable"] as? Boolean,
                bio = contributor["bio"] as? String,
                twitterUsername = contributor["twitter_username"] as? String,
                publicRepos = (contributor["public_repos"] as? Number)?.toInt(),
                publicGists = (contributor["public_gists"] as? Number)?.toInt(),
                followers = (contributor["followers"] as? Number)?.toInt(),
                following = (contributor["following"] as? Number)?.toInt(),
                createdAt = contributor["created_at"] as? String,
                updatedAt = contributor["updated_at"] as? String
            )

            contributorsByLogin[login] = cached

            // Also map by email if available
            if (!cached.email.isNullOrBlank()) {
                val emailUsername = cached.email.substringBefore("@").lowercase()
                emailToLogin[emailUsername] = login
            }

            storedCount++

            if (!name.isNullOrBlank() && name != login) {
                withNameCount++
            }
        }

        logger.info("Cached $storedCount contributors ($withNameCount with display names)")

        if (contributorsByLogin.isNotEmpty()) {
            val samples = contributorsByLogin.entries.take(3).map {
                "${it.key} -> name:'${it.value.displayName ?: "?"}', company:'${it.value.company ?: "?"}', location:'${it.value.location ?: "?"}'"
            }
            logger.info("Sample entries: $samples")
        }

        // Persist to file so cache survives server restarts
        saveToFile()
    }

    /**
     * Get display name for a contributor
     * Returns displayName if available, otherwise login, otherwise null
     */
    fun getDisplayName(contributor: CachedContributor?): String? {
        if (contributor == null) {
            return null
        }
        // Return displayName if it exists and is different from login, otherwise return login
        return if (!contributor.displayName.isNullOrBlank() && contributor.displayName != contributor.login) {
            contributor.displayName
        } else {
            contributor.login
        }
    }

    /**
     * Get display name by username/login
     * Returns displayName if found, otherwise returns the input username
     */
    fun getDisplayNameByUsername(userName: String): String {
        val contributor = contributorsByLogin[userName.lowercase()]
        return getDisplayName(contributor) ?: userName
    }


    /**
     * Get display name by email address
     * Searches by full email or email username (part before @)
     * Returns displayName if found, otherwise returns null
     */
    fun getDisplayNameByEmail(email: String): String? {
        if (email.isBlank()) return null

        val emailLower = email.lowercase()
        val emailUsername = emailLower.substringBefore("@")

        // Strategy 1: Find by exact email match in contributor data
        var contributor = contributorsByLogin.values.find {
            it.email?.lowercase() == emailLower
        }

        // Strategy 2: Try email username as login
        if (contributor == null) {
            contributor = contributorsByLogin[emailUsername]
        }

        // Strategy 3: Check emailToLogin map
        if (contributor == null) {
            val loginFromEmail = emailToLogin[emailUsername]
            if (loginFromEmail != null) {
                contributor = contributorsByLogin[loginFromEmail]
            }
        }

        val result = getDisplayName(contributor)
        if (result != null) {
            logger.debug("Found display name for email '$email': $result")
        }
        return result
    }

    /**
     * Try to find display name by various matching strategies
     */
    fun findDisplayName(nickname: String, emails: Set<String>): String? {
        // Strategy 1: Direct login match
        val cleanNickname = nickname.removePrefix("@").lowercase()
        var result = contributorsByLogin[cleanNickname]?.displayName

        if (result != null && result != cleanNickname) {
            logger.debug("Matched '$nickname' by login -> '$result'")
            return result
        }

        // Strategy 2: Email username match
        for (email in emails) {
            val emailUsername = email.substringBefore("@").lowercase()
            result = contributorsByLogin[emailUsername]?.displayName
            if (result != null && result != emailUsername) {
                logger.debug("Matched '$nickname' by email username '$emailUsername' -> '$result'")
                return result
            }
        }

        return null
    }

    /**
     * Get all cached contributors as a map (login -> displayName)
     */
    fun getContributorsMap(): Map<String, String> {
        return contributorsByLogin
            .filter { !it.value.displayName.isNullOrBlank() && it.value.displayName != it.key }
            .mapValues { it.value.displayName!! }
    }

    /**
     * Check if cache has data
     */
    fun hasData(): Boolean = contributorsByLogin.isNotEmpty()

    /**
     * Get cache size
     */
    fun size(): Int = contributorsByLogin.size

    /**
     * Clear the cache
     */
    fun clear() {
        contributorsByLogin.clear()
        emailToLogin.clear()
        logger.info("Contributor cache cleared")
    }
}

/**
 * Cached contributor data - stores full GitHub/GitLab user profile
 */
data class CachedContributor(
    val login: String,
    val displayName: String?,
    val avatarUrl: String?,
    val profileUrl: String?,
    // Extended GitHub user profile fields
    val id: Long? = null,
    val nodeId: String? = null,
    val gravatarId: String? = null,
    val type: String? = null,  // "User" or "Organization"
    val siteAdmin: Boolean? = null,
    val company: String? = null,
    val blog: String? = null,
    val location: String? = null,
    val email: String? = null,
    val hireable: Boolean? = null,
    val bio: String? = null,
    val twitterUsername: String? = null,
    val publicRepos: Int? = null,
    val publicGists: Int? = null,
    val followers: Int? = null,
    val following: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

