package com.reduan.smartinfo.profile

/**
 * The filtered view of a [Profile] containing ONLY what the user selected for sharing.
 * Every sharing channel (QR, NFC, BLE, vCard file, local Wi-Fi) is built from this,
 * so an unselected field can never leak through any channel.
 */
data class ShareableProfile(
    val entries: List<Pair<ProfileField, String>>,
    val links: List<SocialLink>,
    val photoPath: String?,
) {
    fun value(field: ProfileField): String? = entries.firstOrNull { it.first == field }?.second

    val displayName: String
        get() = value(ProfileField.FULL_NAME) ?: value(ProfileField.COMPANY) ?: "Shared contact"

    val isEmpty: Boolean get() = entries.isEmpty() && links.isEmpty() && photoPath == null

    /** Human-readable list of what will be sent, for consent screens. */
    fun summary(): List<Pair<String, String>> =
        entries.map { it.first.label to it.second } +
            links.map { it.label to it.url } +
            (if (photoPath != null) listOf("Photo" to "Included") else emptyList())

    companion object {
        fun from(profile: Profile): ShareableProfile {
            val sel = profile.sharing
            return ShareableProfile(
                entries = ProfileField.entries
                    .filter { it in sel.fields }
                    .mapNotNull { f -> profile[f].takeIf { it.isNotEmpty() }?.let { f to it } },
                links = profile.links.filter { it.id in sel.linkIds && it.url.isNotBlank() },
                photoPath = profile.photoPath.takeIf { sel.includePhoto },
            )
        }
    }
}
