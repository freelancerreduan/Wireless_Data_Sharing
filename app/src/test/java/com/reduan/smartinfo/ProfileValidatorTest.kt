package com.reduan.smartinfo

import com.reduan.smartinfo.profile.Profile
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.profile.ProfileValidator
import com.reduan.smartinfo.profile.SocialLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test fun nameIsRequired() {
        assertNotNull(ProfileValidator.validate(ProfileField.FULL_NAME, "  "))
        assertNull(ProfileValidator.validate(ProfileField.FULL_NAME, "Any Name"))
    }

    @Test fun optionalFieldsMayBeEmpty() {
        assertNull(ProfileValidator.validate(ProfileField.PHONE, ""))
        assertNull(ProfileValidator.validate(ProfileField.WEBSITE, ""))
    }

    @Test fun phoneRules() {
        assertNull(ProfileValidator.validate(ProfileField.PHONE, "+880 1712-345678"))
        assertNull(ProfileValidator.validate(ProfileField.PHONE, "(02) 9876543"))
        assertNotNull(ProfileValidator.validate(ProfileField.PHONE, "12345"))
        assertNotNull(ProfileValidator.validate(ProfileField.PHONE, "017abc12345"))
        assertNotNull(ProfileValidator.validate(ProfileField.PHONE, "01+7123456789"))
        assertNotNull(ProfileValidator.validate(ProfileField.PHONE, "1234567890123456"))
    }

    @Test fun emailRules() {
        assertNull(ProfileValidator.validate(ProfileField.EMAIL, "name.surname+tag@example.co.uk"))
        assertNotNull(ProfileValidator.validate(ProfileField.EMAIL, "name@example"))
        assertNotNull(ProfileValidator.validate(ProfileField.EMAIL, "name@@example.com"))
        assertNotNull(ProfileValidator.validate(ProfileField.EMAIL, "na..me@example.com"))
    }

    @Test fun urlRules() {
        assertNull(ProfileValidator.validate(ProfileField.WEBSITE, "example.com"))
        assertNull(ProfileValidator.validate(ProfileField.FACEBOOK, "https://facebook.com/someone"))
        assertNotNull(ProfileValidator.validate(ProfileField.WEBSITE, "javascript:alert(1)"))
        assertNotNull(ProfileValidator.validate(ProfileField.WEBSITE, "ftp://example.com"))
        assertNotNull(ProfileValidator.validate(ProfileField.WEBSITE, "not a url"))
        assertNotNull(ProfileValidator.validate(ProfileField.WEBSITE, "localhost"))
        assertEquals("https://example.com", ProfileValidator.normalizeUrl("example.com"))
        assertEquals("http://example.com", ProfileValidator.normalizeUrl("http://example.com"))
    }

    @Test fun lengthLimit() {
        assertNotNull(ProfileValidator.validate(ProfileField.FULL_NAME, "x".repeat(101)))
    }

    @Test fun profileLevelValidationIncludesLinks() {
        val p = Profile(
            values = mapOf(ProfileField.FULL_NAME to "A B"),
            links = listOf(SocialLink("1", "", "bad url"), SocialLink("2", "GitHub", "github.com/x")),
        )
        val e = ProfileValidator.validateProfile(p)
        assertTrue(e.containsKey("link_label:1"))
        assertTrue(e.containsKey("link_url:1"))
        assertTrue(!e.containsKey("link_url:2"))
    }

    @Test fun normalizeCleansValues() {
        val p = ProfileValidator.normalize(Profile(values = mapOf(
            ProfileField.FULL_NAME to "  Name\u0007 ",
            ProfileField.ADDRESS to "Line 1\r\nLine 2",
            ProfileField.WEBSITE to "example.com",
            ProfileField.COMPANY to "   ",
        )))
        assertEquals("Name", p[ProfileField.FULL_NAME])
        assertEquals("Line 1\nLine 2", p[ProfileField.ADDRESS])
        assertEquals("https://example.com", p[ProfileField.WEBSITE])
        assertTrue(ProfileField.COMPANY !in p.values)
    }
}
