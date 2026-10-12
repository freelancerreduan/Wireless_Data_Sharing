package com.reduan.smartinfo.storage

import com.reduan.smartinfo.profile.SocialLink

/** Encodes extra links into one stored string. The validator strips control chars, so the separators are safe. */
object LinkCodec {
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    fun encode(links: List<SocialLink>): String =
        links.joinToString(RECORD.toString()) { "${it.id}$FIELD${it.label}$FIELD${it.url}" }

    fun decode(s: String): List<SocialLink> =
        if (s.isEmpty()) emptyList()
        else s.split(RECORD).mapNotNull { rec ->
            val p = rec.split(FIELD)
            if (p.size == 3 && p[0].isNotEmpty()) SocialLink(p[0], p[1], p[2]) else null
        }
}

object FileNames {
    /** Safe file name for exported .vcf files (keeps Bangla and other letters). */
    fun sanitize(name: String, fallback: String = "contact"): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
            .joinToString("").trim().replace(Regex("\\s+"), "_").take(60)
        return cleaned.trim('_').ifEmpty { fallback }
    }
}
