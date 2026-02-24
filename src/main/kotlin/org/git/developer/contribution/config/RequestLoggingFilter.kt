package org.git.developer.contribution.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * HTTP request logging filter.
 * Logs every incoming request with method, URL, response status, and duration.
 * Helps track what's happening in real-time when watching Docker logs.
 */
@Component
@Order(1)
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Skip logging for static resources
        val uri = request.requestURI
        if (isStaticResource(uri)) {
            filterChain.doFilter(request, response)
            return
        }

        val startTime = System.currentTimeMillis()
        val method = request.method
        val queryString = request.queryString?.let { "?$it" } ?: ""

        try {
            filterChain.doFilter(request, response)
        } finally {
            val duration = System.currentTimeMillis() - startTime
            val status = response.status
            val statusEmoji = when {
                status in 200..299 -> "✅"
                status in 300..399 -> "↪️"
                status in 400..499 -> "⚠️"
                status >= 500 -> "❌"
                else -> "❓"
            }

            log.info("$statusEmoji $method $uri$queryString → $status (${duration}ms)")
        }
    }

    private fun isStaticResource(uri: String): Boolean {
        return uri.endsWith(".css") ||
                uri.endsWith(".js") ||
                uri.endsWith(".html") ||
                uri.endsWith(".svg") ||
                uri.endsWith(".ico") ||
                uri.endsWith(".png") ||
                uri.endsWith(".jpg") ||
                uri.endsWith(".woff") ||
                uri.endsWith(".woff2") ||
                uri.endsWith(".ttf") ||
                uri == "/" ||
                uri == "/favicon.svg"
    }
}

