@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, androidx.compose.animation.ExperimentalAnimationApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.musicclone

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

val Red = Color(0xFFFA243C)
val Sub = Color(0xFF8A8A8E)
fun F(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Sf = FontFamily(F(400), F(500), F(600), F(700), F(800))
fun bg(a: Long, b: Long) = Brush.linearGradient(listOf(Color(a), Color(b)))
fun grad(h: Float, k: Float = 45f) = Brush.linearGradient(listOf(Color.hsv(h % 360, .55f, .95f), Color.hsv((h + k) % 360, .75f, .5f)))
fun fmt(s: Int) = "${s / 60}:${"%02d".format(s % 60)}"

data class Album(val id: Long, val name: String, val artist: String, val art: String, val genre: String, val year: String)
data class Ln(val ms: Long, val text: String)
data class Lyr(val lines: List<Ln>, val synced: Boolean)
class Info(val title: String, val mains: List<String>, val feats: List<String>)

// Nomes que contêm "&" ou "," mas são UM artista só (não podem ser separados)
val PROT = listOf(
    "Tyler, The Creator", "Earth, Wind & Fire", "Simon & Garfunkel", "Hall & Oates", "Crosby, Stills, Nash & Young", "Crosby, Stills & Nash",
    "Emerson, Lake & Palmer", "Peter, Paul & Mary", "Brooks & Dunn", "Mumford & Sons", "Florence + The Machine", "Kool & The Gang",
    "Sly & The Family Stone", "Bob Marley & The Wailers", "Tom Petty & The Heartbreakers", "Echo & The Bunnymen", "Years & Years",
    "Gladys Knight & The Pips", "Diana Ross & The Supremes", "Martha Reeves & The Vandellas",
    "Jorge & Mateus", "Henrique & Juliano", "Zé Neto & Cristiano", "Bruno & Marrone", "Simone & Simaria", "Matheus & Kauan", "Sandy & Junior",
    "Chitãozinho & Xororó", "Munhoz & Mariano", "Fernando & Sorocaba", "Maiara & Maraisa", "Israel & Rodolffo", "Diego & Victor Hugo",
    "João Neto & Frederico", "Hugo & Guilherme", "Edson & Hudson", "Leandro & Leonardo", "Milionário & José Rico"
)
val FEAT_P = Regex("""\s*[(\[]\s*(?:feat|ft|featuring|part|participação|participacao)\.?\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
val FEAT_D = Regex("""\s+[-–]\s+(?:feat|ft|featuring)\.?\s+(.+)$""", RegexOption.IGNORE_CASE)
val FEAT_A = Regex("""\s+(?:feat|ft|featuring)\.?\s+""", RegexOption.IGNORE_CASE)
val SEP_MAIN = Regex("""\s*(?:,|&|\s+x\s+|\s+×\s+)\s*""", RegexOption.IGNORE_CASE)
val SEP_FEAT = Regex("""\s*(?:,|&|\s+and\s+|\s+e\s+|\s+x\s+|\s+×\s+)\s*""", RegexOption.IGNORE_CASE)
val PH = Regex("\u0001(\\d+)\u0001")

fun nrm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

fun splitNames(s: String, loose: Boolean): List<String> {
    var x = s; val ph = mutableListOf<String>()
    PROT.forEach { p ->
        x = Regex(Regex.escape(p), RegexOption.IGNORE_CASE).replace(x) { m -> ph.add(m.value); "\u0001${ph.size - 1}\u0001" }
    }
    return x.split(if (loose) SEP_FEAT else SEP_MAIN).map { it.trim() }.filter { it.isNotEmpty() }
        .map { n -> PH.replace(n) { ph[it.groupValues[1].toInt()] } }
}

fun parseInfo(name: String, artist: String): Info {
    var title = name; val f = mutableListOf<String>()
    FEAT_P.findAll(title).toList().forEach { f.addAll(splitNames(it.groupValues[1], true)) }
    title = FEAT_P.replace(title, "")
    FEAT_D.find(title)?.let { f.addAll(splitNames(it.groupValues[1], true)); title = title.replace(FEAT_D, "") }
    val parts = artist.split(FEAT_A, 2)
    val mains = splitNames(parts[0], false).ifEmpty { listOf(artist) }.distinctBy { nrm(it) }
    if (parts.size > 1) f.addAll(splitNames(parts[1], true))
    val fe = f.distinctBy { nrm(it) }.filter { x -> mains.none { nrm(it) == nrm(x) } }
    return Info(title.trim().ifEmpty { name }, mains, fe)
}

data class Track(val name: String, val artist: String, val art: String, val url: String, val album: String, val dur: Long = 0) {
    private val info = parseInfo(name, artist)
    val title get() = info.title          // título limpo, sem "(feat. ...)"
    val mains get() = info.mains          // artistas principais
    val feats get() = info.feats          // participações
    val all get() = info.mains + info.feats
    val key get() = "$name|$artist"
}
data class Dest(val tab: Int = 0, val album: Album? = null, val artist: String? = null, val depth: Int = 0)

suspend fun http(u: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "MusicClone/2.0 (Android)")
        c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}
suspend fun get(u: String): JSONObject? = http(u)?.let { s -> runCatching { JSONObject(s) }.getOrNull() }
fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

suspend fun albums(term: String): List<Album> {
    val j = get("https://itunes.apple.com/search?entity=album&limit=12&term=${Uri.encode(term)}") ?: return emptyList()
    val a = j.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.map {
        Album(it.optLong("collectionId"), it.optString("collectionName"), it.optString("artistName"), it.optString("artworkUrl100").replace("100x100", "600x600"), it.optString("primaryGenreName"), it.optString("releaseDate").take(4))
    }
}
fun songOf(o: JSONObject, al: Album? = null): Track? {
    if (!o.has("previewUrl") || !o.has("trackName")) return null
    return Track(o.optString("trackName"), o.optString("artistName"), al?.art ?: o.optString("artworkUrl100").replace("100x100", "600x600"), o.optString("previewUrl"), al?.name ?: o.optString("collectionName"), o.optLong("trackTimeMillis"))
}
suspend fun tracks(al: Album): List<Track> {
    val j = get("https://itunes.apple.com/lookup?entity=song&id=${al.id}") ?: return emptyList()
    val a = j.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).mapNotNull { songOf(a.getJSONObject(it), al) }
}
// Perfil do artista: SÓ músicas em que ele é principal ou participação (feat.)
suspend fun artistTracks(name: String): List<Track> = coroutineScope {
    val e = Uri.encode(name); val n = nrm(name)
    val js = listOf(
        "https://itunes.apple.com/search?entity=song&attribute=artistTerm&limit=200&term=$e",
        "https://itunes.apple.com/search?entity=song&limit=200&term=$e"
    ).map { u -> async { get(u) } }.awaitAll().filterNotNull()
    js.flatMap { j ->
        val a = j.optJSONArray("results")
        if (a == null) emptyList<Track>() else (0 until a.length()).mapNotNull { songOf(a.getJSONObject(it)) }
    }
        .filter { t -> t.all.any { nrm(it) == n } }
        .distinctBy { nrm(it.title) + "|" + nrm(it.mains.firstOrNull() ?: "") }
        .sortedBy { t -> if (t.mains.any { nrm(it) == n }) 0 else 1 }
}
suspend fun artistNames(term: String): List<String> {
    val a = get("https://itunes.apple.com/search?entity=musicArtist&limit=4&term=${Uri.encode(term)}")?.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it).optString("artistName") }.filter { it.isNotBlank() }.distinct()
}

// ---------- Letras (LRCLIB) ----------
val TS = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)\]""")
fun parseLrc(s: String): List<Ln> = s.lines().flatMap { raw ->
    val ms = TS.findAll(raw).toList()
    if (ms.isEmpty()) emptyList<Ln>() else {
        val text = raw.substring(ms.last().range.last + 1).trim()
        if (text.isEmpty()) emptyList<Ln>()
        else ms.map { m -> Ln(((m.groupValues[1].toInt() * 60 + m.groupValues[2].replace(':', '.').toFloat()) * 1000).toLong(), text) }
    }
}.sortedBy { it.ms }

suspend fun lyrics(t: Track): Lyr? {
    val ar = t.mains.firstOrNull() ?: t.artist; val tn = nrm(t.title)
    val urls = listOf(
        "https://lrclib.net/api/search?track_name=${Uri.encode(t.title)}&artist_name=${Uri.encode(ar)}",
        "https://lrclib.net/api/search?q=${Uri.encode(t.title + " " + ar)}"
    )
    for (u in urls) {
        val s = http(u) ?: continue
        val a = runCatching { JSONArray(s) }.getOrNull() ?: continue
        val c = (0 until a.length()).map { a.getJSONObject(it) }.filter {
            val n = nrm(it.str("trackName"))
            (it.str("syncedLyrics").isNotBlank() || it.str("plainLyrics").isNotBlank()) && (n.contains(tn) || tn.contains(n))
        }
        val best = c.minByOrNull { o ->
            val diff = if (t.dur > 0) abs(o.optDouble("duration", 0.0) - t.dur / 1000.0).toInt().coerceAtMost(9999) else 0
            diff + (if (o.str("syncedLyrics").isNotBlank()) 0 else 15)
        } ?: continue
        val sy = best.str("syncedLyrics")
        if (sy.isNotBlank()) { val l = parseLrc(sy); if (l.isNotEmpty()) return Lyr(l, true) }
        val pl = best.str("plainLyrics").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (pl.isNotEmpty()) return Lyr(pl.map { Ln(-1, it) }, false)
    }
    return null
}

// ---------- Tradução da letra ----------
suspend fun gt(s: String, to: String): String? {
    val raw = http("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$to&dt=t&q=${Uri.encode(s)}") ?: return null
    return runCatching {
        val a = JSONArray(raw).getJSONArray(0)
        buildString { for (i in 0 until a.length()) { val p = a.getJSONArray(i); if (!p.isNull(0)) append(p.optString(0)) } }
    }.getOrNull()
}
suspend fun trChunk(ch: List<String>, to: String): List<String>? {
    val sp = gt(ch.joinToString("\n"), to)?.split("\n")
    if (sp != null && sp.size == ch.size) return sp.map { it.trim() }
    val r = ch.map { gt(it, to) }
    return if (r.all { it == null }) null else r.map { it ?: "" }
}
suspend fun translateAll(l: List<String>, to: String): List<String>? = coroutineScope {
    val parts = l.chunked(12).map { ch -> async { trChunk(ch, to) } }.awaitAll()
    if (parts.any { it == null }) null else parts.flatMap { it.orEmpty() }
}

class Pl(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lyr", Context.MODE_PRIVATE)
    var q by mutableStateOf(listOf<Track>()); var i by mutableStateOf(0); var playing by mutableStateOf(false)
    var pos by mutableStateOf(0f); var dur by mutableStateOf(30f)
    var lyOpen by mutableStateOf(false); var trOn by mutableStateOf(false)
    // onde a prévia de 30s começa dentro da música (em segundos), salvo no celular por música
    val offs = mutableStateMapOf<String, Float>().also { m -> sp.all.forEach { (k, v) -> if (v is Float) m[k] = v } }
    fun setOff(k: String, v: Float) { offs[k] = v; sp.edit().putFloat(k, v).apply() }
    fun clearOff(k: String) { offs.remove(k); sp.edit().remove(k).apply() }
    val lyCache = mutableMapOf<String, Lyr>(); val trCache = mutableMapOf<String, List<String>>()
    val cur get() = q.getOrNull(i)
    private var mp: MediaPlayer? = null
    fun play(l: List<Track>, k: Int) { q = l; i = k; load() }
    private fun load() {
        mp?.release(); val t = cur ?: return; pos = 0f
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(t.url)
            setOnPreparedListener { m -> dur = m.duration / 1000f; m.start(); playing = true }
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ -> playing = false; true }
            prepareAsync()
        }
    }
    fun toggle() { mp?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } } }
    fun next() { if (q.isNotEmpty()) { i = (i + 1) % q.size; load() } }
    fun prev() { if (q.isNotEmpty()) { i = (i - 1 + q.size) % q.size; load() } }
    fun seek(f: Float) { mp?.seekTo((f.coerceIn(0f, 1f) * dur * 1000).toInt()) }
    fun tick() { mp?.let { if (playing) pos = it.currentPosition / 1000f } }
    fun release() { mp?.release(); mp = null; playing = false }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); enableEdgeToEdge(); setContent { App() } }
}

@Composable fun T(s: String, sz: Int = 16, w: Int = 400, c: Color = Color.Unspecified, m: Modifier = Modifier, ml: Int = 1, a: TextAlign? = null) =
    Text(s, m, c, sz.sp, fontWeight = FontWeight(w), fontFamily = Sf, textAlign = a, maxLines = ml, overflow = TextOverflow.Ellipsis,
        lineHeight = (sz * 1.12f).sp, letterSpacing = if (sz >= 24) (-.02f * sz).sp else 0.sp)

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

@Composable fun Av() = Box(Modifier.size(34.dp).clip(CircleShape).background(bg(0xFFC9A7FF, 0xFFFF9EC4)))

// Cada artista é um texto clicável que abre o PRÓPRIO perfil: "A, B & C"
@Composable fun Names(n: List<String>, sz: Int, c: Color, open: (String) -> Unit, h: Arrangement.Horizontal = Arrangement.Start) =
    FlowRow(Modifier, horizontalArrangement = h) {
        n.forEachIndexed { k, x ->
            T(x, sz, 400, c, Modifier.clickable { open(x) })
            if (k < n.size - 1) T(if (k == n.size - 2) " & " else ", ", sz, 400, c)
        }
    }
@Composable fun CBtn(i: ImageVector, on: Boolean = false, f: () -> Unit = {}) =
    Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = .2f)).clickable(onClick = f), Alignment.Center) {
        Icon(i, null, Modifier.size(18.dp), if (on) Color.Black else Color.White)
    }
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
    Img(a.art, Modifier.fillMaxWidth().aspectRatio(1f)); T(a.name, 12, 600, m = Modifier.padding(top = 6.dp)); T(a.artist, 11, 400, Sub)
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

@Composable fun Home(sec: Sec, open: (Album) -> Unit, rp: () -> Unit) = Scr("Home", { Av() }) {
    T("Made for You", 12, 600, Sub); H("Top Picks for You")
    Rw {
        item { Pick("Friends\nMix", "Card B, Offset, Kendrick Lamar, JID and more", bg(0xFF2A7BFF, 0xFF0B2C8C), 200, 262, 36, Modifier.enter(0)) }
        item { Pick("All\nTime", "Replay: your most played songs", bg(0xFFFF3B5C, 0xFFB8002A), 200, 262, 36, Modifier.enter(1), rp) }
        item { Pick("Chill\nMix", "Calm tracks for focus", bg(0xFF7AD0C6, 0xFF2F6F8F), 200, 262, 36, Modifier.enter(2)) }
    }
    H("Recently Played"); val l = sec["pop"].orEmpty(); Empty(l)
    Rw { itemsIndexed(l) { k, a -> Cv(a, open, Modifier.width(118.dp).enter(k)) } }
    H("Made for You")
    Rw {
        item { Pick("Favourites\nMix", "Updated daily", bg(0xFF7A3DFF, 0xFFE04CFF), 165, 215, 22, Modifier.enter(0)) }
        item { Pick("Get Up!\nMix", "Start your day", bg(0xFFFF8A1A, 0xFFE8380A), 165, 215, 22, Modifier.enter(1)) }
        item { Pick("New Music\nMix", "Fresh finds", bg(0xFF19C7A5, 0xFF0A7A9A), 165, 215, 22, Modifier.enter(2)) }
    }
}

@Composable fun NewS(sec: Sec, open: (Album) -> Unit) = Scr("New", { Av() }) {
    val f = sec["electronic"].orEmpty(); Empty(f)
    f.firstOrNull()?.let { a -> Column(Modifier.fillMaxWidth().enter(0).tap { open(a) }) {
        Img(a.art, Modifier.fillMaxWidth().aspectRatio(1.3f), RoundedCornerShape(14.dp)); T(a.name, 17, 700, m = Modifier.padding(top = 8.dp)); T(a.artist, 14, 400, Sub) } }
    H("Latest Releases"); Grid(sec["hip hop"].orEmpty(), open)
}

@Composable fun Radio(sec: Sec, open: (Album) -> Unit) = Scr("Radio", { Av() }) {
    Pick("Music 1", "Live radio, 24/7", bg(0xFFFF3B5C, 0xFF7A0618), 340, 200, 38, Modifier.enter(0))
    H("Stations")
    sec["r&b"].orEmpty().take(6).forEachIndexed { k, a ->
        Row(Modifier.fillMaxWidth().enter(k).tap { open(a) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Img(a.art, Modifier.size(54.dp)); Column(Modifier.padding(start = 14.dp).weight(1f)) { T(a.artist, 17); T("Live now", 12, 400, Sub) }
            Icon(Icons.Filled.ChevronRight, null, tint = Sub)
        }
    }
}

@Composable fun Lib(sec: Sec, open: (Album) -> Unit) = Scr("Library", { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MoreHoriz, null, tint = Red); Spacer(Modifier.width(12.dp)); Av() } }) {
    val l = sec["r&b"].orEmpty(); Empty(l); Grid(l.take(6), open); Spacer(Modifier.height(6.dp))
    Li(Icons.Filled.QueueMusic, "Playlists", 0); Li(Icons.Filled.Mic, "Artists", 1); Li(Icons.Filled.Album, "Albums", 2); Li(Icons.Filled.MusicNote, "Songs", 3)
    H("Recently Added"); Rw { itemsIndexed(sec["rock"].orEmpty()) { k, a -> Cv(a, open, Modifier.width(150.dp).enter(k)) } }
}

@Composable fun SearchS(open: (Album) -> Unit, openArtist: (String) -> Unit) = Scr("Search") {
    var q by remember { mutableStateOf("") }; var res by remember { mutableStateOf(listOf<Album>()) }; var ars by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(q) {
        if (q.isBlank()) { res = emptyList(); ars = emptyList() } else {
            delay(400)
            val (a, b) = coroutineScope { val x = async { albums(q) }; val y = async { artistNames(q) }; x.await() to y.await() }
            res = a; ars = b
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = .16f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), Sub); Spacer(Modifier.width(8.dp))
        BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = LocalContentColor.current, fontSize = 16.sp, fontFamily = Sf), cursorBrush = SolidColor(Red),
            decorationBox = { inner -> if (q.isEmpty()) T("Artists, Songs, Lyrics and More", 16, 400, Sub); inner() })
    }
    if (q.isBlank()) {
        H("Browse Categories")
        listOf("Spatial Audio" to 280f, "Hits" to 350f, "Hip-Hop" to 30f, "Chill" to 170f, "Dance" to 300f, "Pop" to 330f, "Rock" to 10f, "Workout" to 90f).chunked(2).forEachIndexed { r, row ->
            Row(Modifier.padding(bottom = 12.dp), Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { k, c -> Box(Modifier.weight(1f).height(88.dp).enter(r * 2 + k).tap { q = c.first }.clip(RoundedCornerShape(12.dp)).background(grad(c.second, 30f)).padding(10.dp)) { T(c.first, 16, 700, Color.White) } }
            }
        }
    } else {
        if (ars.isNotEmpty()) {
            T("Artists", 20, 700, m = Modifier.padding(top = 18.dp, bottom = 4.dp))
            ars.forEachIndexed { k, n ->
                Row(Modifier.fillMaxWidth().enter(k).tap { openArtist(n) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(grad(n.length * 37f, 40f)), Alignment.Center) { T(n.take(1).uppercase(), 22, 800, Color.White) }
                    T(n, 16, 600, m = Modifier.padding(start = 14.dp).weight(1f)); Icon(Icons.Filled.ChevronRight, null, tint = Sub)
                }
            }
            T("Albums", 20, 700, m = Modifier.padding(top = 14.dp, bottom = 4.dp))
        }
        res.forEachIndexed { k, a ->
            Row(Modifier.fillMaxWidth().enter(k).tap { open(a) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Img(a.art, Modifier.size(54.dp)); Column(Modifier.padding(start = 14.dp)) { T(a.name, 16, 600); T(a.artist, 13, 400, Sub) }
            }
        }
    }
}

@Composable fun Replay(sec: Sec, openArtist: (String) -> Unit, back: (() -> Unit)? = null) = Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(Color(0x9934C759), Color.Transparent))))
    var tgt by remember { mutableStateOf(0) }; LaunchedEffect(Unit) { tgt = 8491 }
    val m by animateIntAsState(tgt, tween(1800, easing = FastOutSlowInEasing), label = "m")
    Scr("Replay", { T("2025 ⌄", 16, 600) }) {
        Row(Modifier.padding(top = 4.dp, bottom = 16.dp), Arrangement.spacedBy(16.dp)) { listOf("Jan", "Feb", "Mar", "Apr", "May").forEach { T(it, 14, 600, if (it == "May") LocalContentColor.current else Sub) } }
        Text(buildAnnotatedString { append("You listened for "); withStyle(SpanStyle(fontWeight = FontWeight(800))) { append("%,d minutes".format(m)) }; append(" in May.") }, fontSize = 27.sp, fontWeight = FontWeight(700), fontFamily = Sf, lineHeight = 31.sp)
        H("Your Top Artists")
        Rw { sec["pop"].orEmpty().take(2).forEachIndexed { k, a -> item {
            Box(Modifier.size(200.dp, 250.dp).enter(k).tap { openArtist(splitNames(a.artist, false).firstOrNull() ?: a.artist) }.clip(RoundedCornerShape(12.dp))) {
                Img(a.art, Modifier.fillMaxSize(), RoundedCornerShape(0.dp)); Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
                T("${k + 1}", 30, 800, Color.White, Modifier.padding(12.dp))
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { T(a.artist, 15, 700, Color.White); T("${283 - k * 43} minutes", 13, 400, Color.White.copy(alpha = .8f)) }
            } } }
        }
    }
    if (back != null) IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Red) }
}

@Composable fun AlbumS(al: Album, pl: Pl, openArtist: (String) -> Unit, back: () -> Unit) {
    val (c1, _) = rememberPal(al.art); var tr by remember { mutableStateOf(listOf<Track>()) }
    LaunchedEffect(al.id) { tr = tracks(al) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, lerp(c1, Color.Black, .55f), Color.Black)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Img(al.art, Modifier.padding(top = 56.dp).enter(0).size(250.dp).shadow(28.dp, RoundedCornerShape(10.dp)))
            T(al.name, 22, 700, Color.White, Modifier.padding(top = 18.dp), 2, TextAlign.Center)
            Names(splitNames(al.artist, false).ifEmpty { listOf(al.artist) }, 19, lerp(c1, Color.White, .55f), openArtist, Arrangement.Center)
            T("${al.genre} · ${al.year}", 12, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
            Row(Modifier.padding(vertical = 18.dp).tap { if (tr.isNotEmpty()) pl.play(tr, 0) }.clip(CircleShape).background(Color.White).padding(horizontal = 40.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp), Color.Black); T(" Play", 17, 600, Color.Black)
            }
            tr.forEachIndexed { k, t ->
                Row(Modifier.fillMaxWidth().enter(minOf(k + 1, 8)).tap { pl.play(tr, k) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${k + 1}", 15, 400, Color.White.copy(alpha = .6f), Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        T(t.title, 16, 500, Color.White)
                        if (t.all.size > 1) Names(t.all, 12, Color.White.copy(alpha = .6f), openArtist)
                    }
                    Icon(Icons.Filled.MoreHoriz, null, tint = Color.White.copy(alpha = .7f))
                }
                HorizontalDivider(color = Color.White.copy(alpha = .15f))
            }
        }
        IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Color.White) }
    }
}

// Perfil do artista: só músicas em que ele é principal ou colaborador (feat.)
@Composable fun ArtistS(name: String, pl: Pl, openArtist: (String) -> Unit, back: () -> Unit) {
    var list by remember(name) { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(name) { list = artistTracks(name) }
    val l = list.orEmpty(); val art = l.firstOrNull()?.art
    val (c1, _) = rememberPal(art)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, lerp(c1, Color.Black, .6f), Color.Black)))) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 200.dp)) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 56.dp).enter(0).size(170.dp).shadow(24.dp, CircleShape).clip(CircleShape).background(bg(0xFFC9A7FF, 0xFFFF9EC4)), Alignment.Center) {
                        if (art != null) Img(art, Modifier.fillMaxSize(), CircleShape) else T(name.take(1).uppercase(), 64, 800, Color.White)
                    }
                    T(name, 30, 800, Color.White, Modifier.padding(top = 16.dp), 2, TextAlign.Center)
                    T(if (list == null) "Carregando…" else "${l.size} músicas", 13, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
                    Row(Modifier.padding(vertical = 18.dp).tap { list?.let { if (it.isNotEmpty()) pl.play(it, 0) } }.clip(CircleShape).background(Color.White).padding(horizontal = 40.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp), Color.Black); T(" Play", 17, 600, Color.Black)
                    }
                }
                if (list != null && l.isEmpty()) T("Nenhuma música encontrada.", 14, 400, Color.White.copy(alpha = .6f), Modifier.padding(vertical = 20.dp))
                if (l.isNotEmpty()) T("Músicas", 20, 700, Color.White, Modifier.padding(top = 4.dp, bottom = 6.dp))
            }
            itemsIndexed(l) { k, t ->
                val isMain = t.mains.any { nrm(it) == nrm(name) }
                Row(Modifier.fillMaxWidth().enter(minOf(k, 6)).tap { pl.play(list.orEmpty(), k) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Img(t.art, Modifier.size(50.dp), RoundedCornerShape(6.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        T(t.title, 16, 600, Color.White)
                        Names(t.all, 13, Color.White.copy(alpha = .6f), openArtist)
                    }
                    if (!isMain) T("feat.", 11, 600, Color.White.copy(alpha = .8f), Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Color.White) }
    }
}

// Letra sincronizada (+ tradução linha a linha)
@Composable fun LyricsView(pl: Pl, t: Track) {
    val key = t.key; val lang = remember { Locale.getDefault().language }
    var d by remember(key) { mutableStateOf(pl.lyCache[key]) }
    var fail by remember(key) { mutableStateOf(false) }
    var tr by remember(key) { mutableStateOf(pl.trCache[key]) }
    var busy by remember(key) { mutableStateOf(false) }
    var trFail by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (d == null) {
            val r = lyrics(t)
            if (r == null) { fail = true } else { pl.lyCache[key] = r; d = r }
        }
    }
    LaunchedEffect(key, pl.trOn, d) {
        val x = d
        if (pl.trOn && tr == null && x != null) {
            busy = true; trFail = false
            try {
                val r = translateAll(x.lines.map { it.text }, lang)
                if (r != null) { pl.trCache[key] = r; tr = r } else { trFail = true }
            } finally { busy = false }
        }
    }
    val x = d
    if (x == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (fail) T("Letra não disponível para esta música", 16, 600, Color.White.copy(alpha = .75f), a = TextAlign.Center, ml = 3)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                T("Buscando letra…", 14, 500, Color.White.copy(alpha = .7f), Modifier.padding(top = 12.dp))
            }
        }
    } else {
        // Prévia = clipe de 30s de um ponto desconhecido da música. Depois que o usuário marca a linha que está ouvindo,
        // sabemos onde a prévia começa (calib) e mostramos SÓ as linhas que caem nesses 30s. Com áudio completo (> 45s) mostra tudo.
        val preview = pl.dur <= 45f
        val calib: Float? = if (!preview) 0f else pl.offs[key]
        val active = x.synced && calib != null
        val st = pl.pos + (calib ?: 0f) + .3f
        val vis = if (active && preview) {
            val lo = (calib ?: 0f) - 1.5f; val hi = (calib ?: 0f) + pl.dur + .5f
            x.lines.withIndex().filter { it.value.ms / 1000f in lo..hi }
        } else x.lines.withIndex().toList()
        val cur = if (active) vis.indexOfLast { it.value.ms / 1000f <= st } else -1
        val ls = rememberLazyListState()
        LaunchedEffect(cur) { if (cur >= 0) ls.animateScrollToItem((cur - 1).coerceAtLeast(0)) }
        Column(Modifier.fillMaxSize()) {
            val msg = when {
                busy -> "Traduzindo…"
                trFail && pl.trOn -> "Tradução indisponível"
                !x.synced -> "Letra sem sincronização"
                calib == null -> "Toque na linha que está tocando agora"
                else -> null
            }
            val pill = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .16f))
            if (msg != null) T(msg, 12, 600, Color.White.copy(alpha = .85f), pill.padding(horizontal = 12.dp, vertical = 5.dp))
            else if (preview && calib != null) T("Trecho da prévia · Resincronizar", 12, 600, Color.White.copy(alpha = .85f), pill.clickable { pl.clearOff(key) }.padding(horizontal = 12.dp, vertical = 5.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), ls, contentPadding = PaddingValues(top = 14.dp, bottom = 140.dp)) {
                itemsIndexed(vis) { i, iv ->
                    val ln = iv.value
                    val al by animateFloatAsState(if (!active) .85f else if (i == cur) 1f else .3f, tween(400), label = "la")
                    val bl = if (active && cur >= 0) minOf(abs(i - cur), 4) * 1.5f else 0f
                    val tt = tr?.getOrNull(iv.index)
                    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)
                        .then(if (bl > 0f) Modifier.blur(bl.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
                        .graphicsLayer { alpha = al }
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (x.synced) {
                                if (!preview) { pl.seek(ln.ms / 1000f / pl.dur) } else { pl.setOff(key, ln.ms / 1000f - pl.pos) }
                            }
                        }) {
                        Text(ln.text, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight(800), fontFamily = Sf, lineHeight = 34.sp, letterSpacing = (-0.6f).sp)
                        if (pl.trOn && !tt.isNullOrBlank() && nrm(tt) != nrm(ln.text))
                            Text(tt, color = Color.White.copy(alpha = .65f), fontSize = 18.sp, fontWeight = FontWeight(600), fontFamily = Sf, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable fun Now(pl: Pl, openArtist: (String) -> Unit, close: () -> Unit) {
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
        if (!pl.lyOpen) {
            T("AutoMix", 12, 700, Color.White, Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .2f)).padding(horizontal = 12.dp, vertical = 4.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Crossfade(t.art, label = "cv") { u -> Img(u, Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = sc; scaleY = sc }.shadow(30.dp, RoundedCornerShape(16.dp)), RoundedCornerShape(16.dp)) }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                AnimatedContent(t, Modifier.weight(1f), transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) }, label = "tt") { x ->
                    Column { T(x.title, 22, 700, Color.White); Names(x.all, 18, Color.White.copy(alpha = .7f), openArtist) }
                }
                Icon(Icons.Filled.StarBorder, null, tint = Color.White); Spacer(Modifier.width(14.dp)); Icon(Icons.Filled.MoreHoriz, null, tint = Color.White)
            }
        } else {
            // Modo letra (como no Apple Music): capa pequena + título/artistas + botão de traduzir
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Img(t.art, Modifier.size(54.dp), RoundedCornerShape(6.dp))
                Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) { T(t.title, 16, 700, Color.White); Names(t.all, 14, Color.White.copy(alpha = .7f), openArtist) }
                CBtn(Icons.Filled.Translate, pl.trOn) { pl.trOn = !pl.trOn }; Spacer(Modifier.width(8.dp))
                CBtn(Icons.Filled.StarBorder); Spacer(Modifier.width(8.dp)); CBtn(Icons.Filled.MoreHoriz)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { LyricsView(pl, t) }
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
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            IconButton({ pl.lyOpen = !pl.lyOpen }, Modifier.clip(CircleShape).background(if (pl.lyOpen) Color.White.copy(alpha = .25f) else Color.Transparent)) {
                Icon(if (pl.lyOpen) Icons.Filled.ChatBubble else Icons.Filled.ChatBubbleOutline, null, tint = Color.White)
            }
            IconButton({}) { Icon(Icons.Filled.Cast, null, tint = Color.White.copy(alpha = .85f)) }
            IconButton({}) { Icon(Icons.Filled.QueueMusic, null, tint = Color.White.copy(alpha = .85f)) }
        }
    }
}

@Composable fun App() {
    val dark = isSystemInDarkTheme()
    val bgc = if (dark) Color.Black else Color.White; val tx = if (dark) Color.White else Color.Black
    val sf = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val ctx = LocalContext.current
    val pl = remember { Pl(ctx.applicationContext) }
    var dest by remember { mutableStateOf(Dest()) }
    val stack = remember { mutableStateListOf<Dest>() }          // pilha de navegação (álbum/artista -> voltar)
    var np by remember { mutableStateOf(false) }                 // player completo SEMPRE começa fechado
    val sec = remember { mutableStateMapOf<String, List<Album>>() }
    DisposableEffect(Unit) { onDispose { pl.release() } }
    LaunchedEffect(Unit) { listOf("pop", "hip hop", "r&b", "rock", "electronic").forEach { k -> launch { sec[k] = albums(k) } } }
    LaunchedEffect(Unit) { while (true) { delay(200); pl.tick() } }
    val pop: () -> Unit = { if (stack.isNotEmpty()) dest = stack.removeAt(stack.lastIndex) }
    val openAlbum: (Album) -> Unit = { a -> stack.add(dest); dest = Dest(dest.tab, a, null, dest.depth + 1) }
    val openArtist: (String) -> Unit = { n -> np = false; if (dest.artist != n) { stack.add(dest); dest = Dest(dest.tab, null, n, dest.depth + 1) } }
    val go: (Int) -> Unit = { stack.clear(); dest = Dest(it) }
    BackHandler(np || stack.isNotEmpty()) { if (np) np = false else pop() }
    val sub = stack.isNotEmpty()
    CompositionLocalProvider(LocalContentColor provides tx) {
        Box(Modifier.fillMaxSize().background(bgc)) {
            AnimatedContent(dest, transitionSpec = {
                val a = initialState.depth; val b = targetState.depth
                if (b > a) (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                else if (b < a) (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                else fadeIn(tween(300)) togetherWith fadeOut(tween(150))
            }, label = "nav") { d ->
                Box(Modifier.fillMaxSize().background(bgc)) {
                    when {
                        d.album != null -> AlbumS(d.album, pl, openArtist, pop)
                        d.artist != null -> ArtistS(d.artist, pl, openArtist, pop)
                        else -> when (d.tab) {
                            0 -> Home(sec, openAlbum) { go(5) }
                            1 -> NewS(sec, openAlbum)
                            2 -> Radio(sec, openAlbum)
                            3 -> Lib(sec, openAlbum)
                            5 -> Replay(sec, openArtist)
                            else -> SearchS(openAlbum, openArtist)
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // mini player: só aparece depois que o usuário toca uma música (ao abrir o app só a navbar aparece)
                AnimatedVisibility(pl.cur != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    pl.cur?.let { t ->
                        Row(Modifier.fillMaxWidth().height(56.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(sf).clickable { np = true }.padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Img(t.art, Modifier.size(40.dp), RoundedCornerShape(8.dp)); T(t.title, 15, 600, m = Modifier.padding(start = 12.dp).weight(1f))
                            IconButton({ pl.toggle() }) { Crossfade(pl.playing, label = "m") { p -> Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) } }
                            IconButton({ pl.next() }) { Icon(Icons.Filled.SkipNext, null) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f).height(62.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(sf).padding(5.dp)) {
                        listOf(Triple(Icons.Filled.Home, "Home", 0), Triple(Icons.Filled.GridView, "New", 1), Triple(Icons.Filled.Sensors, "Radio", 2), Triple(Icons.Filled.LibraryMusic, "Library", 3), Triple(Icons.Filled.Replay, "Replay", 5)).forEach { (i, n, k) ->
                            val on = dest.tab == k && !sub
                            val col by animateColorAsState(if (on) Red else Sub, label = "ic"); val bk by animateColorAsState(if (on) Color.Gray.copy(alpha = .2f) else Color.Transparent, label = "bk")
                            Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bk).clickable { go(k) }, Arrangement.Center, Alignment.CenterHorizontally) {
                                Icon(i, null, Modifier.size(22.dp), col); T(n, 10, 600, col)
                            }
                        }
                    }
                    Box(Modifier.size(62.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(sf).clickable { go(4) }, Alignment.Center) { Icon(Icons.Filled.Search, null, tint = if (dest.tab == 4 && !sub) Red else Sub) }
                }
            }
            AnimatedVisibility(np && pl.cur != null, enter = slideInVertically(spring(.85f, 300f)) { it } + fadeIn(), exit = slideOutVertically(spring(1f, 400f)) { it } + fadeOut()) { Now(pl, openArtist) { np = false } }
        }
    }
}
