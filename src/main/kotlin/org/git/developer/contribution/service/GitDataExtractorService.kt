package org.git.developer.contribution.service

import org.git.developer.contribution.model.CommitInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Service for extracting Git commit data from local repositories
 */
@Service
class GitDataExtractorService {

    private val logger = LoggerFactory.getLogger(GitDataExtractorService::class.java)

    companion object {
        private const val COMMIT_DELIMITER = "---COMMIT_END---"
        private const val FIELD_DELIMITER = "|||"
    }

    /**
     * Extract all commits from a Git repository
     */
    fun extractCommits(
        repositoryPath: String,
        since: String? = null,
        until: String? = null,
        branch: String? = null,
        excludeMerges: Boolean = true
    ): List<CommitInfo> {
        val repoDir = File(repositoryPath)

        if (!repoDir.exists() || !repoDir.isDirectory) {
            logger.error("Repository path does not exist or is not a directory: $repositoryPath")
            return emptyList()
        }

        // Check for regular repo (.git folder) or bare repo (HEAD file directly in folder)
        val gitDir = File(repoDir, ".git")
        val isBareRepo = File(repoDir, "HEAD").exists() && File(repoDir, "objects").exists()

        if (!gitDir.exists() && !isBareRepo) {
            logger.error("Not a Git repository (no .git folder and not bare): $repositoryPath")
            // List directory contents for debugging
            logger.error("Directory contents: ${repoDir.listFiles()?.map { it.name }}")
            return emptyList()
        }

        logger.info("Detected ${if (isBareRepo) "bare" else "regular"} repository at $repositoryPath")
        logger.info("Options - branch: ${branch ?: "all"}, excludeMerges: $excludeMerges")

        val repoName = repoDir.name

        return try {
            val commits = fetchCommitData(repoDir, since, until, branch, excludeMerges)
            val parsed = parseCommits(commits, repoName)
            logger.info("Parsed ${parsed.size} commits from $repoName")
            parsed
        } catch (e: Exception) {
            logger.error("Error extracting commits from $repositoryPath: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Execute git log command and return raw output
     */
    private fun fetchCommitData(
        repoDir: File,
        since: String?,
        until: String?,
        branch: String? = null,
        excludeMerges: Boolean = true
    ): String {
        // Use format with shortstat for line counts
        // Format: hash|||author|||email|||date|||subject|||parentCount|||
        // %P gives parent hashes - merge commits have 2+ parents
        val formatString = "%H${FIELD_DELIMITER}%an${FIELD_DELIMITER}%ae${FIELD_DELIMITER}%aI${FIELD_DELIMITER}%s${FIELD_DELIMITER}%P${FIELD_DELIMITER}%n"

        val command = mutableListOf(
            "git", "log",
            "--pretty=format:$formatString",
            "--shortstat"
        )

        // Add branch or --all
        if (branch.isNullOrBlank()) {
            command.add("--all")
        } else {
            command.add(branch)
        }

        // Exclude merge commits if requested
        if (excludeMerges) {
            command.add("--no-merges")
        }

        since?.let { command.add("--since=$it") }
        until?.let { command.add("--until=$it") }

        logger.info("Running git command in ${repoDir.path}: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
            .directory(repoDir)
            .redirectErrorStream(true)

        val process = processBuilder.start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            logger.warn("Git command exited with code $exitCode for ${repoDir.path}. Output: ${output.take(500)}")
        } else {
            logger.info("Git log returned ${output.length} characters for ${repoDir.path}")
            if (output.isNotBlank()) {
                logger.debug("First 500 chars: ${output.take(500)}")
            }
        }

        return output
    }

    /**
     * Parse the raw git log output into CommitInfo objects
     */
    private fun parseCommits(rawOutput: String, repositoryName: String): List<CommitInfo> {
        if (rawOutput.isBlank()) {
            logger.warn("Empty git log output for $repositoryName")
            return emptyList()
        }

        val commits = mutableListOf<CommitInfo>()

        // Split by empty lines to separate commits
        val lines = rawOutput.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            // Skip empty lines
            if (line.isBlank()) {
                i++
                continue
            }

            // Check if this is a commit line (contains our delimiter)
            if (line.contains(FIELD_DELIMITER)) {
                val parts = line.split(FIELD_DELIMITER)

                if (parts.size >= 6) {
                    val hash = parts[0].trim()
                    val authorName = parts[1].trim()
                    val authorEmail = parts[2].trim()
                    val dateStr = parts[3].trim()
                    val message = parts[4].trim()
                    val parentHashes = parts[5].trim()

                    // Detect merge commit: has more than 1 parent
                    val isMerge = parentHashes.split(" ").filter { it.isNotBlank() }.size > 1

                    // Look for shortstat on next non-empty line
                    var linesAdded = 0
                    var linesDeleted = 0
                    var filesChanged = 0

                    // Check next lines for stats
                    var j = i + 1
                    while (j < lines.size && j <= i + 3) {
                        val statLine = lines[j].trim()
                        if (statLine.isBlank()) {
                            j++
                            continue
                        }

                        // Parse shortstat: "3 files changed, 10 insertions(+), 5 deletions(-)"
                        if (statLine.contains("changed") || statLine.contains("insertion") || statLine.contains("deletion")) {
                            val filesMatch = Regex("(\\d+) file").find(statLine)
                            val insertMatch = Regex("(\\d+) insertion").find(statLine)
                            val deleteMatch = Regex("(\\d+) deletion").find(statLine)

                            filesChanged = filesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            linesAdded = insertMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            linesDeleted = deleteMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            break
                        }

                        // If we hit another commit line, stop
                        if (statLine.contains(FIELD_DELIMITER)) {
                            break
                        }
                        j++
                    }

                    val dateTime = try {
                        LocalDateTime.parse(dateStr.replace(Regex("[+-]\\d{2}:\\d{2}$"), ""))
                    } catch (e: Exception) {
                        logger.warn("Could not parse date: $dateStr")
                        LocalDateTime.now()
                    }

                    commits.add(
                        CommitInfo(
                            hash = hash,
                            authorName = authorName,
                            authorEmail = authorEmail,
                            date = dateTime,
                            message = message,
                            linesAdded = linesAdded,
                            linesDeleted = linesDeleted,
                            filesChanged = filesChanged,
                            repositoryName = repositoryName,
                            isMerge = isMerge
                        )
                    )
                }
            }
            i++
        }

        val mergeCount = commits.count { it.isMerge }
        logger.info("Parsed ${commits.size} commits ($mergeCount merge commits), total lines added: ${commits.sumOf { it.linesAdded }}, deleted: ${commits.sumOf { it.linesDeleted }}")
        return commits
    }

    /**
     * Extract commits from multiple repositories
     */
    fun extractCommitsFromMultipleRepos(
        repositoryPaths: List<String>,
        since: String? = null,
        until: String? = null,
        branch: String? = null,
        excludeMerges: Boolean = true
    ): List<CommitInfo> {
        return repositoryPaths.flatMap { path ->
            logger.info("Extracting commits from: $path")
            extractCommits(path, since, until, branch, excludeMerges)
        }
    }
}
