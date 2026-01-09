package org.git.developer.contribution.service

import org.git.developer.contribution.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.*

/**
 * Service for aggregating Git contribution data by developer and time period
 */
@Service
class ContributionAggregatorService(
    private val gitDataExtractor: GitDataExtractorService
) {
    private val logger = LoggerFactory.getLogger(ContributionAggregatorService::class.java)

    /**
     * Analyze multiple repositories and return aggregated contribution data
     */
    fun analyzeRepositories(request: AnalyzeRequest): ContributionAnalysisResponse {
        val since = request.startDate?.toString()
        val until = request.endDate?.toString()

        logger.info("Analyzing ${request.repositoryPaths.size} repositories")
        logger.info("Options - branch: ${request.branch ?: "all"}, excludeMerges: ${request.excludeMerges}")

        val allCommits = gitDataExtractor.extractCommitsFromMultipleRepos(
            repositoryPaths = request.repositoryPaths,
            since = since,
            until = until,
            branch = request.branch,
            excludeMerges = request.excludeMerges
        )

        logger.info("Found ${allCommits.size} total commits")

        val filteredCommits = filterCommitsByDate(allCommits, request.startDate, request.endDate)

        val dateRange = calculateDateRange(filteredCommits, request.startDate, request.endDate)
        val developerTimelines = aggregateByDeveloper(filteredCommits, request.period, dateRange)
        val summary = calculateSummary(filteredCommits)

        return ContributionAnalysisResponse(
            analyzedRepositories = request.repositoryPaths,
            dateRange = dateRange,
            period = request.period,
            developers = developerTimelines,
            summary = summary
        )
    }

    /**
     * Get aggregated contribution stats for all developers
     */
    fun getDeveloperContributions(
        repositoryPaths: List<String>,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null
    ): List<DeveloperContribution> {
        val allCommits = gitDataExtractor.extractCommitsFromMultipleRepos(
            repositoryPaths = repositoryPaths,
            since = startDate?.toString(),
            until = endDate?.toString()
        )

        return allCommits
            .groupBy { it.authorEmail.lowercase() }
            .map { (email, commits) ->
                DeveloperContribution(
                    authorName = commits.first().authorName,
                    authorEmail = email,
                    totalCommits = commits.size,
                    totalLinesAdded = commits.sumOf { it.linesAdded },
                    totalLinesDeleted = commits.sumOf { it.linesDeleted },
                    totalFilesChanged = commits.sumOf { it.filesChanged },
                    repositories = commits.map { it.repositoryName }.toSet(),
                    firstCommitDate = commits.minOfOrNull { it.date },
                    lastCommitDate = commits.maxOfOrNull { it.date }
                )
            }
            .sortedByDescending { it.totalCommits }
    }

    private fun filterCommitsByDate(
        commits: List<CommitInfo>,
        startDate: LocalDate?,
        endDate: LocalDate?
    ): List<CommitInfo> {
        return commits.filter { commit ->
            val commitDate = commit.date.toLocalDate()
            (startDate == null || !commitDate.isBefore(startDate)) &&
            (endDate == null || !commitDate.isAfter(endDate))
        }
    }

    private fun calculateDateRange(
        commits: List<CommitInfo>,
        requestedStart: LocalDate?,
        requestedEnd: LocalDate?
    ): DateRange {
        val startDate = requestedStart
            ?: commits.minOfOrNull { it.date.toLocalDate() }
            ?: LocalDate.now().minusMonths(3)

        val endDate = requestedEnd
            ?: commits.maxOfOrNull { it.date.toLocalDate() }
            ?: LocalDate.now()

        return DateRange(startDate, endDate)
    }

    private fun aggregateByDeveloper(
        commits: List<CommitInfo>,
        period: AggregationPeriod,
        dateRange: DateRange
    ): List<DeveloperTimeline> {
        // First, extract nickname for each commit and group by nickname
        // This ensures "levan9999" and "Levan Karanadze" with same email are grouped together
        val commitsWithNickname = commits.map { commit ->
            val nickname = extractNicknameFromEmail(commit.authorEmail)
            Pair(nickname, commit)
        }

        val commitsByNickname = commitsWithNickname.groupBy { it.first }

        return commitsByNickname.map { (nickname, pairs) ->
            val developerCommits = pairs.map { it.second }
            val allNames = developerCommits.map { it.authorName }.toSet()
            val allEmails = developerCommits.map { it.authorEmail.lowercase() }.toSet()
            val primaryEmail = allEmails.first()

            // Use the most "real looking" name - prefer longer names with spaces (likely real names)
            val displayName = allNames
                .sortedByDescending { name ->
                    val hasSpace = if (name.contains(" ")) 10 else 0
                    val length = name.length
                    hasSpace + length
                }
                .first()

            val repositories = developerCommits.map { it.repositoryName }.toSet()

            val dataPoints = generateTimePeriods(dateRange, period).map { (periodLabel, start, end) ->
                val periodCommits = developerCommits.filter { commit ->
                    val commitDate = commit.date.toLocalDate()
                    !commitDate.isBefore(start) && !commitDate.isAfter(end)
                }

                ContributionDataPoint(
                    period = periodLabel,
                    startDate = start,
                    endDate = end,
                    commits = periodCommits.size,
                    linesAdded = periodCommits.sumOf { it.linesAdded },
                    linesDeleted = periodCommits.sumOf { it.linesDeleted },
                    filesChanged = periodCommits.sumOf { it.filesChanged }
                )
            }

            DeveloperTimeline(
                authorName = displayName,
                authorEmail = primaryEmail,
                nickname = nickname,
                emails = allEmails,
                dataPoints = dataPoints,
                repositories = repositories
            )
        }.sortedByDescending { timeline -> timeline.dataPoints.sumOf { it.commits } }
    }

    /**
     * Extract nickname from a single email address
     */
    private fun extractNicknameFromEmail(email: String): String {
        val emailLower = email.lowercase()

        // GitHub noreply email: 126676502+levankiknadze90@users.noreply.github.com
        if (emailLower.contains("users.noreply.github.com")) {
            val match = Regex("\\d+\\+(.+)@").find(emailLower)
            if (match != null) {
                return "@${match.groupValues[1]}"
            }
        }

        // Regular email - extract username part
        val username = emailLower.substringBefore("@")
        return "@$username"
    }

    /**
     * Extract nickname from email addresses (for display - picks best one)
     */
    private fun extractNickname(emails: Set<String>): String {
        // First try to find GitHub noreply email which contains username
        val githubNoReply = emails.find { it.contains("users.noreply.github.com") }
        if (githubNoReply != null) {
            // Format: 126676502+levankiknadze90@users.noreply.github.com
            val match = Regex("\\d+\\+(.+)@").find(githubNoReply)
            if (match != null) {
                return "@${match.groupValues[1]}"
            }
        }

        // Otherwise extract from regular email
        val regularEmail = emails.firstOrNull { !it.contains("noreply") }
        if (regularEmail != null) {
            val username = regularEmail.substringBefore("@")
            return "@$username"
        }

        // Fallback to first email's username
        return "@${emails.first().substringBefore("@")}"
    }

    private fun generateTimePeriods(
        dateRange: DateRange,
        period: AggregationPeriod
    ): List<Triple<String, LocalDate, LocalDate>> {
        val periods = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        var current = dateRange.startDate

        when (period) {
            AggregationPeriod.DAILY -> {
                while (!current.isAfter(dateRange.endDate)) {
                    val label = current.toString()
                    periods.add(Triple(label, current, current))
                    current = current.plusDays(1)
                }
            }
            AggregationPeriod.WEEKLY -> {
                // Start from beginning of week
                val weekFields = WeekFields.of(Locale.getDefault())
                current = current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

                while (!current.isAfter(dateRange.endDate)) {
                    val weekNum = current.get(weekFields.weekOfWeekBasedYear())
                    val year = current.get(weekFields.weekBasedYear())
                    val label = "$year-W${weekNum.toString().padStart(2, '0')}"
                    val weekEnd = current.plusDays(6)
                    periods.add(Triple(label, current, weekEnd))
                    current = current.plusWeeks(1)
                }
            }
            AggregationPeriod.MONTHLY -> {
                current = current.withDayOfMonth(1)

                while (!current.isAfter(dateRange.endDate)) {
                    val label = "${current.year}-${current.monthValue.toString().padStart(2, '0')}"
                    val monthEnd = current.with(TemporalAdjusters.lastDayOfMonth())
                    periods.add(Triple(label, current, monthEnd))
                    current = current.plusMonths(1)
                }
            }
        }

        return periods
    }

    private fun calculateSummary(commits: List<CommitInfo>): AnalysisSummary {
        val developerCounts = commits.groupBy { it.authorEmail.lowercase() }
            .mapValues { it.value.size }

        val repoCounts = commits.groupBy { it.repositoryName }
            .mapValues { it.value.size }

        return AnalysisSummary(
            totalDevelopers = developerCounts.size,
            totalCommits = commits.size,
            totalLinesAdded = commits.sumOf { it.linesAdded },
            totalLinesDeleted = commits.sumOf { it.linesDeleted },
            mostActiveAuthor = developerCounts.maxByOrNull { it.value }?.key,
            mostActiveRepository = repoCounts.maxByOrNull { it.value }?.key
        )
    }
}

