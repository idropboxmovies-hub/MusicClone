package com.musicclone

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
import org.json.JSONObject
import java.net.URL

val Red = Color(0xFFFA243C)
val Sub = Color(0xFF8A8A8E)
fun F(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Sf = FontFamily(F(400), F(500), F(600), F(700), F(800))
fun bg(a: Long, b: Long) = Brush.linearGradient(listOf(Color(a), Color(b)))
fun grad(h: Float, k: Float = 45f) = Brush.linearGradient(listOf(Color.hsv(h % 360, .55f, .95f), Color.hsv((h + k) % 360, .75f, .5f)))
fun fmt(s: Int) = "${s / 60}:${"%02d".format(s % 60)}"

data class Album(val id: Long, val name: String, val artist: String, val art: String, val genre: String, val year: String)
data class Track(val name: String, val artist: String, val art: String, val url: String, val album: String)
data class Dest(val tab: Int = 0, val replay: Boolean = false, val album: Album? = null)

suspend fun get(u: String) = withContext(Dispatchers.IO) { runCatching { JSONObject(URL(u).readText()) }.getOrNull() }
suspend fun albums(term: String): List<Album> {
    val j = get("https://itunes.apple.com/search?entity=album&limit=12&term=${Uri.encode(term)}") ?: return emptyList()
    val a = j.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.map {
        Album(it.optLong("collectionId"), it.optString("collectionName"), it.optString("artistName"), it.optString("artworkUrl100").replace("100x100", "600x600"), it.optString("primaryGenreName"), it.optString("releaseDate").take(4))
    }
}
suspend fun tracks(al: Album): List<Track> {
    val j = get("https://itunes.apple.com/lookup?entity=song&id=${al.id}") ?: return emptyList()
    val a = j.optJSONArray("results") ?: return emptyList()
    return (1 until a.length()).map { a.getJSONObject(it) }.filter { it.has("previewUrl") }.map { Track(it.optString("trackName"), it.optString("artistName"), al.art, it.optString("previewUrl"), al.name) }
}

class Pl {
    var q by mutableStateOf(listOf<Track>()); var i by mutableStateOf(0); var playing by mutableStateOf(false)
    var pos by mutableStateOf(0f); var dur by mutableStateOf(30f)
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
            prepareAsync()
        }
    }
    fun toggle() { mp?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } } }
    fun next() { if (q.isNotEmpty()) { i = (i + 1) % q.size; load() } }
    fun prev() { if (q.isNotEmpty()) { i = (i - 1 + q.size) % q.size; load() } }
    fun seek(f: Float) { mp?.seekTo((f.coerceIn(0f, 1f) * dur * 1000).toInt()) }
    fun tick() { mp?.let { if (playing) pos = it.currentPosition / 1000f } }
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

@Composable fun SearchS(open: (Album) -> Unit) = Scr("Search") {
    var q by remember { mutableStateOf("") }; var res by remember { mutableStateOf(listOf<Album>()) }
    LaunchedEffect(q) { if (q.isBlank()) res = emptyList() else { delay(400); res = albums(q) } }
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
    } else res.forEachIndexed { k, a ->
        Row(Modifier.fillMaxWidth().enter(k).tap { open(a) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Img(a.art, Modifier.size(54.dp)); Column(Modifier.padding(start = 14.dp)) { T(a.name, 16, 600); T(a.artist, 13, 400, Sub) }
        }
    }
}

@Composable fun Replay(sec: Sec, back: () -> Unit) = Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(Color(0x9934C759), Color.Transparent))))
    var tgt by remember { mutableStateOf(0) }; LaunchedEffect(Unit) { tgt = 8491 }
    val m by animateIntAsState(tgt, tween(1800, easing = FastOutSlowInEasing), label = "m")
    Scr("Replay", { T("2025 ⌄", 16, 600) }) {
        Row(Modifier.padding(top = 4.dp, bottom = 16.dp), Arrangement.spacedBy(16.dp)) { listOf("Jan", "Feb", "Mar", "Apr", "May").forEach { T(it, 14, 600, if (it == "May") LocalContentColor.current else Sub) } }
        Text(buildAnnotatedString { append("You listened for "); withStyle(SpanStyle(fontWeight = FontWeight(800))) { append("%,d minutes".format(m)) }; append(" in May.") }, fontSize = 27.sp, fontWeight = FontWeight(700), fontFamily = Sf, lineHeight = 31.sp)
        H("Your Top Artists")
        Rw { sec["pop"].orEmpty().take(2).forEachIndexed { k, a -> item {
            Box(Modifier.size(200.dp, 250.dp).enter(k).clip(RoundedCornerShape(12.dp))) {
                Img(a.art, Modifier.fillMaxSize(), RoundedCornerShape(0.dp)); Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
                T("${k + 1}", 30, 800, Color.White, Modifier.padding(12.dp))
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { T(a.artist, 15, 700, Color.White); T("${283 - k * 43} minutes", 13, 400, Color.White.copy(alpha = .8f)) }
            } } }
        }
    }
    IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Red) }
}

@Composable fun AlbumS(al: Album, pl: Pl, back: () -> Unit) {
    val (c1, _) = rememberPal(al.art); var tr by remember { mutableStateOf(listOf<Track>()) }
    LaunchedEffect(al.id) { tr = tracks(al) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, lerp(c1, Color.Black, .55f), Color.Black)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 200.dp), Alignment.CenterHorizontally) {
            Img(al.art, Modifier.padding(top = 56.dp).enter(0).size(250.dp).shadow(28.dp, RoundedCornerShape(10.dp)))
            T(al.name, 22, 700, Color.White, Modifier.padding(top = 18.dp), 2, TextAlign.Center)
            T(al.artist, 19, 600, lerp(c1, Color.White, .55f)); T("${al.genre} · ${al.year}", 12, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
            Row(Modifier.padding(vertical = 18.dp).tap { if (tr.isNotEmpty()) pl.play(tr, 0) }.clip(CircleShape).background(Color.White).padding(horizontal = 40.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp), Color.Black); T(" Play", 17, 600, Color.Black)
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
                Column { T(x.name, 22, 700, Color.White); T(x.artist, 18, 400, Color.White.copy(alpha = .7f)) }
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

@Composable fun App() {
    val dark = isSystemInDarkTheme()
    val bgc = if (dark) Color.Black else Color.White; val tx = if (dark) Color.White else Color.Black
    val sf = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val pl = remember { Pl() }; var dest by remember { mutableStateOf(Dest()) }; var np by remember { mutableStateOf(false) }
    val sec = remember { mutableStateMapOf<String, List<Album>>() }
    LaunchedEffect(Unit) { listOf("pop", "hip hop", "r&b", "rock", "electronic").forEach { k -> launch { sec[k] = albums(k) } } }
    LaunchedEffect(Unit) { while (true) { delay(200); pl.tick() } }
    BackHandler(np || dest.replay || dest.album != null) { if (np) np = false else dest = dest.copy(replay = false, album = null) }
    val open: (Album) -> Unit = { dest = dest.copy(album = it) }
    val sub = { dest.replay || dest.album != null }
    CompositionLocalProvider(LocalContentColor provides tx) {
        Box(Modifier.fillMaxSize().background(bgc)) {
            AnimatedContent(dest, transitionSpec = {
                val push = targetState.album != null || targetState.replay; val wasSub = initialState.album != null || initialState.replay
                if (push && !wasSub) (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                else if (!push && wasSub) (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                else fadeIn(tween(300)) togetherWith fadeOut(tween(150))
            }, label = "nav") { d ->
                Box(Modifier.fillMaxSize().background(bgc)) {
                    when {
                        d.album != null -> AlbumS(d.album, pl) { dest = dest.copy(album = null) }
                        d.replay -> Replay(sec) { dest = dest.copy(replay = false) }
                        else -> when (d.tab) { 0 -> Home(sec, open) { dest = dest.copy(replay = true) }; 1 -> NewS(sec, open); 2 -> Radio(sec, open); 3 -> Lib(sec, open); else -> SearchS(open) }
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
                        listOf(Icons.Filled.Home to "Home", Icons.Filled.GridView to "New", Icons.Filled.Sensors to "Radio", Icons.Filled.LibraryMusic to "Library").forEachIndexed { k, (i, n) ->
                            val on = dest.tab == k && !sub()
                            val col by animateColorAsState(if (on) Red else Sub, label = "ic"); val bk by animateColorAsState(if (on) Color.Gray.copy(alpha = .2f) else Color.Transparent, label = "bk")
                            Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bk).clickable { dest = Dest(k) }, Arrangement.Center, Alignment.CenterHorizontally) {
                                Icon(i, null, Modifier.size(22.dp), col); T(n, 10, 600, col)
                            }
                        }
                    }
                    Box(Modifier.size(62.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(sf).clickable { dest = Dest(4) }, Alignment.Center) { Icon(Icons.Filled.Search, null, tint = if (dest.tab == 4 && !sub()) Red else Sub) }
                }
            }
            AnimatedVisibility(np && pl.cur != null, enter = slideInVertically(spring(.85f, 300f)) { it } + fadeIn(), exit = slideOutVertically(spring(1f, 400f)) { it } + fadeOut()) { Now(pl) { np = false } }
        }
    }
}
