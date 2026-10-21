package com.varuna.rustify.webplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdblockBuiltinTest {
    // One of these slipping into the built-in rules would be a web player that loads and never plays.
    private val content = listOf("spclient", "audio", "scdn", "apresolve", "weblb", "open.spotify.com", "api.spotify.com")

    @Test
    fun noBuiltinRuleTouchesAHostThePlayerLoadsFrom() {
        AdblockFilters.BUILTIN.forEach { rule ->
            content.forEach { host -> assertFalse("$rule blocks $host", rule.contains(host)) }
        }
    }

    @Test
    fun everyRuleIsADomainAnchor() {
        AdblockFilters.BUILTIN.forEach { assertTrue(it, it.startsWith("||") && it.endsWith("^")) }
    }
}
