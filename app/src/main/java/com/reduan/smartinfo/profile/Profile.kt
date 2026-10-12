package com.reduan.smartinfo.profile

enum class FieldType { TEXT, MULTILINE, PHONE, EMAIL, URL }

enum class FieldGroup(val title: String) {
    IDENTITY("Identity"),
    CONTACT("Contact"),
    BUSINESS("Business"),
    SOCIAL("Social media"),
}

/**
 * Every profile field the user can fill in. Nothing here holds user data —
 * values only ever come from what the user types in the app.
 * [sensitive] fields are OFF for sharing until the user explicitly enables them.
 */
enum class ProfileField(
    val label: String,
    val type: FieldType,
    val maxLength: Int,
    val sensitive: Boolean,
    val group: FieldGroup,
) {
    FULL_NAME("Full name", FieldType.TEXT, 100, false, FieldGroup.IDENTITY),
    JOB_TITLE("Job title", FieldType.TEXT, 100, false, FieldGroup.IDENTITY),
    COMPANY("Company", FieldType.TEXT, 100, false, FieldGroup.IDENTITY),
    PHONE("Phone", FieldType.PHONE, 30, true, FieldGroup.CONTACT),
    EMAIL("Email", FieldType.EMAIL, 254, true, FieldGroup.CONTACT),
    ADDRESS("Address", FieldType.MULTILINE, 300, true, FieldGroup.CONTACT),
    DESCRIPTION("Business description", FieldType.MULTILINE, 500, false, FieldGroup.BUSINESS),
    WEBSITE("Website", FieldType.URL, 300, false, FieldGroup.BUSINESS),
    FACEBOOK("Facebook", FieldType.URL, 300, false, FieldGroup.SOCIAL),
    LINKEDIN("LinkedIn", FieldType.URL, 300, false, FieldGroup.SOCIAL),
    INSTAGRAM("Instagram", FieldType.URL, 300, false, FieldGroup.SOCIAL),
    WHATSAPP("WhatsApp number", FieldType.PHONE, 30, true, FieldGroup.SOCIAL),
    YOUTUBE("YouTube", FieldType.URL, 300, false, FieldGroup.SOCIAL),
}

/** A user-defined extra social / web link ("Other social media URLs"). */
data class SocialLink(val id: String, val label: String, val url: String)

/** Exactly which pieces of the profile the user has chosen to share. */
data class SharingSelection(
    val fields: Set<ProfileField>,
    val includePhoto: Boolean,
    val linkIds: Set<String>,
) {
    fun withField(field: ProfileField, on: Boolean) =
        copy(fields = if (on) fields + field else fields - field)

    fun withLink(id: String, on: Boolean) =
        copy(linkIds = if (on) linkIds + id else linkIds - id)

    companion object {
        /** Only non-sensitive identity fields are shared until the user opts in to more. */
        val DEFAULT = SharingSelection(
            fields = setOf(ProfileField.FULL_NAME, ProfileField.JOB_TITLE, ProfileField.COMPANY, ProfileField.WEBSITE),
            includePhoto = false,
            linkIds = emptySet(),
        )
    }
}

data class Profile(
    val values: Map<ProfileField, String> = emptyMap(),
    val photoPath: String? = null,
    val links: List<SocialLink> = emptyList(),
    val sharing: SharingSelection = SharingSelection.DEFAULT,
    val updatedAt: Long = 0L,
) {
    operator fun get(field: ProfileField): String = values[field]?.trim().orEmpty()

    val hasName: Boolean get() = get(ProfileField.FULL_NAME).isNotEmpty()

    val isEmpty: Boolean
        get() = values.values.all { it.isBlank() } && photoPath == null && links.isEmpty()
}
