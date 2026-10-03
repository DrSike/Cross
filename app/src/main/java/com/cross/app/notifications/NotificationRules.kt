package com.cross.app.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Per-package rule with explicit allow/block semantics and a safe default. */
data class NotificationRule(val packageName: String, val allowed: Boolean)

/** Pure rule evaluation kept separate so it can be tested without Android framework state. */
object NotificationRuleMatcher {
    fun isAllowed(rules: List<NotificationRule>, packageName: String): Boolean = rules.firstOrNull { it.packageName == packageName }?.allowed ?: true
}

class NotificationRules(context: Context) {
    private val prefs = context.getSharedPreferences("cross_notification_rules", Context.MODE_PRIVATE)
    private val _rules = MutableStateFlow(load())
    val rules: StateFlow<List<NotificationRule>> = _rules.asStateFlow()

    fun isAllowed(packageName: String): Boolean = NotificationRuleMatcher.isAllowed(_rules.value, packageName)

    fun setAllowed(packageName: String, allowed: Boolean) {
        val updated = (_rules.value.filterNot { it.packageName == packageName } + NotificationRule(packageName, allowed)).sortedBy { it.packageName }
        _rules.value = updated
        prefs.edit().putString("rules", JSONArray().apply { updated.forEach { put(org.json.JSONObject().put("package", it.packageName).put("allowed", it.allowed)) } }.toString()).apply()
    }

    private fun load(): List<NotificationRule> = runCatching {
        val json = JSONArray(prefs.getString("rules", "[]"))
        (0 until json.length()).map { i -> val item = json.getJSONObject(i); NotificationRule(item.getString("package"), item.optBoolean("allowed", true)) }
    }.getOrDefault(emptyList())
}
