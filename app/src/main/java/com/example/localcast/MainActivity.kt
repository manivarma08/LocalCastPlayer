package com.example.localcast

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address

private const val PORT = 8080

class MainActivity : AppCompatActivity() {
    private lateinit var server: VideoServer
    private var serverError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        server = VideoServer(contentResolver, PORT)
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            serverError = "Could not start local server: ${e.message}"
        }

        val castContext = CastContext.getSharedInstance(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CastScreen(castContext, server, serverError)
                }
            }
        }
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }
}

@Composable
private fun CastScreen(castContext: CastContext, server: VideoServer, serverError: String?) {
    val context = LocalContext.current
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var mime by remember { mutableStateOf("video/mp4") }
    var status by remember { mutableStateOf(serverError ?: "Pick a video to start") }
    var connected by remember {
        mutableStateOf(castContext.sessionManager.currentCastSession?.isConnected == true)
    }
    var playing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            mime = context.contentResolver.getType(uri) ?: "video/mp4"
            title = displayName(context, uri)
            status = "Selected: $title"
        }
    }

    DisposableEffect(castContext) {
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionStarted(s: CastSession, id: String) { connected = true }
            override fun onSessionStartFailed(s: CastSession, error: Int) { connected = false }
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, error: Int) { connected = false }
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { connected = true }
            override fun onSessionResumeFailed(s: CastSession, error: Int) { connected = false }
            override fun onSessionSuspended(s: CastSession, reason: Int) {}
        }
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        onDispose {
            castContext.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
        }
    }

    val client: RemoteMediaClient? =
        if (connected) castContext.sessionManager.currentCastSession?.remoteMediaClient else null

    DisposableEffect(client) {
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() { playing = client?.isPlaying == true }
        }
        client?.registerCallback(callback)
        onDispose { client?.unregisterCallback(callback) }
    }

    fun castVideo() {
        val uri = pickedUri ?: return
        val remote = client ?: return
        val ip = localIpv4(context)
        if (ip == null) {
            status = "No Wi-Fi/LAN address found"
            return
        }
        server.uri = uri
        server.mime = mime
        val url = "http://$ip:$PORT/video?t=${System.currentTimeMillis()}"
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()
        remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        status = "Casting: $title"
    }

    fun seekBy(deltaMs: Long) {
        val remote = client ?: return
        val target = (remote.approximateStreamPosition + deltaMs).coerceAtLeast(0L)
        remote.seek(MediaSeekOptions.Builder().setPosition(target).build())
    }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LocalCast", style = MaterialTheme.typography.headlineMedium)
            AndroidView(factory = { ctx ->
                MediaRouteButton(ctx).also { CastButtonFactory.setUpMediaRouteButton(ctx, it) }
            })
        }
        Text(if (connected) "Connected to Chromecast" else "Tap the cast icon to choose a device")
        Text(status)

        Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Pick video") }
        Button(onClick = { castVideo() }, enabled = connected && pickedUri != null) {
            Text("Cast to TV")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { seekBy(-10_000) }, enabled = client != null) { Text("-10s") }
            OutlinedButton(onClick = { client?.togglePlayback() }, enabled = client != null) {
                Text(if (playing) "Pause" else "Play")
            }
            OutlinedButton(onClick = { seekBy(10_000) }, enabled = client != null) { Text("+10s") }
            OutlinedButton(onClick = { client?.stop() }, enabled = client != null) { Text("Stop") }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment
        ?: "video"

private fun localIpv4(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses
        .map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}
