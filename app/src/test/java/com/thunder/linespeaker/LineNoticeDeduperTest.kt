package com.thunder.linespeaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineNoticeDeduperTest {
    @Test fun repeatedUpdatesOfSameNotificationDoNotRepeatVoice() {
        val deduper = LineNoticeDeduper()
        assertTrue(deduper.accept("line-chat-A", "สมหญิง|ส่งรูปภาพ|1000", 1_000L))
        assertFalse(deduper.accept("line-chat-A", "สมหญิง|ส่งรูปภาพ|1000", 2_000L))
        assertTrue(deduper.accept("line-chat-A", "สมหญิง|ส่งรูปภาพ|1000", 122_000L))
    }

    @Test fun identicalMessagesWithDifferentLineTimestampsBothAnnounce() {
        val deduper = LineNoticeDeduper()
        assertTrue(deduper.accept("same-chat", "ส่งรูปภาพ|1000", 100L))
        assertTrue(deduper.accept("same-chat", "ส่งรูปภาพ|1200", 200L))
        assertFalse(deduper.accept("same-chat", "ส่งรูปภาพ|1200", 300L))
    }

    @Test fun outOfOrderRepostsDoNotRepeatOlderMessage() {
        val deduper = LineNoticeDeduper()
        assertTrue(deduper.accept("chat", "old-at-1000", 1_000L))
        assertTrue(deduper.accept("chat", "new-at-2000", 1_100L))
        assertFalse(deduper.accept("chat", "old-at-1000", 1_200L))
        assertFalse(deduper.accept("chat", "new-at-2000", 1_300L))
    }

    @Test fun differentContentsOfSameNotificationAnnounce() {
        val deduper = LineNoticeDeduper()
        assertTrue(deduper.accept("line", "ยอด 100", 1L))
        assertTrue(deduper.accept("line", "ยอด 200", 2L))
    }

    @Test fun differentNotificationsWithIdenticalTextAnnounce() {
        val deduper = LineNoticeDeduper()
        assertTrue(deduper.accept("chat-one", "มีสลิป", 100L))
        assertTrue(deduper.accept("chat-two", "มีสลิป", 100L))
    }

    @Test fun invalidEntriesAreRejectedAndCapacityIsBounded() {
        val deduper = LineNoticeDeduper(capacity = 1)
        assertFalse(deduper.accept("", "x", 0L))
        assertFalse(deduper.accept("chat", "", 0L))
        assertTrue(deduper.accept("A", "one", 0L))
        assertTrue(deduper.accept("B", "two", 1L))
        assertTrue(deduper.accept("A", "one", 2L))
    }
}
