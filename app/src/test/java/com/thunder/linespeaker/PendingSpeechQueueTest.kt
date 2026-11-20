package com.thunder.linespeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSpeechQueueTest {
    @Test fun rapidNotificationsStayInArrivalOrderBeforeTtsIsReady() {
        val q = PendingSpeechQueue()
        assertTrue(q.offer("ฮานอย VIP", "customer:1", 1000, 90000))
        assertTrue(q.offer("รับเงินเข้า 180 บาทแล้วเด้อ", "payment:2", 1001, 90000))
        assertEquals(2, q.size())
        assertEquals("ฮานอย VIP", q.peek()?.phrase)
        q.markAccepted(1002)
        assertEquals("รับเงินเข้า 180 บาทแล้วเด้อ", q.peek()?.phrase)
        q.markAccepted(1003)
        assertNull(q.peek())
    }
    @Test fun queuedAndRecentlyAcceptedDuplicatesAreSuppressed() {
        val q = PendingSpeechQueue()
        assertTrue(q.offer("a", "id", 1000, 90000))
        assertFalse(q.offer("a", "id", 1001, 90000))
        q.markAccepted(1002)
        assertFalse(q.offer("a", "id", 1003, 90000))
        assertTrue(q.offer("a", "id", 91003, 90000))
    }
    @Test fun ttsFailureDoesNotRemoveOrMarkQueuedUtteranceDelivered() {
        val q = PendingSpeechQueue()
        assertTrue(q.offer("payment", "payment:id", 1000, 90000))
        // No markAccepted() means TTS refused the utterance.
        assertEquals("payment", q.peek()?.phrase)
        assertFalse(q.offer("payment", "payment:id", 2000, 90000))
        q.markAccepted(3000)
        assertNull(q.peek())
    }
    @Test fun stalePendingMessagesExpireWithoutBeingMarkedAsSpoken() {
        val q = PendingSpeechQueue()
        assertTrue(q.offer("old", "line:old", 1_000L, 90_000L))
        assertTrue(q.offer("recent", "line:recent", 130_000L, 90_000L))
        assertEquals(1, q.discardStale(135_000L, 120_000L))
        assertEquals("recent", q.peek()?.phrase)
        assertEquals(0, q.discardStale(250_000L, 120_000L))
        assertEquals("recent", q.peek()?.phrase)
        assertEquals(1, q.discardStale(250_001L, 120_000L))
        assertNull(q.peek())
        assertTrue(q.offer("repeated old event", "line:old", 250_002L, 90_000L))
    }

    @Test fun clockRollbackDiscardsFutureQueueEntries() {
        val q = PendingSpeechQueue()
        assertTrue(q.offer("future", "future", 10_000L, 90_000L))
        assertEquals(1, q.discardStale(9_999L, 120_000L))
        assertNull(q.peek())
    }

    @Test fun memoryBoundPreventsUnboundedBacklog() {
        val q = PendingSpeechQueue(capacity = 2)
        assertTrue(q.offer("a", "a", 1, 90000))
        assertTrue(q.offer("b", "b", 2, 90000))
        assertFalse(q.offer("c", "c", 3, 90000))
        assertEquals(2, q.size())
    }
}
