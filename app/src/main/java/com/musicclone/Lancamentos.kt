@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, androidx.compose.animation.ExperimentalAnimationApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicclone

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Calendar
import java.util.Locale

// =====================================================================
// Lançamentos feitos no iStudio (tabelas releases + release_tracks)
// =====================================================================
class Rel(
    val id: String, val artistUid: String, val artistName: String, val type: String,
    val title: String, val cover: String, val date: String, val explicit: Boolean, val tracks: List<Track>
) {
    val label get() = if (type == "album") "Álbum" else "Single"
}

val LocalRel = staticCompositionLocalOf<(Rel) -> Unit> { { } }

// null = falhou a conexão (a tela mantém o que já tinha); lista vazia = não há lançamentos
suspend fun releasesLoad(artistUid: String? = null, limit: Int = 30): List<Rel>? {
    val f = if (artistUid != null) "&artist_id=eq.$artistUid" else ""
    val r = sb("GET", "/rest/v1/releases?select=*,release_tracks(*)&order=created_at.desc&limit=$limit$f")
    if (!r.ok) return null
    val a = r.arr() ?: return null
    val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    val ids = rows.map { it.optString("artist_id") }.filter { it.isNotEmpty() }.distinct()
    val profs = HashMap<String, Prof>()
    if (ids.isNotEmpty()) {
        val pr = sb("GET", "/rest/v1/profiles?id=in.(${ids.joinToString(",")})&select=*")
        pr.arr()?.let { pa -> for (i in 0 until pa.length()) pa.optJSONObject(i)?.toProf()?.let { profs[it.id] = it } }
    }
    return rows.mapNotNull { o ->
        val uid = o.optString("artist_id")
        val p = profs[uid]
        val nome = p?.name ?: "Artista"
        val cover = o.optString("cover_url")
        val titulo = o.optString("title")
        val data = o.optString("release_date")
        val ta = o.optJSONArray("release_tracks") ?: JSONArray()
        val faixas = (0 until ta.length()).mapNotNull { ta.optJSONObject(it) }
            .sortedBy { it.optInt("track_number") }
            .map { x ->
                Track(x.optString("title"), nome, cover, x.optString("audio_url"), titulo, p?.itunesId ?: 0L,
                    data.take(4), x.optBoolean("explicit", false), x.optInt("duration_sec", 0))
            }
        if (faixas.isEmpty()) null   // lançamento ainda sem faixas (acabou de ser criado)
        else Rel(o.optString("id"), uid, nome, o.optString("type"), titulo, cover, data, o.optBoolean("explicit"), faixas)
    }
}

// perfis reivindicados dos artistas do iTunes: a foto escolhida pelo dono vale mais que a do iTunes
suspend fun claimedByItunes(ids: List<Long>): Map<Long, Prof> {
    if (ids.isEmpty()) return emptyMap()
    val r = sb("GET", "/rest/v1/profiles?claimed=eq.true&itunes_artist_id=in.(${ids.joinToString(",")})&select=*")
    val a = (if (r.ok) r.arr() else null) ?: return emptyMap()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toProf() }.associateBy { it.itunesId }
}

@Composable fun RelCard(r: Rel, m: Modifier = Modifier, c: Color = Color.Unspecified, sub: Color = Sub, line2: String = r.artistName, size: Int = 140) {
    val open = LocalRel.current
    Column(m.width(size.dp).tap { open(r) }) {
        Img(r.cover, Modifier.size(size.dp), RoundedCornerShape(10.dp))
        T(r.title, 14, 600, c, Modifier.padding(top = 6.dp))
        T(line2, 12, 400, sub)
    }
}

@Composable fun RelS(r: Rel, pl: Pl, back: () -> Unit) {
    val (c1, _) = rememberPal(r.cover)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, lerp(c1, Color.Black, .55f), Color.Black)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Img(r.cover, Modifier.padding(top = 56.dp).enter(0).size(250.dp).shadow(28.dp, RoundedCornerShape(10.dp)))
            T(r.title, 22, 700, Color.White, Modifier.padding(top = 18.dp), 2, TextAlign.Center)
            T(r.artistName, 19, 600, lerp(c1, Color.White, .55f))
            T("${r.label} · ${r.date.take(4)}", 12, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
            Row(Modifier.padding(vertical = 18.dp).tap { pl.play(r.tracks, 0) }.clip(CircleShape).background(Color.White).padding(horizontal = 40.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp), Color.Black); T(" Reproduzir", 17, 600, Color.Black)
            }
            r.tracks.forEachIndexed { k, t ->
                Row(Modifier.fillMaxWidth().enter(k + 1).tap { pl.play(r.tracks, k) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${k + 1}", 15, 400, Color.White.copy(alpha = .6f), Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) { T(t.name, 16, 500, Color.White) }
                    if (t.explicit) Box(Modifier.padding(end = 10.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .3f)).padding(horizontal = 4.dp)) { T("E", 10, 700, Color.White) }
                    if (t.dur > 0) T(fmt(t.dur), 13, 400, Color.White.copy(alpha = .6f))
                }
                HorizontalDivider(color = Color.White.copy(alpha = .15f))
            }
        }
        IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Color.White) }
    }
}
