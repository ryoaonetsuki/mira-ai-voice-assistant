package io.github.ryoaonetsuki.mira.service

import ai.pipecat.client.types.Value
import android.content.Context
import androidx.room.Room
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoiceMemoryToolsTest {

    private lateinit var context: Context
    private lateinit var db: MiraDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MiraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testExecuteMemoryTool_SaveRecallForgetRoundTrip() = runBlocking {
        // 1. Unknown tool returns null (caller falls through)
        val nullResult = executeMemoryTool(db, "unknown_tool", emptyMap())
        assertNull("Unknown tool must return null", nullResult)

        // 2. Remember fact
        val saveRes = executeMemoryTool(
            db,
            "remember_fact",
            mapOf("category" to "Preferences", "fact" to "User loves green matcha tea")
        )
        assertNotNull("remember_fact must return Value.Object", saveRes)
        assertEquals(Value.Bool(true), saveRes?.value?.get("success"))
        assertEquals(Value.Str("Saved."), saveRes?.value?.get("result"))

        // Verify DB directly
        val count = db.memoryDao().getMemoryCount()
        assertEquals(1, count)
        val savedEntity = db.memoryDao().getMemoryByContent("User loves green matcha tea")
        assertNotNull(savedEntity)
        assertEquals("Preferences", savedEntity?.category)
        assertEquals(2, savedEntity?.importance)
        assertEquals("explicit", savedEntity?.origin)

        // Deduplication update
        val updateRes = executeMemoryTool(
            db,
            "remember_fact",
            mapOf("category" to "Favorites", "fact" to "User loves green matcha tea")
        )
        assertNotNull(updateRes)
        assertEquals(1, db.memoryDao().getMemoryCount())
        val updatedEntity = db.memoryDao().getMemoryByContent("User loves green matcha tea")
        assertEquals("Favorites", updatedEntity?.category)

        // 3. Recall memory
        val recallRes = executeMemoryTool(
            db,
            "recall_memory",
            mapOf("query" to "green matcha tea")
        )
        assertNotNull(recallRes)
        assertEquals(Value.Bool(true), recallRes?.value?.get("success"))
        val recallText = (recallRes?.value?.get("result") as? Value.Str)?.value ?: ""
        assertTrue(recallText.contains("User loves green matcha tea"))
        assertTrue(recallText.contains("[Favorites]"))

        // Unmatched recall query
        val unmatchRecall = executeMemoryTool(
            db,
            "recall_memory",
            mapOf("query" to "chocolate cake")
        )
        assertNotNull(unmatchRecall)
        assertEquals(Value.Bool(true), unmatchRecall?.value?.get("success"))
        assertEquals(Value.Str("No saved memories match that."), unmatchRecall?.value?.get("result"))

        // 4. Forget memory
        val forgetRes = executeMemoryTool(
            db,
            "forget_memory",
            mapOf("fact" to "matcha")
        )
        assertNotNull(forgetRes)
        assertEquals(Value.Bool(true), forgetRes?.value?.get("success"))
        val forgetText = (forgetRes?.value?.get("result") as? Value.Str)?.value ?: ""
        assertTrue(forgetText.contains("Deleted 1 saved memories."))
        assertEquals(0, db.memoryDao().getMemoryCount())

        // Forget non-existing
        val forgetNone = executeMemoryTool(
            db,
            "forget_memory",
            mapOf("fact" to "matcha")
        )
        assertNotNull(forgetNone)
        assertEquals(Value.Bool(true), forgetNone?.value?.get("success"))
        assertEquals(Value.Str("No saved memories matched that."), forgetNone?.value?.get("result"))
    }

    @Test
    fun testExecuteMemoryTool_SensitiveFact_NeverStored() = runBlocking {
        val saveRes = executeMemoryTool(
            db,
            "remember_fact",
            mapOf("category" to "Security", "fact" to "my password is secret123")
        )
        assertNotNull(saveRes)
        assertEquals(Value.Bool(true), saveRes?.value?.get("success"))
        assertEquals(
            Value.Str("Not saved: that looks like a password, code or card number."),
            saveRes?.value?.get("result")
        )
        assertEquals(0, db.memoryDao().getMemoryCount())
    }

    @Test
    fun testExecuteMemoryTool_EmptyArgs_ReturnFailure() = runBlocking {
        val emptyRemember = executeMemoryTool(db, "remember_fact", emptyMap())
        assertEquals(Value.Bool(false), emptyRemember?.value?.get("success"))
        assertEquals(
            Value.Str("Nothing to remember: the fact was empty."),
            emptyRemember?.value?.get("result")
        )

        val emptyRecall = executeMemoryTool(db, "recall_memory", emptyMap())
        assertEquals(Value.Bool(false), emptyRecall?.value?.get("success"))
        assertEquals(
            Value.Str("Nothing to recall: the query was empty."),
            emptyRecall?.value?.get("result")
        )

        val emptyForget = executeMemoryTool(db, "forget_memory", emptyMap())
        assertEquals(Value.Bool(false), emptyForget?.value?.get("success"))
        assertEquals(
            Value.Str("Nothing to forget: the fact was empty."),
            emptyForget?.value?.get("result")
        )
    }
}
