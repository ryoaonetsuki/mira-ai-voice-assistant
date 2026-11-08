package io.github.ryoaonetsuki.mira.voice

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionResumptionStoreTest {

    private lateinit var context: Context
    private lateinit var store: SessionResumptionStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = SessionResumptionStore(context)
        store.clearHandle()
    }

    @Test
    fun testSavedHandleReturnedWithin2Hours() {
        val now = 1_000_000L
        store.saveHandle("handle-12345", timestampMs = now)

        // Within 2 hours (e.g. 1 hour later = +3_600_000 ms)
        val handle = store.getHandle(currentTimeMs = now + 3_600_000L)
        assertEquals("handle-12345", handle)
    }

    @Test
    fun testConsumeHandleReturnsHandleThenClears() {
        val now = 1_000_000L
        store.saveHandle("handle-single-use", timestampMs = now)

        // First call consumes the handle
        val firstCall = store.consumeHandle(currentTimeMs = now + 1_000L)
        assertEquals("handle-single-use", firstCall)

        // Second call must return null because handle was cleared atomically
        val secondCall = store.consumeHandle(currentTimeMs = now + 2_000L)
        assertNull("Subsequent consumeHandle call must return null", secondCall)

        // getHandle must also be null
        assertNull("getHandle must be null after consumeHandle", store.getHandle(currentTimeMs = now + 3_000L))
    }

    @Test
    fun testHandleOlderThan2HoursRejectedAndCleared() {
        val now = 10_000_000L
        store.saveHandle("expired-handle", timestampMs = now)

        // Exactly 7_200_000 ms later is expired (>= TTL_MS)
        val expiredResult = store.getHandle(currentTimeMs = now + 7_200_000L)
        assertNull("Handle older than 7_200_000 ms must be rejected", expiredResult)

        // Expired handle should also be cleared
        assertNull(store.getHandle(currentTimeMs = now + 1_000L))

        // Also test consumeHandle with handle older than 7_200_000 ms
        store.saveHandle("expired-handle-2", timestampMs = now)
        val expiredConsume = store.consumeHandle(currentTimeMs = now + 7_200_001L)
        assertNull("consumeHandle on expired handle must return null", expiredConsume)
        assertNull(store.getHandle(currentTimeMs = now + 1_000L))
    }

    @Test
    fun testBlankHandleIgnored() {
        store.saveHandle("")
        assertNull(store.getHandle())

        store.saveHandle("   ")
        assertNull(store.getHandle())
    }

    @Test
    fun testClearHandle() {
        store.saveHandle("test-handle")
        assertNotNull(store.getHandle())

        store.clearHandle()
        assertNull(store.getHandle())
    }
}
