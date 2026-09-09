package com.rommmobile.app.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ToastMessage(val text: String, val isError: Boolean = false, val durationMs: Long = 2600)

/** In-app toasts drawn over the content: styled, visible on TV, no system Toast quirks. */
@Singleton
class Toaster @Inject constructor() {
    private val _messages = MutableSharedFlow<ToastMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<ToastMessage> = _messages.asSharedFlow()
    fun show(text: String, isError: Boolean = false) { _messages.tryEmit(ToastMessage(text, isError)) }
}

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<ToastMessage?>(null) }
    LaunchedEffect(toaster) {
        toaster.messages.collectLatest { msg ->
            current = msg
            delay(msg.durationMs)
            if (current === msg) current = null
        }
    }
    val colors = RommTheme.colors
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val msg = current ?: return@AnimatedVisibility
            Text(
                text = msg.text,
                color = if (msg.isError) colors.white else colors.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(bottom = 56.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 480.dp)
                    .background(if (msg.isError) colors.red else colors.toplayer, MaterialTheme.shapes.large)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
