package com.reduan.smartinfo

import com.reduan.smartinfo.profile.Profile
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.profile.ShareableProfile
import com.reduan.smartinfo.profile.SharingSelection
import com.reduan.smartinfo.profile.SocialLink
import com.reduan.smartinfo.profile.VCardBuilder
import com.reduan.smartinfo.profile.VCardParser
import com.reduan.smartinfo.storage.FileNames
import com.reduan.smartinfo.storage.LinkCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
    private val full = Profile(
        values = mapOf(
            ProfileField.FULL_NAME to "Test User",
            ProfileField.PHONE to "+8801700000000",
            ProfileField.EMAIL to "test@example.com",
            ProfileField.COMPANY to "Acme; Inc, Ltd",
            ProfileField.ADDRESS to "Road 1\nCity",
            ProfileField.WHATSAPP to "+880 1700-000000",
        ),
        photoPath = "/data/photo.jpg",
        links = listOf(SocialLink("a", "GitHub", "https://github.com/x"), SocialLink("b", "Blog", "https://b.example")),
    )

    @Test fun defaultSelectionHidesSensitiveFields() {
        val s = ShareableProfile.from(full)
        assertEquals("Test User", s.value(ProfileField.FULL_NAME))
        assertNull(s.value(ProfileField.PHONE))
        assertNull(s.value(ProfileField.EMAIL))
        assertNull(s.value(ProfileField.ADDRESS))
        assertNull(s.photoPath)
        assertTrue(s.links.isEmpty())
    }

    @Test fun onlySelectedItemsAreShared() {
        val p = full.copy(sharing = SharingSelection(setOf(ProfileField.PHONE), includePhoto = true, linkIds = setOf("b")))
        val s = ShareableProfile.from(p)
        assertEquals(listOf(ProfileField.PHONE), s.entries.map { it.first })
        assertEquals(listOf("b"), s.links.map { it.id })
        assertEquals("/data/photo.jpg", s.photoPath)
        val v = VCardBuilder.build(s)
        assertTrue(v.contains("TEL;TYPE=CELL:+8801700000000"))
        assertFalse(v.contains("Test User"))
        assertFalse(v.contains("github.com"))
    }

    @Test fun vcardEscapesAndStructure() {
        val all = full.copy(sharing = SharingSelection(ProfileField.entries.toSet(), false, setOf("a")))
        val v = VCardBuilder.build(ShareableProfile.from(all))
        assertTrue(v.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(v.endsWith("END:VCARD\r\n"))
        assertTrue(v.contains("ORG:Acme\\; Inc\\, Ltd"))
        assertTrue(v.contains("ADR;TYPE=WORK:;;Road 1\\nCity;;;;"))
        assertTrue(v.contains("N:User;Test;;;"))
        assertTrue(v.contains("URL;TYPE=WhatsApp:https://wa.me/8801700000000"))
        assertTrue(v.contains("URL;TYPE=GitHub:https://github.com/x"))
    }

    @Test fun vcardLinesAreFoldedAtUtf8Boundaries() {
        val long = "রেদওয়ান ".repeat(30).trim()
        val p = Profile(values = mapOf(ProfileField.FULL_NAME to long))
        val v = VCardBuilder.build(ShareableProfile.from(p))
        v.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        assertEquals(long, VCardParser.parse(v)!!.displayName)
    }

    @Test fun photoIsEmbeddedOnlyWhenGiven() {
        val s = ShareableProfile.from(full)
        assertFalse(VCardBuilder.build(s).contains("PHOTO"))
        assertTrue(VCardBuilder.build(s, ByteArray(300) { it.toByte() }).contains("PHOTO;ENCODING=b;TYPE=JPEG:"))
    }

    @Test fun parserRoundTrip() {
        val all = full.copy(sharing = SharingSelection(ProfileField.entries.toSet(), false, emptySet()))
        val parsed = VCardParser.parse(VCardBuilder.build(ShareableProfile.from(all)))!!
        assertEquals("Test User", parsed.displayName)
        assertTrue(parsed.lines.contains("Company" to "Acme; Inc, Ltd"))
        assertTrue(parsed.lines.contains("Address" to "Road 1\nCity"))
    }

    @Test fun linkCodecRoundTrip() {
        assertEquals(full.links, LinkCodec.decode(LinkCodec.encode(full.links)))
        assertTrue(LinkCodec.decode("").isEmpty())
    }

    @Test fun fileNames() {
        assertEquals("Test_User", FileNames.sanitize("Test User"))
        assertEquals("a_b", FileNames.sanitize("a/b"))
        assertEquals("contact", FileNames.sanitize("../"))
    }
}
