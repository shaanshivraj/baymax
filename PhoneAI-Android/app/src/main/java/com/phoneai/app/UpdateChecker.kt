package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {

    // Automatically reads the versionCode from build.gradle —
    // GitHub Actions bumps versionCode before building, so this
    // value is always in sync with what's installed on the phone.
    private val CURRENT_VERSION get() = BuildConfig.VERSION_CODE

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/shaanshivraj/baymax/main/version.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val latestVersion: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    /** Call from MainActivity.onCreate — checks silently, shows dialog only if update found */
    suspend fun checkAndPrompt(activity: Activity) {
        val info = fetchUpdateInfo() ?: return
        if (info.latestVersion <= CURRENT_VERSION) return   // already up to date

        withContext(Dispatchers.Main) {
            showUpdateDialog(activity, info)
        }
    }

    private suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(VERSION_URL).build()
            val body = http.newCall(request).execute().body?.string() ?: return@withContext null
            val json = JSONObject(body)
            UpdateInfo(
                latestVersion = json.getInt("version_code"),
                versionName   = json.getString("version_name"),
                apkUrl        = json.getString("apk_url"),
                changelog     = json.optString("changelog", "Bug fixes and improvements")
            )
        } catch (e: Exception) {
            Log.d("UpdateChecker", "No update info: ${e.message}")
            null
        }
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("🆕 Update Available — v${info.versionName}")
            .setMessage("What's new:\n${info.changelog}")
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstall(activity, info.apkUrl, info.versionName)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, url: String, versionName: String) {
        val fileName = "PhoneAI-v$versionName.apk"
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("PhoneAI Update")
            setDescription("Downloading v$versionName...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // Listen for download completion → trigger install
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    activity.unregisterReceiver(this)
                    installApk(activity, fileName)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installApk(context: Context, fileName: String) {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
