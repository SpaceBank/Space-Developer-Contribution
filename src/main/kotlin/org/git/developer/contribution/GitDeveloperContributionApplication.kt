package org.git.developer.contribution

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GitDeveloperContributionApplication

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger(GitDeveloperContributionApplication::class.java)

    logger.info("╔══════════════════════════════════════════════════════════╗")
    logger.info("║   🚀 GitDeveloperContribution — Starting...              ║")
    logger.info("╚══════════════════════════════════════════════════════════╝")

    val context = runApplication<GitDeveloperContributionApplication>(*args)

    val port = context.environment.getProperty("server.port") ?: "8081"
    logger.info("╔══════════════════════════════════════════════════════════╗")
    logger.info("║   ✅ GitDeveloperContribution — READY                    ║")
    logger.info("║   🌐 http://localhost:$port                              ║")
    logger.info("║   📊 All API endpoints available                         ║")
    logger.info("╚══════════════════════════════════════════════════════════╝")
}
