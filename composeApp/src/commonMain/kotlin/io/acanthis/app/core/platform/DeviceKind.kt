package io.acanthis.app.core.platform

object DeviceKind {
    const val DESKTOP = "desktop"
    const val MOBILE = "mobile"
    const val UNKNOWN = "unknown"

    private val desktopLabelRegex = Regex(
        pattern = """\b(desktop|windows|win32|win64|macos|mac os|macbook|imac|linux|ubuntu|fedora|arch|pc)\b""",
        option = RegexOption.IGNORE_CASE,
    )
    private val mobileLabelRegex = Regex(
        pattern = """\b(android|ios|iphone|ipad|ipod|mobile|phone|pixel|xiaomi|redmi|huawei|honor|oppo|vivo|oneplus|samsung|galaxy|meizu|realme|xperia)\b""",
        option = RegexOption.IGNORE_CASE,
    )

    fun normalize(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            DESKTOP -> DESKTOP
            MOBILE -> MOBILE
            else -> UNKNOWN
        }
    }

    fun resolveDisplayKind(deviceKind: String?, deviceLabel: String? = null): String {
        val normalized = normalize(deviceKind)
        if (normalized == DESKTOP || normalized == MOBILE) {
            return normalized
        }
        val label = deviceLabel?.trim().orEmpty()
        if (label.isNotEmpty()) {
            if (desktopLabelRegex.containsMatchIn(label)) {
                return DESKTOP
            }
            if (mobileLabelRegex.containsMatchIn(label)) {
                return MOBILE
            }
        }
        return MOBILE
    }
}
