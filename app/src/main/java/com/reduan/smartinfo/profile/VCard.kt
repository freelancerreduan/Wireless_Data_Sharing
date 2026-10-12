package com.reduan.smartinfo.profile

import java.util.Base64

/** Builds vCard 3.0 (best compatibility with Android Contacts and iOS). */
object VCardBuilder {

    fun build(card: ShareableProfile, photoJpeg: ByteArray? = null): String {
        val out = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        val name = card.value(ProfileField.FULL_NAME)
        out += "FN:" + esc(name ?: card.displayName)
        out += if (name != null) {
            val parts = name.trim().split(Regex("\\s+"))
            val family = if (parts.size > 1) parts.last() else ""
            val given = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else parts[0]
            "N:${esc(family)};${esc(given)};;;"
        } else "N:;;;;"
        card.value(ProfileField.COMPANY)?.let { out += "ORG:" + esc(it) }
        card.value(ProfileField.JOB_TITLE)?.let { out += "TITLE:" + esc(it) }
        card.value(ProfileField.PHONE)?.let { out += "TEL;TYPE=CELL:" + esc(it) }
        card.value(ProfileField.EMAIL)?.let { out += "EMAIL;TYPE=INTERNET:" + esc(it) }
        card.value(ProfileField.ADDRESS)?.let { out += "ADR;TYPE=WORK:;;" + esc(it) + ";;;;" }
        card.value(ProfileField.WEBSITE)?.let { out += "URL;TYPE=WORK:" + uri(it) }
        card.value(ProfileField.FACEBOOK)?.let { out += "URL;TYPE=Facebook:" + uri(it) }
        card.value(ProfileField.LINKEDIN)?.let { out += "URL;TYPE=LinkedIn:" + uri(it) }
        card.value(ProfileField.INSTAGRAM)?.let { out += "URL;TYPE=Instagram:" + uri(it) }
        card.value(ProfileField.YOUTUBE)?.let { out += "URL;TYPE=YouTube:" + uri(it) }
        card.value(ProfileField.WHATSAPP)?.let { wa ->
            val digits = wa.filter { it.isDigit() }
            if (digits.isNotEmpty()) out += "URL;TYPE=WhatsApp:https://wa.me/$digits"
        }
        card.links.forEach { l -> out += "URL;TYPE=${paramSafe(l.label)}:" + uri(l.url) }
        card.value(ProfileField.DESCRIPTION)?.let { out += "NOTE:" + esc(it) }
        if (photoJpeg != null && photoJpeg.isNotEmpty()) {
            out += "PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.getEncoder().encodeToString(photoJpeg)
        }
        out += "END:VCARD"
        return out.joinToString("") { fold(it) + "\r\n" }
    }

    fun esc(s: String): String = s.replace("\\", "\\\\")
        .replace("\r\n", "\n").replace("\r", "\n")
        .replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    private fun uri(s: String) = s.filter { !it.isWhitespace() && !it.isISOControl() }

    private fun paramSafe(s: String): String =
        s.filter { it.isLetterOrDigit() || it == '-' }.take(30).ifEmpty { "Other" }

    /** RFC line folding at 75 octets, never splitting a UTF-8 code point. */
    fun fold(line: String): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val n = ch.toByteArray(Charsets.UTF_8).size
            if (bytes + n > 75) { sb.append("\r\n "); bytes = 1 }
            sb.append(ch); bytes += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}

/** Minimal parser used to PREVIEW a received card before the user decides to save it. */
object VCardParser {
    data class Parsed(val displayName: String, val lines: List<Pair<String, String>>)

    private val LABELS = mapOf(
        "FN" to "Name", "ORG" to "Company", "TITLE" to "Job title", "TEL" to "Phone",
        "EMAIL" to "Email", "ADR" to "Address", "URL" to "Website", "NOTE" to "Note",
    )

    fun parse(text: String): Parsed? {
        if (!text.contains("BEGIN:VCARD", ignoreCase = true)) return null
        val unfolded = text.replace(Regex("\r?\n[ \t]"), "")
        val result = mutableListOf<Pair<String, String>>()
        var fn = ""
        unfolded.split(Regex("\r?\n")).forEach { raw ->
            val colon = raw.indexOf(':')
            if (colon <= 0) return@forEach
            val head = raw.substring(0, colon)
            val name = head.substringBefore(';').substringAfter('.').uppercase()
            val label = LABELS[name] ?: return@forEach
            var value = raw.substring(colon + 1)
            if (name != "URL") value = unescape(value)
            if (name == "ADR") value = value.split(';').filter { it.isNotBlank() }.joinToString(", ")
            if (name == "URL") {
                val type = Regex("TYPE=([^;:]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
                if (type != null && !type.equals("WORK", true)) { result += type to value; return@forEach }
            }
            if (name == "FN") fn = value
            if (value.isNotBlank()) result += label to value
        }
        return Parsed(fn.ifBlank { "Contact" }, result)
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) { 'n', 'N' -> sb.append('\n'); else -> sb.append(n) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
