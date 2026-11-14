package com.rsmonitor.app

import com.rsmonitor.app.car.GTail
import com.rsmonitor.app.car.GTailPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GTailTest {
    @Test
    fun emptyUntilSomethingIsAdded() {
        assertEquals(0, GTail().points(1_000).size)
        assertEquals(GTailPoints.NONE, GTail().points(1_000))
    }

    @Test
    fun newestFirstWithAges() {
        val t = GTail()
        t.add(1_000, 0.1f, 0.2f)
        t.add(1_500, 0.3f, -0.4f)
        val p = t.points(2_000)
        assertEquals(2, p.size)
        assertEquals(0.3f, p.lat(0), 0f)
        assertEquals(-0.4f, p.lon(0), 0f)
        assertEquals(0.5f, p.age(0), 1e-6f)
        assertEquals(0.1f, p.lat(1), 0f)
        assertEquals(1.0f, p.age(1), 1e-6f)
    }

    @Test
    fun dropsWhatIsOlderThanTheTailIsLong() {
        val t = GTail()
        t.add(0, 0.1f, 0f)
        t.add(2_000, 0.2f, 0f)
        t.add(4_000, 0.3f, 0f)
        // three seconds back from 4.5 s reaches 1.5 s: the first reading is too old
        val p = t.points(4_500)
        assertEquals(2, p.size)
        assertEquals(0.2f, p.lat(1), 0f)
        // and once nothing is recent enough, nothing is left
        assertEquals(0, t.points(10_000).size)
    }

    @Test
    fun keepsOnlyTheNewestWhenFull() {
        val t = GTail()
        for (i in 0 until 500) t.add(i * 10L, i.toFloat(), 0f)   // far more than it holds
        val p = t.points(499 * 10L)
        assertEquals(499f, p.lat(0), 0f)
        // every point kept is one of the latest, in order
        for (i in 1 until p.size) assertEquals(499f - i, p.lat(i), 0f)
    }

    @Test
    fun clearEmptiesIt() {
        val t = GTail()
        t.add(0, 0.1f, 0.1f)
        t.clear()
        assertEquals(0, t.points(10).size)
    }

    @Test
    fun equalWhenTheNumbersAreEqual() {
        val a = GTail().apply { add(0, 0.1f, 0.2f) }
        val b = GTail().apply { add(0, 0.1f, 0.2f) }
        assertEquals(a.points(100), b.points(100))
        assertEquals(a.points(100).hashCode(), b.points(100).hashCode())
        assertNotEquals(a.points(100), a.points(200))   // the same point, older
    }
}
