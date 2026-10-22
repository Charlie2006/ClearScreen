package com.qingping.app

import android.content.Context
import java.util.Date

data class AppStat(
    val packageName: String,
    val label: String,
    val count: Long,
    val lastBlockedAt: Long
)

object StatsStore {
    const val PREFS_NAME = "qingping_stats"
    const val KEY_AUTO_BACK = "auto_back"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return

        val p = prefs(context)
        val countKey = "count:$packageName"
        val nextCount = p.getLong(countKey, 0L) + 1L
        val label = resolveAppLabel(context, packageName)

        p.edit()
            .putLong(countKey, nextCount)
            .putLong("last:$packageName", System.currentTimeMillis())
            .putString("label:$packageName", label)
            .apply()
    }

    fun getStats(context: Context): List<AppStat> {
        val p = prefs(context)
        return p.all.keys
            .filter { it.startsWith("count:") }
            .mapNotNull { key ->
                val pkg = key.removePrefix("count:")
                val count = p.getLong(key, 0L)
                if (count <= 0L) {
                    null
                } else {
                    val savedLabel = p.getString("label:$pkg", null)
                    AppStat(
                        packageName = pkg,
                        label = savedLabel?.takeIf { it.isNotBlank() }
                            ?: resolveAppLabel(context, pkg),
                        count = count,
                        lastBlockedAt = p.getLong("last:$pkg", 0L)
                    )
                }
            }
            .sortedWith(compareByDescending<AppStat> { it.count }.thenBy { it.label.lowercase() })
    }

    fun clearStats(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        p.all.keys
            .filter { it.startsWith("count:") || it.startsWith("last:") || it.startsWith("label:") }
            .forEach { editor.remove(it) }
        // 保留用户对“疑似跳转后自动返回”的选择。
        editor.apply()
    }

    fun autoBackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_BACK, false)

    fun setAutoBackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_BACK, enabled).apply()
    }

    fun resolveAppLabel(context: Context, packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "暂无记录"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(Date(timestamp))
    }
}
