package com.mushafqiyam.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun testCompareSemanticVersions_numericOrder() {
        assertTrue(AppUpdateChecker.compareSemanticVersions("0.7.6.0", "0.7.5.4") > 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("0.7.5.4", "0.7.6.0") < 0)
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("0.7.6.0", "0.7.6.0"))
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("v0.7.6.0", "0.7.6.0"))
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("V0.7.6.0", "0.7.6.0"))

        // Multi-digit segment comparison: 10 > 6
        assertTrue(AppUpdateChecker.compareSemanticVersions("0.7.10.0", "0.7.6.0") > 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("0.7.6.0", "0.7.10.0") < 0)

        // Trailing zeros equivalence
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("0.7.6", "0.7.6.0"))
    }

    @Test
    fun testEvaluateReleasesJson_multiRelease_picksHighestVersion() {
        val json = """
        [
            {
                "tag_name": "v0.7.5.0",
                "name": "Release 0.7.5.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                    {
                        "name": "mushaf-qiyam-b-full.apk",
                        "browser_download_url": "https://example.com/v0.7.5.0-full.apk",
                        "size": 110000000
                    }
                ]
            },
            {
                "tag_name": "v0.7.6.0",
                "name": "Release 0.7.6.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                    {
                        "name": "mushaf-qiyam-v0.7.6.0-lite.apk",
                        "browser_download_url": "https://example.com/v0.7.6.0-lite.apk",
                        "size": 42000000
                    }
                ]
            }
        ]
        """.trimIndent()

        val result = AppUpdateChecker.evaluateReleasesJson(json, "0.7.5.4")
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val update = result as UpdateCheckResult.UpdateAvailable
        assertEquals("v0.7.6.0", update.release.tagName)
        assertEquals("0.7.6.0", update.release.versionString)
        assertNotNull(update.release.selectedAsset)
        assertTrue(update.release.selectedAsset!!.isLite)
        assertEquals("mushaf-qiyam-v0.7.6.0-lite.apk", update.release.selectedAsset!!.name)
    }

    @Test
    fun testEvaluateReleasesJson_installedEqualsRemote_returnsUpToDate() {
        val json = """
        [
            {
                "tag_name": "v0.7.6.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                    { "name": "app-lite.apk", "browser_download_url": "https://example.com/lite.apk", "size": 40000000 }
                ]
            }
        ]
        """.trimIndent()

        val result = AppUpdateChecker.evaluateReleasesJson(json, "0.7.6.0")
        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("0.7.6.0", (result as UpdateCheckResult.UpToDate).currentVersion)
    }

    @Test
    fun testEvaluateReleasesJson_installedNewerThanRemote_returnsUpToDateNoDowngrade() {
        val json = """
        [
            {
                "tag_name": "v0.7.5.4",
                "draft": false,
                "prerelease": false,
                "assets": [
                    { "name": "app-lite.apk", "browser_download_url": "https://example.com/lite.apk", "size": 40000000 }
                ]
            }
        ]
        """.trimIndent()

        val result = AppUpdateChecker.evaluateReleasesJson(json, "0.7.6.0")
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun testEvaluateReleasesJson_emptyAssetsOrDraft_discardedByGate1() {
        val json = """
        [
            {
                "tag_name": "v0.7.7.0",
                "draft": false,
                "prerelease": false,
                "assets": []
            },
            {
                "tag_name": "v0.7.6.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                    { "name": "mushaf-lite.apk", "browser_download_url": "https://example.com/lite.apk", "size": 40000000 }
                ]
            }
        ]
        """.trimIndent()

        val result = AppUpdateChecker.evaluateReleasesJson(json, "0.7.5.4")
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val update = result as UpdateCheckResult.UpdateAvailable
        // v0.7.7.0 had no APK assets, so Gate 1 correctly discarded it and picked v0.7.6.0!
        assertEquals("v0.7.6.0", update.release.tagName)
    }
}
