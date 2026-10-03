package com.cross.app.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** A user-facing installed app that can be selected for notification forwarding. */
data class InstalledNotificationApp(
    val packageName: String,
    val label: String,
)

/** Discovers app packages through Android's launcher query instead of asking the user to type IDs. */
object InstalledNotificationAppDiscovery {
    fun discover(context: Context): List<InstalledNotificationApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val packageName = appInfo.packageName
                if (packageName == context.packageName || !appInfo.enabled) return@mapNotNull null
                InstalledNotificationApp(
                    packageName = packageName,
                    label = appInfo.loadLabel(packageManager).toString().ifBlank { packageName },
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
