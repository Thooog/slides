package com.slides.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/**
 * 检测"双指捏合"并回调间距增量（px）。
 * 仅在有 >=2 根手指按下时才消费事件，单指滚动/点击不被打断，
 * 因此可以叠加在可滚动 LazyGrid / Pager 上而不破坏原有单指手势。
 * 正增量 = 双指张开，负增量 = 双指收拢。
 * 回调通过 State 传入，每次读取最新闭包，避免 pointerInput 捕获旧列数。
 */
fun Modifier.detectTwoFingerPinch(onPinchChange: State<(Float) -> Unit>): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            var lastSpan = 0f
            var active = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val span = distance(a, b)
                    if (!active) {
                        active = true
                        lastSpan = span
                    } else {
                        val delta = span - lastSpan
                        lastSpan = span
                        if (delta != 0f) onPinchChange.value(delta)
                    }
                    event.changes.forEach { it.consume() }
                } else {
                    if (active) {
                        // 手指从 2 降到 1，结束本轮捏合
                        break
                    }
                }
            }
        }
    }

private fun distance(a: Offset, b: Offset): Float = hypot(b.x - a.x, b.y - a.y)
