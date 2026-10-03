package com.cross.app.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for allow/block precedence and the safe allow-by-default behavior. */
class NotificationRulesTest {
    @Test
    fun explicitBlockWins() {
        val rules = listOf(NotificationRule("com.example.chat", false), NotificationRule("com.example.mail", true))
        assertFalse(NotificationRuleMatcher.isAllowed(rules, "com.example.chat"))
        assertTrue(NotificationRuleMatcher.isAllowed(rules, "com.example.mail"))
        assertTrue(NotificationRuleMatcher.isAllowed(rules, "com.example.other"))
    }
}
