package com.openminis.app.agent.pi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Pi Agent config + types.
 *
 * Kept in a separate file so PiAgentService.kt can stay focused on
 * the transport. The config carries only the data Pi actually needs
 * at process start time; provider-specific routing continues to live
 * in Minis's existing `Providers` / `Models` collections.
 */
data class PiAgentConfig(
    val provider: String,
    val model: String,
    val apiKey: String?,
    val thinkingLevel: String? = null,
    val systemPrompt: String? = null,
) {
    /**
     * Pi reads `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` /
     * `GEMINI_API_KEY` / `GOOGLE_API_KEY` / etc. — map our internal
     * provider name to the canonical env var Pi expects.
     *
     * Keep in sync with `packages/coding-agent/src/ai/registry.ts` in
     * earendil-works/pi.
     */
    val envVarName: String
        get() = when (provider.lowercase()) {
            "anthropic", "claude" -> "ANTHROPIC_API_KEY"
            "openai", "gpt" -> "OPENAI_API_KEY"
            "google", "gemini" -> "GOOGLE_API_KEY"
            "xai", "grok" -> "XAI_API_KEY"
            "openrouter" -> "OPENROUTER_API_KEY"
            "mistral" -> "MISTRAL_API_KEY"
            "groq" -> "GROQ_API_KEY"
            "deepseek" -> "DEEPSEEK_API_KEY"
            "kimi", "moonshot" -> "MOONSHOT_API_KEY"
            else -> "${provider.uppercase()}_API_KEY"
        }
}

/**
 * Sealed event types emitted by Pi over the RPC stream. Pi's raw
 * event payloads are JSON; we don't model every field — we only
 * surface the bits the chat UI cares about. The original payload is
 * kept on each event so callers can render unrecognised ones.
 */
sealed class PiEvent {
    abstract val raw: JsonObject

    data class AgentStart(override val raw: JsonObject) : PiEvent()
    data class AgentEnd(override val raw: JsonObject) : PiEvent()
    data class TurnStart(override val raw: JsonObject) : PiEvent()
    data class TurnEnd(override val raw: JsonObject) : PiEvent()
    data class MessageUpdate(override val raw: JsonObject) : PiEvent()
    data class ToolExecutionStart(override val raw: JsonObject) : PiEvent()
    data class ToolExecutionUpdate(override val raw: JsonObject) : PiEvent()
    data class ToolExecutionEnd(override val raw: JsonObject) : PiEvent()
    data class BashExecutionUpdate(override val raw: JsonObject) : PiEvent()
    data class AgentError(override val raw: JsonObject) : PiEvent()
    data class Opaque(override val raw: JsonObject) : PiEvent()

    companion object {
        fun from(obj: JsonObject): PiEvent {
            val type = obj["type"]?.toString()?.trim('"') ?: return Opaque(obj)
            return when (type) {
                "agent_start" -> AgentStart(obj)
                "agent_end" -> AgentEnd(obj)
                "turn_start" -> TurnStart(obj)
                "turn_end" -> TurnEnd(obj)
                "message_update" -> MessageUpdate(obj)
                "tool_execution_start" -> ToolExecutionStart(obj)
                "tool_execution_update" -> ToolExecutionUpdate(obj)
                "tool_execution_end" -> ToolExecutionEnd(obj)
                "bash_execution_update" -> BashExecutionUpdate(obj)
                "agent_error" -> AgentError(obj)
                else -> Opaque(obj)
            }
        }
    }
}

/**
 * Wire-format envelope for a command request — kept as a separate
 * type so callers can build requests in a type-safe way without
 * reaching for JsonObject directly.
 */
@Serializable
internal data class PiCommand(
    val id: String,
    val type: String,
    val message: String? = null,
    val streamingBehavior: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
)
