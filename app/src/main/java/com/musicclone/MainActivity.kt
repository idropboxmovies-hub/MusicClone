package com.musicclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

val Red = Color(0xFFFA243C)
val Sub = Color(0xFF8A8A8E)
fun bg(a: Long, b: Long) = Brush.linearGradient(listOf(Color(a), Color(b)))
fun grad(h: Float, k: Float = 45f) = Brush.linearGradient(listOf(Color.hsv(h % 360, .55f, .95f), Color.hsv((h + k) % 360, .75f, .5f)))
fun fmt(s: Int) = "${s / 60}:${"%02d".format(s % 60)}"

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); enableEdgeToEdge(); setContent { App() } }
}

@Composable fun T(s: String, sz: Int = 16, w: FontWeight = FontWeight.Normal, c: Color = Color.Unspecified, m: Modifier = Modifier, ml: Int = 1, a: TextAlign? = null) =
    Text(s, m, c, sz.sp, fontWeight = w, textAlign = a, maxLines = ml, lineHeight = (sz * 1.1f).sp)

@Composable fun Art(h: Float, m: Modifier, s: Shape = RoundedCornerShape(10.dp)) = Box(m.clip(s).background(grad(h)))
@Composable fun Av() = Box(Modifier.size(34.dp).clip(CircleShape).background(bg(0xFFC9A7FF, 0xFFFF9EC4)))
@Composable fun Rw(c: LazyListScope.() -> Unit) = LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), content = c)
@Composable fun H(t: String) = Row(Modifier.padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    T(t, 19, FontWeight.Bold); Icon(Icons.Filled.ChevronRight, null, tint = Sub)
}

@Composable fun Scr(title: String, right: @Composable () -> Unit = {}, c: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 190.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { T(title, 32, FontWeight.ExtraBold); right() }
        c()
    }

@Composable fun Pick(t: String, s: String, b: Brush, w: Int = 290, onClick: () -> Unit = {}) {
    val small = w < 200
    Column(Modifier.size(w.dp, (if (small) w * 1.16f else w.toFloat()).dp).clip(RoundedCornerShape(14.dp)).background(b).clickable(onClick = onClick).padding(14.dp), Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(14.dp), Color.White); T(" Music", 12, FontWeight.SemiBold, Color.White) }
        Column { T(t, if (small) 24 else 36, FontWeight.ExtraBold, Color.White, ml = 2); T(s, 11, c = Color.White.copy(alpha = .85f), ml = 2) }
    }
}

@Composable fun Cv(t: String, h: Float, s: String = "", m: Modifier = Modifier.width(118.dp)) = Column(m) {
    Art(h, Modifier.fillMaxWidth().aspectRatio(1f))
    T(t, 12, FontWeight.SemiBold, m = Modifier.padding(top = 6.dp)); if (s != "") T(s, 11, c = Sub)
}

@Composable fun Grid(l: List<Triple<String, Float, String>>, n: Int = 3) = l.chunked(n).forEach { r ->
    Row(Modifier.padding(bottom = 12.dp), Arrangement.spacedBy(12.dp)) {
        r.forEach { Cv(it.first, it.second, it.third, Modifier.weight(1f)) }; repeat(n - r.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable fun Li(i: ImageVector, t: String, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(i, null, Modifier.size(22.dp), Red); T(t, 17, m = Modifier.padding(start = 14.dp).weight(1f)); Icon(Icons.Filled.ChevronRight, null, tint = Sub)
    }
    HorizontalDivider(color = Color.Gray.copy(alpha = .25f))
}

@Composable fun Home(go: () -> Unit) = Scr("Home", { Av() }) {
    T("Made for You", 12, FontWeight.SemiBold, Sub); H("Top Picks for You")
    Rw {
        item { Pick("Friends\nMix", "Card B, Offset, Kendrick Lamar, JID and more", bg(0xFF2A7BFF, 0xFF0B2C8C)) }
        item { Pick("Replay\nAll Time", "Your most played songs ever", bg(0xFFFF3B5C, 0xFFB8002A), onClick = go) }
        item { Pick("Chill\nMix", "Calm tracks for focus", bg(0xFF7AD0C6, 0xFF2F6F8F)) }
    }
    H("Recently Played")
    Rw { listOf("Lessons in Gravity" to 20f, "Duck Mark" to 300f, "Dark Room" to 260f, "Neon Tapes" to 170f).forEach { item { Cv(it.first, it.second, "Various") } } }
    H("Made for You")
    Rw {
        item { Pick("Favourites\nMix", "Updated daily", bg(0xFF7A3DFF, 0xFFE04CFF), 150) }
        item { Pick("Get Up!\nMix", "Start your day", bg(0xFFFF8A1A, 0xFFE8380A), 150) }
        item { Pick("New Music\nMix", "Fresh finds", bg(0xFF19C7A5, 0xFF0A7A9A), 150) }
    }
}

@Composable fun NewS() = Scr("New", { Av() }) {
    Pick("Fresh\nFinds", "New releases picked for you", bg(0xFFFF9A3C, 0xFFD61F69), 340)
    H("Latest Releases")
    Grid(listOf(10f, 50f, 90f, 150f, 200f, 250f, 300f, 340f, 30f).mapIndexed { k, h -> Triple("Album ${k + 1}", h, "Artist") })
}

@Composable fun Radio() = Scr("Radio", { Av() }) {
    Pick("Music 1", "Live radio, 24/7", bg(0xFFFF3B5C, 0xFF7A0618), 340)
    H("Stations")
    listOf("Hits", "Hip-Hop", "Country", "Chill", "Dance").forEachIndexed { k, n ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(k * 60f, Modifier.size(52.dp)); Column(Modifier.padding(start = 14.dp).weight(1f)) { T(n, 17); T("Live now", 12, c = Sub) }
            Icon(Icons.Filled.ChevronRight, null, tint = Sub)
        }
    }
}

@Composable fun Lib() = Scr("Library", { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MoreHoriz, null, tint = Red); Spacer(Modifier.width(12.dp)); Av() } }) {
    Grid(listOf("Why Not More?" to 30f, "R&B Now" to 350f, "MIKE" to 0f, "Like A Ribbon" to 48f, "Earcandy" to 200f, "Jupiter" to 8f).map { Triple(it.first, it.second, "") })
    Spacer(Modifier.height(8.dp))
    Li(Icons.Filled.QueueMusic, "Playlists"); Li(Icons.Filled.Mic, "Artists"); Li(Icons.Filled.Album, "Albums"); Li(Icons.Filled.MusicNote, "Songs")
    H("Recently Added")
    Rw { listOf("Midnight Static" to 320f, "Glasshouse" to 190f, "Paper Suns" to 40f).forEach { item { Cv(it.first, it.second, "Artist") } } }
}

@Composable fun SearchS() = Scr("Search") {
    var q by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = .16f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), Sub); Spacer(Modifier.width(8.dp))
        BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = LocalContentColor.current, fontSize = 16.sp), cursorBrush = SolidColor(Red),
            decorationBox = { inner -> if (q.isEmpty()) T("Artists, Songs, Lyrics and More", 16, c = Sub); inner() })
    }
    H("Browse Categories")
    val cats = listOf("Spatial Audio" to 280f, "Hits" to 350f, "Hip-Hop" to 30f, "Chill" to 170f, "Dance" to 300f, "Pop" to 330f, "Rock" to 10f, "Workout" to 90f).filter { it.first.contains(q, true) }
    cats.chunked(2).forEach { r ->
        Row(Modifier.padding(bottom = 12.dp), Arrangement.spacedBy(12.dp)) {
            r.forEach { Box(Modifier.weight(1f).height(88.dp).clip(RoundedCornerShape(12.dp)).background(grad(it.second, 30f)).padding(10.dp)) { T(it.first, 16, FontWeight.Bold, Color.White) } }
            if (r.size < 2) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable fun Replay(back: () -> Unit) = Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(Color(0x9934C759), Color.Transparent))))
    Scr("Replay", { T("2025 ⌄", 16, FontWeight.SemiBold) }) {
        Row(Modifier.padding(top = 4.dp, bottom = 16.dp), Arrangement.spacedBy(16.dp)) {
            listOf("Jan", "Feb", "Mar", "Apr", "May").forEach { T(it, 14, FontWeight.SemiBold, if (it == "May") LocalContentColor.current else Sub) }
        }
        Text(buildAnnotatedString { append("You listened for "); withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("8,491 minutes") }; append(" in May.") }, fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
        H("Your Top Artists")
        Rw {
            item { RankCard("1", "Artist One", "283 minutes", bg(0xFF666677, 0xFF111111)) }
            item { RankCard("2", "Artist Two", "240 minutes", bg(0xFF2A6FB8, 0xFF0C1B3A)) }
        }
    }
    IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Red) }
}

@Composable fun RankCard(n: String, a: String, m: String, b: Brush) =
    Column(Modifier.size(200.dp, 250.dp).clip(RoundedCornerShape(12.dp)).background(b).padding(12.dp), Arrangement.SpaceBetween) {
        T(n, 30, FontWeight.ExtraBold, Color.White)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { T(a, 15, FontWeight.Bold, Color.White); T(m, 13, c = Color.White.copy(alpha = .8f)) }
    }

@Composable fun Now(t: Triple<String, String, Int>, pos: Int, playing: Boolean, onPlay: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, close: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFD9233B), Color(0xFF7A0618), Color(0xFF33030B)))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = .4f)).clickable(onClick = close))
        T("AutoMix", 12, FontWeight.Bold, Color.White, Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .2f)).padding(horizontal = 12.dp, vertical = 4.dp))
        Canvas(Modifier.padding(vertical = 22.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))) {
            drawRect(bg(0xFFB30F28, 0xFF5A0516))
            listOf(Triple(.5f, .36f, .09f) to 0xFFF2C4AE, Triple(.24f, .28f, .10f) to 0xFFFFFFFF, Triple(.78f, .24f, .11f) to 0xFFFFD3DD, Triple(.18f, .74f, .14f) to 0xFFFF6B8A, Triple(.82f, .78f, .12f) to 0xFFFFF2C2, Triple(.5f, .86f, .09f) to 0xFFFFFFFF)
                .forEach { (p, c) -> drawCircle(Color(c), size.width * p.third, Offset(size.width * p.first, size.height * p.second)) }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column { T(t.first, 21, FontWeight.Bold, Color.White); T(t.second, 17, c = Color.White.copy(alpha = .7f)) }
            Row { Icon(Icons.Filled.StarBorder, null, tint = Color.White); Spacer(Modifier.width(14.dp)); Icon(Icons.Filled.MoreHoriz, null, tint = Color.White) }
        }
        LinearProgressIndicator({ pos / t.third.toFloat() }, Modifier.padding(top = 20.dp, bottom = 6.dp).fillMaxWidth().height(5.dp).clip(CircleShape), Color.White, Color.White.copy(alpha = .28f))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { T(fmt(pos), 11, c = Color.White.copy(alpha = .7f)); T("-" + fmt(t.third - pos), 11, c = Color.White.copy(alpha = .7f)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            IconButton(onPrev) { Icon(Icons.Filled.SkipPrevious, null, Modifier.size(42.dp), Color.White) }
            IconButton(onPlay, Modifier.size(80.dp)) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(60.dp), Color.White) }
            IconButton(onNext) { Icon(Icons.Filled.SkipNext, null, Modifier.size(42.dp), Color.White) }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) { listOf(Icons.Filled.ChatBubbleOutline, Icons.Filled.Cast, Icons.Filled.QueueMusic).forEach { Icon(it, null, tint = Color.White.copy(alpha = .85f)) } }
    }
}

@Composable fun App() {
    val dark = isSystemInDarkTheme()
    val bgc = if (dark) Color.Black else Color.White
    val tx = if (dark) Color.White else Color.Black
    val glass = if (dark) Color(0xE6303034) else Color(0xE6F6F6F8)
    var tab by remember { mutableStateOf(0) }; var replay by remember { mutableStateOf(false) }; var np by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }; var idx by remember { mutableStateOf(0) }; var pos by remember { mutableStateOf(84) }
    val tr = listOf(Triple("Girl Like Me", "PinkPantheress", 198), Triple("Lessons in Gravity", "Aurora Vale", 214), Triple("Midnight Static", "Nova Reyes", 187))
    LaunchedEffect(playing, idx) { while (playing) { delay(1000); pos = (pos + 1) % tr[idx].third } }
    fun skip(d: Int) { idx = (idx + d + tr.size) % tr.size; pos = 0 }
    BackHandler(np || replay) { if (np) np = false else replay = false }
    CompositionLocalProvider(LocalContentColor provides tx) {
        Box(Modifier.fillMaxSize().background(bgc)) {
            if (replay) Replay { replay = false } else when (tab) { 0 -> Home { replay = true }; 1 -> NewS(); 2 -> Radio(); 3 -> Lib(); else -> SearchS() }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(glass).clickable { np = true }.padding(start = 7.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(20f, Modifier.size(40.dp), CircleShape); T(tr[idx].first, 14, FontWeight.SemiBold, m = Modifier.padding(start = 10.dp).weight(1f))
                    IconButton({ playing = !playing }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) }
                    IconButton({ skip(1) }) { Icon(Icons.Filled.SkipNext, null) }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f).height(60.dp).clip(CircleShape).background(glass).padding(5.dp)) {
                        listOf(Icons.Filled.Home to "Home", Icons.Filled.GridView to "New", Icons.Filled.Sensors to "Radio", Icons.Filled.LibraryMusic to "Library").forEachIndexed { k, (i, n) ->
                            val on = tab == k && !replay
                            Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (on) Color.Gray.copy(alpha = .2f) else Color.Transparent).clickable { tab = k; replay = false }, Arrangement.Center, Alignment.CenterHorizontally) {
                                Icon(i, null, Modifier.size(22.dp), if (on) Red else Sub); T(n, 10, FontWeight.SemiBold, if (on) Red else Sub)
                            }
                        }
                    }
                    Box(Modifier.size(60.dp).clip(CircleShape).background(glass).clickable { tab = 4; replay = false }, Alignment.Center) { Icon(Icons.Filled.Search, null, tint = if (tab == 4 && !replay) Red else Sub) }
                }
            }
            AnimatedVisibility(np, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Now(tr[idx], pos, playing, { playing = !playing }, { skip(-1) }, { skip(1) }) { np = false }
            }
        }
    }
}
