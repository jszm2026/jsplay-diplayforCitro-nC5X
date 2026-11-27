package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact

/** Converts Android MotionEvents into normalized CarPlay touch contacts. */
object CarPlayTouchMapper {
    private const val MAX_CONTACTS = 2

    data class Frame(
        val contacts: List<AirPlayContact>,
        val edge: Boolean,
        val changed: Boolean,
    )

    /** Stateful pointerId -> HID slot mapper for one SurfaceView touch stream. */
    class Session {
        private data class Slot(
            val pointerId: Int,
            var x: Double,
            var y: Double,
        )

        private val slots = arrayOfNulls<Slot>(MAX_CONTACTS)

        fun reset() {
            slots.fill(null)
        }

        fun map(event: MotionEvent, content: CarPlayVideoLayout): Frame? {
            val width = content.width.coerceAtLeast(1f)
            val height = content.height.coerceAtLeast(1f)
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) reset()

            if (action == MotionEvent.ACTION_CANCEL) {
                updateCoordinates(event, content, width, height)
                val released = contacts(down = false)
                reset()
                return Frame(released, edge = true, changed = released.isNotEmpty())
            }

            val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
            updateCoordinates(event, content, width, height, liftedIndex)
            var changed = false

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                val index = if (action == MotionEvent.ACTION_DOWN) 0 else event.actionIndex
                val pointerId = event.getPointerId(index)
                if (findSlot(pointerId) < 0) {
                    val freeSlot = slots.indexOfFirst { it == null }
                    if (freeSlot >= 0) {
                        slots[freeSlot] = Slot(pointerId, normalizedX(event.getX(index), content, width), normalizedY(event.getY(index), content, height))
                        changed = true
                    }
                }
                return if (changed) Frame(contacts(down = true), edge = true, changed = true) else null
            }

            if (action == MotionEvent.ACTION_MOVE) {
                changed = slots.any { it != null }
                return if (changed) Frame(contacts(down = true), edge = false, changed = true) else null
            }

            if (action == MotionEvent.ACTION_POINTER_UP) {
                val pointerId = event.getPointerId(event.actionIndex)
                val slot = findSlot(pointerId)
                if (slot < 0) return null
                val released = contacts(down = true).map { contact ->
                    if (contact.id == slot) contact.copy(down = false) else contact
                }
                slots[slot] = null
                return Frame(released, edge = true, changed = true)
            }

            if (action == MotionEvent.ACTION_UP) {
                val released = contacts(down = false)
                reset()
                return Frame(released, edge = true, changed = released.isNotEmpty())
            }
            return null
        }

        private fun updateCoordinates(
            event: MotionEvent,
            content: CarPlayVideoLayout,
            width: Float,
            height: Float,
            liftedIndex: Int = -1,
        ) {
            for (index in 0 until event.pointerCount) {
                if (index == liftedIndex) {
                    // ACTION_POINTER_UP still carries the final coordinate; keep it for the
                    // release report before freeing the slot.
                }
                val slot = findSlot(event.getPointerId(index))
                if (slot >= 0) {
                    slots[slot]?.x = normalizedX(event.getX(index), content, width)
                    slots[slot]?.y = normalizedY(event.getY(index), content, height)
                }
            }
        }

        private fun contacts(down: Boolean): List<AirPlayContact> =
            slots.mapIndexedNotNull { index, slot ->
                slot?.let { AirPlayContact(index, it.x, it.y, down) }
            }

        private fun findSlot(pointerId: Int): Int =
            slots.indexOfFirst { it?.pointerId == pointerId }
    }

    /** Stateless compatibility helper retained for existing callers and tests. */
    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> =
        contacts(event, CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))

    fun contacts(event: MotionEvent, content: CarPlayVideoLayout): List<AirPlayContact> =
        Session().map(event, content)?.contacts?.takeIf { it.isNotEmpty() }
            ?: legacyContacts(event, content)

    private fun legacyContacts(event: MotionEvent, content: CarPlayVideoLayout): List<AirPlayContact> {
        val width = content.width.coerceAtLeast(1f)
        val height = content.height.coerceAtLeast(1f)
        val liftedIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val up = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        return (0 until minOf(MAX_CONTACTS, event.pointerCount)).map { index ->
            AirPlayContact(
                id = index,
                x = normalizedX(event.getX(index), content, width),
                y = normalizedY(event.getY(index), content, height),
                down = !up && index != liftedIndex,
            )
        }
    }

    private fun normalizedX(value: Float, content: CarPlayVideoLayout, width: Float): Double =
        ((value - content.left).toDouble() / width).coerceIn(0.0, 1.0)

    private fun normalizedY(value: Float, content: CarPlayVideoLayout, height: Float): Double =
        ((value - content.top).toDouble() / height).coerceIn(0.0, 1.0)
}
