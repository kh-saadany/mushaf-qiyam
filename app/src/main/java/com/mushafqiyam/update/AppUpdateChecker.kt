package com.mushafqiyam.update

import android.content.Context
import android.content.pm.PackageManager
import com.mushafqiyam.AppLogger
import com.mushafqiyam.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val isLite: Boolean
)

data class ReleaseInfo(
    val tagName: String,
    val versionString: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val selectedAsset: ReleaseAsset?,
    val publishedAt: String
)

sealed interface UpdateCheckResult {
    data object Idle : UpdateCheckResult
    data object Checking : UpdateCheckResult
    data class UpdateAvailable(val currentVersion: String, val release: ReleaseInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Error(
        val message: String,
        val releasesPageUrl: String = "https://github.com/kh-saadany/mushaf-qiyam/releases"
    ) : UpdateCheckResult
}

object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/kh-saadany/mushaf-qiyam/releases?per_page=10"

    /**
     * Reads actual installed version name from PackageManager with fallback to MainActivity.APP_VERSION.
     */
    fun getInstalledVersionName(context: Context): String {
        return try {
            val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: MainActivity.APP_VERSION
        } catch (_: Exception) {
            MainActivity.APP_VERSION
        }
    }

    /**
     * Compares two semantic version strings numerically (e.g. "0.7.6.0" vs "0.7.5.4" or "0.7.10.0" vs "0.7.6.0").
     * Strips leading 'v' / 'V' and pre-release suffix (-pre, -beta).
     * Returns:
     *   > 0 if v1 > v2
     *   < 0 if v1 < v2
     *   0 if v1 == v2
     */
    fun compareSemanticVersions(v1: String, v2: String): Int {
        val clean1 = v1.trim().removePrefix("v").removePrefix("V").substringBefore("-").trim()
        val clean2 = v2.trim().removePrefix("v").removePrefix("V").substringBefore("-").trim()

        val parts1 = clean1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = clean2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = if (i < parts1.size) parts1[i] else 0
            val num2 = if (i < parts2.size) parts2[i] else 0
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }
        return 0
    }

    /**
     * Pure, isolated, 4-Gate evaluation pipeline from GitHub Releases JSON Array.
     * Easy to unit-test without real network calls.
     */
    fun evaluateReleasesJson(jsonArrayString: String, installedVersion: String): UpdateCheckResult {
        return try {
            val jsonArray = JSONArray(jsonArrayString)
            if (jsonArray.length() == 0) {
                return UpdateCheckResult.UpToDate(installedVersion)
            }

            var bestReleaseJson: JSONObject? = null
            var bestReleaseTag = ""

            // Gate 1 & Gate 2: Filter valid releases and pick the strictly highest semantic version
            for (i in 0 until jsonArray.length()) {
                val rel = jsonArray.optJSONObject(i) ?: continue

                // Gate 1: Discard drafts and prereleases
                if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) {
                    continue
                }

                val tag = rel.optString("tag_name", "").trim()
                if (tag.isBlank()) continue

                // Gate 1 check: must have at least one .apk asset
                val assets = rel.optJSONArray("assets") ?: continue
                var hasApk = false
                for (a in 0 until assets.length()) {
                    val asset = assets.optJSONObject(a) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        hasApk = true
                        break
                    }
                }
                if (!hasApk) continue

                // Gate 2: Find highest semantic version among valid releases
                if (bestReleaseJson == null || compareSemanticVersions(tag, bestReleaseTag) > 0) {
                    bestReleaseJson = rel
                    bestReleaseTag = tag
                }
            }

            if (bestReleaseJson == null) {
                return UpdateCheckResult.UpToDate(installedVersion)
            }

            // Gate 3: Strict Upgrade Gate (remoteVersion > installedVersion)
            val upgradeDiff = compareSemanticVersions(bestReleaseTag, installedVersion)
            if (upgradeDiff <= 0) {
                return UpdateCheckResult.UpToDate(installedVersion)
            }

            // Gate 4: Asset Selection inside the proven-newer release
            val assets = bestReleaseJson.optJSONArray("assets") ?: JSONArray()
            var selectedAsset: ReleaseAsset? = null

            // Prioritize -lite.apk asset
            for (a in 0 until assets.length()) {
                val assetObj = assets.optJSONObject(a) ?: continue
                val aName = assetObj.optString("name", "")
                val dUrl = assetObj.optString("browser_download_url", "")
                val size = assetObj.optLong("size", 0L)
                if (aName.endsWith("-lite.apk", ignoreCase = true)) {
                    selectedAsset = ReleaseAsset(
                        name = aName,
                        downloadUrl = dUrl,
                        sizeBytes = size,
                        isLite = true
                    )
                    break
                }
            }

            // Fallback to any .apk asset if -lite.apk is absent
            if (selectedAsset == null) {
                for (a in 0 until assets.length()) {
                    val assetObj = assets.optJSONObject(a) ?: continue
                    val aName = assetObj.optString("name", "")
                    val dUrl = assetObj.optString("browser_download_url", "")
                    val size = assetObj.optLong("size", 0L)
                    if (aName.endsWith(".apk", ignoreCase = true)) {
                        selectedAsset = ReleaseAsset(
                            name = aName,
                            downloadUrl = dUrl,
                            sizeBytes = size,
                            isLite = false
                        )
                        break
                    }
                }
            }

            val releaseInfo = ReleaseInfo(
                tagName = bestReleaseTag,
                versionString = bestReleaseTag.removePrefix("v").removePrefix("V"),
                name = bestReleaseJson.optString("name", bestReleaseTag),
                body = bestReleaseJson.optString("body", ""),
                htmlUrl = bestReleaseJson.optString("html_url", "https://github.com/kh-saadany/mushaf-qiyam/releases"),
                selectedAsset = selectedAsset,
                publishedAt = bestReleaseJson.optString("published_at", "")
            )

            UpdateCheckResult.UpdateAvailable(installedVersion, releaseInfo)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error evaluating releases JSON", e)
            UpdateCheckResult.Error("تعذر تحليل بيانات التحديث: ${e.message}")
        }
    }

    /**
     * Executes the GitHub Releases check on Dispatchers.IO using built-in HttpURLConnection (Zero-Bloat).
     */
    suspend fun checkForUpdate(context: Context): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            val installed = getInstalledVersionName(context)
            var connection: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_RELEASES_URL)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "MushafQiyam-Android")
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.use { it.readText() }
                    evaluateReleasesJson(response, installed)
                } else if (responseCode == 403 || responseCode == 429) {
                    UpdateCheckResult.Error("تم تجاوز حد استعلامات GitHub، يرجى المحاولة لاحقاً.")
                } else {
                    UpdateCheckResult.Error("خطأ في الاتصال بالخادم ($responseCode)")
                }
            } catch (_: UnknownHostException) {
                UpdateCheckResult.Error("تعذر الاتصال بالإنترنت، يرجى التحقق من اتصال جهازك.")
            } catch (e: java.io.IOException) {
                UpdateCheckResult.Error("تعذر إتمام الاتصال بخادم التحديثات: ${e.message}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Unexpected error checking for updates", e)
                UpdateCheckResult.Error("حدث خطأ أثناء فحص التحديثات: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
