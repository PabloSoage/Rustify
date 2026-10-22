package com.varuna.rustify.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatActionsTest {
    @Test
    fun theActionLineIsReadAndHidden() {
        val answer = "Here's Queen.\nACTION: {\"do\": \"play\", \"query\": \"Queen\"}"
        assertEquals(ChatAction.Play("Queen"), ChatActions.parse(answer))
        assertEquals("Here's Queen.", ChatActions.visibleText(answer))
    }

    @Test
    fun whatTheModelWrapsAroundTheJsonDoesNotMatter() {
        val answer = "Added!\n`ACTION: {\"do\":\"queue\",\"query\":\"Eagles - Hotel California\"}`"
        assertEquals(ChatAction.Queue("Eagles - Hotel California"), ChatActions.parse(answer))
        assertEquals("Added!", ChatActions.visibleText(answer))
        assertEquals(ChatAction.Next, ChatActions.parse("Skipping.\naction: {\"do\": \"skip\"}"))
    }

    @Test
    fun aMixTakesItsRequestOrFallsBackToTheQuery() {
        assertEquals(ChatAction.Dj("chill for studying"), ChatActions.parse("ACTION: {\"do\":\"dj\",\"request\":\"chill for studying\"}"))
        assertEquals(ChatAction.Dj("80s rock"), ChatActions.parse("ACTION: {\"do\":\"dj\",\"query\":\"80s rock\"}"))
    }

    @Test
    fun noActionUnreadableOrUnknownIsNothing() {
        assertNull(ChatActions.parse("Bohemian Rhapsody is from A Night at the Opera."))
        assertNull(ChatActions.parse("ACTION: {\"do\": \"play\"}"))
        assertNull(ChatActions.parse("ACTION: {\"do\": \"delete_library\"}"))
        assertNull(ChatActions.parse("ACTION: not json"))
    }

    @Test
    fun aHalfWrittenActionLineIsAlreadyHiddenWhileStreaming() {
        assertEquals("Here's Queen.", ChatActions.visibleText("Here's Queen.\nACTION: {\"do\": \"pl"))
        assertEquals("Here's Queen.", ChatActions.visibleText("Here's Queen.\nACTI"))
        assertEquals("A", ChatActions.visibleText("A"))
        assertEquals("Just talking.", ChatActions.visibleText("Just talking."))
    }
}
