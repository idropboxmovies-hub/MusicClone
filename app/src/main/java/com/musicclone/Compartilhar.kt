@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicclone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as GCanvas
import android.graphics.Paint as GPaint
import android.graphics.Path as GPath
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
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
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
// CONFIG: troque pelos seus valores
//  BASE      = endereço do Cloudflare Worker (arquivo share-worker.js)
//  FB_APP_ID = ID de um app criado em developers.facebook.com (o Instagram exige para o story)
// =====================================================================
object ShareCfg {
    const val BASE = "https://beats-share.SEUUSUARIO.workers.dev"
    const val FB_APP_ID = "SEU_FACEBOOK_APP_ID"
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

private fun textoCompleto(d: ShareData) = "${d.title} · ${d.subtitle}\n${d.link}"

fun copiarLink(ctx: Context, d: ShareData) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("link", d.link))
    Toast.makeText(ctx, "Link copiado", Toast.LENGTH_SHORT).show()
}

// ---------- card do story (PNG com fundo transparente) ----------
private class CardOut(val file: File, val top: String, val bottom: String)

private suspend fun baixar(ctx: Context, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(hq(url)).allowHardware(false).build())
    return (r as? SuccessResult)?.drawable?.toBitmap()
}

private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

private suspend fun montarCard(ctx: Context, d: ShareData): CardOut = withContext(Dispatchers.Default) {
    val cover = baixar(ctx, d.image)
    val W = 900; val pad = 60; val img = W - 2 * pad
    val flags = GPaint.ANTI_ALIAS_FLAG
    val tp = TextPaint(flags).apply { color = 0xFF000000.toInt(); textSize = 54f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val ap = TextPaint(flags).apply { color = 0xFF6E6E73.toInt(); textSize = 44f; typeface = Typeface.SANS_SERIF }
    val bp = TextPaint(flags).apply { color = 0xFFFA243C.toInt(); textSize = 36f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val titleBase = pad + img + 36 + 54
    val artistBase = titleBase + 16 + 44
    val beatsBase = artistBase + 34 + 36
    val H = beatsBase + pad
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val c = GCanvas(bmp)
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

    val pal = cover?.let { Palette.from(it).generate() }
    val top = pal?.getVibrantColor(pal.getDominantColor(0xFF8A1020.toInt())) ?: 0xFF8A1020.toInt()
    val bottom = pal?.getDarkMutedColor(pal.getDarkVibrantColor(0xFF200509.toInt())) ?: 0xFF200509.toInt()

    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val f = File(dir, "story_card.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    CardOut(f, hex(top), hex(bottom))
}

// abre o editor de story do Instagram com o card como adesivo (se o Instagram não existir, abre o menu do sistema com a imagem)
suspend fun compartilharStory(ctx: Context, d: ShareData) {
    val card = runCatching { montarCard(ctx, d) }.getOrNull()
    if (card == null) { Toast.makeText(ctx, "Não consegui montar o card", Toast.LENGTH_SHORT).show(); return }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", card.file)
    copiarLink(ctx, d)   // o story não aceita link pelo app: o link já fica copiado para colar num adesivo "Link"

    val ig = Intent("com.instagram.share.ADD_TO_STORY").apply {
        putExtra("source_application", ShareCfg.FB_APP_ID)
        type = "image/png"
        putExtra("interactive_asset_uri", uri)
        putExtra("top_background_color", card.top)
        putExtra("bottom_background_color", card.bottom)
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    ctx.grantUriPermission("com.instagram.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (ctx.packageManager.resolveActivity(ig, 0) != null) {
        ctx.startActivity(ig)
        Toast.makeText(ctx, "Link copiado: cole num adesivo de link no story", Toast.LENGTH_LONG).show()
    } else {
        val s = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, textoCompleto(d))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(s, null))
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
                val s = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, textoCompleto(d)) }
                ctx.startActivity(Intent.createChooser(s, null)); close()
            }
        }
    }
}
