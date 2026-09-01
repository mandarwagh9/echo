package com.mandar.echo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mandar.echo.audio.EchoServiceState
import com.mandar.echo.audio.Notifications
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.components.EchoButton
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.MinTouchTarget
import com.mandar.echo.ui.onboarding.OnboardingFlow
import com.mandar.echo.ui.screens.DayScreen
import com.mandar.echo.ui.screens.DaysScreen
import com.mandar.echo.ui.screens.HomeScreen
import com.mandar.echo.ui.screens.SettingsScreen
import com.mandar.echo.ui.theme.EchoMotion
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import java.time.LocalDate

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

    /**
     * The service does work that exists only to feed this screen: the level meter
     * and the transcription progress bar. Both are pure waste while no UI is on
     * screen, which for a 24/7 recorder is nearly all of the time.
     */
    override fun onStart() {
        super.onStart()
        EchoServiceState.setUiVisible(true)
    }

    override fun onStop() {
        EchoServiceState.setUiVisible(false)
        super.onStop()
    }

    // The activity is launchMode="singleTask", so a resume tapped while Echo is
    // already open is delivered here rather than through onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_RESUME, false)) resumeRequest.value = true
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Days("Days", Icons.Default.DateRange),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
private fun EchoRoot(vm: EchoViewModel, resumeRequest: MutableState<Boolean>) {
    val colors = EchoTheme.colors
    val context = LocalContext.current

    val launch by vm.launch.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()

    fun micIsGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    var micGranted by remember { mutableStateOf(micIsGranted()) }

    var tab by rememberSaveable { mutableIntStateOf(0) }

    /** Non-null while a single day is open on top of the tabs. */
    var openDay by rememberSaveable { mutableStateOf<Long?>(null) }

    // Re-checked on resume: permission may have been granted by hand in system
    // settings, and the date may have rolled over while the app sat in the
    // background, which would otherwise leave Home showing yesterday.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = micIsGranted()
        vm.refreshToday()
    }

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

    // Which door to open is resolved off the settings store and the database, so
    // for the first frame or two it is genuinely unknown. Painting the background
    // is the honest answer: rendering either destination on a guess means every
    // cold start flashes the welcome screen at someone who onboarded months ago.
    if (launch == EchoViewModel.Launch.Undecided) {
        Box(Modifier.fillMaxSize().background(colors.background))
        return
    }

    // First run owns the whole window. It is where consent is given and where
    // the permissions and the model that make Echo work are collected.
    if (launch == EchoViewModel.Launch.Onboarding) {
        OnboardingFlow(vm) { micGranted = micIsGranted() }
        return
    }

    // Onboarding granted the microphone, but it can be revoked later from system
    // settings, and an app that silently does nothing afterwards is worse than
    // one that says why.
    if (!micGranted) {
        MicrophoneRevoked(
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
        )
        return
    }

    // openDay is saveable and vm.selectedDate is not, so after process death the
    // day screen would restore and show today. Re-applying it here covers both
    // that restore and ordinary navigation, which is why the call sites below do
    // not set the date themselves.
    LaunchedEffect(openDay) {
        openDay?.let { vm.selectDate(LocalDate.ofEpochDay(it)) }
    }

    val dayOpen = openDay != null
    BackHandler(enabled = dayOpen || tab != 0) {
        if (dayOpen) openDay = null else tab = 0
    }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            // Hidden while a day is open: that screen is a push, and it carries
            // its own back control.
            if (!dayOpen) {
                Column(
                    Modifier
                        .background(colors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    Hairline()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Gutter, vertical = 4.dp)
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Tab.entries.forEachIndexed { index, entry ->
                            TabItem(
                                tab = entry,
                                selected = tab == index,
                                onClick = { tab = index },
                            )
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
            AnimatedContent(
                targetState = openDay to tab,
                transitionSpec = {
                    fadeIn(EchoMotion.standard()) togetherWith fadeOut(EchoMotion.quick())
                },
                label = "destination",
            ) { (day, selected) ->
                when {
                    day != null -> DayScreen(vm, onBack = { openDay = null })
                    selected == Tab.Days.ordinal -> DaysScreen(vm) { date ->
                        openDay = date.toEpochDay()
                    }
                    selected == Tab.Settings.ordinal -> SettingsScreen(vm)
                    else -> HomeScreen(
                        vm,
                        onOpenSettings = { tab = Tab.Settings.ordinal },
                        onOpenToday = { openDay = LocalDate.now().toEpochDay() },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val colors = EchoTheme.colors
    val alpha by animateFloatAsState(
        if (selected) 1f else 0.55f,
        EchoMotion.quick(),
        label = "tab",
    )
    val tint = (if (selected) colors.foreground else colors.faint).copy(alpha = alpha)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .sizeIn(minHeight = MinTouchTarget, minWidth = MinTouchTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/** Shown when the microphone permission has been taken away after first run. */
@Composable
private fun MicrophoneRevoked(onOpenSettings: () -> Unit) {
    val colors = EchoTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "The microphone permission was turned off.",
            style = MaterialTheme.typography.displayMedium,
            color = colors.foreground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Echo cannot record without it. Everything already transcribed is still here " +
                "and untouched; granting the permission again picks up where it left off.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.muted,
        )
        Spacer(Modifier.height(28.dp))
        EchoButton("Open settings", filled = true, onClick = onOpenSettings)
    }
}
