package com.reduan.smartinfo.profile

import java.net.URI

object ProfileValidator {
    const val MAX_LINKS = 10
    const val MAX_LINK_LABEL = 40

    private val EMAIL = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$")
    private val PHONE_CHARS = Regex("^\\+?[0-9 ()\\-.]+$")
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
    private val OTHER_SCHEME = Regex("^(javascript|data|file|intent|content|mailto|tel|sms):", RegexOption.IGNORE_CASE)

    /** Removes control characters (keeps newlines for multi-line fields) and trims. */
    fun sanitize(raw: String, multiline: Boolean): String =
        raw.replace("\r\n", "\n")
            .filter { c -> !c.isISOControl() || (multiline && c == '\n') }
            .trim()

    /** Adds https:// when the user typed a bare domain like "example.com". */
    fun normalizeUrl(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return v
        return if (HAS_SCHEME.containsMatchIn(v) || OTHER_SCHEME.containsMatchIn(v)) v else "https://$v"
    }

    /** Returns a user-facing error message, or null when the value is valid. */
    fun validate(field: ProfileField, raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return if (field == ProfileField.FULL_NAME) "Name is required" else null
        if (v.length > field.maxLength) return "Too long (max ${field.maxLength} characters)"
        return when (field.type) {
            FieldType.PHONE -> validatePhone(v)
            FieldType.EMAIL -> if (EMAIL.matches(v) && !v.contains("..")) null else "Enter a valid email address"
            FieldType.URL -> validateUrl(v)
            else -> null
        }
    }

    fun validatePhone(v: String): String? {
        if (!PHONE_CHARS.matches(v)) return "Use digits, spaces, +, - and ( ) only"
        if (v.lastIndexOf('+') > 0) return "'+' is only allowed at the start"
        val digits = v.count { it.isDigit() }
        if (digits !in 7..15) return "Phone number must have 7–15 digits"
        return null
    }

    fun validateUrl(raw: String): String? {
        val v = normalizeUrl(raw)
        if (v.any { it.isWhitespace() }) return "A link cannot contain spaces"
        val uri = try { URI(v) } catch (e: Exception) { return "Enter a valid link" }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return "Only http:// or https:// links are allowed"
        val host = uri.host ?: return "Enter a valid link"
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return "Enter a valid domain"
        return null
    }

    /** Validates a whole profile. Keys: field name, or "link_label:<id>" / "link_url:<id>". */
    fun validateProfile(p: Profile): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        ProfileField.entries.forEach { f -> validate(f, p.values[f].orEmpty())?.let { errors[f.name] = it } }
        if (p.links.size > MAX_LINKS) errors["links"] = "At most $MAX_LINKS extra links"
        p.links.forEach { l ->
            when {
                l.label.isBlank() -> errors["link_label:${l.id}"] = "Label is required"
                l.label.trim().length > MAX_LINK_LABEL -> errors["link_label:${l.id}"] = "Max $MAX_LINK_LABEL characters"
            }
            if (l.url.isBlank()) errors["link_url:${l.id}"] = "Link is required"
            else validateUrl(l.url)?.let { errors["link_url:${l.id}"] = it }
        }
        return errors
    }

    /** Cleans every value so what is stored and shared is consistent. Call after validation passes. */
    fun normalize(p: Profile): Profile = p.copy(
        values = p.values.mapValues { (f, v) ->
            val clean = sanitize(v, f.type == FieldType.MULTILINE)
            if (f.type == FieldType.URL) normalizeUrl(clean) else clean
        }.filterValues { it.isNotEmpty() },
        links = p.links.map {
            it.copy(label = sanitize(it.label, false), url = normalizeUrl(sanitize(it.url, false)))
        },
    )
}
