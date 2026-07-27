package com.openminis.app.ui.pi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.agent.pi.PiAgentService
import com.openminis.app.ui.theme.MinisTheme

/**
 * Chat surface for the Pi Agent backend. Distinct from the main
 * ChatScreen so we can keep the diff small and the UX intentionally
 * "dev mode" — monospace, code-first, sparse chrome.
 */
@Composable
fun PiChatScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: PiChatViewModel = viewModel(factory = PiChatViewModel.Factory(context))
    val state by vm.state.collectAsState()

    LaunchedEffect(state.installState) {
        if (state.installState.installed && state.sessionId == null) {
            vm.ensureSession()
        }
    }

    MinisTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                PiHeader(
                    state = state,
                    onBack = onBack,
                    onAbort = { vm.abort() },
                    onInstall = { vm.ensureSession() },
                )
                InstallBanner(state.installState)
                PiMessageList(
                    messages = state.messages,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Composer(
                    enabled = state.installState.installed && state.backendState == PiAgentService.State.Ready,
                    onSend = vm::send,
                )
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.shutdown() }
    }
}

@Composable
private fun PiHeader(
    state: PiChatState,
    onBack: () -> Unit,
    onAbort: () -> Unit,
    onInstall: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("←") }
                Spacer(Modifier.width(8.dp))
                Text(
                    "OpenMinis Ubuntu · Pi Agent",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.weight(1f))
                when (state.backendState) {
                    PiAgentService.State.Running -> TextButton(onClick = onAbort) { Text("Stop") }
                    else -> {}
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "provider=${state.provider}  model=${state.model}  " +
                    "session=${state.sessionId?.takeLast(8) ?: "—"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstallBanner(install: PiAgentService.InstallState) {
    when {
        install.installed -> {
            Row(
                containerColor = Color(0xFF1B5E20),
                content = {
                    Text(
                        "✓ Pi ${install.version ?: "ready"}",
                        modifier = Modifier.padding(12.dp),
                        color = Color.White,
                    )
                }
            )
        }
        install.installing -> {
            Row(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                content = {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(12.dp)
                            .height(18.dp)
                            .width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "Installing Pi Agent inside the sandbox…",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            )
        }
        install.error != null -> {
            Row(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                content = {
                    Text(
                        "Install failed: ${install.error}",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            )
        }
    }
}

@Composable
private fun Row(
    containerColor: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

@Composable
private fun PiMessageList(messages: List<PiChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = messages.size,
            key = { idx -> "msg-$idx" },
        ) { idx ->
            PiMessageRow(messages[idx])
        }
    }
}

@Composable
private fun PiMessageRow(message: PiChatMessage) {
    when (message) {
        is PiChatMessage.User -> Bubble(
            text = message.text,
            align = Alignment.CenterEnd,
            bg = MaterialTheme.colorScheme.primary,
            fg = MaterialTheme.colorScheme.onPrimary,
        )
        is PiChatMessage.Assistant -> Bubble(
            text = message.text,
            align = Alignment.CenterStart,
            bg = MaterialTheme.colorScheme.surfaceVariant,
            fg = MaterialTheme.colorScheme.onSurface,
            streaming = message.isStreaming,
        )
        is PiChatMessage.ToolCall -> ToolBubble(message)
    }
}

@Composable
private fun Bubble(
    text: String,
    align: Alignment,
    bg: Color,
    fg: Color,
    streaming: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = align,
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = bg),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(text, color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                if (streaming) {
                    Text(
                        "▌",
                        color = fg,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolBubble(call: PiChatMessage.ToolCall) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "${if (call.running) "⚙" else "✓"} ${call.name}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (call.args.isNotBlank()) {
                    Text(
                        call.args,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (call.result.isNotBlank()) {
                    Text(
                        call.result.take(400),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, onSend: (String) -> Unit) {
    val textState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.SideEffect { textState.value } // keep imports used under hot-reload
    Surface(tonalElevation = 3.dp) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = textState.value,
                onValueChange = { textState.value = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the Pi coding agent…") },
                enabled = enabled,
                singleLine = false,
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onSend(textState.value)
                    textState.value = ""
                },
                enabled = enabled && textState.value.isNotBlank(),
            ) { Text("Send") }
        }
    }
}
