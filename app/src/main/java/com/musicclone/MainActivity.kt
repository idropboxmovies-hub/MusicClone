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
import org.json.JSONObject
import java.net.URL
import java.util.Calendar
import java.util.Locale

val Red = Color(0xFFFA243C)
val Sub = Color(0xFF8A8A8E)
fun F(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Sf = FontFamily(F(400), F(500), F(600), F(700), F(800))
fun bg(a: Long, b: Long) = Brush.linearGradient(listOf(Color(a), Color(b)))
fun grad(h: Float, k: Float = 45f) = Brush.linearGradient(listOf(Color.hsv(h % 360, .55f, .95f), Color.hsv((h + k) % 360, .75f, .5f)))
fun fmt(s: Int) = "${s / 60}:${"%02d".format(s % 60)}"

val mesesCurtos = listOf("Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")
val mesesLongos = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
val mesesAbrev = listOf("jan.", "fev.", "mar.", "abr.", "mai.", "jun.", "jul.", "ago.", "set.", "out.", "nov.", "dez.")
val ptBR: Locale = Locale.forLanguageTag("pt-BR")

fun dataBr(iso: String): String = runCatching {
    val p = iso.take(10).split("-"); "${p[2].toInt()} de ${mesesAbrev[p[1].toInt() - 1]} de ${p[0]}"
}.getOrDefault("")

fun tempo(s: Float): String {
    val t = s.toInt()
    return if (t < 60) "$t ${if (t == 1) "segundo" else "segundos"}"
    else { val m = t / 60; "${String.format(ptBR, "%,d", m)} ${if (m == 1) "minuto" else "minutos"}" }
}

data class Album(val id: Long, val name: String, val artist: String, val art: String, val genre: String, val year: String, val artistId: Long = 0)
data class Track(val name: String, val artist: String, val art: String, val url: String, val album: String, val artistId: Long = 0, val year: String = "", val explicit: Boolean = false)
data class ArtistData(val photo: String?, val latest: Album?, val latestCount: Int, val latestDate: String, val top: List<Track>)
data class Latest(val album: Album, val count: Int, val date: String)

sealed class Page {
    data class Alb(val a: Album) : Page()
    data class Art(val name: String, val id: Long, val art: String?) : Page()
    object Rep : Page()
}
data class Nav(val tab: Int, val page: Page?, val depth: Int)

suspend fun get(u: String) = withContext(Dispatchers.IO) { runCatching { JSONObject(URL(u).readText()) }.getOrNull() }

suspend fun albums(term: String): List<Album> {
    val j = get("https://itunes.apple.com/search?entity=album&limit=12&country=BR&term=${Uri.encode(term)}") ?: return emptyList()
    val a = j.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.map {
        Album(it.optLong("collectionId"), it.optString("collectionName"), it.optString("artistName"), it.optString("artworkUrl100").replace("100x100", "600x600"), it.optString("primaryGenreName"), it.optString("releaseDate").take(4), it.optLong("artistId"))
    }
}

suspend fun tracks(al: Album): List<Track> {
    val j = get("https://itunes.apple.com/lookup?entity=song&country=BR&id=${al.id}") ?: return emptyList()
    val a = j.optJSONArray("results") ?: return emptyList()
    return (1 until a.length()).map { a.getJSONObject(it) }.filter { it.has("previewUrl") }
        .map { Track(it.optString("trackName"), it.optString("artistName"), al.art, it.optString("previewUrl"), al.name, it.optLong("artistId"), al.year, it.optString("trackExplicitness") == "explicit") }
}

suspend fun photoOf(name: String): String? =
    get("https://api.deezer.com/search/artist?limit=1&q=${Uri.encode(name)}")?.optJSONArray("data")?.optJSONObject(0)?.optString("picture_xl")
        ?.takeIf { it.startsWith("http") && !it.contains("/artist//") }

suspend fun topSongs(id: Long): List<Track> {
    if (id == 0L) return emptyList()
    val a = get("https://itunes.apple.com/lookup?id=$id&entity=song&limit=15&country=BR")?.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("wrapperType") == "track" && it.has("previewUrl") }
        .map { Track(it.optString("trackName"), it.optString("artistName"), it.optString("artworkUrl100").replace("100x100", "300x300"), it.optString("previewUrl"), it.optString("collectionName"), it.optLong("artistId"), it.optString("releaseDate").take(4), it.optString("trackExplicitness") == "explicit") }
}

suspend fun latest(id: Long): Latest? {
    if (id == 0L) return null
    val a = get("https://itunes.apple.com/lookup?id=$id&entity=album&limit=6&sort=recent&country=BR")?.optJSONArray("results") ?: return null
    val o = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("wrapperType") == "collection" } ?: return null
    val al = Album(o.optLong("collectionId"), o.optString("collectionName"), o.optString("artistName"), o.optString("artworkUrl100").replace("100x100", "600x600"), o.optString("primaryGenreName"), o.optString("releaseDate").take(4), id)
    return Latest(al, o.optInt("trackCount"), dataBr(o.optString("releaseDate")))
}

suspend fun artistData(name: String, id0: Long): ArtistData = coroutineScope {
    val id = if (id0 != 0L) id0 else (get("https://itunes.apple.com/search?entity=musicArtist&limit=1&country=BR&term=${Uri.encode(name)}")?.optJSONArray("results")?.optJSONObject(0)?.optLong("artistId") ?: 0L)
    val ph = async { photoOf(name) }
    val tp = async { topSongs(id) }
    val lt = async { latest(id) }
    val l = lt.await()
    ArtistData(ph.await(), l?.album, l?.count ?: 0, l?.date ?: "", tp.await())
}

// ---------- Estatísticas reais do Replay ----------
data class Item(val name: String, val sub: String, val img: String, val secs: Float)
class MonthData(val total: Float, val artists: List<Item>, val songs: List<Item>)

class Stats(ctx: Context) {
    private val sp = ctx.getSharedPreferences("replay", Context.MODE_PRIVATE)
    private val cache = HashMap<String, JSONObject>()
    var tick by mutableStateOf(0)
    private var acc = 0f
    private var sv = 0f
    private fun key(y: Int, m: Int) = String.format(Locale.US, "%04d-%02d", y, m)
    private fun load(k: String): JSONObject = cache.getOrPut(k) { runCatching { JSONObject(sp.getString(k, null) ?: "{}") }.getOrElse { JSONObject() } }
    private fun bump(o: JSONObject, grp: String, key: String, s: Float, img: String, name: String, sub: String) {
        val g = o.optJSONObject(grp) ?: JSONObject().also { o.put(grp, it) }
        val e = g.optJSONObject(key) ?: JSONObject().also { g.put(key, it) }
        e.put("s", e.optDouble("s", 0.0) + s); e.put("img", img); e.put("n", name); e.put("r", sub)
    }
    fun add(t: Track, s: Float) {
        if (s <= 0f) return
        val c = Calendar.getInstance(); val k = key(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1); val o = load(k)
        o.put("t", o.optDouble("t", 0.0) + s)
        bump(o, "a", t.artist, s, t.art, t.artist, "")
        bump(o, "s", t.name + "|" + t.artist, s, t.art, t.name, t.artist)
        acc += s; sv += s
        if (acc >= 1f) { acc = 0f; tick++ }
        if (sv >= 5f) { sv = 0f; save() }
    }
    fun save() { val e = sp.edit(); cache.forEach { (k, v) -> e.putString(k, v.toString()) }; e.apply() }
    fun month(y: Int, m: Int): MonthData {
        @Suppress("UNUSED_VARIABLE") val tk = tick
        val o = load(key(y, m))
        fun items(g: String): List<Item> {
            val j = o.optJSONObject(g) ?: return emptyList()
            return j.keys().asSequence().map { j.getJSONObject(it) }.map { Item(it.optString("n"), it.optString("r"), it.optString("img"), it.optDouble("s", 0.0).toFloat()) }.sortedByDescending { it.secs }.toList()
        }
        return MonthData(o.optDouble("t", 0.0).toFloat(), items("a"), items("s"))
    }
}

// ---------- Player ----------
class Pl(private val ctx: Context, private val stats: Stats) {
    var q by mutableStateOf(listOf<Track>()); var i by mutableStateOf(0); var playing by mutableStateOf(false)
    var pos by mutableStateOf(0f); var dur by mutableStateOf(30f)
    val cur get() = q.getOrNull(i)
    private var mp: MediaPlayer? = null
    private var ready = false
    private var fails = 0
    private var last = System.nanoTime()
    private val h = Handler(Looper.getMainLooper())
    private val loop = object : Runnable { override fun run() { tick(); h.postDelayed(this, 250) } }
    init { h.postDelayed(loop, 250) }

    fun play(l: List<Track>, k: Int) { q = l; i = k; fails = 0; load() }
    private fun fail() { fails++; if (fails < q.size) next() else playing = false }
    private fun load() {
        mp?.release(); mp = null
        val t = cur ?: return
        pos = 0f; ready = false
        val m = MediaPlayer(); mp = m
        try { m.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK) } catch (e: Exception) { }
        m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        m.setOnPreparedListener { x -> if (mp === x) { dur = x.duration / 1000f; ready = true; fails = 0; x.start(); playing = true; last = System.nanoTime() } }
        m.setOnCompletionListener { x -> if (mp === x) next() }          // terminou -> próxima música
        m.setOnErrorListener { x, _, _ -> if (mp === x) fail(); true }   // erro -> pula
        try { m.setDataSource(t.url); m.prepareAsync() } catch (e: Exception) { fail() }
    }
    fun toggle() {
        val m = mp ?: return
        if (!ready) return
        if (m.isPlaying) { m.pause(); playing = false; stats.save() } else { m.start(); playing = true; last = System.nanoTime() }
    }
    fun next() { if (q.isNotEmpty()) { i = (i + 1) % q.size; load() } }
    fun prev() { if (q.isNotEmpty()) { i = (i - 1 + q.size) % q.size; load() } }
    fun seek(f: Float) { if (ready) mp?.seekTo((f.coerceIn(0f, 1f) * dur * 1000).toInt()) }
    private fun tick() {
        val now = System.nanoTime(); val dt = ((now - last) / 1e9f).coerceIn(0f, 1f); last = now
        val m = mp ?: return
        if (!ready) return
        val on = try { m.isPlaying } catch (e: Exception) { false }
        if (on) { pos = m.currentPosition / 1000f; cur?.let { stats.add(it, dt) } }
    }
    fun release() { h.removeCallbacks(loop); mp?.release(); mp = null; stats.save() }
}

class MainActivity : ComponentActivity() {
    private lateinit var stats: Stats
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); enableEdgeToEdge()
        stats = Stats(applicationContext)
        setContent { App(stats) }
    }
    override fun onStop() { super.onStop(); stats.save() }
}

// ---------- Utilitários de UI ----------
val LocalArtist = staticCompositionLocalOf<(String, Long, String?) -> Unit> { { _, _, _ -> } }

@Composable fun T(s: String, sz: Int = 16, w: Int = 400, c: Color = Color.Unspecified, m: Modifier = Modifier, ml: Int = 1, a: TextAlign? = null) =
    Text(s, m, c, sz.sp, fontWeight = FontWeight(w), fontFamily = Sf, textAlign = a, maxLines = ml, overflow = TextOverflow.Ellipsis,
        lineHeight = (sz * 1.12f).sp, letterSpacing = if (sz >= 24) (-.02f * sz).sp else 0.sp)

// Nome de artista: tocar abre o perfil do artista, em qualquer tela
@Composable fun AT(n: String, id: Long = 0, art: String? = null, sz: Int = 16, w: Int = 400, c: Color = Color.Unspecified, m: Modifier = Modifier, ml: Int = 1, a: TextAlign? = null) {
    val go = LocalArtist.current
    T(n, sz, w, c, m.pointerInput(n) { detectTapGestures { go(n, id, art) } }, ml, a)
}

@Composable fun Modifier.enter(i: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(i * 70L); a.animateTo(1f, spring(.8f, 180f)) }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1 - a.value) * 70f; scaleX = .92f + .08f * a.value; scaleY = scaleX }
}
@Composable fun Modifier.tap(f: () -> Unit): Modifier {
    var d by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (d) .95f else 1f, spring(.5f, 500f), label = "tap")
    return graphicsLayer { scaleX = s; scaleY = s }.pointerInput(Unit) { detectTapGestures(onPress = { d = true; tryAwaitRelease(); d = false }, onTap = { f() }) }
}

@Composable fun Img(u: String?, m: Modifier, s: Shape = RoundedCornerShape(10.dp)) =
    Box(m.clip(s).background(Color.Gray.copy(alpha = .3f))) { AsyncImage(model = u, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }

@Composable fun rememberPal(url: String?): Pair<Color, Color> {
    val ctx = LocalContext.current
    var p by remember { mutableStateOf(Color(0xFF8A1020) to Color(0xFF2A0509)) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(200).build())
        val bmp = (r as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
        val pa = withContext(Dispatchers.Default) { Palette.from(bmp).generate() }
        p = Color(pa.getVibrantColor(pa.getDominantColor(0xFF8A1020.toInt()))) to Color(pa.getDarkMutedColor(pa.getDarkVibrantColor(0xFF200509.toInt())))
    }
    val c1 by animateColorAsState(p.first, tween(900), label = "c1")
    val c2 by animateColorAsState(p.second, tween(900), label = "c2")
    return c1 to c2
}

// cor suave (mutada) da foto, usada no fundo do perfil do artista
@Composable fun rememberMuted(url: String?): Color {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(Color(0xFF3A3A3C)) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(200).build())
        val bmp = (r as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
        val pa = withContext(Dispatchers.Default) { Palette.from(bmp).generate() }
        c = Color(pa.getMutedColor(pa.getDominantColor(0xFF3A3A3C.toInt())))
    }
    val a by animateColorAsState(c, tween(700), label = "mc")
    return a
}

@Composable fun Av() = Box(Modifier.size(34.dp).clip(CircleShape).background(bg(0xFFC9A7FF, 0xFFFF9EC4)))
@Composable fun Rw(c: LazyListScope.() -> Unit) = LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), content = c)
@Composable fun H(t: String) = Row(Modifier.padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { T(t, 20, 700); Icon(Icons.Filled.ChevronRight, null, tint = Sub) }

@Composable fun Scr(title: String, right: @Composable () -> Unit = {}, c: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 200.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { T(title, 32, 800); right() }
        c()
    }

@Composable fun Pick(t: String, s: String, b: Brush, w: Int, h: Int, sz: Int, m: Modifier = Modifier, onClick: () -> Unit = {}) =
    Column(m.size(w.dp, h.dp).tap(onClick).clip(RoundedCornerShape(14.dp)).background(b).padding(14.dp), Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(14.dp), Color.White); T(" Music", 12, 600, Color.White) }
        Column { T(t, sz, 800, Color.White, ml = 2); T(s, 11, 400, Color.White.copy(alpha = .85f), Modifier.padding(top = 4.dp), 2) }
    }

@Composable fun Cv(a: Album, open: (Album) -> Unit, m: Modifier = Modifier.width(118.dp)) = Column(m.tap { open(a) }) {
    Img(a.art, Modifier.fillMaxWidth().aspectRatio(1f)); T(a.name, 12, 600, m = Modifier.padding(top = 6.dp)); AT(a.artist, a.artistId, a.art, 11, 400, Sub)
}
@Composable fun Grid(l: List<Album>, open: (Album) -> Unit, n: Int = 3) = l.chunked(n).forEachIndexed { r, row ->
    Row(Modifier.padding(bottom = 14.dp), Arrangement.spacedBy(12.dp)) {
        row.forEachIndexed { k, a -> Cv(a, open, Modifier.weight(1f).enter(r * n + k)) }; repeat(n - row.size) { Spacer(Modifier.weight(1f)) }
    }
}
@Composable fun Empty(l: List<Album>) { if (l.isEmpty()) T("Carregando… (precisa de internet)", 13, 400, Sub, Modifier.padding(vertical = 20.dp)) }
@Composable fun Li(i: ImageVector, t: String, k: Int) {
    Row(Modifier.fillMaxWidth().enter(k).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(i, null, Modifier.size(22.dp), Red); T(t, 17, 400, m = Modifier.padding(start = 14.dp).weight(1f)); Icon(Icons.Filled.ChevronRight, null, tint = Sub)
    }
    HorizontalDivider(color = Color.Gray.copy(alpha = .25f))
}

typealias Sec = Map<String, List<Album>>

// ---------- Telas ----------
@Composable fun Home(sec: Sec, open: (Album) -> Unit, rp: () -> Unit, mix: (List<Album>) -> Unit) = Scr("Início", { Av() }) {
    T("Feito para Você", 12, 600, Sub); H("Seleção para Você")
    Rw {
        item { Pick("Mix de\nAmigos", "Card B, Offset, Kendrick Lamar, JID e mais", bg(0xFF2A7BFF, 0xFF0B2C8C), 200, 262, 36, Modifier.enter(0)) { mix(sec["hip hop"].orEmpty()) } }
        item { Pick("Todos os\nTempos", "Replay: suas músicas mais tocadas", bg(0xFFFF3B5C, 0xFFB8002A), 200, 262, 36, Modifier.enter(1), rp) }
        item { Pick("Mix\nRelax", "Faixas calmas para focar", bg(0xFF7AD0C6, 0xFF2F6F8F), 200, 262, 36, Modifier.enter(2)) { mix(sec["electronic"].orEmpty()) } }
    }
    H("Tocados Recentemente"); val l = sec["pop"].orEmpty(); Empty(l)
    Rw { itemsIndexed(l) { k, a -> Cv(a, open, Modifier.width(118.dp).enter(k)) } }
    H("Feito para Você")
    Rw {
        item { Pick("Mix de\nFavoritas", "Atualizado diariamente", bg(0xFF7A3DFF, 0xFFE04CFF), 165, 215, 22, Modifier.enter(0)) { mix(sec["pop"].orEmpty()) } }
        item { Pick("Mix\nAcorda!", "Comece o seu dia", bg(0xFFFF8A1A, 0xFFE8380A), 165, 215, 22, Modifier.enter(1)) { mix(sec["rock"].orEmpty()) } }
        item { Pick("Mix de\nNovidades", "Descobertas recentes", bg(0xFF19C7A5, 0xFF0A7A9A), 165, 215, 22, Modifier.enter(2)) { mix(sec["r&b"].orEmpty()) } }
    }
}

@Composable fun NewS(sec: Sec, open: (Album) -> Unit) = Scr("Novidades", { Av() }) {
    val f = sec["electronic"].orEmpty(); Empty(f)
    f.firstOrNull()?.let { a -> Column(Modifier.fillMaxWidth().enter(0).tap { open(a) }) {
        Img(a.art, Modifier.fillMaxWidth().aspectRatio(1.3f), RoundedCornerShape(14.dp)); T(a.name, 17, 700, m = Modifier.padding(top = 8.dp)); AT(a.artist, a.artistId, a.art, 14, 400, Sub) } }
    H("Últimos Lançamentos"); Grid(sec["hip hop"].orEmpty(), open)
}

@Composable fun Radio(sec: Sec, open: (Album) -> Unit, mix: (List<Album>) -> Unit) = Scr("Rádio", { Av() }) {
    Pick("Music 1", "Ao vivo, 24 horas por dia", bg(0xFFFF3B5C, 0xFF7A0618), 340, 200, 38, Modifier.enter(0)) { mix(sec["pop"].orEmpty() + sec["hip hop"].orEmpty()) }
    H("Estações")
    sec["r&b"].orEmpty().take(6).forEachIndexed { k, a ->
        Row(Modifier.fillMaxWidth().enter(k).tap { open(a) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Img(a.art, Modifier.size(54.dp)); Column(Modifier.padding(start = 14.dp).weight(1f)) { AT(a.artist, a.artistId, a.art, 17); T("Ao vivo agora", 12, 400, Sub) }
            Icon(Icons.Filled.ChevronRight, null, tint = Sub)
        }
    }
}

@Composable fun Lib(sec: Sec, open: (Album) -> Unit) = Scr("Biblioteca", { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MoreHoriz, null, tint = Red); Spacer(Modifier.width(12.dp)); Av() } }) {
    val l = sec["r&b"].orEmpty(); Empty(l); Grid(l.take(6), open); Spacer(Modifier.height(6.dp))
    Li(Icons.Filled.QueueMusic, "Playlists", 0); Li(Icons.Filled.Mic, "Artistas", 1); Li(Icons.Filled.Album, "Álbuns", 2); Li(Icons.Filled.MusicNote, "Músicas", 3)
    H("Adicionados Recentemente"); Rw { itemsIndexed(sec["rock"].orEmpty()) { k, a -> Cv(a, open, Modifier.width(150.dp).enter(k)) } }
}

@Composable fun SearchS(open: (Album) -> Unit) = Scr("Buscar") {
    var q by remember { mutableStateOf("") }; var res by remember { mutableStateOf(listOf<Album>()) }
    LaunchedEffect(q) { if (q.isBlank()) res = emptyList() else { delay(400); res = albums(q) } }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = .16f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), Sub); Spacer(Modifier.width(8.dp))
        BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = LocalContentColor.current, fontSize = 16.sp, fontFamily = Sf), cursorBrush = SolidColor(Red),
            decorationBox = { inner -> if (q.isEmpty()) T("Artistas, Músicas, Letras e mais", 16, 400, Sub); inner() })
    }
    if (q.isBlank()) {
        H("Explorar Categorias")
        listOf(Triple("Áudio Espacial", 280f, "dolby atmos"), Triple("Sucessos", 350f, "hits"), Triple("Hip-Hop", 30f, "hip hop"), Triple("Relax", 170f, "chill"),
            Triple("Dance", 300f, "dance"), Triple("Pop", 330f, "pop"), Triple("Rock", 10f, "rock"), Triple("Treino", 90f, "workout")).chunked(2).forEachIndexed { r, row ->
            Row(Modifier.padding(bottom = 12.dp), Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { k, c -> Box(Modifier.weight(1f).height(88.dp).enter(r * 2 + k).tap { q = c.third }.clip(RoundedCornerShape(12.dp)).background(grad(c.second, 30f)).padding(10.dp)) { T(c.first, 16, 700, Color.White) } }
            }
        }
    } else res.forEachIndexed { k, a ->
        Row(Modifier.fillMaxWidth().enter(k).tap { open(a) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Img(a.art, Modifier.size(54.dp)); Column(Modifier.padding(start = 14.dp)) { T(a.name, 16, 600); AT(a.artist, a.artistId, a.art, 13, 400, Sub) }
        }
    }
}

// Replay com contagem real de tempo ouvido
@Composable fun Replay(st: Stats, back: () -> Unit) = Box(Modifier.fillMaxSize()) {
    val cal = remember { Calendar.getInstance() }
    val y = cal.get(Calendar.YEAR); val cur = cal.get(Calendar.MONTH) + 1
    var sel by remember { mutableStateOf(cur) }
    val md = st.month(y, sel)
    val secs = md.total.toInt()
    val ts by animateIntAsState(secs, tween(1200, easing = FastOutSlowInEasing), label = "m")
    Box(Modifier.fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(Color(0x9934C759), Color.Transparent))))
    Scr("Replay", { T("$y", 16, 600) }) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp), Arrangement.spacedBy(16.dp)) {
            (1..cur).forEach { k -> T(mesesCurtos[k - 1], 14, 600, if (k == sel) LocalContentColor.current else Sub, Modifier.tap { sel = k }) }
        }
        val mes = mesesLongos[sel - 1]
        Text(buildAnnotatedString {
            if (secs == 0) append("Você ainda não ouviu nada em $mes.")
            else { append("Você ouviu por "); withStyle(SpanStyle(fontWeight = FontWeight(800))) { append(tempo(ts.toFloat())) }; append(" em $mes.") }
        }, fontSize = 27.sp, fontWeight = FontWeight(700), fontFamily = Sf, lineHeight = 31.sp)
        if (md.artists.isEmpty()) T("Toque algumas músicas e seus artistas e músicas mais ouvidos aparecem aqui.", 14, 400, Sub, Modifier.padding(top = 18.dp), 3)
        else {
            H("Seus Artistas Mais Ouvidos")
            Rw { md.artists.take(5).forEachIndexed { k, a -> item {
                Box(Modifier.size(200.dp, 250.dp).enter(k).clip(RoundedCornerShape(12.dp))) {
                    Img(a.img, Modifier.fillMaxSize(), RoundedCornerShape(0.dp)); Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
                    T("${k + 1}", 30, 800, Color.White, Modifier.padding(12.dp))
                    Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { AT(a.name, 0, a.img, 15, 700, Color.White, ml = 2, a = TextAlign.Center); T(tempo(a.secs), 13, 400, Color.White.copy(alpha = .8f)) }
                }
            } } }
            H("Suas Músicas Mais Ouvidas")
            md.songs.take(5).forEachIndexed { k, s ->
                Row(Modifier.fillMaxWidth().enter(k).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${k + 1}", 15, 600, Sub, Modifier.width(24.dp)); Img(s.img, Modifier.size(50.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) { T(s.name, 16, 600); AT(s.sub, 0, s.img, 13, 400, Sub) }
                    T(tempo(s.secs), 12, 500, Sub)
                }
            }
        }
    }
    IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Red) }
}

@Composable fun AlbumS(al: Album, pl: Pl, back: () -> Unit) {
    val (c1, _) = rememberPal(al.art); var tr by remember { mutableStateOf(listOf<Track>()) }
    LaunchedEffect(al.id) { tr = tracks(al) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, lerp(c1, Color.Black, .55f), Color.Black)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Img(al.art, Modifier.padding(top = 56.dp).enter(0).size(250.dp).shadow(28.dp, RoundedCornerShape(10.dp)))
            T(al.name, 22, 700, Color.White, Modifier.padding(top = 18.dp), 2, TextAlign.Center)
            AT(al.artist, al.artistId, al.art, 19, 600, lerp(c1, Color.White, .55f)); T("${al.genre} · ${al.year}", 12, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
            Row(Modifier.padding(vertical = 18.dp).tap { if (tr.isNotEmpty()) pl.play(tr, 0) }.clip(CircleShape).background(Color.White).padding(horizontal = 40.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp), Color.Black); T(" Reproduzir", 17, 600, Color.Black)
            }
            tr.forEachIndexed { k, t ->
                Row(Modifier.fillMaxWidth().enter(k + 1).tap { pl.play(tr, k) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${k + 1}", 15, 400, Color.White.copy(alpha = .6f), Modifier.width(30.dp)); T(t.name, 16, 500, Color.White, Modifier.weight(1f)); Icon(Icons.Filled.MoreHoriz, null, tint = Color.White.copy(alpha = .7f))
                }
                HorizontalDivider(color = Color.White.copy(alpha = .15f))
            }
        }
        IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Color.White) }
    }
}

@Composable fun Glass(i: ImageVector, f: () -> Unit) =
    Box(Modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = .3f)).pointerInput(Unit) { detectTapGestures { f() } }, Alignment.Center) { Icon(i, null, Modifier.size(18.dp), Color.White) }

// Perfil do artista (estilo Apple Music)
@Composable fun ArtistS(p: Page.Art, pl: Pl, open: (Album) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var d by remember(p) { mutableStateOf<ArtistData?>(null) }
    LaunchedEffect(p) { d = artistData(p.name, p.id) }
    val photo = d?.let { it.photo ?: p.art }
    val base = rememberMuted(photo)
    val top = lerp(base, Color.Black, .3f); val bot = lerp(base, Color.Black, .8f)
    val songs = d?.top.orEmpty()
    val lt = d?.latest
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bot)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 200.dp)) {
            Box(Modifier.fillMaxWidth().height(440.dp)) {
                if (photo != null) AsyncImage(model = photo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .5f to Color.Transparent, 1f to top)))
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .28f)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarToday, null, Modifier.size(12.dp), Color.White); T("  Próximos Shows", 11, 600, Color.White)
                    }
                    T(p.name, 36, 700, Color.White, Modifier.padding(top = 8.dp), 2, TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), Arrangement.Center, Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, Modifier.size(24.dp), Color.White.copy(alpha = .85f)); Spacer(Modifier.width(30.dp))
                Box(Modifier.size(66.dp).tap { if (songs.isNotEmpty()) pl.play(songs, 0) }.clip(CircleShape).background(Color.White), Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(40.dp), Color.Black) }
                Spacer(Modifier.width(30.dp)); Icon(Icons.Filled.StarBorder, null, Modifier.size(26.dp), Color.White.copy(alpha = .85f))
            }
            if (lt != null) Row(Modifier.padding(horizontal = 18.dp).padding(top = 20.dp).fillMaxWidth().enter(0).tap { open(lt.album) }.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .14f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Img(lt.album.art, Modifier.size(54.dp), RoundedCornerShape(6.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    T(lt.date, 11, 500, Color.White.copy(alpha = .6f)); T(lt.album.name, 16, 700, Color.White)
                    T("${lt.count} ${if (lt.count == 1) "música" else "músicas"}", 12, 400, Color.White.copy(alpha = .6f))
                }
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)), Alignment.Center) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp), Color.White) }
            }
            Row(Modifier.padding(horizontal = 18.dp).padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { T("Músicas Mais Tocadas", 19, 700, Color.White); Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(alpha = .6f)) }
            songs.forEachIndexed { k, t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).enter(k).tap { pl.play(songs, k) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Img(t.art, Modifier.size(46.dp), RoundedCornerShape(6.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(t.name, 16, 600, Color.White, Modifier.weight(1f, false))
                            if (t.explicit) Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .3f)).padding(horizontal = 4.dp)) { T("E", 10, 700, Color.White) }
                        }
                        T(if (t.year.isNotEmpty()) "${t.album} · ${t.year}" else t.album, 13, 400, Color.White.copy(alpha = .6f))
                    }
                    Icon(Icons.Filled.MoreHoriz, null, tint = Color.White.copy(alpha = .7f))
                }
            }
            if (d == null) T("Carregando…", 13, 400, Color.White.copy(alpha = .6f), Modifier.padding(18.dp))
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Glass(Icons.Filled.ArrowBackIosNew) { back() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Glass(Icons.Filled.Share) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Ouça ${p.name} no Music") }, null)) }
                Glass(Icons.Filled.MoreHoriz) { }
            }
        }
    }
}

@Composable fun Now(pl: Pl, close: () -> Unit) {
    val t = pl.cur ?: return
    val (c1, c2) = rememberPal(t.art)
    val ph by rememberInfiniteTransition(label = "i").animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    val sc by animateFloatAsState(if (pl.playing) 1f else .86f, spring(.5f, 260f), label = "s")
    val fr by animateFloatAsState(if (pl.dur > 0) pl.pos / pl.dur else 0f, tween(220, easing = LinearEasing), label = "f")
    var drag by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().background(Color.Black).drawBehind {
        drawRect(Brush.verticalGradient(listOf(c1, c2, Color.Black)))
        val r = size.width * .9f; val o1 = Offset(size.width * (.15f + .7f * ph), size.height * (.2f + .15f * ph)); val o2 = Offset(size.width * (.9f - .6f * ph), size.height * .65f)
        drawCircle(Brush.radialGradient(listOf(lerp(c1, Color.White, .3f).copy(alpha = .45f), Color.Transparent), o1, r), r, o1)
        drawCircle(Brush.radialGradient(listOf(c2.copy(alpha = .9f), Color.Transparent), o2, r), r, o2)
    }.pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (drag > 160) close(); drag = 0f }) { _, dy -> drag += dy } }
        .statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = .4f)).clickable(onClick = close))
        T("AutoMix", 12, 700, Color.White, Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .2f)).padding(horizontal = 12.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Crossfade(t.art, label = "cv") { u -> Img(u, Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = sc; scaleY = sc }.shadow(30.dp, RoundedCornerShape(16.dp)), RoundedCornerShape(16.dp)) }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            AnimatedContent(t, Modifier.weight(1f), transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) }, label = "tt") { x ->
                Column { T(x.name, 22, 700, Color.White); AT(x.artist, x.artistId, x.art, 18, 400, Color.White.copy(alpha = .7f)) }
            }
            Icon(Icons.Filled.StarBorder, null, tint = Color.White); Spacer(Modifier.width(14.dp)); Icon(Icons.Filled.MoreHoriz, null, tint = Color.White)
        }
        Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(18.dp).pointerInput(Unit) { detectTapGestures { pl.seek(it.x / size.width) } }, Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f)))
            Box(Modifier.fillMaxWidth(fr.coerceIn(0f, 1f)).height(5.dp).clip(CircleShape).background(Color.White))
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { T(fmt(pl.pos.toInt()), 11, 500, Color.White.copy(alpha = .7f)); T("-" + fmt((pl.dur - pl.pos).toInt().coerceAtLeast(0)), 11, 500, Color.White.copy(alpha = .7f)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            IconButton({ pl.prev() }) { Icon(Icons.Filled.SkipPrevious, null, Modifier.size(42.dp), Color.White) }
            IconButton({ pl.toggle() }, Modifier.size(84.dp)) { Crossfade(pl.playing, label = "pp") { p -> Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(64.dp), Color.White) } }
            IconButton({ pl.next() }) { Icon(Icons.Filled.SkipNext, null, Modifier.size(42.dp), Color.White) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceEvenly) { listOf(Icons.Filled.ChatBubbleOutline, Icons.Filled.Cast, Icons.Filled.QueueMusic).forEach { Icon(it, null, tint = Color.White.copy(alpha = .85f)) } }
    }
}

@Composable fun App(stats: Stats) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val bgc = if (dark) Color.Black else Color.White; val tx = if (dark) Color.White else Color.Black
    val sf = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val pl = remember { Pl(ctx.applicationContext, stats) }
    DisposableEffect(Unit) { onDispose { pl.release() } }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }; var np by remember { mutableStateOf(false) }
    val stack = remember { mutableStateListOf<Page>() }
    val sec = remember { mutableStateMapOf<String, List<Album>>() }
    LaunchedEffect(Unit) { listOf("pop", "hip hop", "r&b", "rock", "electronic").forEach { k -> launch { sec[k] = albums(k) } } }
    BackHandler(np || stack.isNotEmpty()) { if (np) np = false else stack.removeAt(stack.lastIndex) }
    val open: (Album) -> Unit = { stack.add(Page.Alb(it)) }
    val mix: (List<Album>) -> Unit = { l -> scope.launch { val t = l.shuffled().take(3).flatMap { tracks(it) }.shuffled(); if (t.isNotEmpty()) pl.play(t, 0) } }
    val openArtist: (String, Long, String?) -> Unit = { n, id, art ->
        np = false
        val t = stack.lastOrNull()
        if (!(t is Page.Art && t.name == n)) stack.add(Page.Art(n, id, art))
    }
    val sub = { stack.isNotEmpty() }
    CompositionLocalProvider(LocalContentColor provides tx, LocalArtist provides openArtist) {
        Box(Modifier.fillMaxSize().background(bgc)) {
            AnimatedContent(Nav(tab, stack.lastOrNull(), stack.size), transitionSpec = {
                val push = targetState.depth > initialState.depth; val pop = targetState.depth < initialState.depth
                if (push) (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                else if (pop) (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                else fadeIn(tween(300)) togetherWith fadeOut(tween(150))
            }, label = "nav") { d ->
                Box(Modifier.fillMaxSize().background(bgc)) {
                    val back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                    when (val pg = d.page) {
                        is Page.Alb -> AlbumS(pg.a, pl) { back() }
                        is Page.Art -> ArtistS(pg, pl, open) { back() }
                        Page.Rep -> Replay(stats) { back() }
                        null -> when (d.tab) { 0 -> Home(sec, open, { stack.add(Page.Rep) }, mix); 1 -> NewS(sec, open); 2 -> Radio(sec, open, mix); 3 -> Lib(sec, open); else -> SearchS(open) }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedVisibility(pl.cur != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    pl.cur?.let { t ->
                        Row(Modifier.fillMaxWidth().height(56.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(sf).clickable { np = true }.padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Img(t.art, Modifier.size(40.dp), RoundedCornerShape(8.dp)); T(t.name, 15, 600, m = Modifier.padding(start = 12.dp).weight(1f))
                            IconButton({ pl.toggle() }) { Crossfade(pl.playing, label = "m") { p -> Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) } }
                            IconButton({ pl.next() }) { Icon(Icons.Filled.SkipNext, null) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f).height(62.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(sf).padding(5.dp)) {
                        listOf(Icons.Filled.Home to "Início", Icons.Filled.GridView to "Novidades", Icons.Filled.Sensors to "Rádio", Icons.Filled.LibraryMusic to "Biblioteca").forEachIndexed { k, (i, n) ->
                            val on = tab == k && !sub()
                            val col by animateColorAsState(if (on) Red else Sub, label = "ic"); val bk by animateColorAsState(if (on) Color.Gray.copy(alpha = .2f) else Color.Transparent, label = "bk")
                            Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bk).clickable { stack.clear(); tab = k }, Arrangement.Center, Alignment.CenterHorizontally) {
                                Icon(i, null, Modifier.size(22.dp), col); T(n, 10, 600, col)
                            }
                        }
                    }
                    Box(Modifier.size(62.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(sf).clickable { stack.clear(); tab = 4 }, Alignment.Center) { Icon(Icons.Filled.Search, null, tint = if (tab == 4 && !sub()) Red else Sub) }
                }
            }
            AnimatedVisibility(np && pl.cur != null, enter = slideInVertically(spring(.85f, 300f)) { it } + fadeIn(), exit = slideOutVertically(spring(1f, 400f)) { it } + fadeOut()) { Now(pl) { np = false } }
        }
    }
}
