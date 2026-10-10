package de.chris.dexreloaded.ui

import android.content.Context
import android.content.Intent

/** Eine startbare App. [component] ist "paket/activity". */
data class AppEntry(
    val label: String,
    val packageName: String,
    val component: String,
)

object Apps {
    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map {
                val ai = it.activityInfo
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    component = ai.packageName + "/" + ai.name,
                )
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }
}
