package com.mandar.echo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.PillButton
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.screens.SettingsScreen
import com.mandar.echo.ui.screens.SummariesScreen
import com.mandar.echo.ui.screens.TodayScreen
import com.mandar.echo.ui.screens.TranscriptScreen
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter

private val TABS = listOf("Today", "Transcript", "Summary", "Settings")

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EchoTheme {
                val vm: EchoViewModel = viewModel()
                EchoRoot(vm)
            }
        }
    }
}

@Composable
private fun EchoRoot(vm: EchoViewModel) {
    val colors = EchoTheme.colors
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        micGranted = result[Manifest.permission.RECORD_AUDIO] == true
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
                            .padding(horizontal = Gutter, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TABS.forEachIndexed { index, label ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { tab = index },
                            ) {
                                Text(
                                    label.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tab == index) colors.foreground else colors.faint,
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (tab == index) colors.foreground
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
                "Nothing is uploaded. Nothing leaves the device.",
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
