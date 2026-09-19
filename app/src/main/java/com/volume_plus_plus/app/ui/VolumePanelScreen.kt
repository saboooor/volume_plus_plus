package com.volume_plus_plus.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
import com.volume_plus_plus.app.overlay.ActiveAppPlayer
import com.volume_plus_plus.app.overlay.AppVolumeController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.i18n.Strings
import com.volume_plus_plus.app.i18n.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A standard system audio stream we expose as a slider. [label] resolves against the current
 * translation rather than being fixed at declaration, so the list can stay a top-level constant.
 */
private data class StreamInfo(
    val type: Int,
    val label: (Strings) -> String,
    @DrawableRes val icon: Int,
)

// STREAM_SYSTEM (UI/touch-sound volume) is deliberately omitted — it's rarely meaningful to end
// users and just adds clutter next to the streams people actually care about.
private val STREAMS = listOf(
    StreamInfo(AudioManager.STREAM_MUSIC, { it.streamMedia }, R.drawable.ic_stream_media),
    StreamInfo(AudioManager.STREAM_VOICE_CALL, { it.streamCall }, R.drawable.ic_stream_call),
    StreamInfo(AudioManager.STREAM_RING, { it.streamRing }, R.drawable.ic_stream_ring_phone),
    StreamInfo(
        AudioManager.STREAM_NOTIFICATION,
        { it.streamNotification },
        R.drawable.ic_stream_notification,
    ),
    StreamInfo(AudioManager.STREAM_ALARM, { it.streamAlarm }, R.drawable.ic_stream_alarm),
)

// Broadcast the system sends whenever any stream's volume changes (e.g. hardware keys). Undocumented
// but stable and widely used; lets our sliders track the real system state live.
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

/**
 * The Volume tab: stock-style sliders over the public [AudioManager] stream volumes. Needs no
 * privileges. Sliders re-read on external volume changes so hardware keys stay in sync.
 */
@Composable
fun VolumePanelScreen(contentPadding: PaddingValues, snackbar: SnackbarHostState) {
    val s = strings()
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()
    // Bumped by the volume-changed receiver to force each slider to re-read the system value.
    var refreshKey by remember { mutableIntStateOf(0) }

    val appVolume = remember { AppVolumeController.get(context) }
    var activeApps by remember { mutableStateOf<List<ActiveAppPlayer>>(emptyList()) }

    LaunchedEffect(refreshKey) {
        activeApps = appVolume.queryPlayingApps()
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            val queried = appVolume.queryPlayingApps()
            if (queried != activeApps) {
                activeApps = queried
            }
            delay(1500L)
        }
    }

    // Ring at zero means the ringer is on vibrate/silent — notifications can't make sound then,
    // so the Notification row is disabled while this is true.
    var ringMuted by remember {
        mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_RING) == 0)
    }
    LaunchedEffect(refreshKey) {
        ringMuted = audio.getStreamVolume(AudioManager.STREAM_RING) == 0
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                refreshKey++
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = s.volumeTitle, subtitle = s.volumeSubtitle)

        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            STREAMS.forEach { stream ->
                StreamRow(
                    stream = stream,
                    audio = audio,
                    refreshKey = refreshKey,
                    enabled = !(stream.type == AudioManager.STREAM_NOTIFICATION && ringMuted),
                    onDenied = { showNotificationPolicyPrompt(scope, snackbar, context, s) },
                )
            }
        }

        if (activeApps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = s.volumeAppVolumes,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                activeApps.forEach { app ->
                    AppVolumeRow(
                        app = app,
                        appVolume = appVolume,
                        refreshKey = refreshKey,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StreamRow(
    stream: StreamInfo,
    audio: AudioManager,
    refreshKey: Int,
    enabled: Boolean,
    onDenied: () -> Unit,
) {
    val max = remember(stream.type) { audio.getStreamMaxVolume(stream.type) }
    val min = remember(stream.type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(stream.type)
        else 0
    }
    var value by remember(stream.type) { mutableFloatStateOf(audio.getStreamVolume(stream.type).toFloat()) }

    // Re-read whenever the system reports a volume change (hardware keys, other apps).
    LaunchedEffect(refreshKey) {
        value = audio.getStreamVolume(stream.type).toFloat()
    }

    val s = strings()
    val label = stream.label(s)
    val range = (max - min).coerceAtLeast(1)
    val percent = (((value - min) / range) * 100f).roundToInt().coerceIn(0, 100)

    // Streams with a distinct muted state swap their icon at zero; a disabled Notification row
    // shows the crossed bell.
    val icon = when {
        !enabled && stream.type == AudioManager.STREAM_NOTIFICATION -> R.drawable.ic_ring_off
        percent == 0 && stream.type == AudioManager.STREAM_MUSIC -> R.drawable.ic_stream_media_off
        percent == 0 && stream.type == AudioManager.STREAM_RING -> R.drawable.ic_ring_vibrate
        else -> stream.icon
    }
    val contentTint =
        if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = contentTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else contentTint,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (enabled) s.percent(percent) else s.off,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint,
                    textAlign = TextAlign.End,
                    // Fixed width so the row doesn't jitter as digits change while dragging.
                    modifier = Modifier.width(44.dp),
                )
            }
            VolumeSlider(
                value = value,
                onValueChange = { new ->
                    value = new
                    val target = new.roundToInt().coerceIn(min, max)
                    try {
                        audio.setStreamVolume(stream.type, target, 0)
                    } catch (e: SecurityException) {
                        // Ring/Notification under Do Not Disturb needs notification-policy access.
                        onDenied()
                    }
                },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun showNotificationPolicyPrompt(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
    s: Strings,
) {
    scope.launch {
        val result = snackbar.showSnackbar(
            message = s.dndBlocking,
            actionLabel = s.settings,
        )
        if (result == SnackbarResult.ActionPerformed) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}


@Composable
private fun AppVolumeRow(
    app: ActiveAppPlayer,
    appVolume: AppVolumeController,
    refreshKey: Int,
) {
    var value by remember(app.packageName) {
        mutableFloatStateOf(appVolume.volumeFor(app.packageName))
    }

    LaunchedEffect(refreshKey) {
        value = appVolume.volumeFor(app.packageName)
    }

    val s = strings()
    val percent = (value * 100f).roundToInt().coerceIn(0, 100)
    val bitmap = remember(app.packageName, app.icon) {
        runCatching { app.icon?.toBitmap()?.asImageBitmap() }.getOrNull()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = app.label,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_stream_media),
                contentDescription = app.label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = s.percent(percent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }
            VolumeSlider(
                value = value,
                onValueChange = { new ->
                    value = new
                    appVolume.setVolume(app.packageName, new, app.piids)
                },
                valueRange = 0f..1f,
                steps = 99,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
