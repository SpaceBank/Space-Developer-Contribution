package org.git.developer.contribution.service

import org.git.developer.contribution.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.*

/**
 * Service for calculating engineering metrics
 */
@Service
class MetricsService(
    private val gitProviderService: GitProviderService
) {
    private val logger = LoggerFactory.getLogger(MetricsService::class.java)

    companion object {
        // Metric thresholds in hours (Elite, Good, Fair boundaries)
        // Values beyond Fair are "Needs Focus"

        // Coding Time: < 54 mins (0.9h), 54 mins - 4h, 5 - 23h, > 23h
        val CODING_TIME_THRESHOLDS = listOf(0.9, 4.0, 23.0)

        // Pickup Time: < 1h, 1 - 4h, 5 - 16h, > 16h
        val PICKUP_TIME_THRESHOLDS = listOf(1.0, 4.0, 16.0)

        // Approve Time: < 10h, 10 - 22h, 23 - 42h, > 42h
        val APPROVE_TIME_THRESHOLDS = listOf(10.0, 22.0, 42.0)

        // Merge Time: < 1h, 1 - 3h, 4 - 16h, > 16h
        val MERGE_TIME_THRESHOLDS = listOf(1.0, 3.0, 16.0)

        // Review Time: < 3h, 3 - 14h, 15 - 24h, > 24h
        val REVIEW_TIME_THRESHOLDS = listOf(3.0, 14.0, 24.0)

        // Cycle Time: < 25h, 25 - 72h, 73 - 161h, > 161h
        val CYCLE_TIME_THRESHOLDS = listOf(25.0, 72.0, 161.0)

        // Merge Frequency (per dev/week): > 2.0, 2 - 1.2, 1.2 - 0.66, < 0.66
        // Note: Higher is better, so thresholds are reversed
        val MERGE_FREQUENCY_THRESHOLDS = listOf(2.0, 1.2, 0.66)

        // PR Size (lines): < 100, 100 - 155, 156 - 228, > 228
        val PR_SIZE_THRESHOLDS = listOf(100.0, 155.0, 228.0)
    }

    /**
     * Analyze repositories and calculate metrics
     */
    fun analyzeMetrics(request: MetricsAnalyzeRequest): MetricsAnalysisResponse {
        logger.info("Analyzing metrics for ${request.repositoryFullNames.size} repositories")

        val startDate = request.startDate?.let { LocalDate.parse(it) }
            ?: LocalDate.now().minusMonths(3)
        val endDate = request.endDate?.let { LocalDate.parse(it) }
            ?: LocalDate.now()

        // Fetch PR data from all repositories
        val allPRData = mutableListOf<PRDetailedInfo>()

        for (repoFullName in request.repositoryFullNames) {
            logger.info("Fetching PR data for $repoFullName")
            val prData = fetchPRDetailedData(
                token = request.token,
                provider = request.provider,
                repositoryFullName = repoFullName,
                since = startDate.toString(),
                baseUrl = request.baseUrl
            )
            allPRData.addAll(prData)
        }

        logger.info("Fetched ${allPRData.size} PRs with detailed data")

        // Filter PRs by date range
        val filteredPRs = allPRData.filter { pr ->
            pr.mergedAt != null && pr.mergedAt >= startDate.toString() && pr.mergedAt <= endDate.toString()
        }

        logger.info("Filtered to ${filteredPRs.size} merged PRs in date range")

        // Calculate number of weeks in range
        val weeksInRange = ChronoUnit.WEEKS.between(startDate, endDate).coerceAtLeast(1)

        // Calculate team metrics
        val teamMetrics = calculateTeamMetrics(filteredPRs, weeksInRange)

        // Calculate per-developer metrics
        val developerMetrics = calculateDeveloperMetrics(filteredPRs, weeksInRange)

        // Calculate weekly trend
        val weeklyTrend = calculateWeeklyTrend(filteredPRs, startDate, endDate)

        return MetricsAnalysisResponse(
            analyzedRepositories = request.repositoryFullNames,
            dateRange = DateRange(startDate, endDate),
            teamMetrics = teamMetrics,
            developerMetrics = developerMetrics,
            weeklyTrend = weeklyTrend
        )
    }

    /**
     * Fetch detailed PR data including review times
     */
    private fun fetchPRDetailedData(
        token: String,
        provider: GitProvider,
        repositoryFullName: String,
        since: String?,
        baseUrl: String?
    ): List<PRDetailedInfo> {
        return when (provider) {
            GitProvider.GITHUB -> fetchGitHubPRDetails(token, repositoryFullName, since, baseUrl)
            GitProvider.GITLAB -> fetchGitLabMRDetails(token, repositoryFullName, since, baseUrl)
        }
    }

    private fun fetchGitHubPRDetails(
        token: String,
        repositoryFullName: String,
        since: String?,
        baseUrl: String?
    ): List<PRDetailedInfo> {
        val apiUrl = baseUrl ?: "https://api.github.com"

        val exchangeStrategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()

        val webClient = WebClient.builder()
            .baseUrl(apiUrl)
            .exchangeStrategies(exchangeStrategies)
            .defaultHeader("Authorization", "Bearer $token")
            .defaultHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val prList = mutableListOf<PRDetailedInfo>()
        var page = 1
        var hasMore = true

        while (hasMore && page <= 10) {
            try {
                val response = webClient.get()
                    .uri("/repos/$repositoryFullName/pulls?state=closed&per_page=100&page=$page&sort=updated&direction=desc")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (response.isNullOrEmpty()) {
                    hasMore = false
                } else {
                    for (pr in response) {
                        val mergedAt = pr["merged_at"] as? String ?: continue

                        // Filter by since date
                        if (since != null && mergedAt < since) continue

                        val prNumber = (pr["number"] as Number).toInt()
                        val user = pr["user"] as? Map<*, *>
                        val authorLogin = user?.get("login") as? String ?: "unknown"

                        // Fetch additional PR details
                        val prDetails = fetchGitHubPRAdditionalDetails(
                            webClient, repositoryFullName, prNumber, pr, authorLogin, mergedAt
                        )

                        if (prDetails != null) {
                            prList.add(prDetails)
                        }
                    }
                    page++
                    if (response.size < 100) hasMore = false
                }
            } catch (e: Exception) {
                logger.error("Error fetching GitHub PRs for $repositoryFullName: ${e.message}")
                hasMore = false
            }
        }

        return prList
    }

    private fun fetchGitHubPRAdditionalDetails(
        webClient: WebClient,
        repositoryFullName: String,
        prNumber: Int,
        pr: Map<String, Any?>,
        authorLogin: String,
        mergedAt: String
    ): PRDetailedInfo? {
        return try {
            val createdAt = pr["created_at"] as? String ?: return null

            // Fetch individual PR to get additions/deletions (not available in list response)
            var additions = 0
            var deletions = 0
            try {
                val prDetail = webClient.get()
                    .uri("/repos/$repositoryFullName/pulls/$prNumber")
                    .retrieve()
                    .bodyToMono<Map<String, Any?>>()
                    .block()

                if (prDetail != null) {
                    additions = (prDetail["additions"] as? Number)?.toInt() ?: 0
                    deletions = (prDetail["deletions"] as? Number)?.toInt() ?: 0
                }
            } catch (e: Exception) {
                logger.debug("Could not fetch PR details for #$prNumber: ${e.message}")
            }
            val prSize = additions + deletions

            // Fetch commits to get first commit time
            var firstCommitTime: String? = null
            try {
                val commits = webClient.get()
                    .uri("/repos/$repositoryFullName/pulls/$prNumber/commits?per_page=100")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (!commits.isNullOrEmpty()) {
                    val firstCommit = commits.first()
                    val commitData = firstCommit["commit"] as? Map<*, *>
                    val author = commitData?.get("author") as? Map<*, *>
                    firstCommitTime = author?.get("date") as? String
                }
            } catch (e: Exception) {
                logger.debug("Could not fetch commits for PR #$prNumber: ${e.message}")
            }

            // Fetch reviews to get review times
            var firstReviewTime: String? = null
            var firstApprovalTime: String? = null
            var firstCommentTime: String? = null

            try {
                val reviews = webClient.get()
                    .uri("/repos/$repositoryFullName/pulls/$prNumber/reviews")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (!reviews.isNullOrEmpty()) {
                    // First review (any type) - this is when someone started reviewing
                    val firstReview = reviews.minByOrNull { it["submitted_at"] as? String ?: "z" }
                    firstReviewTime = firstReview?.get("submitted_at") as? String
                    firstCommentTime = firstReviewTime

                    // First approval
                    val approvals = reviews.filter { it["state"] == "APPROVED" }
                    if (approvals.isNotEmpty()) {
                        val firstApproval = approvals.minByOrNull { it["submitted_at"] as? String ?: "z" }
                        firstApprovalTime = firstApproval?.get("submitted_at") as? String
                        logger.debug("PR #$prNumber: found ${approvals.size} approvals, first at $firstApprovalTime")
                    }
                }
            } catch (e: Exception) {
                logger.debug("Could not fetch reviews for PR #$prNumber: ${e.message}")
            }

            // Also try to get comments (review comments may come before formal reviews)
            try {
                val comments = webClient.get()
                    .uri("/repos/$repositoryFullName/pulls/$prNumber/comments?per_page=1")
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block()

                if (!comments.isNullOrEmpty()) {
                    val firstComment = comments.first()
                    val commentTime = firstComment["created_at"] as? String
                    if (commentTime != null && (firstCommentTime == null || commentTime < firstCommentTime)) {
                        firstCommentTime = commentTime
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Use firstCommentTime if we have it and it's earlier than firstReviewTime
            if (firstCommentTime != null && (firstReviewTime == null || firstCommentTime < firstReviewTime)) {
                firstReviewTime = firstCommentTime
            }

            logger.debug("PR #$prNumber: size=$prSize, firstCommit=$firstCommitTime, firstReview=$firstReviewTime, firstApproval=$firstApprovalTime")

            PRDetailedInfo(
                prNumber = prNumber,
                title = pr["title"] as? String ?: "",
                authorLogin = authorLogin,
                repositoryFullName = repositoryFullName,
                createdAt = createdAt,
                mergedAt = mergedAt,
                firstCommitTime = firstCommitTime,
                firstReviewTime = firstReviewTime,
                firstApprovalTime = firstApprovalTime,
                additions = additions,
                deletions = deletions,
                prSize = prSize
            )
        } catch (e: Exception) {
            logger.error("Error fetching PR details for #$prNumber: ${e.message}")
            null
        }
    }

    private fun fetchGitLabMRDetails(
        token: String,
        repositoryFullName: String,
        since: String?,
        baseUrl: String?
    ): List<PRDetailedInfo> {
        // Simplified GitLab implementation
        val apiUrl = baseUrl ?: "https://gitlab.com/api/v4"

        val exchangeStrategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()

        val webClient = WebClient.builder()
            .baseUrl(apiUrl)
            .exchangeStrategies(exchangeStrategies)
            .defaultHeader("PRIVATE-TOKEN", token)
            .build()

        val encodedPath = repositoryFullName.replace("/", "%2F")
        val prList = mutableListOf<PRDetailedInfo>()

        try {
            val response = webClient.get()
                .uri("/projects/$encodedPath/merge_requests?state=merged&per_page=100")
                .retrieve()
                .bodyToMono<List<Map<String, Any?>>>()
                .block() ?: emptyList()

            for (mr in response) {
                val mergedAt = mr["merged_at"] as? String ?: continue
                if (since != null && mergedAt < since) continue

                val author = mr["author"] as? Map<*, *>

                prList.add(PRDetailedInfo(
                    prNumber = (mr["iid"] as Number).toInt(),
                    title = mr["title"] as? String ?: "",
                    authorLogin = author?.get("username") as? String ?: "unknown",
                    repositoryFullName = repositoryFullName,
                    createdAt = mr["created_at"] as? String ?: "",
                    mergedAt = mergedAt,
                    firstCommitTime = null,
                    firstReviewTime = null,
                    firstApprovalTime = null,
                    additions = 0,
                    deletions = 0,
                    prSize = 0
                ))
            }
        } catch (e: Exception) {
            logger.error("Error fetching GitLab MRs: ${e.message}")
        }

        return prList
    }

    /**
     * Calculate team-wide aggregated metrics
     */
    private fun calculateTeamMetrics(prs: List<PRDetailedInfo>, weeksInRange: Long): TeamMetrics {
        if (prs.isEmpty()) {
            return TeamMetrics(
                codingTime = createMetricValue(0.0, "hours", CODING_TIME_THRESHOLDS, false),
                pickupTime = createMetricValue(0.0, "hours", PICKUP_TIME_THRESHOLDS, false),
                approveTime = createMetricValue(0.0, "hours", APPROVE_TIME_THRESHOLDS, false),
                mergeTime = createMetricValue(0.0, "hours", MERGE_TIME_THRESHOLDS, false),
                reviewTime = createMetricValue(0.0, "hours", REVIEW_TIME_THRESHOLDS, false),
                cycleTime = createMetricValue(0.0, "hours", CYCLE_TIME_THRESHOLDS, false),
                mergeFrequency = createMetricValue(0.0, "PRs/dev/week", MERGE_FREQUENCY_THRESHOLDS, true),
                prSize = createMetricValue(0.0, "lines", PR_SIZE_THRESHOLDS, false),
                totalPRs = 0,
                totalDevelopers = 0
            )
        }

        val codingTimes = prs.mapNotNull { calculateCodingTime(it) }
        val pickupTimes = prs.mapNotNull { calculatePickupTime(it) }
        val approveTimes = prs.mapNotNull { calculateApproveTime(it) }
        val mergeTimes = prs.mapNotNull { calculateMergeTime(it) }
        val reviewTimes = prs.mapNotNull { calculateReviewTime(it) }
        val cycleTimes = prs.mapNotNull { calculateCycleTime(it) }
        val prSizes = prs.map { it.prSize.toDouble() }

        val uniqueDevelopers = prs.map { it.authorLogin.lowercase() }.distinct().size
        val mergeFreq = if (uniqueDevelopers > 0 && weeksInRange > 0) {
            prs.size.toDouble() / uniqueDevelopers / weeksInRange
        } else 0.0

        return TeamMetrics(
            codingTime = createMetricValue(codingTimes.averageOrNull() ?: 0.0, "hours", CODING_TIME_THRESHOLDS, false),
            pickupTime = createMetricValue(pickupTimes.averageOrNull() ?: 0.0, "hours", PICKUP_TIME_THRESHOLDS, false),
            approveTime = createMetricValue(approveTimes.averageOrNull() ?: 0.0, "hours", APPROVE_TIME_THRESHOLDS, false),
            mergeTime = createMetricValue(mergeTimes.averageOrNull() ?: 0.0, "hours", MERGE_TIME_THRESHOLDS, false),
            reviewTime = createMetricValue(reviewTimes.averageOrNull() ?: 0.0, "hours", REVIEW_TIME_THRESHOLDS, false),
            cycleTime = createMetricValue(cycleTimes.averageOrNull() ?: 0.0, "hours", CYCLE_TIME_THRESHOLDS, false),
            mergeFrequency = createMetricValue(mergeFreq, "PRs/dev/week", MERGE_FREQUENCY_THRESHOLDS, true),
            prSize = createMetricValue(prSizes.averageOrNull() ?: 0.0, "lines", PR_SIZE_THRESHOLDS, false),
            totalPRs = prs.size,
            totalDevelopers = uniqueDevelopers
        )
    }

    /**
     * Calculate per-developer metrics
     */
    private fun calculateDeveloperMetrics(prs: List<PRDetailedInfo>, weeksInRange: Long): List<DeveloperMetrics> {
        val prsByDeveloper = prs.groupBy { it.authorLogin.lowercase() }

        return prsByDeveloper.map { (login, devPRs) ->
            val codingTimes = devPRs.mapNotNull { calculateCodingTime(it) }
            val pickupTimes = devPRs.mapNotNull { calculatePickupTime(it) }
            val approveTimes = devPRs.mapNotNull { calculateApproveTime(it) }
            val mergeTimes = devPRs.mapNotNull { calculateMergeTime(it) }
            val reviewTimes = devPRs.mapNotNull { calculateReviewTime(it) }
            val cycleTimes = devPRs.mapNotNull { calculateCycleTime(it) }
            val prSizes = devPRs.map { it.prSize.toDouble() }

            val mergeFreq = if (weeksInRange > 0) devPRs.size.toDouble() / weeksInRange else 0.0

            val prDetails = devPRs.map { pr ->
                PRMetrics(
                    prNumber = pr.prNumber,
                    prTitle = pr.title,
                    author = pr.authorLogin,
                    codingTimeHours = calculateCodingTime(pr),
                    pickupTimeHours = calculatePickupTime(pr),
                    approveTimeHours = calculateApproveTime(pr),
                    mergeTimeHours = calculateMergeTime(pr),
                    reviewTimeHours = calculateReviewTime(pr),
                    cycleTimeHours = calculateCycleTime(pr),
                    prSize = pr.prSize,
                    createdAt = pr.createdAt,
                    mergedAt = pr.mergedAt
                )
            }

            DeveloperMetrics(
                developerName = login,
                nickname = "@$login",
                codingTime = createMetricValue(codingTimes.averageOrNull() ?: 0.0, "hours", CODING_TIME_THRESHOLDS, false),
                pickupTime = createMetricValue(pickupTimes.averageOrNull() ?: 0.0, "hours", PICKUP_TIME_THRESHOLDS, false),
                approveTime = createMetricValue(approveTimes.averageOrNull() ?: 0.0, "hours", APPROVE_TIME_THRESHOLDS, false),
                mergeTime = createMetricValue(mergeTimes.averageOrNull() ?: 0.0, "hours", MERGE_TIME_THRESHOLDS, false),
                reviewTime = createMetricValue(reviewTimes.averageOrNull() ?: 0.0, "hours", REVIEW_TIME_THRESHOLDS, false),
                cycleTime = createMetricValue(cycleTimes.averageOrNull() ?: 0.0, "hours", CYCLE_TIME_THRESHOLDS, false),
                mergeFrequency = createMetricValue(mergeFreq, "PRs/week", MERGE_FREQUENCY_THRESHOLDS, true),
                prSize = createMetricValue(prSizes.averageOrNull() ?: 0.0, "lines", PR_SIZE_THRESHOLDS, false),
                totalPRs = devPRs.size,
                prDetails = prDetails
            )
        }.sortedByDescending { it.totalPRs }
    }

    /**
     * Calculate weekly trend
     */
    private fun calculateWeeklyTrend(
        prs: List<PRDetailedInfo>,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<WeeklyMetricsTrend> {
        val trends = mutableListOf<WeeklyMetricsTrend>()
        val weekFields = WeekFields.of(Locale.getDefault())

        var current = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        while (!current.isAfter(endDate)) {
            val weekEnd = current.plusDays(6)
            val weekNum = current.get(weekFields.weekOfWeekBasedYear())
            val year = current.get(weekFields.weekBasedYear())
            val weekLabel = "$year-W${weekNum.toString().padStart(2, '0')}"

            val weekPRs = prs.filter { pr ->
                val mergedDate = pr.mergedAt?.substring(0, 10)?.let { LocalDate.parse(it) }
                mergedDate != null && !mergedDate.isBefore(current) && !mergedDate.isAfter(weekEnd)
            }

            val cycleTimes = weekPRs.mapNotNull { calculateCycleTime(it) }
            val reviewTimes = weekPRs.mapNotNull { calculateReviewTime(it) }

            trends.add(WeeklyMetricsTrend(
                week = weekLabel,
                startDate = current.toString(),
                endDate = weekEnd.toString(),
                cycleTime = cycleTimes.averageOrNull() ?: 0.0,
                reviewTime = reviewTimes.averageOrNull() ?: 0.0,
                mergeFrequency = weekPRs.size.toDouble(),
                prCount = weekPRs.size
            ))

            current = current.plusWeeks(1)
        }

        return trends
    }

    // Time calculation helpers

    private fun calculateCodingTime(pr: PRDetailedInfo): Double? {
        val firstCommit = parseDateTime(pr.firstCommitTime) ?: return null
        val created = parseDateTime(pr.createdAt) ?: return null
        return hoursBetween(firstCommit, created)
    }

    private fun calculatePickupTime(pr: PRDetailedInfo): Double? {
        val created = parseDateTime(pr.createdAt) ?: return null
        val firstReview = parseDateTime(pr.firstReviewTime) ?: return null
        return hoursBetween(created, firstReview)
    }

    private fun calculateApproveTime(pr: PRDetailedInfo): Double? {
        val firstReview = parseDateTime(pr.firstReviewTime) ?: return null
        // If we have formal approval, use that. Otherwise, use merge time as the "approval" point
        val approvalTime = parseDateTime(pr.firstApprovalTime) ?: parseDateTime(pr.mergedAt) ?: return null
        val result = hoursBetween(firstReview, approvalTime)
        // Only return positive values (approval should be after review)
        return if (result >= 0) result else null
    }

    private fun calculateMergeTime(pr: PRDetailedInfo): Double? {
        // If we have formal approval, calculate time from approval to merge
        // Otherwise, this metric is not applicable
        val firstApproval = parseDateTime(pr.firstApprovalTime)
        val merged = parseDateTime(pr.mergedAt) ?: return null

        return if (firstApproval != null) {
            val result = hoursBetween(firstApproval, merged)
            if (result >= 0) result else null
        } else {
            // No formal approval - can't calculate merge time separately
            null
        }
    }

    private fun calculateReviewTime(pr: PRDetailedInfo): Double? {
        val created = parseDateTime(pr.createdAt) ?: return null
        val merged = parseDateTime(pr.mergedAt) ?: return null
        return hoursBetween(created, merged)
    }

    private fun calculateCycleTime(pr: PRDetailedInfo): Double? {
        val firstCommit = parseDateTime(pr.firstCommitTime) ?: parseDateTime(pr.createdAt) ?: return null
        val merged = parseDateTime(pr.mergedAt) ?: return null
        return hoursBetween(firstCommit, merged)
    }

    private fun parseDateTime(dateStr: String?): LocalDateTime? {
        if (dateStr == null) return null
        return try {
            LocalDateTime.parse(dateStr.replace("Z", "").substringBefore("+").substringBefore(".") +
                if (dateStr.contains("T")) "" else "T00:00:00")
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun hoursBetween(start: LocalDateTime, end: LocalDateTime): Double {
        return ChronoUnit.MINUTES.between(start, end) / 60.0
    }

    private fun createMetricValue(
        value: Double,
        unit: String,
        thresholds: List<Double>,
        higherIsBetter: Boolean
    ): MetricValue {
        val rating = if (higherIsBetter) {
            when {
                value >= thresholds[0] -> MetricRating.ELITE
                value >= thresholds[1] -> MetricRating.GOOD
                value >= thresholds[2] -> MetricRating.FAIR
                else -> MetricRating.NEEDS_FOCUS
            }
        } else {
            when {
                value <= thresholds[0] -> MetricRating.ELITE
                value <= thresholds[1] -> MetricRating.GOOD
                value <= thresholds[2] -> MetricRating.FAIR
                else -> MetricRating.NEEDS_FOCUS
            }
        }

        val displayValue = when {
            unit == "hours" && value < 1 -> "${(value * 60).toInt()} mins"
            unit == "hours" -> String.format("%.1f hrs", value)
            unit.contains("week") -> String.format("%.2f", value)
            else -> value.toInt().toString()
        }

        return MetricValue(
            value = value,
            unit = unit,
            rating = rating,
            displayValue = displayValue
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}

/**
 * Internal data class for PR details
 */
data class PRDetailedInfo(
    val prNumber: Int,
    val title: String,
    val authorLogin: String,
    val repositoryFullName: String,
    val createdAt: String,
    val mergedAt: String?,
    val firstCommitTime: String?,
    val firstReviewTime: String?,
    val firstApprovalTime: String?,
    val additions: Int,
    val deletions: Int,
    val prSize: Int
)

