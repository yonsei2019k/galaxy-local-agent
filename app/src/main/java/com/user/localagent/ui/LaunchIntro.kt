package com.user.localagent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.user.localagent.runtime.R
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/** Shortest time (from intro start) the intro stays up after the chat is ready when the video never started. */
internal const val LAUNCH_INTRO_MIN_MS = 1400L

/** Longest time (from intro start) the intro stays up after the chat is ready while the video is playing. */
internal const val LAUNCH_INTRO_MAX_MS = 3000L

/**
 * Whether the launch intro is still shown. It always waits for the chat; a tap ends it at once. With animations on,
 * once the chat is ready: if the video started rendering it stays until the video completes or [LAUNCH_INTRO_MAX_MS]
 * after intro start; if the video never started it stays until [LAUNCH_INTRO_MIN_MS]. Animations off do not wait.
 */
internal fun launchIntroVisible(
    elapsedMs: Long,
    chatReady: Boolean,
    animationsOn: Boolean,
    dismissed: Boolean,
    videoStarted: Boolean = false,
    videoDone: Boolean = false,
): Boolean {
    if (dismissed) return false
    if (!chatReady) return true
    if (!animationsOn) return false
    return if (videoStarted) !videoDone && elapsedMs < LAUNCH_INTRO_MAX_MS else elapsedMs < LAUNCH_INTRO_MIN_MS
}

/** Branding for the first chat render. It never owns input or delays WebView readiness. */
@Composable
internal fun LaunchIntro(chatReady: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var play by remember { mutableStateOf(false) }
    val animationsOn = remember { ValueAnimator.areAnimatorsEnabled() }
    var elapsedMs by remember { mutableStateOf(0L) }
    var videoDone by remember { mutableStateOf(false) }
    var videoStarted by remember { mutableStateOf(false) }
    var hasFrame by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(animationsOn) {
        val start = SystemClock.uptimeMillis()
        while (animationsOn && elapsedMs < LAUNCH_INTRO_MAX_MS) {
            delay(40)
            elapsedMs = SystemClock.uptimeMillis() - start
        }
    }
    val visible = launchIntroVisible(elapsedMs, chatReady, animationsOn, dismissed, videoStarted, videoDone)
    LaunchedEffect(visible) { if (!visible) onDismiss() }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            play = ValueAnimator.areAnimatorsEnabled()
            try {
                awaitCancellation()
            } finally {
                play = false
            }
        }
    }
    Box(
        modifier.background(Color(0xfff3f4f8))
            .semantics { contentDescription = "집합 시작 화면, 누르면 넘어가요" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "건너뛰기") { dismissed = true },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = 20.dp).widthIn(max = 440.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
            if (play) {
                AndroidView(
                    factory = { context ->
                        LaunchIntroVideo(context, { hasFrame = it; if (it) videoStarted = true }, { videoDone = true })
                    },
                    modifier = Modifier.matchParentSize(),
                    onRelease = LaunchIntroVideo::dispose,
                    update = {},
                )
            }
            if (!play || !hasFrame) {
                Image(
                    painterResource(R.drawable.ziphap_launch_intro),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/** A texture can show its first frame over the poster without blocking or tinting the chat. */
private class LaunchIntroVideo(
    context: Context,
    private val frameVisible: (Boolean) -> Unit,
    private val completed: () -> Unit,
) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    init {
        isOpaque = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        try {
            val output = Surface(texture).also { surface = it }
            val video = MediaPlayer().also { player = it }
            video.setSurface(output)
            video.setVolume(0f, 0f)
            video.setOnPreparedListener {
                if (player === it) try {
                    it.start()
                } catch (_: RuntimeException) {
                    releasePlayer()
                }
            }
            video.setOnInfoListener { current, what, _ ->
                if (player === current && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) frameVisible(true)
                false
            }
            video.setOnErrorListener { current, _, _ ->
                if (player === current) releasePlayer()
                true
            }
            video.setOnCompletionListener {
                if (player === it) {
                    completed()
                    dispose()
                }
            }
            checkNotNull(resources.openRawResourceFd(R.raw.ziphap_launch_intro)).use { asset ->
                video.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
            video.prepareAsync()
        } catch (_: RuntimeException) {
            releasePlayer()
        } catch (_: java.io.IOException) {
            releasePlayer()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    private fun releasePlayer() {
        frameVisible(false)
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    fun dispose() {
        surfaceTextureListener = null
        releasePlayer()
    }
}
