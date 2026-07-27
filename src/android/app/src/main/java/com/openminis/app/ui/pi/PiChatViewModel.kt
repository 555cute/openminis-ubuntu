package com.openminis.app.ui.pi

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.pi.PiAgentConfig
import com.openminis.app.agent.pi.PiAgentService
import com.openminis.app.agent.pi.PiEvent
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives Pi-powdered chat sessions. Each session is identified by
 * its Minis chat-session id so messages stay linked across the two
 * backends. We stream Pi's RPC events into a UI state that mirrors
 * the original Minis chat state shape so the dev mode surface looks
 * familiar.
 */
class PiChatViewModel(
    private val context: Context,
    private val piAgent: PiAgentService = PiAgentService.getInstance(context),
) : ViewModel() {

    private val _state = MutableStateFlow(PiChatState())
    val state: StateFlow<PiChatState> = _state.asStateFlow()

    private var eventJob: kotlinx.coroutines.Job? = null

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            _state.update { it.copy(installState = piAgent.installState.value) }
            if (!piAgent.installState.value.installed) {
                try {
                    piAgent.installIfNeeded()
                } catch (t: Throwable) {
                    _state.update {
                        it.copy(
                            installState = PiAgentService.InstallState(
                                installed = false,
                                error = "Install failed: ${t.javaClass.simpleName}: ${t.message}",
                            ),
                            lastError = "install failed: ${t.message}",
                        )
                    }
                    return@launch
                }
            }
            _state.update { it.copy(installState = piAgent.installState.value) }
        }
    }

    fun chooseProviderModel(provider: String, model: String) {
        _state.update { it.copy(provider = provider, model = model) }
    }

    fun ensureSession() {
        val snapshot = _state.value
        if (snapshot.sessionId != null && snapshot.backendState != PiAgentService.State.Crashed) return
        viewModelScope.launch {
            try {
                val store = ProviderRepository.getInstance(context)
                val apiKey = run {
                    val match = store.instances.firstOrNull {
                        it.providerType.name.equals(snapshot.provider, ignoreCase = true)
                    } ?: store.instances.firstOrNull()
                    match?.let { store.loadApiKey(it.id) }
                }
                val session = piAgent.startSession(
                    sessionId = snapshot.sessionId ?: newSessionId(),
                    config = PiAgentConfig(
                        provider = snapshot.provider,
                        model = snapshot.model,
                        apiKey = apiKey,
                    ),
                )
                _state.update { it.copy(sessionId = session.id, backendState = session.state.value) }
                subscribeEvents(session.id)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        backendState = PiAgentService.State.Crashed,
                        lastError = "session failed: ${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            }
        }
    }

    private fun subscribeEvents(sessionId: String) {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            piAgent.events(sessionId).collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: PiEvent) {
        when (event) {
            is PiEvent.AgentStart -> _state.update { it.copy(backendState = PiAgentService.State.Running) }
            is PiEvent.AgentEnd -> _state.update { it.copy(backendState = PiAgentService.State.Ready) }
            is PiEvent.AgentError -> _state.update {
                it.copy(backendState = PiAgentService.State.Crashed, lastError = event.raw.toString())
            }
            is PiEvent.MessageUpdate -> appendAssistant(event.raw)
            is PiEvent.ToolExecutionStart -> appendTool(event.raw, running = true)
            is PiEvent.ToolExecutionUpdate -> appendTool(event.raw, running = true)
            is PiEvent.ToolExecutionEnd -> appendTool(event.raw, running = false)
            is PiEvent.BashExecutionUpdate -> { /* streamed into the active tool entry */ }
            is PiEvent.TurnStart -> _state.update { it.copy(backendState = PiAgentService.State.Running) }
            is PiEvent.TurnEnd -> _state.update { it.copy(backendState = PiAgentService.State.Ready) }
            is PiEvent.Opaque -> { /* ignore unknown events for now */ }
        }
    }

    private fun appendAssistant(raw: JsonObject) {
        val message = raw["message"]?.jsonObject ?: return
        val role = message["role"]?.jsonPrimitive?.contentOrNull() ?: return
        val content = extractTextContent(message)
        if (role == "assistant" && content.isNotBlank()) {
            _state.update { st ->
                val updated = st.messages.toMutableList()
                val last = updated.lastOrNull()
                if (last is PiChatMessage.Assistant && last.isStreaming) {
                    updated[updated.lastIndex] = last.copy(text = content, isStreaming = true)
                } else {
                    updated += PiChatMessage.Assistant(text = content, isStreaming = true)
                }
                st.copy(messages = updated)
            }
        }
    }

    private fun appendTool(raw: JsonObject, running: Boolean) {
        val name = raw["toolName"]?.jsonPrimitive?.contentOrNull() ?: raw["name"]?.jsonPrimitive?.contentOrNull() ?: "tool"
        val args = raw["args"]?.jsonObject?.toString() ?: ""
        val result = raw["result"]?.toString() ?: ""
        _state.update { st ->
            val updated = st.messages.toMutableList()
            updated += PiChatMessage.ToolCall(
                name = name,
                args = args,
                result = result,
                running = running,
            )
            st.copy(messages = updated)
        }
    }

    fun send(text: String) {
        val snapshot = _state.value
        if (snapshot.backendState != PiAgentService.State.Ready ||
            !snapshot.installState.installed) return
        _state.update { st ->
            st.copy(messages = st.messages + PiChatMessage.User(text))
        }
        viewModelScope.launch {
            val sessionId = snapshot.sessionId ?: return@launch
            piAgent.prompt(sessionId, text)
        }
    }

    fun abort() {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch { piAgent.abort(sessionId) }
    }

    fun shutdown() {
        eventJob?.cancel()
        _state.value.sessionId?.let { piAgent.stopSession(it) }
    }

    private fun newSessionId(): String = "pi-" + java.util.UUID.randomUUID().toString()

    private fun extractTextContent(message: JsonObject): String {
        // Pi message content is either a string or an array of content parts.
        val content = message["content"] ?: return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull() ?: ""
            else -> content.toString()
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PiChatViewModel(context.applicationContext) as T
    }
}

data class PiChatState(
    val installState: PiAgentService.InstallState = PiAgentService.InstallState(),
    val provider: String = "anthropic",
    val model: String = "claude-sonnet-4.5",
    val sessionId: String? = null,
    val backendState: PiAgentService.State = PiAgentService.State.Idle,
    val messages: List<PiChatMessage> = emptyList(),
    val lastError: String? = null,
)

sealed class PiChatMessage {
    data class User(val text: String) : PiChatMessage()
    data class Assistant(val text: String, val isStreaming: Boolean) : PiChatMessage()
    data class ToolCall(
        val name: String,
        val args: String,
        val result: String,
        val running: Boolean,
    ) : PiChatMessage()
}
