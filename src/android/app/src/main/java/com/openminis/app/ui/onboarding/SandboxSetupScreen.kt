package com.openminis.app.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.pi.PiAgentService
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsInstallState
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run / onboarding step: prepare the Ubuntu PRoot sandbox and
 * install Pi Agent so the user is ready without opening Dev Mode.
 *
 * Phases:
 *  1. Extract Ubuntu rootfs from assets (progress from RootfsManager)
 *  2. Stage proot into filesDir/bin/proot
 *  3. Run pi-install (Node + pi-coding-agent) inside the guest
 */
@Composable
fun SandboxSetupScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit = onComplete,
    autoStart: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootfs = remember { RootfsManager.getInstance(context) }
    val pi = remember { PiAgentService.getInstance(context) }

    val rootfsState by rootfs.installState.collectAsState()
    val piState by pi.installState.collectAsState()

    var phase by remember { mutableStateOf(SetupPhase.Idle) }
    var detail by remember { mutableStateOf("Ready to prepare the Ubuntu sandbox and Pi Agent.") }
    var logTail by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var finishedOk by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    // Reflect rootfs extract progress into the detail line.
    LaunchedEffect(rootfsState) {
        when (val s = rootfsState) {
            is RootfsInstallState.Preparing -> {
                phase = SetupPhase.Rootfs
                detail = "Preparing Ubuntu rootfs…"
            }
            is RootfsInstallState.Extracting -> {
                phase = SetupPhase.Rootfs
                detail = "Extracting Ubuntu rootfs… ${(s.progress * 100).toInt()}%"
            }
            is RootfsInstallState.Finalizing -> {
                phase = SetupPhase.Rootfs
                detail = "Finalizing rootfs…"
            }
            is RootfsInstallState.Installed -> {
                if (phase == SetupPhase.Rootfs) detail = "Ubuntu rootfs ready."
            }
            is RootfsInstallState.Failed -> {
                lastError = s.error
                detail = "Rootfs failed: ${s.error}"
                phase = SetupPhase.Failed
                running = false
            }
            else -> {}
        }
    }

    LaunchedEffect(piState) {
        if (piState.installing) {
            phase = SetupPhase.Pi
            detail = piState.statusMessage?.takeIf { it.isNotBlank() }
                ?: "Installing Pi Agent (Node + pi-coding-agent)…"
            if (piState.logTail.isNotBlank()) logTail = piState.logTail
        } else if (piState.installed && running) {
            phase = SetupPhase.Done
            detail = "Pi ${piState.version ?: "ready"}."
            finishedOk = true
            running = false
        } else if (piState.error != null && running) {
            lastError = piState.error
            detail = piState.error ?: "Pi install failed"
            phase = SetupPhase.Failed
            running = false
            logTail = piState.logTail
        }
    }

    fun startSetup() {
        if (running) return
        running = true
        finishedOk = false
        lastError = null
        logTail = ""
        phase = SetupPhase.Rootfs
        detail = "Starting sandbox setup…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Boot installs rootfs + stages proot + overlay.
                    if (!PRootKernel.isBooted) {
                        phase = SetupPhase.Rootfs
                        detail = "Booting PRoot / extracting Ubuntu…"
                        PRootKernel.boot(context)
                    } else {
                        rootfs.installIfNeeded()
                        rootfs.installProotIfNeeded()
                    }
                    detail = "Sandbox ready. Installing Pi Agent…"
                    phase = SetupPhase.Pi
                    val result = pi.installIfNeeded()
                    if (result.installed) {
                        phase = SetupPhase.Done
                        detail = "All set — Pi ${result.version ?: "ready"}."
                        finishedOk = true
                    } else {
                        phase = SetupPhase.Failed
                        lastError = result.error ?: "Pi install failed"
                        detail = lastError ?: "failed"
                        logTail = result.logTail
                    }
                }
            } catch (t: Throwable) {
                phase = SetupPhase.Failed
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                detail = lastError ?: "failed"
            } finally {
                running = false
            }
        }
    }

    // Auto-start once when the step appears (first-run path).
    LaunchedEffect(autoStart) {
        if (autoStart) {
            // Tiny delay so the UI paints the progress chrome first.
            delay(150)
            // If already fully ready, skip work.
            val already = withContext(Dispatchers.IO) {
                rootfs.isInstalled && pi.installState.value.installed
            }
            if (already) {
                finishedOk = true
                phase = SetupPhase.Done
                detail = "Already prepared."
            } else {
                startSetup()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Prepare environment",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Extract Ubuntu 24.04 sandbox and install the Pi coding agent. " +
                "Needs network once for Node/npm packages. Usually a few minutes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        PhaseRow(
            label = "1. Ubuntu rootfs + PRoot",
            active = phase == SetupPhase.Rootfs,
            done = phase.ordinal > SetupPhase.Rootfs.ordinal && phase != SetupPhase.Failed,
            failed = phase == SetupPhase.Failed && lastError?.contains("Rootfs", true) == true,
        )
        PhaseRow(
            label = "2. Pi Agent (Node + pi)",
            active = phase == SetupPhase.Pi,
            done = phase == SetupPhase.Done || finishedOk,
            failed = phase == SetupPhase.Failed && lastError != null,
        )

        Spacer(Modifier.height(20.dp))

        when {
            running || phase == SetupPhase.Rootfs || phase == SetupPhase.Pi -> {
                if (rootfsState is RootfsInstallState.Extracting) {
                    val p = (rootfsState as RootfsInstallState.Extracting).progress
                    LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
            finishedOk || phase == SetupPhase.Done -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(detail, fontWeight = FontWeight.Medium)
                }
            }
            phase == SetupPhase.Failed -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        detail,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (logTail.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                logTail.takeLast(600),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(32.dp))

        when {
            finishedOk || phase == SetupPhase.Done -> {
                MinisButton(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue")
                }
            }
            phase == SetupPhase.Failed -> {
                MinisButton(onClick = { startSetup() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry")
                }
                Spacer(Modifier.height(8.dp))
                MinisTextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now")
                }
            }
            !running && phase == SetupPhase.Idle -> {
                MinisButton(onClick = { startSetup() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start setup")
                }
                Spacer(Modifier.height(8.dp))
                MinisTextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now")
                }
            }
            else -> {
                // In progress — allow skip so user isn't trapped on slow network.
                MinisTextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip — finish later in Settings")
                }
            }
        }
    }
}

private enum class SetupPhase { Idle, Rootfs, Pi, Done, Failed }

@Composable
private fun PhaseRow(
    label: String,
    active: Boolean,
    done: Boolean,
    failed: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            done -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            failed -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            active -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> Spacer(Modifier.size(20.dp))
        }
        Text(
            label,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                failed -> MaterialTheme.colorScheme.error
                done -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Fire-and-forget: kick off rootfs extract + proot stage in the background
 * as soon as the app has an empty first-run session list, so the later
 * onboarding Pi step (or Settings entry) is often already warm.
 */
fun warmSandboxInBackground(context: Context) {
    val appCtx = context.applicationContext
    kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
    ).launch {
        runCatching {
            val rm = RootfsManager.getInstance(appCtx)
            if (!rm.isInstalled) {
                rm.installIfNeeded()
            }
            rm.installProotIfNeeded()
        }
    }
}
