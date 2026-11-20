package com.thunder.linespeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineNoticeBatchSelectorTest {
    private fun item(text: String, time: Long, sender: String = "ลูกค้า", picture: Boolean = false) =
        LineNoticeItem(sender, text, picture, time)

    @Test fun readsSeveralRecentMessagesInsteadOfOnlyLatest() {
        val now = 5_000_000L
        val items = listOf(
            item("เก่ามาก", now - 180_000L),
            item("ส่งรูปภาพ", now - 3_000L, picture = true),
            item("ยอดเงิน 180 บาท", now - 2_000L),
            item("สวัสดี", now - 1_000L)
        )
        assertEquals(listOf("ส่งรูปภาพ", "ยอดเงิน 180 บาท", "สวัสดี"),
            LineNoticeBatchSelector.fresh(items, now).map { it.body })
    }

    @Test fun mixedMediaWithSameTimeAndSenderIsOneNotice() {
        val now = 1_000_000L
        val selected = LineNoticeBatchSelector.fresh(
            listOf(item("ส่งสลิป", now - 1000L), item("", now - 1000L, picture = true)),
            now
        )
        assertEquals(1, selected.size)
        assertEquals("ส่งสลิป", selected.first().body)
        assertTrue(selected.first().hasPicture)
    }

    @Test fun sameTimeFromDifferentSendersStaysSeparate() {
        val now = 1_000_000L
        val selected = LineNoticeBatchSelector.fresh(
            listOf(item("ส่งรูปภาพ", now, "สมหญิง"), item("ส่งรูปภาพ", now, "สมชาย")),
            now
        )
        assertEquals(2, selected.size)
    }

    @Test fun unknownTimestampsOnlyTakeLatestInsteadOfReplayingEntireHistory() {
        val selected = LineNoticeBatchSelector.fresh(
            listOf(item("ข้อความก่อนหน้า", 0), item("ล่าสุด", 0)), 1_000_000L
        )
        assertEquals(listOf("ล่าสุด"), selected.map { it.body })
    }

    @Test fun rejectsFutureAndExpiredHistoryButAcceptsClockSkewBoundary() {
        val now = 1_000_000L
        val selected = LineNoticeBatchSelector.fresh(
            listOf(
                item("old", now - 120_001L),
                item("boundary", now - 120_000L),
                item("allowed future", now + 60_000L),
                item("bad future", now + 60_001L)
            ), now
        )
        assertEquals(listOf("boundary", "allowed future"), selected.map { it.body })
    }

    @Test fun twoDistinctTextMessagesAtTheSameMillisecondStaySeparate() {
        val now = 1_000_000L
        val selected = LineNoticeBatchSelector.fresh(
            listOf(item("ข้อความ A", now), item("ข้อความ B", now)),
            now
        )
        assertEquals(listOf("ข้อความ A", "ข้อความ B"), selected.map { it.body })
    }

    @Test fun unknownTimestampBeforeStaleKnownLastDoesNotReplayOldMessage() {
        val now = 1_000_000L
        val selected = LineNoticeBatchSelector.fresh(
            listOf(item("ไม่ทราบเวลา", 0L), item("เก่าเกิน", now - 300_000L)),
            now
        )
        assertTrue(selected.isEmpty())
    }

    @Test fun unknownTimestampBeforeFreshKnownLastStillReadsLatest() {
        val now = 1_000_000L
        val selected = LineNoticeBatchSelector.fresh(
            listOf(item("ไม่ทราบเวลา", 0L), item("สดใหม่", now - 1000L)),
            now
        )
        assertEquals(listOf("สดใหม่"), selected.map { it.body })
    }

    @Test fun twoSeparateImageMessagesWithIdenticalTimeAreNotMerged() {
        val now = 1_000_000L
        val selected = LineNoticeBatchSelector.fresh(
            listOf(item("รูปหนึ่ง", now, picture = true),
                item("รูปสอง", now, picture = true)),
            now
        )
        assertEquals(listOf("รูปหนึ่ง", "รูปสอง"), selected.map { it.body })
    }

    @Test fun capBatchSizeAndHandleEmpty() {
        val now = 1_000_000L
        val all = (1..50).map { item("msg" + it, now - 100 + it) }
        assertEquals(10, LineNoticeBatchSelector.fresh(all, now).size)
        assertEquals("msg41", LineNoticeBatchSelector.fresh(all, now).first().body)
        assertTrue(LineNoticeBatchSelector.fresh(emptyList(), now).isEmpty())
        assertTrue(LineNoticeBatchSelector.fresh(all, now, maxBatchSize = 0).isEmpty())
    }
}
