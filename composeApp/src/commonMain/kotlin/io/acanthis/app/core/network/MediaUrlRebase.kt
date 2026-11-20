package io.acanthis.app.core.network

fun normalizeBaseUrl(url: String): String {
    return url.trim().trimEnd('/')
}

fun stripTrailingV1Prefix(url: String): String {
    val normalized = normalizeBaseUrl(url)
    return if (normalized.endsWith("/v1")) {
        normalized.dropLast(3)
    } else {
        normalized
    }
}

fun rebaseMediaUrlWithBase(rawUrl: String?, apiBaseUrl: String): String {
    if (rawUrl.isNullOrBlank()) {
        return ""
    }
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
        return ""
    }

    val base = stripTrailingV1Prefix(normalizeBaseUrl(apiBaseUrl))

    if (trimmed.startsWith("/v1/")) {
        return "$base$trimmed"
    }

    val schemeSeparatorIndex = trimmed.indexOf("://")
    if (schemeSeparatorIndex <= 0) {
        return trimmed
    }

    val scheme = trimmed.substring(0, schemeSeparatorIndex)
    if (!isValidUrlScheme(scheme)) {
        return trimmed
    }

    val authorityStart = schemeSeparatorIndex + 3
    if (authorityStart >= trimmed.length) {
        return trimmed
    }

    var pathStart = -1
    for (i in authorityStart until trimmed.length) {
        val ch = trimmed[i]
        if (ch == '/' || ch == '?' || ch == '#') {
            pathStart = i
            break
        }
    }

    if (pathStart == -1 || trimmed[pathStart] != '/') {
        return trimmed
    }

    val hashIndex = trimmed.indexOf('#', startIndex = pathStart)
    val endBeforeHash = if (hashIndex == -1) trimmed.length else hashIndex

    val queryIndex = trimmed.indexOf('?', startIndex = pathStart)
    val pathnameEnd = if (queryIndex != -1 && queryIndex < endBeforeHash) {
        queryIndex
    } else {
        endBeforeHash
    }

    val pathname = trimmed.substring(pathStart, pathnameEnd)
    val search = if (queryIndex != -1 && queryIndex < endBeforeHash) {
        trimmed.substring(queryIndex, endBeforeHash)
    } else {
        ""
    }

    val v1Index = pathname.indexOf("/v1/")
    if (v1Index == -1) {
        return trimmed
    }

    val v1PathAndQuery = "${pathname.substring(v1Index)}$search"
    return "$base$v1PathAndQuery"
}

private fun isValidUrlScheme(scheme: String): Boolean {
    if (scheme.isEmpty() || !scheme[0].isLetter()) {
        return false
    }
    for (i in 1 until scheme.length) {
        val ch = scheme[i]
        if (!ch.isLetterOrDigit() && ch != '+' && ch != '-' && ch != '.') {
            return false
        }
    }
    return true
}
