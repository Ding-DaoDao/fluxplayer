package com.fluxplayer.app.core.ui.navbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    private val animationsEnabled: () -> Boolean = { true },
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    private val enabled: () -> Boolean = { true },
) {

    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(this) {
        inspectDragGestures(
            enabled = enabled,
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            val isInside = canDrag(change.position)
            val wasInside = canDrag(change.previousPosition)
            if (isInside && wasInside) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, if (animationsEnabled()) pressProgressAnimationSpec else snap()) }
            launch { scaleXAnimation.animateTo(pressedScale, if (animationsEnabled()) scaleXAnimationSpec else snap()) }
            launch { scaleYAnimation.animateTo(pressedScale, if (animationsEnabled()) scaleYAnimationSpec else snap()) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, if (animationsEnabled()) pressProgressAnimationSpec else snap()) }
            launch { scaleXAnimation.animateTo(initialScale, if (animationsEnabled()) scaleXAnimationSpec else snap()) }
            launch { scaleYAnimation.animateTo(initialScale, if (animationsEnabled()) scaleYAnimationSpec else snap()) }
        }
    }

    fun updateValue(value: Float) {
        val coercedTargetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch {
                valueAnimation.animateTo(coercedTargetValue, if (animationsEnabled()) valueAnimationSpec else snap()) {
                    updateVelocity()
                }
            }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val coercedTargetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(coercedTargetValue, if (animationsEnabled()) valueAnimationSpec else snap()) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, if (animationsEnabled()) velocityAnimationSpec else snap()) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f),
        )
        val targetVelocity =
            if (valueRange.endInclusive > valueRange.start) velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start) else 0f
        animationScope.launch {
            velocityAnimation.animateTo(targetVelocity, if (animationsEnabled()) velocityAnimationSpec else snap())
        }
    }
}
