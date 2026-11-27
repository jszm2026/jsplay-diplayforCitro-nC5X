package com.shilapi.xcertplay.media

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayTouchMapperTest {
    private val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)

    @Test fun contentCornersMapToCanvasCorners() {
        contact(MotionEvent.ACTION_DOWN, content.left, content.top).let {
            assertEquals(0.0, it.x, 1e-6)
            assertEquals(0.0, it.y, 1e-6)
            assertTrue(it.down)
        }
        contact(MotionEvent.ACTION_UP, content.left + content.width, content.top + content.height).let {
            assertEquals(1.0, it.x, 1e-6)
            assertEquals(1.0, it.y, 1e-6)
            assertFalse(it.down)
        }
    }

    @Test fun centreRemainsCentred() {
        contact(MotionEvent.ACTION_MOVE, 960f, 471f).let {
            assertEquals(0.5, it.x, 1e-6)
            assertEquals(0.5, it.y, 1e-6)
        }
    }

    @Test fun dragIntoABarClampsToTheCanvasEdge() {
        assertEquals(0.0, contact(MotionEvent.ACTION_MOVE, 0f, 471f).x, 1e-6)
        assertEquals(1.0, contact(MotionEvent.ACTION_MOVE, 1920f, 471f).x, 1e-6)
    }

    @Test fun cancelInABarReleasesTheContact() {
        assertFalse(contact(MotionEvent.ACTION_CANCEL, 0f, 471f).down)
    }

    @Test fun fullViewOverloadKeepsExistingCoordinates() {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 480f, 247.5f, 0)
        try {
            val contact = CarPlayTouchMapper.contacts(event, 1920, 990).single()
            assertEquals(0.25, contact.x, 1e-6)
            assertEquals(0.25, contact.y, 1e-6)
        } finally {
            event.recycle()
        }
    }

    @Test fun pointerSlotsStayStableWhenFirstFingerLifts() {
        val session = CarPlayTouchMapper.Session()
        val down = event(MotionEvent.ACTION_DOWN, intArrayOf(7), floatArrayOf(100f), floatArrayOf(100f))
        val second = event(MotionEvent.ACTION_POINTER_DOWN, intArrayOf(7, 3), floatArrayOf(100f, 200f), floatArrayOf(100f, 200f), actionIndex = 1)
        val liftFirst = event(MotionEvent.ACTION_POINTER_UP, intArrayOf(7, 3), floatArrayOf(120f, 220f), floatArrayOf(120f, 220f), actionIndex = 0)
        try {
            session.map(down, content)
            session.map(second, content)
            val frame = session.map(liftFirst, content)!!
            assertEquals(2, frame.contacts.size)
            assertFalse(frame.contacts.first { it.id == 0 }.down)
            assertTrue(frame.contacts.first { it.id == 1 }.down)
        } finally { down.recycle(); second.recycle(); liftFirst.recycle() }
    }

    @Test fun cancelReleasesAllStableSlots() {
        val session = CarPlayTouchMapper.Session()
        val down = event(MotionEvent.ACTION_DOWN, intArrayOf(7), floatArrayOf(100f), floatArrayOf(100f))
        val cancel = event(MotionEvent.ACTION_CANCEL, intArrayOf(7), floatArrayOf(120f), floatArrayOf(120f))
        try {
            session.map(down, content)
            val frame = session.map(cancel, content)!!
            assertTrue(frame.edge)
            assertTrue(frame.contacts.all { !it.down })
        } finally { down.recycle(); cancel.recycle() }
    }

    private fun contact(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, action, x, y, 0).let {
        try {
            CarPlayTouchMapper.contacts(it, content).single()
        } finally {
            it.recycle()
        }
    }

    private fun event(action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, actionIndex: Int = 0): MotionEvent {
        val properties = Array(ids.size) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { MotionEvent.PointerCoords().apply { x = xs[it]; y = ys[it] } }
        val encodedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(0, 0, encodedAction, ids.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }
}
