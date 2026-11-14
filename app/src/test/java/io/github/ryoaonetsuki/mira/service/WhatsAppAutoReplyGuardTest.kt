package io.github.ryoaonetsuki.mira.service

import org.junit.Test
import org.junit.Assert.*

class WhatsAppAutoReplyGuardTest {

    @Test
    fun duplicateSameMessage_repliesOnce() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertTrue(guard.shouldReply("conv1", "Hello", "Template", "key1", now, now))
        assertFalse(guard.shouldReply("conv1", "Hello", "Template", "key1", now, now))
    }

    @Test
    fun templateText_neverReplies() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertFalse(guard.shouldReply("conv1", "Template", "Template", "key1", now, now))
    }

    @Test
    fun blankTemplate_neverReplies() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertFalse(guard.shouldReply("conv1", "Hello", "", "key1", now, now))
        assertFalse(guard.shouldReply("conv1", "Hello", "   ", "key1", now, now))
    }

    @Test
    fun ownMessage_neverReplies() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertFalse(guard.shouldReply("conv1", null, "Template", "key1", now, now))
        assertFalse(guard.shouldReply("conv1", "", "Template", "key1", now, now))
        assertFalse(guard.shouldReply("conv1", "   ", "Template", "key1", now, now))
    }

    @Test
    fun twoDifferentConversations_bothReply() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertTrue(guard.shouldReply("conv1", "Hello", "Template", "key1", now, now))
        assertTrue(guard.shouldReply("conv2", "Hi", "Template", "key2", now, now))
    }

    @Test
    fun rateLimit_blocksSecondMessageWithin60s() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertTrue(guard.shouldReply("conv1", "Hello", "Template", "key1", now, now))
        assertFalse(guard.shouldReply("conv1", "Hello again", "Template", "key2", now, now + 10_000))
    }

    @Test
    fun newMessageAfter60s_repliesAgain() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertTrue(guard.shouldReply("conv1", "Hello", "Template", "key1", now, now))
        assertTrue(guard.shouldReply("conv1", "Hello again", "Template", "key2", now, now + 61_000))
    }

    @Test
    fun staleNotification_neverReplies() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertFalse(guard.shouldReply("conv1", "Hello", "Template", "key1", now - 121_000, now))
    }

    @Test
    fun sameMessageAfterDedupWindow_repliesAgain() {
        val guard = WhatsAppAutoReplyGuard()
        val now = System.currentTimeMillis()
        assertTrue(guard.shouldReply("conv1", "Hello", "Template", "key1", now, now))
        // Fresh re-post 301s later: fresh postTime (recency passes), dedup window expired.
        assertTrue(guard.shouldReply("conv1", "Hello", "Template", "key1", now + 301_000, now + 301_000))
    }
}
