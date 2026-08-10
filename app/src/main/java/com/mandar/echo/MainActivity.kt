package com.mandar.echo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mandar.echo.audio.Notifications
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.MinTouchTarget
import com.mandar.echo.ui.components.PillButton
import com.mandar.echo.ui.components.SectionLabel
import java.util.Locale
import com.mandar.echo.ui.screens.SettingsScreen
import com.mandar.echo.ui.screens.SummariesScreen
import com.mandar.echo.ui.screens.TodayScreen
import com.mandar.echo.ui.screens.TranscriptScreen
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter

private val TABS = listOf("Today", "Transcript", "Summary", "Settings")

class MainActivity : ComponentActivity() {

    /** Set when the "Resume recording" notification action brought us here. */
    private val resumeRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        resumeRequest.value = intent?.getBooleanExtra(Notifications.EXTRA_RESUME, false) == true
        setContent {
            EchoTheme {
                val vm: EchoViewModel = viewModel()
                EchoRoot(vm, resumeRequest)
            }
        }
    }

    // The activity is launchMode="singleTask", so a resume tapped while Echo is
    // already open is delivered here rather than through onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_RESUME, false)) resumeRequest.value = true
    }
}

@Composable
private fun EchoRoot(vm: EchoViewModel, resumeRequest: MutableState<Boolean>) {
    val colors = EchoTheme.colors
    val context = LocalContext.current
    val recording by vm.recording.collectAsStateWithLifecycle()

    // Saveable: a rotation used to drop the user back to Today from wherever
    // they were reading.
    var tab by rememberSaveable { mutableIntStateOf(0) }

    fun micIsGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    var micGranted by remember { mutableStateOf(micIsGranted()) }

    // Re-checked on every resume. Denying twice sends the user to system settings
    // to grant it by hand, and without this they came back to the same gate with
    // the permission already granted and no way forward but killing the app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { micGranted = micIsGranted() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        micGranted = result[Manifest.permission.RECORD_AUDIO] == true
    }

    // Back returns to Today instead of leaving the app. The tabs are a plain
    // index with no back stack, so every back press from Transcript, Summary or
    // Settings used to drop the user straight out of Echo.
    BackHandler(enabled = micGranted && tab != 0) { tab = 0 }

    // Deferred until the permission is actually in hand: a resume tapped on a
    // fresh install would otherwise be swallowed by the gate and never fire.
    LaunchedEffect(resumeRequest.value, micGranted) {
        if (resumeRequest.value && micGranted) {
            vm.startRecording(context)
            resumeRequest.value = false
        }
    }

    // The alert is ongoing, so nothing else takes it down. Capture actually
    // running is the only honest reason to clear it.
    LaunchedEffect(recording) {
        if (recording) Notifications.cancel(context, Notifications.ID_RESUME)
    }

    fun requestPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        launcher.launch(wanted.toTypedArray())
    }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            if (micGranted) {
                Column(
                    Modifier
                        .background(colors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    Hairline()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 2 dp here + a 48 dp target below reproduces the old
                            // 52 dp bar exactly, so the touch target grows without
                            // the dots moving.
                            .padding(horizontal = Gutter, vertical = 2.dp)
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TABS.forEachIndexed { index, label ->
                            val selected = tab == index
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .sizeIn(minHeight = MinTouchTarget, minWidth = MinTouchTarget)
                                    .selectable(
                                        selected = selected,
                                        role = Role.Tab,
                                        onClick = { tab = index },
                                    ),
                            ) {
                                Text(
                                    label.uppercase(Locale.ROOT),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) colors.foreground else colors.faint,
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) colors.foreground
                                            else androidx.compose.ui.graphics.Color.Transparent
                                        )
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.statusBars)
                .consumeWindowInsets(padding)
        ) {
            if (!micGranted) {
                PermissionGate(onGrant = ::requestPermissions)
            } else {
                when (tab) {
                    0 -> TodayScreen(vm, onOpenSettings = { tab = 3 })
                    1 -> TranscriptScreen(vm)
                    2 -> SummariesScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    val colors = EchoTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = Gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        SectionLabel("Echo")
        Spacer(Modifier.height(20.dp))
        Text(
            "A record of your day, kept entirely on your phone.",
            style = MaterialTheme.typography.displayMedium,
            color = colors.foreground,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Echo listens in the background, transcribes what it hears on-device, and " +
                "deletes each recording as soon as its transcript is saved. At 11 PM it " +
                "writes up your day.\n\n" +
                "Out of the box nothing is uploaded and nothing leaves the phone. " +
                "Settings can point transcription at a server you run, which does send " +
                "audio — that switch is yours, and it is off until you throw it.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.muted,
        )
        Spacer(Modifier.height(28.dp))
        Hairline()
        Spacer(Modifier.height(20.dp))
        Text(
            "This app records the people around you. Be aware that recording others " +
                "without their knowledge carries real legal weight in many places.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )
        Spacer(Modifier.height(32.dp))
        PillButton("Allow microphone", filled = true, onClick = onGrant)
        Spacer(Modifier.height(12.dp))
        Text(
            "Echo cannot work without it.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
            textAlign = TextAlign.Start,
        )
    }
}
