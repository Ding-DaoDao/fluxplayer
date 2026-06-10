package dev.anilbeesetti.nextplayer.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

val settingsEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        initialOffsetX = { fullWidth -> fullWidth },
    ) + scaleIn(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        initialScale = 0.95f,
    ) + fadeIn(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
    )
}

val settingsExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        targetOffsetX = { fullWidth -> -fullWidth },
    ) + scaleOut(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        targetScale = 0.95f,
    ) + fadeOut(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
    )
}

val settingsPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        initialOffsetX = { fullWidth -> -fullWidth },
    ) + scaleIn(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        initialScale = 0.95f,
    ) + fadeIn(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
    )
}

val settingsPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        targetOffsetX = { fullWidth -> fullWidth },
    ) + scaleOut(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        targetScale = 0.95f,
    ) + fadeOut(
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
    )
}
