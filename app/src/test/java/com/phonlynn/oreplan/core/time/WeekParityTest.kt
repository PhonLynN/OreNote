package com.phonlynn.oreplan.core.time

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekParityTest {

    @Test
    fun `不限单双周时任何周都匹配`() {
        (1..20).forEach { week ->
            assertTrue("第 $week 周", WeekParity.ALL.matches(week))
        }
    }

    @Test
    fun `单周只匹配奇数周`() {
        assertTrue(WeekParity.ODD.matches(1))
        assertFalse(WeekParity.ODD.matches(2))
        assertTrue(WeekParity.ODD.matches(17))
    }

    @Test
    fun `双周只匹配偶数周`() {
        assertFalse(WeekParity.EVEN.matches(1))
        assertTrue(WeekParity.EVEN.matches(2))
        assertTrue(WeekParity.EVEN.matches(18))
    }

    @Test
    fun `单双周互补`() {
        (1..20).forEach { week ->
            val odd = WeekParity.ODD.matches(week)
            val even = WeekParity.EVEN.matches(week)
            assertTrue("第 $week 周应该恰好落在单周或双周之一", odd != even)
        }
    }
}
