@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicclone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as GCanvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint as GPaint
import android.graphics.Path as GPath
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
// CONFIG: BASE = endereço (domínio) onde ficam as páginas de link de perfil/música/lançamento, SEM barra no final.
// O story do Instagram NÃO usa isso: ele só manda a imagem do card.
// =====================================================================
object ShareCfg {
    const val BASE = "https://beats-share.SEUUSUARIO.workers.dev"
}

class ShareData(val kind: String, val title: String, val subtitle: String, val image: String?, val link: String, val circle: Boolean = false)

val LocalShare = staticCompositionLocalOf<(ShareData) -> Unit> { { } }

// ---------- links (o Worker devolve a página com título + capa/foto para as mensagens) ----------
private fun fallback(k: String, t: String, a: String, i: String?) =
    "${ShareCfg.BASE}/s?k=$k&t=${Uri.encode(t)}&a=${Uri.encode(a)}&i=${Uri.encode(i ?: "")}"

fun shareTrack(t: Track) = ShareData("track", t.name, t.artist, t.art,
    if (t.tid.isNotEmpty()) "${ShareCfg.BASE}/t/${t.tid}" else fallback("track", t.name, t.artist, t.art))

fun shareAlbum(a: Album) = ShareData("album", a.name, a.artist, a.art, fallback("album", a.name, a.artist, a.art))

fun shareRel(r: Rel) = ShareData("album", r.title, r.artistName, r.cover, "${ShareCfg.BASE}/r/${r.id}")

fun shareArtist(nome: String, uid: String?, foto: String?) = ShareData("artist", nome, "Artista no beats", foto,
    if (!uid.isNullOrEmpty()) "${ShareCfg.BASE}/a/$uid" else fallback("artist", nome, "Artista no beats", foto), circle = true)

fun copiarLink(ctx: Context, d: ShareData) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("link", d.link))
    Toast.makeText(ctx, "Link copiado", Toast.LENGTH_SHORT).show()
}

// ---------- card do story (vira uma FOTO 1080x1920 e vai pro story como foto) ----------
private suspend fun baixar(ctx: Context, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(hq(url)).allowHardware(false).build())
    return (r as? SuccessResult)?.drawable?.toBitmap()
}

private suspend fun montarCard(ctx: Context, d: ShareData): Bitmap = withContext(Dispatchers.Default) {
    val cover = baixar(ctx, d.image)
    val pal = cover?.let { runCatching { Palette.from(it).generate() }.getOrNull() }
    val corTopo = pal?.getVibrantColor(pal.getDominantColor(0xFF8A1020.toInt())) ?: 0xFF8A1020.toInt()
    val corBase = pal?.getDarkMutedColor(pal.getDarkVibrantColor(0xFF200509.toInt())) ?: 0xFF200509.toInt()

    val SW = 1080; val SH = 1920
    val bmp = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)
    val c = GCanvas(bmp)
    val flags = GPaint.ANTI_ALIAS_FLAG
    // fundo do story: degradê com as cores da capa
    c.drawRect(0f, 0f, SW.toFloat(), SH.toFloat(), GPaint().apply { shader = LinearGradient(0f, 0f, 0f, SH.toFloat(), corTopo, corBase, Shader.TileMode.CLAMP) })

    val W = 900; val pad = 60; val img = W - 2 * pad
    val tp = TextPaint(flags).apply { color = 0xFF000000.toInt(); textSize = 54f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val ap = TextPaint(flags).apply { color = 0xFF6E6E73.toInt(); textSize = 44f; typeface = Typeface.SANS_SERIF }
    val bp = TextPaint(flags).apply { color = 0xFFFA243C.toInt(); textSize = 36f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val titleBase = pad + img + 36 + 54
    val artistBase = titleBase + 16 + 44
    val beatsBase = artistBase + 34 + 36
    val H = beatsBase + pad
    // centraliza o card branco no meio da tela do story
    c.translate(((SW - W) / 2).toFloat(), ((SH - H) / 2).toFloat())
    c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 48f, 48f, GPaint(flags).apply { color = 0xFFFFFFFF.toInt() })

    val dst = RectF(pad.toFloat(), pad.toFloat(), (pad + img).toFloat(), (pad + img).toFloat())
    c.save()
    val clip = GPath()
    if (d.circle) clip.addCircle(dst.centerX(), dst.centerY(), img / 2f, GPath.Direction.CW) else clip.addRoundRect(dst, 28f, 28f, GPath.Direction.CW)
    c.clipPath(clip)
    if (cover != null) {
        val s = minOf(cover.width, cover.height)
        val src = Rect((cover.width - s) / 2, (cover.height - s) / 2, (cover.width + s) / 2, (cover.height + s) / 2)
        c.drawBitmap(cover, src, dst, GPaint(flags or GPaint.FILTER_BITMAP_FLAG))
    } else c.drawRect(dst, GPaint().apply { color = 0xFFCCCCCC.toInt() })
    c.restore()

    val largura = (W - 2 * pad).toFloat()
    c.drawText(TextUtils.ellipsize(d.title, tp, largura, TextUtils.TruncateAt.END).toString(), pad.toFloat(), titleBase.toFloat(), tp)
    c.drawText(TextUtils.ellipsize(d.subtitle, ap, largura, TextUtils.TruncateAt.END).toString(), pad.toFloat(), artistBase.toFloat(), ap)
    c.drawText("beats", pad.toFloat(), beatsBase.toFloat(), bp)
    bmp
}

// Salva a foto na galeria (Pictures/beats) e devolve o endereço dela. Não precisa de FileProvider nem de nada no AndroidManifest.
private fun salvarNaGaleria(ctx: Context, bmp: Bitmap): Uri? = runCatching {
    val nome = "beats_story_${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT >= 29) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nome)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/beats")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@runCatching null
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        uri
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.insertImage(ctx.contentResolver, bmp, nome, "beats")?.let { Uri.parse(it) }
    }
}.getOrNull()

// Gera a foto do card e abre o editor de story do Instagram com ela de fundo.
// Se o Instagram não abrir, abre o menu do sistema com a foto. Nunca derruba o app.
suspend fun compartilharStory(ctx: Context, d: ShareData) {
    try {
        val bmp = montarCard(ctx, d)
        val uri = withContext(Dispatchers.IO) { salvarNaGaleria(ctx, bmp) }
        if (uri == null) { Toast.makeText(ctx, "Não consegui salvar a foto do card", Toast.LENGTH_SHORT).show(); return }
        runCatching { ctx.grantUriPermission("com.instagram.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val ig = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setPackage("com.instagram.android")
            setDataAndType(uri, "image/png")
            putExtra("source_application", ctx.packageName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startActivity(ig)
        } catch (e: Exception) {
            val s = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(s, null))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Toast.makeText(ctx, "Não foi possível compartilhar no story", Toast.LENGTH_SHORT).show()
    }
}

// ---------- menu de compartilhar ----------
@Composable private fun OpcaoShare(i: ImageVector, t: String, busy: Boolean = false, f: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .08f)).clickable(enabled = !busy) { f() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(i, null, Modifier.size(24.dp), Color.White)
        T(t, 16, 600, Color.White, Modifier.padding(start = 14.dp))
    }
}

@Composable fun ShareSheet(d: ShareData?, close: () -> Unit) {
    if (d == null) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF1C1C1E), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Img(d.image, Modifier.size(56.dp), if (d.circle) CircleShape else RoundedCornerShape(8.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    T(d.title, 17, 700, Color.White)
                    T(d.subtitle, 13, 400, Color.White.copy(alpha = .6f))
                }
            }
            OpcaoShare(Icons.Filled.CameraAlt, if (busy) "Montando o card…" else "Story do Instagram", busy) {
                busy = true
                scope.launch { compartilharStory(ctx, d); busy = false; close() }
            }
            OpcaoShare(Icons.Filled.Link, "Copiar link") { copiarLink(ctx, d); close() }
            OpcaoShare(Icons.Filled.Share, "Mais opções…") {
                // manda SÓ o link: assim o WhatsApp, Telegram, Direct etc. mostram como link com a prévia (título + capa)
                val s = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, d.link)
                    putExtra(Intent.EXTRA_TITLE, d.title)
                }
                runCatching { ctx.startActivity(Intent.createChooser(s, null)) }
                close()
            }
        }
    }
}
