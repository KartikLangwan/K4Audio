package com.k4music.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test fun `newer means a bigger number part by part`() {
        assertTrue(AppUpdate.isNewer("v2.15.0", "2.14.26"))
        assertTrue(AppUpdate.isNewer("2.14.27", "2.14.26"))
        assertFalse(AppUpdate.isNewer("2.14.26", "2.14.26"))
        assertFalse(AppUpdate.isNewer("2.9.0", "2.14.26"))
    }

    @Test fun `the latest release json gives the version and the apk`() {
        val json = """{"tag_name":"v2.15.0","html_url":"https://github.com/KartikLangwan/K4Audio/releases/tag/v2.15.0","draft":false,"prerelease":false,
            "assets":[{"name":"K4Audio-Setup-2.15.0.exe","browser_download_url":"https://github.com/x/y.exe"},
            {"name":"K4Audio-v2.15.0-RELEASE.apk","browser_download_url":"https://github.com/KartikLangwan/K4Audio/releases/download/v2.15.0/K4Audio-v2.15.0-RELEASE.apk"}]}"""
        val release = AppUpdate.parse(json)!!
        assertEquals("2.15.0", release.version)
        assertTrue(release.apkUrl!!.endsWith("K4Audio-v2.15.0-RELEASE.apk"))
    }

    @Test fun `drafts, prereleases and odd tags are ignored`() {
        assertNull(AppUpdate.parse("""{"tag_name":"v2.15.0","draft":true}"""))
        assertNull(AppUpdate.parse("""{"tag_name":"nightly","draft":false}"""))
    }
}
