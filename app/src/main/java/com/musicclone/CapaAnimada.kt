package com.musicclone

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage

// ---------- iStudio: campo da capa animada ----------

// confere as regras (MP4, 1:1, até 10 s); devolve a mensagem de erro ou null se estiver tudo certo
fun capaAnimadaErro(ctx: Context, u: Uri): String? {
    if (ctx.contentResolver.getType(u) != "video/mp4") return "O arquivo precisa ser um vídeo MP4."
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, u)
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        when {
            w == 0 || h == 0 -> "Não consegui ler esse vídeo."
            w != h -> "O vídeo precisa ser quadrado (1:1). O seu é ${w}x${h}."
            ms > 10_500 -> "O vídeo precisa ter até 10 segundos."   // 0,5 s de folga na leitura da duração
            else -> null
        }
    } catch (e: Exception) { "Não consegui ler esse vídeo." } finally { r.release() }
}

@Composable fun CampoCapaAnimada(uri: Uri?, onPick: (Uri?) -> Unit) {
    val ctx = LocalContext.current
    var erro by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            val e = capaAnimadaErro(ctx, u)
            erro = e
            if (e == null) onPick(u)   // só troca se o vídeo novo for válido
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        T("Enviar Capa Animada (Opcional)", 16, 700)
        T("Regras: vídeo MP4, formato 1:1 (quadrado) e até 10 segundos.", 12, 400, Sub, Modifier.padding(top = 4.dp), 3)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = .16f)).clickable { pick.launch("video/mp4") }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Movie, null, Modifier.size(20.dp), Red)
                T(if (uri == null) "  Escolher vídeo" else "  Trocar vídeo", 14, 600)
            }
            if (uri != null) Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = .16f)).clickable { erro = null; onPick(null) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Remover", 14, 600, Sub)
            }
        }
        if (uri != null) T("Vídeo selecionado ✓", 12, 500, Color(0xFF34C759), Modifier.padding(top = 6.dp))
        erro?.let { T(it, 12, 500, Red, Modifier.padding(top = 6.dp), 3) }
    }
}

// ---------- Player: vídeo da capa (sem som, em loop, cortado em quadrado centralizado) ----------
@Composable fun CapaVideo(url: String, rodando: Boolean, m: Modifier = Modifier) {
    var mp by remember(url) { mutableStateOf<MediaPlayer?>(null) }
    val vai by rememberUpdatedState(rodando)
    DisposableEffect(url) { onDispose { runCatching { mp?.release() }; mp = null } }
    LaunchedEffect(rodando, mp) { runCatching { if (rodando) mp?.start() else mp?.pause() } }   // acompanha play/pause da música
    BoxWithConstraints(m.clipToBounds(), contentAlignment = Alignment.Center) {
        val lado = if (maxHeight > maxWidth) maxHeight else maxWidth
        key(url) {
            AndroidView(modifier = Modifier.requiredSize(lado), factory = { c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val p = MediaPlayer()
                            runCatching {
                                p.setSurface(Surface(st)); p.setDataSource(url)
                                p.isLooping = true; p.setVolume(0f, 0f)
                                p.setOnPreparedListener { x -> if (vai) x.start() }
                                p.setOnErrorListener { _, _, _ -> true }
                                p.prepareAsync()
                                mp = p
                            }.onFailure { p.release() }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { mp?.release() }; mp = null; return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            })
        }
    }
}

// igual ao CapaBlur, mas com o vídeo por cima da capa (a capa aparece até o vídeo carregar)
@Composable fun CapaBlurV(u: String, video: String, rodando: Boolean, escuro: Color, invade: Dp, cheio: Dp, funde: Dp, m: Modifier = Modifier.fillMaxSize()) {
    Box(m.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val f = funde.toPx()
        drawRect(Brush.verticalGradient(0f to Color.Black, .3f to Color.Black.copy(alpha = .85f), .6f to Color.Black.copy(alpha = .45f), .85f to Color.Black.copy(alpha = .12f), 1f to Color.Transparent, startY = size.height - f, endY = size.height), blendMode = BlendMode.DstIn)
    }) {
        Img(hq(u), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        if (video.isNotEmpty()) CapaVideo(video, rodando, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val a = size.height - invade.toPx(); val b = size.height - cheio.toPx()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, .25f to Color.Black.copy(alpha = .1f), .5f to Color.Black.copy(alpha = .4f), .75f to Color.Black.copy(alpha = .8f), 1f to Color.Black, startY = a, endY = b), blendMode = BlendMode.DstIn)
        }) {
            AsyncImage(model = hq(u), contentDescription = null, modifier = Modifier.fillMaxSize().blur(60.dp), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, escuro.copy(alpha = .30f), escuro.copy(alpha = .55f)))))
        }
    }
}
