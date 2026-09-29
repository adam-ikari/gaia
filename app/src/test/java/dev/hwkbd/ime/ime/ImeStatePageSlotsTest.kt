package dev.hwkbd.ime.ime

import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.engine.CandidateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImeState] 的两个取页接口语义必须分清:
 * - [ImeState.pageSlots] = **当前页**,页号要**夹到有效范围**(候选集变小后 `page` 会暂时越界);
 * - [ImeState.pageSlotsAt] = **预览相邻页**,越界即"那一侧没有页",返回空表。
 *
 * 2026-09-29 真机踩到:把 [ImeState.pageSlots] 改成直接转发 [ImeState.pageSlotsAt] 之后,
 * 敲字让候选集缩小时 `page` 越界 ⇒ 候选行画出**五个空槽**、而翻页箭头还亮着(看着像词丢了)。
 */
class ImeStatePageSlotsTest {

    private fun state(pages: Int, page: Int): ImeState {
        val s = ImeState()
        s.pageSize = 5
        s.page = page
        s.candidates = (0 until pages * 5).map {
            Candidate("w$it", "p$it", CandidateKind.EXACT, it.toLong())
        }
        return s
    }

    @Test
    fun `pageSlots clamps an out-of-range current page instead of returning empty`() {
        // 3 页时把 page 留在 2,候选缩到 1 页 ⇒ page=2 已越界
        val s = state(pages = 1, page = 2)
        assertEquals(1, s.totalPages)
        val slots = s.pageSlots()
        assertEquals("当前页必须夹住并画出 5 个槽,不能是空表", 5, slots.size)
        assertTrue("槽里要有词", slots.any { it != null })
    }

    @Test
    fun `pageSlots is empty only when there are no candidates at all`() {
        val s = ImeState()
        assertTrue(s.pageSlots().isEmpty())
        assertEquals(0, s.totalPages)
    }

    @Test
    fun `pageSlotsAt is strict - out of range means no page that way`() {
        val s = state(pages = 3, page = 0)
        assertEquals(3, s.totalPages)
        assertTrue("下一页存在", s.pageSlotsAt(1).any { it != null })
        assertTrue("下一页存在", s.pageSlotsAt(2).any { it != null })
        assertTrue("末页之后没有页", s.pageSlotsAt(3).isEmpty())
        assertTrue("首页之前没有页", s.pageSlotsAt(-1).isEmpty())
    }

    @Test
    fun `pageSlotsAt matches pageSlots for the current page`() {
        val s = state(pages = 3, page = 1)
        assertEquals(
            s.pageSlots().map { it?.word },
            s.pageSlotsAt(1).map { it?.word },
        )
    }
}
