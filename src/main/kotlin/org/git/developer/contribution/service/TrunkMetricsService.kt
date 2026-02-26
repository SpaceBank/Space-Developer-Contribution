package org.git.developer.contribution.service

import org.git.developer.contribution.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Service for Trunk-Based Matrix — DORA metrics based on commits + GitHub Actions.
 *
 * Core logic:
 *   For each commit on the branch, find the FIRST completed run of the named
 *   workflow whose `run_started_at` is >= the commit's authored time.
 *     • If that run succeeded  → commit is SUCCESS,  lead time = run.completedAt − commit.committedAt
 *     • If that run failed     → commit is FAILURE
 *     • If no matching run     → commit is PENDING
 */
@Service
class TrunkMetricsService {

    private val logger = LoggerFactory.getLogger(TrunkMetricsService::class.java)

    // ════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════

    fun analyze(request: TrunkMetricsRequest): TrunkMetricsResponse {
        logger.info("🌳 Trunk-Based Matrix analysis starting")
        logger.info("   Repo: ${request.owner}/${request.repo}  Branch: ${request.branch}")
        logger.info("   Workflow: '${request.workflowName}'")
        logger.info("   Date range: ${request.startDate} → ${request.endDate}")

        val startDate = LocalDate.parse(request.startDate)
        val endDate   = LocalDate.parse(request.endDate)
        val startInstant = startDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        val endInstant   = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val daysInRange  = ChronoUnit.DAYS.between(startDate, endDate).toDouble().coerceAtLeast(1.0)

        val client = buildClient(request.token)

        // 1 — Fetch commits
        logger.info("📡 Fetching commits …")
        val rawCommits = fetchCommits(client, request.owner, request.repo, request.branch, startInstant, endInstant)
        logger.info("   ✅ ${rawCommits.size} commits fetched")

        // 2 — Fetch action runs
        //     Exact range: for accurate deploy counts
        //     Extended range (+7 days forward): so we can match late-deploying commits
        //     MTTR range (-7 days back, +7 days forward): so MTTR can see failures before the range
        //       that are resolved by the first success inside the range
        logger.info("📡 Fetching workflow runs for '${request.workflowName}' …")
        val actionRuns = fetchActionRuns(
            client, request.owner, request.repo, request.branch,
            request.workflowName, startInstant, endInstant
        )
        val actionRunsExtended = fetchActionRuns(
            client, request.owner, request.repo, request.branch,
            request.workflowName, startInstant,
            endInstant.plus(7, ChronoUnit.DAYS)
        )
        val actionRunsForMTTR = fetchActionRuns(
            client, request.owner, request.repo, request.branch,
            request.workflowName,
            startInstant.minus(7, ChronoUnit.DAYS),
            endInstant.plus(7, ChronoUnit.DAYS)
        )
        logger.info("   ✅ ${actionRuns.size} runs in range, ${actionRunsExtended.size} extended, ${actionRunsForMTTR.size} for MTTR (with lookback)")

        // 3 — Map each commit to its first action (use extended range for matching)
        val mappedCommits = mapCommitsToFirstAction(rawCommits, actionRunsExtended)

        // 4 — Exclude merge commits from DORA calculations
        val nonMergeCommits = mappedCommits.filter { !it.isMerge }
        val mergeCount = mappedCommits.size - nonMergeCommits.size
        logger.info("   📊 ${mappedCommits.size} total commits, $mergeCount merge commits excluded, ${nonMergeCommits.size} used for metrics")

        val successful = nonMergeCommits.filter { it.deploymentResult == "SUCCESS" }
        val failed     = nonMergeCommits.filter { it.deploymentResult == "FAILURE" }
        val pending    = nonMergeCommits.filter { it.deploymentResult == "PENDING" }

        // DORA deploy counts come directly from actual workflow runs, NOT from commit mapping
        val uniqueSuccessfulRuns = actionRuns.count { it.status == "SUCCESS" }
        val uniqueFailedRuns     = actionRuns.count { it.status == "FAILURE" }
        val uniqueResolvedRuns   = uniqueSuccessfulRuns + uniqueFailedRuns
        val deployFreq      = uniqueSuccessfulRuns / daysInRange
        // Lead time: only from non-merge commits that have a lead time (commit → first success run)
        val leadTimes       = nonMergeCommits.mapNotNull { it.leadTimeMinutes }
        val avgLeadHours    = if (leadTimes.isNotEmpty()) leadTimes.average() / 60.0 else 0.0
        val cycleTimes      = nonMergeCommits.mapNotNull { it.cycleTimeMinutes }
        val avgCycleHours   = if (cycleTimes.isNotEmpty()) cycleTimes.average() / 60.0 else 0.0
        val changeFailRate  = if (uniqueResolvedRuns > 0) uniqueFailedRuns.toDouble() / uniqueResolvedRuns * 100.0 else 0.0
        val mttrHours       = calculateMTTR(actionRunsForMTTR, startInstant, endInstant)
        val commitFreq      = nonMergeCommits.size / daysInRange
        val avgBatch        = if (nonMergeCommits.isNotEmpty()) nonMergeCommits.map { it.linesAdded + it.linesDeleted }.average() else 0.0
        val completedRuns   = actionRuns.filter { it.completedAt != null }
        val avgPipeline     = if (completedRuns.isNotEmpty()) completedRuns.map { it.durationMinutes }.average() else 0.0
        val successRate     = 100.0 - changeFailRate

        // Daily stats and weekly trend use ALL commits (including merge) for display
        val dailyStats  = buildDailyStats(mappedCommits, actionRuns, startDate, endDate)
        val weeklyTrend = buildWeeklyTrend(mappedCommits, actionRuns, startDate, endDate)
        // Author stats use only non-merge commits
        val authorStats = buildAuthorStats(nonMergeCommits, daysInRange)
        val ratings     = rate(deployFreq, avgLeadHours, avgCycleHours, changeFailRate, mttrHours, commitFreq, avgBatch, avgPipeline)
        val teamMetrics = buildTeamMetrics(
            deployFreq, avgLeadHours, avgCycleHours, changeFailRate, mttrHours,
            commitFreq, avgBatch, avgPipeline, successRate,
            nonMergeCommits.size, authorStats.size, ratings
        )

        logger.info("🏁 Analysis complete — $uniqueSuccessfulRuns successful deploys, $uniqueFailedRuns failed deploys (from ${nonMergeCommits.size} non-merge commits, ${pending.size} pending)")

        return TrunkMetricsResponse(
            owner = request.owner, repo = request.repo,
            branch = request.branch, workflowName = request.workflowName,
            startDate = request.startDate, endDate = request.endDate,
            totalCommits = nonMergeCommits.size, totalActionRuns = actionRuns.size,
            successfulCommits = successful.size, failedCommits = failed.size,
            pendingCommits = pending.size,
            successfulDeploys = uniqueSuccessfulRuns, failedDeploys = uniqueFailedRuns,
            deploymentFrequency = deployFreq,
            leadTimeForChangesHours = avgLeadHours, cycleTimeHours = avgCycleHours,
            changeFailureRate = changeFailRate, meanTimeToRecoveryHours = mttrHours,
            commitFrequency = commitFreq, avgBatchSize = avgBatch,
            avgPipelineDurationMinutes = avgPipeline, deploySuccessRate = successRate,
            teamMetrics = teamMetrics,
            commits = nonMergeCommits, actionRuns = actionRuns,
            dailyStats = dailyStats, weeklyTrend = weeklyTrend,
            authorStats = authorStats, ratings = ratings
        )
    }

    /**
     * List available workflows for a repository so the user can pick one.
     */
    fun listWorkflows(token: String, owner: String, repo: String): List<Map<String, String>> {
        val client = buildClient(token)
        return try {
            val response = client.get()
                .uri("/repos/$owner/$repo/actions/workflows?per_page=100")
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block() ?: return emptyList()

            val workflows = response["workflows"] as? List<*> ?: return emptyList()
            workflows.mapNotNull { wf ->
                val w = wf as? Map<*, *> ?: return@mapNotNull null
                val name  = w["name"] as? String  ?: return@mapNotNull null
                val state = w["state"] as? String  ?: "unknown"
                val path  = w["path"] as? String   ?: ""
                mapOf("name" to name, "state" to state, "path" to path)
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to list workflows for $owner/$repo: ${e.message}")
            emptyList()
        }
    }

    // ════════════════════════════════════════════════════════════
    //  GITHUB API HELPERS
    // ════════════════════════════════════════════════════════════

    private fun buildClient(token: String): WebClient {
        val strategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()

        return WebClient.builder()
            .baseUrl("https://api.github.com")
            .exchangeStrategies(strategies)
            .defaultHeader("Authorization", "Bearer $token")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()
    }

    // ── Fetch commits ──────────────────────────────────────────

    private fun fetchCommits(
        client: WebClient, owner: String, repo: String,
        branch: String, since: Instant, until: Instant
    ): List<TrunkCommitInfo> {
        val result = mutableListOf<TrunkCommitInfo>()
        var page = 1

        while (page <= 20) {
            val response = try {
                client.get()
                    .uri { uri ->
                        uri.path("/repos/$owner/$repo/commits")
                            .queryParam("sha", branch)
                            .queryParam("since", since.toString())
                            .queryParam("until", until.toString())
                            .queryParam("per_page", 100)
                            .queryParam("page", page)
                            .build()
                    }
                    .retrieve()
                    .bodyToMono<List<Map<String, Any?>>>()
                    .block() ?: break
            } catch (e: Exception) {
                logger.error("   ❌ Error fetching commits page $page: ${e.message}")
                break
            }

            if (response.isEmpty()) break

            for (c in response) {
                val sha = c["sha"] as? String ?: continue
                val commitData  = c["commit"] as? Map<*, *> ?: continue
                val authorBlock = commitData["author"] as? Map<*, *>
                val topAuthor   = c["author"] as? Map<*, *>
                val parents     = c["parents"] as? List<*> ?: emptyList<Any>()

                val message     = (commitData["message"] as? String ?: "").lines().firstOrNull() ?: ""
                val authorLogin = topAuthor?.get("login") as? String ?: "unknown"
                val authorName  = authorBlock?.get("name") as? String ?: authorLogin
                val avatarUrl   = topAuthor?.get("avatar_url") as? String
                val dateStr     = authorBlock?.get("date") as? String ?: continue
                val isMerge     = parents.size > 1

                result.add(TrunkCommitInfo(
                    sha = sha, shortSha = sha.take(7),
                    message = message,
                    authorLogin = authorLogin, authorName = authorName,
                    authorAvatarUrl = avatarUrl, committedAt = dateStr,
                    isMerge = isMerge,
                    linesAdded = 0, linesDeleted = 0, filesChanged = 0,
                    firstActionRunId = null, firstActionStatus = null,
                    firstActionConclusion = null, firstActionStartedAt = null,
                    firstActionCompletedAt = null, firstActionUrl = null,
                    leadTimeMinutes = null, cycleTimeMinutes = null,
                    deploymentResult = "PENDING"
                ))
            }

            if (response.size < 100) break
            page++
        }

        // Batch-fetch commit stats via GraphQL (much faster than per-commit REST)
        return enrichCommitsWithStats(client, owner, repo, result)
    }

    /**
     * Use GraphQL to fetch additions/deletions for many commits at once.
     * Falls back gracefully if GraphQL fails.
     */
    private fun enrichCommitsWithStats(
        client: WebClient, owner: String, repo: String,
        commits: List<TrunkCommitInfo>
    ): List<TrunkCommitInfo> {
        if (commits.isEmpty()) return commits

        // Process in batches of 50 (GraphQL alias limit)
        val enriched = commits.toMutableList()
        val batches = commits.chunked(50)

        for ((batchIdx, batch) in batches.withIndex()) {
            try {
                // Build GraphQL query with aliases
                val aliases = batch.mapIndexed { i, c ->
                    """c${i}: object(oid: "${c.sha}") {
                        ... on Commit {
                            additions
                            deletions
                            changedFilesIfAvailable
                        }
                    }"""
                }.joinToString("\n")

                val query = """
                    query {
                        repository(owner: "$owner", name: "$repo") {
                            $aliases
                        }
                    }
                """.trimIndent()

                val response = client.post()
                    .uri("/graphql")
                    .bodyValue(mapOf("query" to query))
                    .retrieve()
                    .bodyToMono<Map<String, Any?>>()
                    .block()

                val data = response?.get("data") as? Map<*, *>
                val repository = data?.get("repository") as? Map<*, *>

                if (repository != null) {
                    for ((i, c) in batch.withIndex()) {
                        val obj = repository["c$i"] as? Map<*, *> ?: continue
                        val add = (obj["additions"] as? Number)?.toInt() ?: 0
                        val del = (obj["deletions"] as? Number)?.toInt() ?: 0
                        val files = (obj["changedFilesIfAvailable"] as? Number)?.toInt() ?: 0
                        val globalIdx = batchIdx * 50 + i
                        enriched[globalIdx] = c.copy(
                            linesAdded = add, linesDeleted = del, filesChanged = files
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn("   ⚠️ GraphQL stats enrichment failed for batch $batchIdx: ${e.message}")
                // Commits will keep 0/0/0 — acceptable fallback
            }
        }

        return enriched
    }

    // ── Fetch action runs ──────────────────────────────────────

    private fun fetchActionRuns(
        client: WebClient, owner: String, repo: String,
        branch: String, workflowName: String,
        since: Instant, until: Instant
    ): List<TrunkActionRun> {
        val result = mutableListOf<TrunkActionRun>()
        var page = 1

        val sinceDate = since.toString().substring(0, 10)
        val untilDate = until.toString().substring(0, 10)

        while (page <= 20) {
            val response = try {
                client.get()
                    .uri { uri ->
                        uri.path("/repos/$owner/$repo/actions/runs")
                            .queryParam("branch", branch)
                            .queryParam("status", "completed")
                            .queryParam("per_page", 100)
                            .queryParam("page", page)
                            .queryParam("created", "$sinceDate..$untilDate")
                            .build()
                    }
                    .retrieve()
                    .bodyToMono<Map<String, Any?>>()
                    .block() ?: break
            } catch (e: Exception) {
                logger.error("   ❌ Error fetching action runs page $page: ${e.message}")
                break
            }

            val runs = response["workflow_runs"] as? List<*> ?: break
            if (runs.isEmpty()) break

            for (run in runs) {
                val r = run as? Map<*, *> ?: continue
                val name = r["name"] as? String ?: continue

                if (!name.equals(workflowName, ignoreCase = true)) continue

                val runId       = (r["id"] as? Number)?.toLong() ?: continue
                val conclusion  = r["conclusion"] as? String
                val createdAt   = r["created_at"] as? String ?: continue
                val updatedAt   = r["updated_at"] as? String
                val runStarted  = r["run_started_at"] as? String ?: createdAt
                val headSha     = r["head_sha"] as? String ?: ""
                val htmlUrl     = r["html_url"] as? String ?: ""
                val event       = r["event"] as? String

                val completedAt = updatedAt ?: createdAt
                val durationMin = try {
                    val s = Instant.parse(runStarted)
                    val e2 = Instant.parse(completedAt)
                    ChronoUnit.SECONDS.between(s, e2) / 60.0
                } catch (_: Exception) { 0.0 }

                val status = when (conclusion) {
                    "success" -> "SUCCESS"
                    "failure", "timed_out", "cancelled" -> "FAILURE"
                    else -> "UNKNOWN"
                }
                if (status == "UNKNOWN") continue

                result.add(TrunkActionRun(
                    runId = runId, workflowName = name,
                    status = status, conclusion = conclusion,
                    startedAt = runStarted, completedAt = completedAt,
                    durationMinutes = durationMin, headSha = headSha,
                    htmlUrl = htmlUrl, event = event
                ))
            }

            if (runs.size < 100) break
            page++
        }

        return result.sortedBy { it.startedAt }
    }

    // ════════════════════════════════════════════════════════════
    //  COMMIT → ACTION MAPPING
    // ════════════════════════════════════════════════════════════

    private fun mapCommitsToFirstAction(
        commits: List<TrunkCommitInfo>,
        runs: List<TrunkActionRun>
    ): List<TrunkCommitInfo> {
        // Only consider completed SUCCESS / FAILURE runs, sorted by start time
        val sortedRuns = runs
            .filter { it.status == "SUCCESS" || it.status == "FAILURE" }
            .sortedBy { it.startedAt }

        return commits.map { commit ->
            val commitInstant = try { Instant.parse(commit.committedAt) } catch (_: Exception) { return@map commit }

            // Find the FIRST action run (any status) that started at or after this commit
            val firstRun = sortedRuns.firstOrNull { run ->
                try {
                    val runStart = Instant.parse(run.startedAt)
                    !runStart.isBefore(commitInstant)
                } catch (_: Exception) { false }
            } ?: return@map commit.copy(deploymentResult = "PENDING")

            // Lead Time: commit → first SUCCESS run completed after commit
            // (this may be a different run than firstRun if firstRun was a failure)
            val firstSuccessRun = sortedRuns.firstOrNull { run ->
                run.status == "SUCCESS" && try {
                    val runStart = Instant.parse(run.startedAt)
                    !runStart.isBefore(commitInstant)
                } catch (_: Exception) { false }
            }

            val leadTimeMin = if (firstSuccessRun != null) {
                try {
                    val completedInstant = Instant.parse(firstSuccessRun.completedAt!!)
                    ChronoUnit.SECONDS.between(commitInstant, completedInstant) / 60.0
                } catch (_: Exception) { null }
            } else null

            commit.copy(
                firstActionRunId       = firstRun.runId,
                firstActionStatus      = firstRun.status,
                firstActionConclusion  = firstRun.conclusion,
                firstActionStartedAt   = firstRun.startedAt,
                firstActionCompletedAt = firstRun.completedAt,
                firstActionUrl         = firstRun.htmlUrl,
                leadTimeMinutes        = leadTimeMin,
                cycleTimeMinutes       = leadTimeMin,   // same in trunk-based
                deploymentResult       = firstRun.status
            )
        }
    }

    // ════════════════════════════════════════════════════════════
    //  MTTR
    // ════════════════════════════════════════════════════════════

    /**
     * MTTR — Mean Time To Recovery (grouped, with lookback).
     *
     * Uses runs fetched with a -7 day lookback window so that if the first
     * event in the selected range is a SUCCESS, we can find its preceding
     * failures from before the range and correctly measure that recovery.
     *
     * Walk through runs sorted by completedAt. When we hit a FAILURE,
     * record it as the start of an incident. Skip all subsequent failures
     * (they belong to the same incident). When we hit the next SUCCESS,
     * that's the recovery point.
     *
     * Recovery time = success.completedAt − firstFailure.completedAt
     *
     * Example: F1, F2, F3, S1, F4, F5, S2
     *   → 2 incidents:
     *       incident 1: S1.completed − F1.completed  (F2, F3 skipped)
     *       incident 2: S2.completed − F4.completed  (F5 skipped)
     *   → MTTR = average of the 2 recovery times
     *
     * Only incidents where the recovering SUCCESS is within [startInstant, endInstant)
     * are counted:
     *   - Lookback-only incidents (resolved before the range) are excluded.
     *   - Forward-only incidents (resolved after the range) are excluded.
     *   - Lookback failures recovered by the first success IN the range ARE included.
     * Unrecovered failures are NOT counted (no synthetic recovery time).
     *
     * @param allRuns      runs fetched with -7 day lookback and +7 day forward extension
     * @param startInstant the original selected range start
     * @param endInstant   the original selected range end (exclusive upper bound)
     */
    private fun calculateMTTR(allRuns: List<TrunkActionRun>, startInstant: Instant, endInstant: Instant): Double {
        val sorted = allRuns
            .filter { (it.status == "SUCCESS" || it.status == "FAILURE") && it.completedAt != null }
            .sortedBy { it.completedAt }

        val recoveryTimes = mutableListOf<Double>()
        var i = 0
        while (i < sorted.size) {
            val run = sorted[i]
            if (run.status == "FAILURE") {
                // This is the FIRST failure of a new incident
                val firstFailTime = try { Instant.parse(run.completedAt!!) } catch (_: Exception) { i++; continue }

                // Skip all subsequent failures (same incident)
                var j = i + 1
                while (j < sorted.size && sorted[j].status == "FAILURE") {
                    j++
                }

                // j now points to the next SUCCESS (or end of list)
                if (j < sorted.size && sorted[j].status == "SUCCESS") {
                    try {
                        val recoverTime = Instant.parse(sorted[j].completedAt!!)
                        val hours = ChronoUnit.SECONDS.between(firstFailTime, recoverTime) / 3600.0

                        // Only count this incident if the recovery SUCCESS falls within [startInstant, endInstant).
                        // - !recoverTime.isBefore(startInstant): filters out incidents fully resolved before the range
                        //   (lookback-only incidents).
                        // - recoverTime.isBefore(endInstant): filters out incidents resolved after the range
                        //   (from the +7 day forward extension, not part of the selected period).
                        // This ensures lookback failures recovered by the first success IN the range ARE counted,
                        // but incidents entirely outside the range are not.
                        if (hours > 0 && !recoverTime.isBefore(startInstant) && recoverTime.isBefore(endInstant)) {
                            recoveryTimes.add(hours)
                            logger.debug("   📊 MTTR incident: F(${run.completedAt}) → S(${sorted[j].completedAt}) = ${"%.2f".format(hours)}h")
                        }
                    } catch (_: Exception) {}
                    i = j + 1  // move past the success
                } else {
                    // No recovery found within fetched data — skip unrecovered failures
                    // (do NOT use endInstant as a synthetic recovery time; that inflates MTTR)
                    i = j
                }
            } else {
                i++
            }
        }

        logger.info("   📊 MTTR: ${recoveryTimes.size} incidents found, recovery times: ${recoveryTimes.map { "%.2f".format(it) }}")
        return if (recoveryTimes.isNotEmpty()) recoveryTimes.average() else 0.0
    }

    // ════════════════════════════════════════════════════════════
    //  DAILY STATS
    // ════════════════════════════════════════════════════════════

    private fun buildDailyStats(
        commits: List<TrunkCommitInfo>,
        runs: List<TrunkActionRun>,
        startDate: LocalDate, endDate: LocalDate
    ): List<DailyTrunkStat> {
        val stats = mutableListOf<DailyTrunkStat>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val d = date.toString()
            val dayCommits = commits.filter { it.committedAt.startsWith(d) && !it.isMerge }
            val dayRuns    = runs.filter { (it.completedAt ?: it.startedAt).startsWith(d) }
            val leads  = dayCommits.mapNotNull { it.leadTimeMinutes }
            val cycles = dayCommits.mapNotNull { it.cycleTimeMinutes }
            stats.add(DailyTrunkStat(
                date = d,
                commits = dayCommits.size,
                actionRuns = dayRuns.size,
                successfulRuns = dayRuns.count { it.status == "SUCCESS" },
                failedRuns = dayRuns.count { it.status == "FAILURE" },
                avgLeadTimeMinutes  = if (leads.isNotEmpty()) leads.average() else null,
                avgCycleTimeMinutes = if (cycles.isNotEmpty()) cycles.average() else null
            ))
            date = date.plusDays(1)
        }
        return stats
    }

    // ════════════════════════════════════════════════════════════
    //  PER-AUTHOR STATS
    // ════════════════════════════════════════════════════════════

    private fun buildAuthorStats(commits: List<TrunkCommitInfo>, daysInRange: Double): List<TrunkAuthorStats> {
        return commits.groupBy { it.authorLogin }.map { (login, authorCommits) ->
            val first = authorCommits.first()
            val succ    = authorCommits.count { it.deploymentResult == "SUCCESS" }
            val fail    = authorCommits.count { it.deploymentResult == "FAILURE" }
            val pend    = authorCommits.count { it.deploymentResult == "PENDING" }
            val resolved = succ + fail
            val rate    = if (resolved > 0) succ.toDouble() / resolved * 100.0 else 0.0
            val leads   = authorCommits.mapNotNull { it.leadTimeMinutes }
            val cycles  = authorCommits.mapNotNull { it.cycleTimeMinutes }

            // Time to deploy = for successful commits, time from action started to action completed
            val deployTimes = authorCommits.filter { it.deploymentResult == "SUCCESS" && it.firstActionStartedAt != null && it.firstActionCompletedAt != null }
                .mapNotNull {
                    try {
                        val s = Instant.parse(it.firstActionStartedAt!!)
                        val e = Instant.parse(it.firstActionCompletedAt!!)
                        ChronoUnit.SECONDS.between(s, e) / 60.0
                    } catch (_: Exception) { null }
                }

            val authorUniqueSuccRuns = authorCommits
                .filter { it.deploymentResult == "SUCCESS" }
                .mapNotNull { it.firstActionRunId }.distinct().size

            TrunkAuthorStats(
                authorLogin = login, authorName = first.authorName,
                authorAvatarUrl = first.authorAvatarUrl,
                totalCommits = authorCommits.size,
                successfulCommits = succ, failedCommits = fail, pendingCommits = pend,
                commitSuccessRate = rate,
                avgLeadTimeMinutes  = if (leads.isNotEmpty()) leads.average() else null,
                avgCycleTimeMinutes = if (cycles.isNotEmpty()) cycles.average() else null,
                avgTimeToDeployMinutes = if (deployTimes.isNotEmpty()) deployTimes.average() else null,
                avgBatchSize = authorCommits.map { it.linesAdded + it.linesDeleted }.average(),
                totalLinesAdded  = authorCommits.sumOf { it.linesAdded },
                totalLinesDeleted = authorCommits.sumOf { it.linesDeleted },
                deploymentFrequency = authorUniqueSuccRuns / daysInRange,
                commits = authorCommits
            )
        }.sortedByDescending { it.totalCommits }
    }

    // ════════════════════════════════════════════════════════════
    //  DORA RATINGS
    // ════════════════════════════════════════════════════════════

    private fun rate(
        deployFreq: Double, leadHours: Double, cycleHours: Double,
        failRate: Double, mttrHours: Double,
        commitFreq: Double, batchSize: Double, pipelineMinutes: Double
    ): TrunkRatings {
        val df = rateDeployFreq(deployFreq)
        val lt = rateLeadTime(leadHours)
        val ct = rateCycleTime(cycleHours)
        val cfr = rateFailRate(failRate)
        val mt = rateMTTR(mttrHours)
        val cf = rateCommitFreq(commitFreq)
        val bs = rateBatchSize(batchSize)
        val td = rateTimeToDeploy(pipelineMinutes)

        val scores = listOf(df, lt, ct, cfr, mt, cf, bs, td).map {
            when (it) { "Elite" -> 4; "High" -> 3; "Medium" -> 2; else -> 1 }
        }
        val overall = when {
            scores.average() >= 3.5 -> "Elite"
            scores.average() >= 2.5 -> "High"
            scores.average() >= 1.5 -> "Medium"
            else -> "Low"
        }
        return TrunkRatings(df, lt, ct, cfr, mt, cf, bs, td, overall)
    }

    // Individual rating helpers
    private fun rateDeployFreq(v: Double) = when {
        v >= 1.0 -> "Elite"; v >= 0.14 -> "High"; v >= 0.03 -> "Medium"; else -> "Low"
    }
    private fun rateLeadTime(h: Double) = when {
        h < 1.0 -> "Elite"; h < 24.0 -> "High"; h < 168.0 -> "Medium"; else -> "Low"
    }
    private fun rateCycleTime(h: Double) = when {
        h < 1.0 -> "Elite"; h < 24.0 -> "High"; h < 168.0 -> "Medium"; else -> "Low"
    }
    private fun rateFailRate(pct: Double) = when {
        pct <= 5.0 -> "Elite"; pct <= 15.0 -> "High"; pct <= 30.0 -> "Medium"; else -> "Low"
    }
    private fun rateMTTR(h: Double) = when {
        h < 1.0 -> "Elite"; h < 24.0 -> "High"; h < 168.0 -> "Medium"; else -> "Low"
    }
    private fun rateCommitFreq(v: Double) = when {
        v >= 5.0 -> "Elite"; v >= 2.0 -> "High"; v >= 0.5 -> "Medium"; else -> "Low"
    }
    private fun rateBatchSize(lines: Double) = when {
        lines < 50.0 -> "Elite"; lines < 150.0 -> "High"; lines < 400.0 -> "Medium"; else -> "Low"
    }
    private fun rateTimeToDeploy(min: Double) = when {
        min < 5.0 -> "Elite"; min < 15.0 -> "High"; min < 30.0 -> "Medium"; else -> "Low"
    }

    // ── Team Metrics ───────────────────────────────────────────

    private fun buildTeamMetrics(
        deployFreq: Double, leadHours: Double, cycleHours: Double,
        failRate: Double, mttrHours: Double,
        commitFreq: Double, batchSize: Double, pipelineMin: Double,
        successRate: Double, totalCommits: Int, totalDevs: Int,
        ratings: TrunkRatings
    ): TrunkTeamMetrics {
        return TrunkTeamMetrics(
            deploymentFrequency = TrunkMetricValue(deployFreq, "deploys/day", "%.2f".format(deployFreq), ratings.deploymentFrequency),
            leadTime = TrunkMetricValue(leadHours, "hours", formatHours(leadHours), ratings.leadTimeForChanges),
            cycleTime = TrunkMetricValue(cycleHours, "hours", formatHours(cycleHours), ratings.cycleTime),
            changeFailureRate = TrunkMetricValue(failRate, "%", "%.1f%%".format(failRate), ratings.changeFailureRate),
            mttr = TrunkMetricValue(mttrHours, "hours", formatHours(mttrHours), ratings.meanTimeToRecovery),
            commitFrequency = TrunkMetricValue(commitFreq, "commits/day", "%.1f".format(commitFreq), ratings.commitFrequency),
            batchSize = TrunkMetricValue(batchSize, "lines", "%.0f".format(batchSize), ratings.batchSize),
            timeToDeploy = TrunkMetricValue(pipelineMin, "minutes", "%.1f min".format(pipelineMin), ratings.timeToDeploy),
            deploySuccessRate = TrunkMetricValue(successRate, "%", "%.1f%%".format(successRate), rateDeployFreq(successRate / 100.0)),
            totalCommits = totalCommits,
            totalDevelopers = totalDevs
        )
    }

    private fun formatHours(h: Double): String {
        return when {
            h < 1.0 -> "%.0f min".format(h * 60)
            h < 24.0 -> "%.1f hrs".format(h)
            else -> "%.1f days".format(h / 24.0)
        }
    }

    // ── Weekly Trend ───────────────────────────────────────────

    private fun buildWeeklyTrend(
        commits: List<TrunkCommitInfo>,
        runs: List<TrunkActionRun>,
        startDate: LocalDate, endDate: LocalDate
    ): List<WeeklyTrunkTrend> {
        val weeks = mutableListOf<WeeklyTrunkTrend>()
        var weekStart = startDate
        var weekNum = 1

        while (!weekStart.isAfter(endDate)) {
            val weekEnd = weekStart.plusDays(6).let { if (it.isAfter(endDate)) endDate else it }
            val weekStartStr = weekStart.toString()
            val weekEndStr = weekEnd.toString()

            val weekCommits = commits.filter {
                !it.isMerge && run {
                    val d = it.committedAt.substring(0, 10)
                    d >= weekStartStr && d <= weekEndStr
                }
            }
            val weekRuns = runs.filter {
                val d = (it.completedAt ?: it.startedAt).substring(0, 10)
                d >= weekStartStr && d <= weekEndStr
            }

            val weekSuccDeploys = weekRuns.count { it.status == "SUCCESS" }
            val weekFailDeploys = weekRuns.count { it.status == "FAILURE" }
            val daysInWeek = ChronoUnit.DAYS.between(weekStart, weekEnd).toDouble().coerceAtLeast(1.0) + 1.0
            val leads = weekCommits.mapNotNull { it.leadTimeMinutes }
            val cycles = weekCommits.mapNotNull { it.cycleTimeMinutes }
            val weekResolved = weekSuccDeploys + weekFailDeploys

            weeks.add(WeeklyTrunkTrend(
                week = "W$weekNum",
                startDate = weekStartStr,
                endDate = weekEndStr,
                commits = weekCommits.size,
                deployments = weekRuns.size,
                successfulDeploys = weekSuccDeploys,
                failedDeploys = weekFailDeploys,
                avgLeadTimeHours = if (leads.isNotEmpty()) leads.average() / 60.0 else null,
                avgCycleTimeHours = if (cycles.isNotEmpty()) cycles.average() / 60.0 else null,
                deploymentFrequency = weekSuccDeploys / daysInWeek,
                changeFailureRate = if (weekResolved > 0) weekFailDeploys.toDouble() / weekResolved * 100.0 else 0.0
            ))

            weekStart = weekEnd.plusDays(1)
            weekNum++
        }

        return weeks
    }
}

