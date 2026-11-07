package io.github.ryoaonetsuki.mira.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.local.entities.ConversationEntity
import io.github.ryoaonetsuki.mira.ui.screens.safeInsertMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatDaoForeignConstraintRegressionTest {

    private lateinit var context: Context
    private lateinit var db: MiraDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MiraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Ensure SQLite enforces foreign keys in this connection
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * PROOF OF ROOT CAUSE:
     * Directly calling insertMessage with a conversationId that has NO corresponding
     * row in the 'conversations' table MUST fail with SQLiteConstraintException (code 787).
     * This reproduces the exact crash seen in production on fresh install / empty DB.
     */
    @Test
    fun testDirectInsertMessage_ThrowsSQLiteConstraintException_WhenParentConversationMissing() = runBlocking {
        val nonExistentConvoId = "default_chat"

        // Confirm parent row does not exist
        assertNull(db.chatDao().getConversationById(nonExistentConvoId))

        val danglingMessage = ChatMessageEntity(
            conversationId = nonExistentConvoId,
            role = "user",
            content = "Hello Mira"
        )

        var constraintExceptionThrown = false
        try {
            db.chatDao().insertMessage(danglingMessage)
        } catch (e: SQLiteConstraintException) {
            constraintExceptionThrown = true
            assertTrue(
                "Exception message must mention FOREIGN KEY constraint failure",
                e.message?.contains("FOREIGN KEY", ignoreCase = true) == true ||
                        e.message?.contains("constraint failed", ignoreCase = true) == true
            )
        }

        assertTrue(
            "Direct insertMessage must fail with SQLiteConstraintException when conversation row is missing",
            constraintExceptionThrown
        )
    }

    /**
     * PROOF OF FIX (Step 1):
     * Calling insertMessageEnsuringConversation automatically creates the missing parent
     * conversation row in the same transaction, successfully persisting both the conversation
     * and the message without throwing any constraint exception.
     */
    @Test
    fun testInsertMessageEnsuringConversation_CreatesParentAndMessage_WhenParentMissing() = runBlocking {
        val convoId = "default_chat"

        // Confirm parent row does not exist initially
        assertNull(db.chatDao().getConversationById(convoId))

        val userMessage = ChatMessageEntity(
            conversationId = convoId,
            role = "user",
            content = "Hello, this is the first message on fresh install"
        )

        val messageId = db.chatDao().insertMessageEnsuringConversation(userMessage)
        assertTrue("Inserted message ID must be positive", messageId > 0)

        // Verify parent conversation was automatically created
        val conversation = db.chatDao().getConversationById(convoId)
        assertNotNull("Parent conversation row must now exist in DB", conversation)
        assertEquals(convoId, conversation?.id)
        assertEquals("New Chat", conversation?.title)

        // Verify message was stored and retrieved correctly
        val messages = db.chatDao().getMessagesForConversation(convoId).first()
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello, this is the first message on fresh install", messages[0].content)
        assertEquals(convoId, messages[0].conversationId)
    }

    /**
     * PROOF OF MULTI-MESSAGE ROUTING:
     * Tests short prompt, long prompt (multi-KB / multi-line), and image generation user/model messages.
     * Also asserts that CASCADE delete still works as expected (deleting conversation removes all messages).
     */
    @Test
    fun testMessageRouting_ShortLongAndImageGen_AndCascadeDelete() = runBlocking {
        val convoId = "test_conversation_flow"

        // 1. Short user message
        val shortMsg = ChatMessageEntity(
            conversationId = convoId,
            role = "user",
            content = "Hi"
        )
        db.chatDao().insertMessageEnsuringConversation(shortMsg)

        // 2. Long multi-line message (multi-KB text prompt)
        val longContent = "Line:\n" + "Detailed instruction text for Mira AI with arguments and context. ".repeat(80)
        val longMsg = ChatMessageEntity(
            conversationId = convoId,
            role = "user",
            content = longContent
        )
        db.chatDao().insertMessageEnsuringConversation(longMsg)

        // 3. Image-generation user message
        val imagePrompt = "a cyberpunk cat floating in space"
        val imageUserMsg = ChatMessageEntity(
            conversationId = convoId,
            role = "user",
            content = imagePrompt
        )
        db.chatDao().insertMessageEnsuringConversation(imageUserMsg)

        // 4. Image-generation model response
        val imageUrl = "https://image.pollinations.ai/prompt/cyberpunk%20cat?width=1024&height=1024"
        val imageModelMsg = ChatMessageEntity(
            conversationId = convoId,
            role = "model",
            content = "Here is your image:\n\n![$imagePrompt]($imageUrl)"
        )
        db.chatDao().insertMessageEnsuringConversation(imageModelMsg)

        // Verify all 4 messages exist in conversation
        val messages = db.chatDao().getMessagesForConversation(convoId).first()
        assertEquals(4, messages.size)
        assertEquals("Hi", messages[0].content)
        assertEquals(longContent, messages[1].content)
        assertEquals(imagePrompt, messages[2].content)
        assertTrue(messages[3].content.contains("Here is your image"))

        // Verify CASCADE delete: deleting conversation must delete all its messages
        db.chatDao().deleteConversation(convoId)
        assertNull(db.chatDao().getConversationById(convoId))
        val messagesAfterDelete = db.chatDao().getMessagesForConversation(convoId).first()
        assertTrue("Messages must be cascade deleted when conversation is deleted", messagesAfterDelete.isEmpty())
    }

    /**
     * PROOF OF RESUME PATH (Step 4):
     * When lastConversationId restored from preferences does not exist in DB (stale/deleted id),
     * sending a message creates the conversation on first use rather than crashing on dangling id.
     */
    @Test
    fun testResumePath_StaleSavedId_CreatesRowOnFirstMessage() = runBlocking {
        val staleRestoredId = "restored-stale-uuid-98765"

        // DB does NOT have this ID
        assertNull(db.chatDao().getConversationById(staleRestoredId))

        // When user sends a message in resumed chat:
        val resumeMsg = ChatMessageEntity(
            conversationId = staleRestoredId,
            role = "user",
            content = "Continuing an old chat"
        )
        val msgId = db.chatDao().insertMessageEnsuringConversation(resumeMsg)
        assertTrue(msgId > 0)

        // Conversation now exists
        val convo = db.chatDao().getConversationById(staleRestoredId)
        assertNotNull(convo)
        assertEquals("New Chat", convo?.title)
    }

    /**
     * PROOF OF FAIL-SAFE DEFENSE (Step 3):
     * Verifies safeInsertMessage helper gracefully handles message inserts
     * and guarantees no SQLiteConstraintException escapes uncaught.
     */
    @Test
    fun testSafeInsertMessage_PersistsSuccessfullyWithoutCrashing() = runBlocking {
        val convoId = "safe_test_convo"
        val testMsg = ChatMessageEntity(
            conversationId = convoId,
            role = "user",
            content = "Safe insert test"
        )

        val insertedId = safeInsertMessage(context, db, testMsg)
        assertNotNull("safeInsertMessage must return non-null ID on success", insertedId)
        assertTrue(insertedId!! > 0)

        val saved = db.chatDao().getMessagesForConversation(convoId).first()
        assertEquals(1, saved.size)
        assertEquals("Safe insert test", saved[0].content)
    }
}
