package com.mandar.echo.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.stt.DownloadState
import com.mandar.echo.stt.WhisperModel
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.ChoiceRow
import com.mandar.echo.ui.components.EchoButton
import com.mandar.echo.ui.components.EchoCard
import com.mandar.echo.ui.components.Figure
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.components.ThinProgress
import com.mandar.echo.ui.theme.EchoMotion
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter

/**
 * First run.
 *
 * Every step here exists because skipping it produces a specific, reported
 * failure rather than merely a rougher introduction:
 *
 *  - **Consent.** Echo records whoever is in the room. In a good many places
 *    that carries real legal weight, and a stranger installing a recorder has
 *    not thought about it. This is stated once, plainly, before the microphone
 *    is ever opened.
 *  - **Microphone.** Nothing works without it, and a second denial is permanent
 *    until the user visits system settings, so that road has to be signposted.
 *  - **Notifications.** The ongoing notification is the only always-visible sign
 *    that a recorder is running, and the alert channel is the only thing that
 *    says "the microphone was taken and capture has stopped."
 *  - **Battery.** Without the Doze exemption, capture dies some time after the
 *    screen goes off and the user wakes to an unrecorded night with nothing on
 *    screen explaining it. This is the step most likely to be skipped and the
 *    one that most reliably breaks the product.
 *  - **Model.** On-device transcription needs weights that are not in the APK.
 *    Until they arrive the recorder records and nothing is ever transcribed.
 */

private enum class Step { Welcome, Consent, Microphone, Battery, Model, Ready }

@Composable
fun OnboardingFlow(vm: EchoViewModel, onFinished: () -> Unit) {
    val colors = EchoTheme.colors
    val context = LocalContext.current

    var stepIndex by remember { mutableIntStateOf(0) }
    val steps = Step.entries
    val step = steps[stepIndex]

    fun advance() {
        if (stepIndex < steps.lastIndex) stepIndex++ else onFinished()
    }

    fun goBack() {
        if (stepIndex > 0) stepIndex--
    }

    BackHandler(enabled = stepIndex > 0) { goBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        StepIndicator(
            current = stepIndex,
            total = steps.size,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 20.dp),
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val offset = if (forward) 1 else -1
                (slideInHorizontally(EchoMotion.standard()) { it / 6 * offset } +
                    fadeIn(EchoMotion.standard()))
                    .togetherWith(
                        slideOutHorizontally(EchoMotion.standard()) { -it / 6 * offset } +
                            fadeOut(EchoMotion.quick())
                    )
            },
            label = "onboarding-step",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                Step.Welcome -> WelcomeStep(onNext = ::advance)
                Step.Consent -> ConsentStep(onNext = ::advance)
                Step.Microphone -> MicrophoneStep(onNext = ::advance)
                Step.Battery -> BatteryStep(vm = vm, onNext = ::advance)
                Step.Model -> ModelStep(vm = vm, onNext = ::advance)
                Step.Ready -> ReadyStep(
                    onDone = {
                        vm.completeOnboarding()
                        vm.startRecording(context)
                        onFinished()
                    },
                    onLater = {
                        vm.completeOnboarding()
                        onFinished()
                    },
                )
            }
        }
    }
}

/**
 * Progress through the flow, as filled segments.
 *
 * Announced as one phrase. Six separate unlabelled bars are noise to a screen
 * reader, and "step 3 of 6" is the entire information they carry.
 */
@Composable
private fun StepIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Step ${current + 1} of $total"
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(total) { i ->
            val filled = i <= current
            val alpha by animateFloatAsState(
                if (filled) 1f else 0.25f,
                EchoMotion.standard(),
                label = "seg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(
                        (if (filled) colors.accent else colors.hairline).copy(alpha = alpha)
                    )
            )
        }
    }
}

/** Shared page frame: scrolling body, actions pinned at the bottom. */
@Composable
private fun StepScaffold(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    eyebrow: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = EchoTheme.colors
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter),
        ) {
            Spacer(Modifier.height(20.dp))
            if (eyebrow != null) {
                SectionLabel(eyebrow, color = colors.accent)
                Spacer(Modifier.height(14.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                color = colors.foreground,
            )
            Spacer(Modifier.height(16.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            if (content != null) {
                Spacer(Modifier.height(28.dp))
                content()
            }
            Spacer(Modifier.height(32.dp))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Gutter, vertical = 16.dp),
        ) {
            EchoButton(
                primaryLabel,
                filled = true,
                enabled = primaryEnabled,
                modifier = Modifier.fillMaxWidth(),
                onClick = onPrimary,
            )
            if (secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(10.dp))
                EchoButton(
                    secondaryLabel,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun WelcomeStep(onNext: () -> Unit) = StepScaffold(
    eyebrow = "Echo",
    title = "A record of your day, kept on your phone.",
    body = "Echo listens in the background, writes down what it hears, and deletes " +
        "each recording as soon as its transcript is saved. At the end of the day it " +
        "writes the day up for you.\n\n" +
        "Transcription runs on this phone. Nothing is uploaded, and there is no " +
        "account to make.",
    primaryLabel = "Get started",
    onPrimary = onNext,
)

@Composable
private fun ConsentStep(onNext: () -> Unit) {
    val colors = EchoTheme.colors
    var acknowledged by remember { mutableStateOf(false) }

    StepScaffold(
        title = "It records other people too.",
        body = "A microphone left open in a room picks up everyone in it, not just you. " +
            "Recording other people without telling them is illegal in a good many " +
            "places, and Echo cannot tell where you are or who you are with.",
        primaryLabel = "I understand",
        primaryEnabled = acknowledged,
        onPrimary = onNext,
        content = {
            Column {
                EchoCard {
                    Column {
                        Text(
                            "Before you switch it on",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.foreground,
                        )
                        Spacer(Modifier.height(10.dp))
                        Bullet("Find out what the law where you live says about recording a conversation.")
                        Bullet("Tell the people you are regularly around that you are doing this.")
                        Bullet("Turn it off in rooms where other people would not expect it.")
                    }
                }
                Spacer(Modifier.height(18.dp))
                ChoiceRow(
                    label = "I have read this and I am responsible for how I use Echo",
                    selected = acknowledged,
                    role = Role.Checkbox,
                    onClick = { acknowledged = !acknowledged },
                )
            }
        },
    )
}

@Composable
private fun Bullet(text: String) {
    val colors = EchoTheme.colors
    Row(Modifier.padding(top = 8.dp)) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(colors.faint)
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
    }
}

@Composable
private fun MicrophoneStep(onNext: () -> Unit) {
    val context = LocalContext.current

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var micGranted by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var asked by remember { mutableStateOf(false) }

    // Re-read on resume: the user may have granted it by hand in system settings
    // and come back, and without this they would return to the same locked gate.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = granted(Manifest.permission.RECORD_AUDIO)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        asked = true
        micGranted = result[Manifest.permission.RECORD_AUDIO] == true
    }

    // A second denial is permanent until system settings, and the system dialog
    // simply does not appear again. Sending the user round the same button is
    // the dead end this branch exists to avoid.
    val stuck = asked && !micGranted

    StepScaffold(
        title = "Echo needs the microphone.",
        body = if (stuck) {
            "The permission was declined. Android will not ask a second time, so it has " +
                "to be granted from system settings: open Permissions, then Microphone, " +
                "and choose Allow."
        } else {
            "This is the whole app. Echo also asks to post notifications, which is how it " +
                "tells you it is running, and how it tells you if recording ever stops."
        },
        primaryLabel = when {
            micGranted -> "Continue"
            stuck -> "Open settings"
            else -> "Allow microphone"
        },
        onPrimary = {
            when {
                micGranted -> onNext()
                stuck -> context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
                else -> launcher.launch(
                    buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.toTypedArray()
                )
            }
        },
        content = {
            StatusLine(
                done = micGranted,
                doneText = "Microphone granted",
                pendingText = "Microphone not granted yet",
            )
        },
    )
}

@Composable
private fun BatteryStep(vm: EchoViewModel, onNext: () -> Unit) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(vm.batteryExemptionGranted(context)) }

    // The system dialog is a separate activity, so the answer only arrives back
    // here as a resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = vm.batteryExemptionGranted(context)
    }

    StepScaffold(
        title = "Let it keep running.",
        body = "Android suspends apps once the screen has been off for a while. Echo has " +
            "to be exempt from that, or recording stops some time after you put the " +
            "phone down and the rest of the night is never captured.\n\n" +
            "This is the setting people most often skip and then report as a bug.",
        primaryLabel = if (exempt) "Continue" else "Allow background running",
        onPrimary = {
            if (exempt) {
                onNext()
            } else {
                runCatching { context.startActivity(vm.batteryExemptionIntent(context)) }
            }
        },
        secondaryLabel = if (exempt) null else "Skip for now",
        onSecondary = if (exempt) null else onNext,
        content = {
            StatusLine(
                done = exempt,
                doneText = "Echo can run in the background",
                pendingText = "Recording will stop when the phone sleeps",
            )
        },
    )
}

@Composable
private fun ModelStep(vm: EchoViewModel, onNext: () -> Unit) {
    val colors = EchoTheme.colors
    val download by vm.downloadState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Keyed on the download state: the set of installed files does not itself
    // emit, so without this the screen would never notice one had arrived.
    val installed = remember(download) { vm.modelsInstalled() }
    val chosen = remember(settings.modelFile) { WhisperModel.byFileName(settings.modelFile) }
    val running = download as? DownloadState.Running
    val failed = download as? DownloadState.Failed

    LaunchedEffect(installed) {
        // Nothing is selected on a fresh install, so adopt whatever landed.
        if (installed.isNotEmpty() && !vm.isInstalled(chosen)) vm.setModel(installed.first())
    }

    StepScaffold(
        title = "Choose a speech model.",
        body = "Transcription runs here, on the phone, so the model has to be downloaded " +
            "once. It stays on the device and works with no connection afterwards.",
        primaryLabel = if (installed.isNotEmpty()) "Continue" else "Download",
        primaryEnabled = running == null,
        onPrimary = {
            if (installed.isNotEmpty()) onNext() else vm.downloadModel(chosen)
        },
        secondaryLabel = if (installed.isEmpty() && running == null) "Set this up later" else null,
        onSecondary = if (installed.isEmpty() && running == null) onNext else null,
        content = {
            Column {
                WhisperModel.entries.forEach { model ->
                    val here = vm.isInstalled(model)
                    ChoiceRow(
                        label = model.label,
                        body = if (here) {
                            "Installed, ${Format.bytes(model.bytes)}"
                        } else {
                            "${Format.bytes(model.bytes)} download. ${model.note}"
                        },
                        selected = chosen == model,
                        enabled = running == null,
                        onClick = { vm.setModel(model) },
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (running != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Downloading ${running.model.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.foreground,
                        )
                        Figure(
                            "${Format.bytes(running.bytes)} / ${Format.bytes(running.total)}"
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    ThinProgress(running.fraction)
                    Spacer(Modifier.height(14.dp))
                    EchoButton("Cancel", onClick = vm::cancelDownload)
                }

                if (failed != null) {
                    Spacer(Modifier.height(14.dp))
                    EchoCard(accented = true) {
                        Column {
                            Text(
                                "Download failed",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.accent,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                failed.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.muted,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ReadyStep(onDone: () -> Unit, onLater: () -> Unit) = StepScaffold(
    title = "That is everything.",
    body = "Echo is ready. Starting now opens the microphone and keeps it open until you " +
        "stop it, and you can stop it at any time from the app or from the notification.\n\n" +
        "The first transcript appears once the current chunk closes, about ten minutes in.",
    primaryLabel = "Start listening",
    onPrimary = onDone,
    secondaryLabel = "Not right now",
    onSecondary = onLater,
)

/** A one-line "this is done / this is not" under a permission step. */
@Composable
private fun StatusLine(done: Boolean, doneText: String, pendingText: String) {
    val colors = EchoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            androidx.compose.material3.Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Box(
                Modifier
                    .size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.faint)
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Text(
            if (done) doneText else pendingText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) colors.accent else colors.faint,
        )
    }
}
