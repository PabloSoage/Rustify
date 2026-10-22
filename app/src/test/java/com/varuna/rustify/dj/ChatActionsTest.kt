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
    fun onlyAMessageThatAsksForSomethingRunsAnAction() {
        // Seen on the phone: a question about a cover came back with a search nobody asked for.
        assertEquals(false, ChatActions.isRequest("What album is this cover from?"))
        assertEquals(false, ChatActions.isRequest("¿Quién canta esta canción?"))
        assertEquals(false, ChatActions.isRequest("この曲は誰が歌っていますか？"))
        assertEquals(true, ChatActions.isRequest("Play something by Queen"))
        assertEquals(true, ChatActions.isRequest("Can you add Hotel California to the queue?"))
        assertEquals(true, ChatActions.isRequest("Pon algo de Queen"))
        assertEquals(true, ChatActions.isRequest("Hazme una mezcla tranquila para estudiar"))
        assertEquals(true, ChatActions.isRequest("クイーンの曲を再生して"))
        // A word that only contains a request word is not one: "display" is not "play".
        assertEquals(false, ChatActions.isRequest("Is the display on this cover a hotel?"))
    }

    @Test
    fun aHalfWrittenActionLineIsAlreadyHiddenWhileStreaming() {
        assertEquals("Here's Queen.", ChatActions.visibleText("Here's Queen.\nACTION: {\"do\": \"pl"))
        assertEquals("Here's Queen.", ChatActions.visibleText("Here's Queen.\nACTI"))
        assertEquals("A", ChatActions.visibleText("A"))
        assertEquals("Just talking.", ChatActions.visibleText("Just talking."))
    }
}
