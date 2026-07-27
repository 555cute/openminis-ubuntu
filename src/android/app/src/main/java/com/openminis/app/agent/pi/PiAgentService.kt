package com.openminis.app.agent.pi

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pi Agent RPC bridge.
 *
 * Spawns the Pi coding-agent CLI inside the Ubuntu sandbox and
 * communicates with it via the stdin/stdout JSON-RPC protocol
 * documented in `packages/coding-agent/docs/rpc.md` of
 * earendil-works/pi. One long-lived process per chat session;
 * commands are framed as JSON Lines (LF-delimited) and responses
 * / events are streamed back as JSON Lines.
 *
 * Mirrors the role of `PersistentShell` for the regular Minis agent
 * loop, but instead of driving the agent ourselves we let Pi own the
 * loop and we forward chat messages + tool outputs in and out.
 *
 * iOS counterpart: `src/ios/Agent/Pi/PiAgent.swift` (added in this
 * fork). Keep the protocol version in sync.
 */
class PiAgentService private constructor(private val context: Context) {

    enum class State {
        Idle, Booting, Installing, Ready, Running, Crashed, Stopped
    }

    data class Session(
        val id: String,
        val process: Process,
        val stdin: BufferedWriter,
        val readerJob: Job,
        val requestMap: ConcurrentHashMap<Long, (JsonObject) -> Unit>,
        val state: MutableStateFlow<State>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val _installState = MutableStateFlow(InstallState())
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val sessions = ConcurrentHashMap<String, Session>()
    private val nextRequestId = AtomicLong(1L)

    /**
     * Install Pi Agent inside the sandbox, idempotent. Safe to call
     * repeatedly — the install script writes a sentinel after success.
     * Returns InstallState.installed=true when the user can rely on
     * `pi --version` succeeding.
     */
    suspend fun installIfNeeded(): InstallState = withContext(Dispatchers.IO) {
        fun setProgress(msg: String, progress: Float = 0f, log: String = "") {
            _installState.value = InstallState(
                installing = true,
                progress = progress.coerceIn(0f, 0.99f),
                statusMessage = msg,
                logTail = log.takeLast(800),
            )
        }

        setProgress("Booting Ubuntu sandbox…", 0.05f)
        if (!PRootKernel.isBooted) {
            PRootKernel.boot(context)
        }
        // Always ensure proot is staged under filesDir (never exec nativeLibraryDir).
        RootfsManager.getInstance(context).installProotIfNeeded()

        setProgress("Checking for existing Pi install…", 0.15f)
        val probed = probePi()
        if (probed) {
            val current = InstallState(installed = true, version = readPiVersion())
            _installState.value = current
            return@withContext current
        }

        setProgress("Running pi-install (apt + Node + npm)…", 0.25f)
        // Stream-ish: poll install log while the long shell runs in parallel
        // is hard with ShellExecutor's one-shot API; instead run the command
        // and surface the full output tail on completion. Progress ticks via
        // a lightweight heartbeat so the UI doesn't look frozen.
        val heartbeat = scope.launch {
            var tick = 0.25f
            while (true) {
                kotlinx.coroutines.delay(3_000)
                tick = (tick + 0.03f).coerceAtMost(0.9f)
                val tail = runCatching {
                    ShellExecutor.execute(
                        context = context,
                        command = "tail -n 12 /var/minis/pi/install.log 2>/dev/null || true",
                        timeout = 8_000L,
                    ).output.trim()
                }.getOrDefault("")
                setProgress(
                    msg = "Installing Pi… (still working, may take several minutes)",
                    progress = tick,
                    log = tail,
                )
            }
        }

        val result = try {
            ShellExecutor.execute(
                context = context,
                command = "/usr/local/bin/pi-install 2>&1",
                timeout = 15 * 60 * 1000L, // 15 min for npm install + node fetch
            )
        } finally {
            heartbeat.cancel()
        }

        if (result.exitCode == 0 && probePi()) {
            val ok = InstallState(
                installed = true,
                version = readPiVersion(),
                statusMessage = "Pi ready",
                logTail = result.output.takeLast(400),
            )
            _installState.value = ok
            ok
        } else {
            val failed = InstallState(
                installed = false,
                error = "pi-install exit=${result.exitCode}; ${result.output.take(400)}",
                logTail = result.output.takeLast(800),
                statusMessage = "Install failed",
            )
            _installState.value = failed
            failed
        }
    }

    private suspend fun probePi(): Boolean = try {
        val r = ShellExecutor.execute(
            context = context,
            command = "command -v pi && pi --version 2>&1 | head -n1",
            timeout = 30_000L,
        )
        r.exitCode == 0 && r.output.contains("pi", ignoreCase = true)
    } catch (t: Throwable) {
        Log.w(TAG, "probePi failed: ${t.message}")
        false
    }

    private suspend fun readPiVersion(): String? = try {
        ShellExecutor.execute(
            context = context,
            command = "pi --version 2>&1 | head -n1",
            timeout = 15_000L,
        ).output.lineSequence().firstOrNull()?.trim()
    } catch (_: Throwable) {
        null
    }

    /**
     * Start a fresh Pi RPC session. The caller is responsible for
     * selecting the provider + model + API key before calling; the
     * resulting session will route Pi's LLM calls through the chosen
     * provider. The first session on a fresh install will wait for
     * `installIfNeeded()` to complete.
     */
    suspend fun startSession(
        sessionId: String,
        config: PiAgentConfig,
    ): Session = withContext(Dispatchers.IO) {
        check(PRootKernel.isBooted) { "PRootKernel must be booted before starting Pi session" }
        sessions.remove(sessionId)?.let { stopSession(it) }

        val cmd = buildList {
            add("pi")
            add("--mode")
            add("rpc")
            add("--provider")
            add(config.provider)
            add("--model")
            add(config.model)
            add("--session-dir")
            add("/var/minis/pi/sessions/${sessionId}")
            config.thinkingLevel?.let { add("--thinking"); add(it) }
            config.systemPrompt?.let { add("--system-prompt"); add(it) }
        }

        val prootCommand = PRootKernel.buildProotCommand(cmd.joinToString(" "))
        val processBuilder = ProcessBuilder(prootCommand)
        processBuilder.redirectErrorStream(false)

        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        env["TERM"] = "dumb"
        env["PS1"] = ""
        env["PI_HOME"] = "/var/minis/pi"
        env["PI_CONFIG_DIR"] = "/var/minis/pi/.pi"

        // LLM API key — set whichever provider the user selected.
        // Pi reads ANTHROPIC_API_KEY / OPENAI_API_KEY / GOOGLE_API_KEY, etc.
        config.apiKey?.takeIf { it.isNotBlank() }?.let { env[config.envVarName] = it }

        // Force UTF-8 JSON framing
        env["LC_ALL"] = "C.UTF-8"
        env["LANG"] = "C.UTF-8"

        Log.i(TAG, "starting pi RPC session: $sessionId (provider=${config.provider}, model=${config.model})")
        val process = processBuilder.start()
        val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

        val state = MutableStateFlow<State>(State.Booting)
        val requestMap = ConcurrentHashMap<Long, (JsonObject) -> Unit>()

        val readerJob = scope.launch {
            readLoop(process.inputStream, process.errorStream, sessionId, state, requestMap)
        }

        val session = Session(
            id = sessionId,
            process = process,
            stdin = stdin,
            readerJob = readerJob,
            requestMap = requestMap,
            state = state,
        )
        sessions[sessionId] = session
        state.value = State.Ready
        session
    }

    /**
     * Send a `prompt` command. Returns the response id immediately;
     * actual streamed events arrive via the SharedFlow returned by
     * `events(sessionId)`.
     */
    suspend fun prompt(sessionId: String, message: String, images: List<ImageAttachment> = emptyList()): Long =
        withContext(Dispatchers.IO) {
            val session = requireSession(sessionId)
            val reqId = nextRequestId.getAndIncrement()
            val payload = buildJsonObject {
                put("id", "req-$reqId")
                put("type", "prompt")
                put("message", message)
                if (images.isNotEmpty()) {
                    put("images", buildJsonArray {
                        images.forEach { img ->
                            add(buildJsonObject {
                                put("type", "image")
                                put("data", img.dataBase64)
                                put("mimeType", img.mimeType)
                            })
                        }
                    })
                }
            }
            sendCommand(session, payload)
            reqId
        }

    suspend fun abort(sessionId: String): Long = withContext(Dispatchers.IO) {
        val session = requireSession(sessionId)
        val reqId = nextRequestId.getAndIncrement()
        sendCommand(session, buildJsonObject {
            put("id", "req-$reqId")
            put("type", "abort")
        })
        reqId
    }

    suspend fun newSession(sessionId: String): Long = withContext(Dispatchers.IO) {
        val session = requireSession(sessionId)
        val reqId = nextRequestId.getAndIncrement()
        sendCommand(session, buildJsonObject {
            put("id", "req-$reqId")
            put("type", "new_session")
        })
        reqId
    }

    fun events(sessionId: String): SharedFlow<PiEvent> {
        val session = sessions[sessionId] ?: error("no Pi session $sessionId")
        return session.eventFlow.asSharedFlow()
    }

    fun state(sessionId: String): StateFlow<State> {
        val session = sessions[sessionId] ?: error("no Pi session $sessionId")
        return session.state.asStateFlow()
    }

    fun stopSession(sessionId: String) {
        sessions.remove(sessionId)?.let { stopSession(it) }
    }

    fun shutdown() {
        sessions.values.toList().forEach { stopSession(it) }
        scope.cancel()
    }

    private fun stopSession(session: Session) {
        runCatching {
            session.stdin.write("{\"type\":\"abort\"}\n")
            session.stdin.flush()
        }
        runCatching { session.process.destroy() }
        session.readerJob.cancel()
        session.state.value = State.Stopped
    }

    private fun requireSession(sessionId: String): Session =
        sessions[sessionId] ?: error("Pi session $sessionId not found; call startSession() first")

    private fun sendCommand(session: Session, payload: JsonObject) {
        val line = json.encodeToString(JsonObject.serializer(), payload)
        synchronized(session.stdin) {
            session.stdin.write(line)
            session.stdin.write("\n")
            session.stdin.flush()
        }
    }

    private suspend fun readLoop(
        stdout: InputStream,
        stderr: InputStream,
        sessionId: String,
        state: MutableStateFlow<State>,
        requestMap: ConcurrentHashMap<Long, (JsonObject) -> Unit>,
    ) {
        val events = mutableListOf<PiEvent>()
        val stdoutReader = stdout.bufferedReader(StandardCharsets.UTF_8)
        val stderrReader = stderr.bufferedReader(StandardCharsets.UTF_8)

        // Drain stderr to logcat — Pi prints progress to stderr.
        scope.launch {
            stderrReader.lineSequence().forEach { line ->
                Log.i(TAG, "[pi stderr:$sessionId] $line")
            }
        }

        // Read LF-delimited JSON lines from stdout.
        val lines = stdoutReader.lineSequence()
        for (line in lines) {
            if (line.isBlank()) continue
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                handleRecord(obj, state, requestMap, events)
            } catch (t: Throwable) {
                Log.w(TAG, "[pi] malformed JSON line: $line (${t.message})")
            }
        }

        // Stream ended → process exited.
        state.value = State.Crashed
        sessions.remove(sessionId)
    }

    private fun handleRecord(
        obj: JsonObject,
        state: MutableStateFlow<State>,
        requestMap: ConcurrentHashMap<Long, (JsonObject) -> Unit>,
        events: MutableList<PiEvent>,
    ) {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull()
        val id = obj["id"]?.jsonPrimitive?.contentOrNull()
        when (type) {
            "response" -> {
                val reqId = id?.removePrefix("req-")?.toLongOrNull()
                if (reqId != null) {
                    requestMap.remove(reqId)?.invoke(obj)
                }
            }
            "event" -> {
                val event = PiEvent.from(obj)
                emitEvent(event)
                if (event is PiEvent.AgentEnd || event is PiEvent.AgentError) {
                    state.value = State.Ready
                }
                if (event is PiEvent.TurnStart) state.value = State.Running
            }
            else -> {
                // Pi uses 'type' as the event discriminator; handle
                // common variants explicitly above and treat the rest
                // as opaque events.
                emitEvent(PiEvent.Opaque(obj))
            }
        }
    }

    private fun emitEvent(event: PiEvent) {
        val session = sessions.values.firstOrNull() ?: return
        session.eventFlow.tryEmit(event)
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (this is JsonNull) null else content

    private val Session.eventFlow: MutableSharedFlow<PiEvent>
        get() = _eventFlowS.getOrPut(this) {
            MutableSharedFlow(
                extraBufferCapacity = 256,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    private val _eventFlowS = ConcurrentHashMap<Session, MutableSharedFlow<PiEvent>>()

    data class ImageAttachment(val dataBase64: String, val mimeType: String)

    data class InstallState(
        val installed: Boolean = false,
        val installing: Boolean = false,
        val progress: Float = 0f,
        val version: String? = null,
        val error: String? = null,
        /** Short human status for onboarding / banners. */
        val statusMessage: String? = null,
        /** Recent install log lines for UI debugging. */
        val logTail: String = "",
    )

    companion object {
        private const val TAG = "PiAgentService"

        @Volatile private var INSTANCE: PiAgentService? = null

        fun getInstance(context: Context): PiAgentService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PiAgentService(context.applicationContext).also { INSTANCE = it }
            }
    }
}
