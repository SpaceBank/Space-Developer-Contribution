/**
 * common.js — Shared JavaScript utilities for all pages.
 *
 * Include this file ONCE in every HTML page:
 *   <script src="/common.js"></script>
 *
 * It provides:
 *   • getSessionData()      — read session from localStorage
 *   • handleApiError(resp)  — classify 401/403/429 responses, notify parent on expiry
 *   • notifySessionExpired()— tell the parent frame the session is dead
 */

// ─── Session helpers ────────────────────────────────────────

/** Read the session object from localStorage (or null). */
function getSessionData() {
    try {
        var raw = localStorage.getItem('spaceAnalyticsSession');
        return raw ? JSON.parse(raw) : null;
    } catch (e) {
        console.warn('⚠️ Failed to read session data:', e);
        return null;
    }
}

/** Persist session object to localStorage. */
function saveSessionData(data) {
    try {
        localStorage.setItem('spaceAnalyticsSession', JSON.stringify(data));
    } catch (e) {
        console.warn('⚠️ Failed to save session data:', e);
    }
}

/** Clear session and redirect to login. */
function clearSession() {
    localStorage.removeItem('spaceAnalyticsSession');
}

/** Alias for backward compatibility. */
var clearSessionData = clearSession;

// ─── API error handling ─────────────────────────────────────

/**
 * Inspect a fetch() Response for token-expiry / rate-limit errors.
 *
 * Call this BEFORE reading the body:
 *   const resp = await fetch(url, opts);
 *   if (await handleApiError(resp, 'contribution')) return;
 *   const data = await resp.json();
 *
 * @param {Response} response   — the fetch Response object
 * @param {string}   [source]   — page name for the SESSION_EXPIRED message
 * @returns {boolean} true if the caller should abort (session expired or rate-limited)
 */
async function handleApiError(response, source) {
    source = source || 'unknown';

    // ── 401 — token expired ──
    if (response.status === 401) {
        var errorData = {};
        try { errorData = await response.clone().json(); } catch (_) { /* ignore */ }
        console.error('🔑 Token expired — received 401 from backend');
        notifySessionExpired(source, errorData.message);
        return true;
    }

    // ── 429 — explicit rate limit ──
    if (response.status === 429) {
        console.warn('⏱️ Rate limited by GitHub API');
        _showRateLimitWarning();
        return true;
    }

    // ── 403 — might be rate limit in disguise ──
    if (response.status === 403) {
        var errorData2 = {};
        try { errorData2 = await response.clone().json(); } catch (_) { /* ignore */ }
        if (errorData2.error === 'rate_limited') {
            console.warn('⏱️ Rate limited (403) by GitHub API');
            _showRateLimitWarning();
            return true;
        }
    }

    return false;
}

/**
 * Notify the parent frame (index.html) that the session has expired.
 * If we ARE the top frame, redirect to login.
 */
function notifySessionExpired(source, message) {
    clearSession();
    if (window.parent !== window) {
        window.parent.postMessage({
            type: 'SESSION_EXPIRED',
            source: source || 'unknown',
            message: message || 'Your session has expired. Please login again.'
        }, '*');
    } else {
        window.location.href = '/';
    }
}

// ─── Internal helpers ───────────────────────────────────────

/** Show a rate-limit warning using whatever modal/alert the page provides. */
function _showRateLimitWarning() {
    var msg = 'GitHub API rate limit exceeded. Please wait a few minutes and try again.';
    // If the page defines showModal (custom styled dialog), use it
    if (typeof showModal === 'function') {
        showModal('⏱️', 'Rate Limited', msg);
    } else {
        alert(msg);
    }
}

