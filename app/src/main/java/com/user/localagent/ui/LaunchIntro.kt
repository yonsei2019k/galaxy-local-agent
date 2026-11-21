package com.user.localagent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.user.localagent.runtime.R
import kotlinx.coroutines.awaitCancellation

/** Branding for the first chat render. It never owns input or delays WebView readiness. */
@Composable
internal fun LaunchIntro(modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var play by remember { mutableStateOf(false) }
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
    Box(modifier.background(Color(0xfff8f7f3)), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(horizontal = 20.dp).widthIn(max = 440.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
            var hasFrame by remember { mutableStateOf(false) }
            if (play) {
                AndroidView(
                    factory = { context -> LaunchIntroVideo(context) { hasFrame = it } },
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
private class LaunchIntroVideo(context: Context, private val frameVisible: (Boolean) -> Unit) :
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
            video.setOnCompletionListener { if (player === it) dispose() }
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
