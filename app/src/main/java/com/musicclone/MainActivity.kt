@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, androidx.compose.animation.ExperimentalAnimationApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicclone

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.roundToInt

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
data class Track(val name: String, val artist: String, val art: String, val url: String, val album: String, val artistId: Long = 0, val year: String = "", val explicit: Boolean = false, val dur: Int = 0)
data class ArtistData(val photo: String?, val latest: Album?, val latestCount: Int, val latestDate: String, val top: List<Track>)
data class Latest(val album: Album, val count: Int, val date: String)

sealed class Page {
    data class Alb(val a: Album) : Page()
    data class Art(val name: String, val id: Long, val art: String?, val app: Boolean = false, val feat: Track? = null, val uid: String? = null) : Page()
    object Me : Page()
    object Edit : Page()
    object Rep : Page()
    data class Plst(val p: PlDef) : Page()
    object Beats : Page()
    object IStudio : Page()
    data class Lan(val r: Rel) : Page()
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
        .map { Track(it.optString("trackName"), it.optString("artistName"), al.art, it.optString("previewUrl"), al.name, it.optLong("artistId"), al.year, it.optString("trackExplicitness") == "explicit", (it.optLong("trackTimeMillis") / 1000).toInt()) }
}

fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

suspend fun photoOf(name: String): String? {
    val d = get("https://api.deezer.com/search/artist?limit=1&q=${Uri.encode(name)}")?.optJSONArray("data")?.optJSONObject(0) ?: return null
    val n = norm(d.optString("name")); val m = norm(name)
    if (n.isEmpty() || m.isEmpty() || !(n == m || n.contains(m) || m.contains(n))) return null
    return d.optString("picture_xl").takeIf { it.startsWith("http") && !it.contains("/artist//") }
}

fun toTrack(o: JSONObject) = Track(o.optString("trackName"), o.optString("artistName"), o.optString("artworkUrl100").replace("100x100", "300x300"), o.optString("previewUrl"), o.optString("collectionName"), o.optLong("artistId"), o.optString("releaseDate").take(4), o.optString("trackExplicitness") == "explicit", (o.optLong("trackTimeMillis") / 1000).toInt())

suspend fun topSongs(id: Long): List<Track> {
    if (id == 0L) return emptyList()
    for (cc in listOf("BR", "US")) {
        val a = get("https://itunes.apple.com/lookup?id=$id&entity=song&limit=15&country=$cc")?.optJSONArray("results") ?: continue
        val l = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("wrapperType") == "track" && it.has("previewUrl") }.map { toTrack(it) }
        if (l.isNotEmpty()) return l
    }
    return emptyList()
}

suspend fun latest(id: Long): Latest? {
    if (id == 0L) return null
    for (cc in listOf("BR", "US")) {
        val a = get("https://itunes.apple.com/lookup?id=$id&entity=album&limit=6&sort=recent&country=$cc")?.optJSONArray("results") ?: continue
        val o = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("wrapperType") == "collection" } ?: continue
        val al = Album(o.optLong("collectionId"), o.optString("collectionName"), o.optString("artistName"), o.optString("artworkUrl100").replace("100x100", "600x600"), o.optString("primaryGenreName"), o.optString("releaseDate").take(4), id)
        return Latest(al, o.optInt("trackCount"), dataBr(o.optString("releaseDate")))
    }
    return null
}

// acha o id do artista pelo nome (só aceita nome igual ou parecido, para não cair no perfil errado)
suspend fun resolveId(name: String): Long {
    val a = get("https://itunes.apple.com/search?entity=musicArtist&limit=5&country=BR&term=${Uri.encode(name)}")?.optJSONArray("results") ?: return 0L
    val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    val m = norm(name)
    val o = l.firstOrNull { norm(it.optString("artistName")) == m }
        ?: l.firstOrNull { val n = norm(it.optString("artistName")); n.isNotEmpty() && m.isNotEmpty() && (n.contains(m) || m.contains(n)) }
    return o?.optLong("artistId") ?: 0L
}

suspend fun searchRaw(q: String, cc: String): List<Track> {
    val a = get("https://itunes.apple.com/search?entity=song&media=music&limit=50&country=$cc&$q")?.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.has("previewUrl") }.map { toTrack(it) }
}

// músicas em que o nome aparece como artista (inclui "A & B") e músicas com "feat." no título
suspend fun collabSongs(name: String): List<Track> = coroutineScope {
    val a = async { searchRaw("attribute=artistTerm&term=${Uri.encode(name)}", "BR") }
    val b = async { searchRaw("term=${Uri.encode(name)}", "BR") }
    a.await() + b.await()
}

// a música é do artista (principal) ou ele é colaborador (feat / vários artistas)?
fun envolve(t: Track, nome: String, id: Long): Boolean {
    if (id != 0L && t.artistId == id) return true
    val n = norm(nome)
    return n.isNotEmpty() && creditos(t.artist, t.name).any { norm(it) == n }
}

suspend fun artistData(name: String, id0: Long): ArtistData = coroutineScope {
    val id = if (id0 != 0L) id0 else resolveId(name)
    val ph = async { photoOf(name) }
    val tp = async { topSongs(id) }
    val co = async { collabSongs(name) }
    val lt = async { latest(id) }
    val l = lt.await()
    val top = (tp.await() + co.await()).filter { envolve(it, name, id) }.distinctBy { norm(it.name + it.artist) }.take(25)
    ArtistData(ph.await(), l?.album, l?.count ?: 0, l?.date ?: "", top)
}

// ---------- Créditos (feat / vários artistas) ----------
// Duplas e bandas cujo nome tem "&" ou "," e NÃO devem virar dois perfis. Se aparecer outra, é só incluir aqui.
val GRUPOS = listOf(
    "Tyler, The Creator", "Earth, Wind & Fire", "Simon & Garfunkel", "Hall & Oates", "Mumford & Sons", "Brooks & Dunn",
    "Kool & the Gang", "Sly & the Family Stone", "Hootie & the Blowfish", "Katrina & the Waves", "Echo & the Bunnymen",
    "Bob Marley & The Wailers", "Tom Petty & The Heartbreakers", "Prince & The Revolution", "Marina & the Diamonds",
    "Jorge & Mateus", "Simone & Simaria", "Zezé Di Camargo & Luciano", "Matheus & Kauan", "Hugo & Guilherme", "Henrique & Juliano",
    "Maiara & Maraisa", "Bruno & Marrone", "Fernando & Sorocaba", "Cleber & Cauan", "Israel & Rodolffo", "Chitãozinho & Xororó",
    "Leandro & Leonardo", "Edson & Hudson", "Munhoz & Mariano", "João Neto & Frederico", "Day & Lara", "Zé Neto & Cristiano",
    "Thaeme & Thiago", "Gino & Geno", "Rionegro & Solimões", "Milionário & José Rico"
)
private val SEP = Regex("\\s*(?:,|&)\\s*|\\s+(?:x|feat\\.?|ft\\.?|featuring|with|con)\\s+", RegexOption.IGNORE_CASE)
private val FEAT_RX = Regex("[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with|participa[çc][ãa]o(?:\\s+especial)?(?:\\s+de)?)\\s+([^)\\]]+)[)\\]]", RegexOption.IGNORE_CASE)
private val FEAT2_RX = Regex("\\s(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$", RegexOption.IGNORE_CASE)

// "Kapo & Myke Towers" -> [Kapo, Myke Towers]
fun splitArtistas(s: String): List<String> {
    var t = s
    GRUPOS.forEach { g -> if (t.contains(g, true)) t = t.replace(g, g.replace(",", "\u0001").replace("&", "\u0002").replace(" ", "\u0003"), true) }
    return t.split(SEP).map { it.replace("\u0001", ",").replace("\u0002", "&").replace("\u0003", " ").trim() }.filter { it.isNotEmpty() }
}

// "ILY (feat. Myke Towers)" -> [Myke Towers]
fun featDoTitulo(n: String): List<String> {
    val m = FEAT_RX.find(n) ?: FEAT2_RX.find(n) ?: return emptyList()
    return splitArtistas(m.groupValues[1]).filter { x -> x.any { it.isLetter() } }
}

fun creditos(artist: String, title: String = ""): List<String> = (splitArtistas(artist) + featDoTitulo(title)).distinctBy { norm(it) }

// o id que veio do iTunes só vale para o primeiro nome, e só quando o campo de artista tem um nome só
fun idDe(artist: String, k: Int, id: Long): Long = if (k == 0 && splitArtistas(artist).size == 1) id else 0L

fun tituloLimpo(n: String): String = n.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
    .replace(Regex("\\s+[-–—]\\s+.*(?:remaster|version|versão|edit|live|ao vivo|mix|from|bonus|deluxe|acoustic|acústic|radio|mono|stereo|single|original|feat|ft\\.).*$", RegexOption.IGNORE_CASE), "")
    .replace(FEAT2_RX, "").trim().ifEmpty { n }

// ---------- Letra sincronizada (LRCLIB) e tradução ----------
class Ly(val ms: Long, val txt: String)
class Lrc(val synced: List<Ly>, val plain: List<String>)

suspend fun httpText(u: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "beats/1.0 (Android)")
        c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}

fun js(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k)

private val LRC_TAG = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

// "[01:23.45] texto" -> (tempo em ms, texto)
fun parseLrc(s: String): List<Ly> {
    val out = ArrayList<Ly>()
    for (raw in s.lines()) {
        val tags = LRC_TAG.findAll(raw).toList()
        if (tags.isEmpty()) continue
        val txt = raw.substring(tags.last().range.last + 1).trim()
        if (txt.isEmpty()) continue
        for (m in tags) {
            val ms = m.groupValues[1].toLong() * 60000L + m.groupValues[2].toLong() * 1000L + (m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
            out.add(Ly(ms, txt))
        }
    }
    return out.sortedBy { it.ms }
}

// busca pelo título limpo + primeiro artista; escolhe o que bate o nome, prefere a versão com tempos e a duração mais próxima
suspend fun lrclib(t: Track): Lrc? {
    val titulo = tituloLimpo(t.name)
    val art = splitArtistas(t.artist).firstOrNull() ?: t.artist
    suspend fun busca(q: String): List<JSONObject> {
        val a = httpText("https://lrclib.net/api/search?$q")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    var r = busca("track_name=${Uri.encode(titulo)}&artist_name=${Uri.encode(art)}")
    if (r.isEmpty()) r = busca("q=${Uri.encode("$titulo $art")}")
    val nt = norm(titulo)
    val ok = r.filter { val n = norm(js(it, "trackName")); n.isNotEmpty() && (n == nt || n.contains(nt) || nt.contains(n)) }
    if (ok.isEmpty()) return null
    val perto = if (t.dur > 0) ok.filter { Math.abs(it.optDouble("duration", 0.0) - t.dur) <= 30 }.ifEmpty { ok } else ok
    val melhor = perto.sortedWith(compareByDescending<JSONObject> { js(it, "syncedLyrics").isNotBlank() }.thenBy { if (t.dur > 0) Math.abs(it.optDouble("duration", 0.0) - t.dur) else 0.0 }).first()
    val sy = js(melhor, "syncedLyrics")
    val synced = if (sy.isNotBlank()) parseLrc(sy) else emptyList()
    val plain = js(melhor, "plainLyrics").lines().map { it.trim() }.filter { it.isNotEmpty() }
    return if (synced.isEmpty() && plain.isEmpty()) null else Lrc(synced, plain)
}

val LRC_CACHE = HashMap<String, Lrc>()
suspend fun lrcDe(t: Track): Lrc? {
    val k = norm(t.name) + "|" + norm(t.artist)
    LRC_CACHE[k]?.let { return it }
    return lrclib(t)?.also { LRC_CACHE[k] = it }
}

// Google Tradutor: um bloco de até 12 linhas por pedido; se o número de linhas voltar diferente, traduz linha a linha
suspend fun traduzBloco(b: List<String>, tl: String): List<String>? {
    suspend fun um(s: String): String? {
        val j = httpText("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$tl&dt=t&q=${Uri.encode(s)}") ?: return null
        return runCatching {
            val seg = JSONArray(j).getJSONArray(0)
            buildString { for (i in 0 until seg.length()) { val x = seg.optJSONArray(i) ?: continue; if (!x.isNull(0)) append(x.optString(0)) } }
        }.getOrNull()
    }
    val t = um(b.joinToString("\n")) ?: return null
    val p = t.trimEnd('\n').split("\n")
    if (p.size == b.size) return p.map { it.trim() }
    return coroutineScope { b.map { l -> async { um(l)?.trim() ?: "" } }.awaitAll() }
}

suspend fun traduzir(linhas: List<String>, tl: String): List<String>? = coroutineScope {
    val blocos = linhas.chunked(12)
    val res = blocos.map { b -> async { traduzBloco(b, tl) } }.awaitAll()
    if (res.all { it == null }) null else res.flatMapIndexed { i, r -> r ?: List(blocos[i].size) { "" } }
}

// ---------- Playlists do beats ----------
data class PlDef(val id: String, val title: String, val sub: String, val c1: Long, val c2: Long)
val PLS = listOf(
    PlDef("global", "Top Global", "As músicas mais tocadas do mundo", 0xFF2A7BFF, 0xFF0B2C8C),
    PlDef("brasil", "Top Brasil", "O que o Brasil mais escuta", 0xFF19C75A, 0xFF0A6B3A),
    PlDef("pops", "Top Pops", "Os maiores sucessos do pop", 0xFFFF4FA3, 0xFF8A1E7A),
    PlDef("rnb", "Melhores R&B", "O melhor do R&B e do soul", 0xFFFF8A1A, 0xFF9A2A0A)
)

suspend fun chartIds(url: String): List<String> {
    val f = get(url)?.optJSONObject("feed") ?: return emptyList()
    f.optJSONArray("results")?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { x -> x.isNotEmpty() } } }
    f.optJSONArray("entry")?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.takeIf { x -> x.isNotEmpty() } } }
    return emptyList()
}

suspend fun songsByIds(ids: List<String>, cc: String): List<Track> {
    if (ids.isEmpty()) return emptyList()
    val a = get("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&country=$cc")?.optJSONArray("results") ?: return emptyList()
    val by = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.has("previewUrl") }.associateBy { it.optString("trackId") }
    return ids.mapNotNull { by[it] }.map { toTrack(it) }
}

suspend fun searchSongs(term: String, cc: String): List<Track> {
    val a = get("https://itunes.apple.com/search?entity=song&media=music&limit=50&country=$cc&term=${Uri.encode(term)}")?.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.has("previewUrl") }.map { toTrack(it) }.distinctBy { norm(it.name + it.artist) }
}

fun rssNovo(cc: String) = "https://rss.applemarketingtools.com/api/v2/$cc/music/most-played/50/songs.json"
fun rssGenero(cc: String, g: Int) = "https://itunes.apple.com/$cc/rss/topsongs/limit=50/genre=$g/json"

suspend fun playlistTracks(id: String): List<Track> = coroutineScope {
    val r: List<Track> = when (id) {
        "global" -> {
            val ls = listOf("us", "gb", "br", "mx", "de", "fr").map { cc -> async { songsByIds(chartIds(rssNovo(cc)).take(12), cc.uppercase()) } }.awaitAll()
            val out = mutableListOf<Track>()
            for (k in 0 until (ls.maxOfOrNull { it.size } ?: 0)) ls.forEach { l -> l.getOrNull(k)?.let { out.add(it) } }
            out.distinctBy { norm(it.name + it.artist) }
        }
        "brasil" -> songsByIds(chartIds(rssNovo("br")).take(50), "BR").ifEmpty { songsByIds(chartIds(rssGenero("br", 14)).take(50), "BR") }
        "pops" -> songsByIds(chartIds(rssGenero("us", 14)).take(50), "US")
        else -> songsByIds(chartIds(rssGenero("us", 15)).take(50), "US")
    }
    if (r.isNotEmpty()) r else when (id) {
        "global" -> searchSongs("top hits", "US")
        "brasil" -> searchSongs("hits brasil", "BR")
        "pops" -> searchSongs("pop hits", "US")
        else -> searchSongs("r&b soul hits", "US")
    }
}

// ---------- Os Melhores (perfis em destaque) ----------
data class Feat(val id: Long, val name: String, val photo: String?)
val FEAT = listOf(1754597620L to "Tocanna", 1804418508L to "Tuko Moedas", 1831894114L to "Asguizz", 1848389054L to "Porkani", 1766758983L to "Veada", 1755830864L to "Boar Carter")

suspend fun featured(id: Long, slug: String): Feat = coroutineScope {
    val nm = async { get("https://itunes.apple.com/lookup?id=$id")?.optJSONArray("results")?.optJSONObject(0)?.optString("artistName")?.takeIf { it.isNotEmpty() } }
    val lt = async { latest(id)?.album?.art }
    val name = nm.await() ?: slug
    Feat(id, name, lt.await() ?: photoOf(name))
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
        creditos(t.artist, t.name).ifEmpty { listOf(t.artist) }.forEach { n -> bump(o, "a", n, s, t.art, n, "") }
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
    var pos by mutableStateOf(0f); var posMs by mutableStateOf(0); var dur by mutableStateOf(30f)
    val cur get() = q.getOrNull(i)
    private var mp: MediaPlayer? = null
    private var ready = false
    private var fails = 0
    private var last = System.nanoTime()
    private val h = Handler(Looper.getMainLooper())
    private val loop = object : Runnable { override fun run() { tick(); h.postDelayed(this, 200) } }
    init { h.postDelayed(loop, 200) }

    fun play(l: List<Track>, k: Int) { q = l; i = k; fails = 0; load() }
    private fun fail() { fails++; if (fails < q.size) next() else playing = false }
    private fun load() {
        mp?.release(); mp = null
        val t = cur ?: return
        pos = 0f; posMs = 0; ready = false
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
    fun seek(f: Float) { if (ready) seekMs((f.coerceIn(0f, 1f) * dur * 1000).toInt()) }
    fun seekMs(ms: Int) { if (ready) { val x = ms.coerceIn(0, (dur * 1000).toInt()); mp?.seekTo(x); posMs = x; pos = x / 1000f } }
    fun nowMs(): Int = if (ready) (try { mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }) else 0
    private fun tick() {
        val now = System.nanoTime(); val dt = ((now - last) / 1e9f).coerceIn(0f, 1f); last = now
        val m = mp ?: return
        if (!ready) return
        val on = try { m.isPlaying } catch (e: Exception) { false }
        if (on) { val c = m.currentPosition; posMs = c; pos = c / 1000f; cur?.let { stats.add(it, dt) } }
    }
    fun release() { h.removeCallbacks(loop); mp?.release(); mp = null; stats.save() }
}

class MainActivity : ComponentActivity() {
    private lateinit var stats: Stats
    private lateinit var auth: Auth
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); enableEdgeToEdge()
        stats = Stats(applicationContext)
        auth = Auth(applicationContext)
        setContent { Root(stats, auth) }   // Root: carregando -> entrar/cadastrar -> App
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

// Vários artistas (feat, "A & B"): cada nome é um toque separado e abre o próprio perfil
@Composable fun ATs(artist: String, title: String = "", id: Long = 0, art: String? = null, sz: Int = 16, w: Int = 400, c: Color = Color.Unspecified, m: Modifier = Modifier, a: TextAlign? = null) {
    val nomes = remember(artist, title) { creditos(artist, title) }
    if (nomes.size <= 1) AT(nomes.firstOrNull() ?: artist, id, art, sz, w, c, m, 1, a)
    else Row(m, verticalAlignment = Alignment.CenterVertically) {
        nomes.forEachIndexed { k, n ->
            if (k > 0) T(if (k == nomes.size - 1) " & " else ", ", sz, w, c)
            AT(n, idDe(artist, k, id), art, sz, w, c, Modifier.weight(1f, false))
        }
    }
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

// ícone redondo de perfil: mostra a foto/inicial da conta e abre o perfil ao tocar
@Composable fun Av() {
    val me = LocalMe.current
    val p = LocalAuth.current?.prof
    val ini = (p?.name ?: "").take(1).uppercase()
    Box(Modifier.size(34.dp).tap { me() }.clip(CircleShape).background(Color(0xFF2C2C2E)), Alignment.Center) {
        if (ini.isNotEmpty()) T(ini, 15, 800, Color.White)
        if (p?.photo != null) AsyncImage(model = p.photo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
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
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(14.dp), Color.White); T(" beats", 12, 600, Color.White) }
        Column { T(t, sz, 800, Color.White, ml = 2); T(s, 11, 400, Color.White.copy(alpha = .85f), Modifier.padding(top = 4.dp), 2) }
    }

@Composable fun Cv(a: Album, open: (Album) -> Unit, m: Modifier = Modifier.width(118.dp)) = Column(m.tap { open(a) }) {
    Img(a.art, Modifier.fillMaxWidth().aspectRatio(1f)); T(a.name, 12, 600, m = Modifier.padding(top = 6.dp)); ATs(a.artist, "", a.artistId, a.art, 11, 400, Sub)
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

@Composable fun PlCard(p: PlDef, m: Modifier = Modifier, onClick: () -> Unit) =
    Column(m.tap(onClick).aspectRatio(.8f).clip(RoundedCornerShape(14.dp)).background(bg(p.c1, p.c2)).padding(14.dp), Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(14.dp), Color.White); T(" beats", 12, 600, Color.White) }
        Column { T(p.title.replaceFirst(" ", "\n"), 22, 800, Color.White, ml = 2); T(p.sub, 11, 400, Color.White.copy(alpha = .85f), Modifier.padding(top = 4.dp), 2) }
    }

typealias Sec = Map<String, List<Album>>

// ---------- Telas ----------
@Composable fun Home(sec: Sec, open: (Album) -> Unit, rp: () -> Unit, mix: (List<Album>) -> Unit, pls: (PlDef) -> Unit, feat: List<Feat>, appArt: List<Prof>, openApp: (Prof) -> Unit, rels: List<Rel> = emptyList()) = Scr("Início", { Av() }) {
    val go = LocalArtist.current
    T("Feito para Você", 12, 600, Sub); H("Seleção para Você")
    Rw {
        item { Pick("Mix de\nAmigos", "Card B, Offset, Kendrick Lamar, JID e mais", bg(0xFF2A7BFF, 0xFF0B2C8C), 200, 262, 36, Modifier.enter(0)) { mix(sec["hip hop"].orEmpty()) } }
        item { Pick("Todos os\nTempos", "Replay: suas músicas mais tocadas", bg(0xFFFF3B5C, 0xFFB8002A), 200, 262, 36, Modifier.enter(1), rp) }
        item { Pick("Mix\nRelax", "Faixas calmas para focar", bg(0xFF7AD0C6, 0xFF2F6F8F), 200, 262, 36, Modifier.enter(2)) { mix(sec["electronic"].orEmpty()) } }
    }
    if (rels.isNotEmpty()) {
        H("Lançamentos")
        Rw { itemsIndexed(rels) { k, r -> RelCard(r, Modifier.enter(k)) } }
    }
    H("Playlists do beats")
    Rw { itemsIndexed(PLS) { k, p -> PlCard(p, Modifier.width(165.dp).enter(k)) { pls(p) } } }
    H("Os Melhores")
    Rw { itemsIndexed(feat) { k, f ->
        Column(Modifier.width(96.dp).enter(k).tap { go(f.name, f.id, f.photo) }, horizontalAlignment = Alignment.CenterHorizontally) {
            Img(f.photo, Modifier.size(96.dp), CircleShape); T(f.name, 12, 600, m = Modifier.padding(top = 6.dp), a = TextAlign.Center)
        }
    } }
    if (appArt.isNotEmpty()) {
        H("Artistas do beats")
        Rw { itemsIndexed(appArt) { k, a ->
            Column(Modifier.width(96.dp).enter(k).tap { openApp(a) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2C2C2E)), Alignment.Center) {
                    T(a.name.take(1).uppercase(), 34, 800, Color.White)
                    if (a.photo != null) AsyncImage(model = a.photo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                T(a.name, 12, 600, m = Modifier.padding(top = 6.dp), a = TextAlign.Center)
            }
        } }
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
        Img(a.art, Modifier.fillMaxWidth().aspectRatio(1.3f), RoundedCornerShape(14.dp)); T(a.name, 17, 700, m = Modifier.padding(top = 8.dp)); ATs(a.artist, "", a.artistId, a.art, 14, 400, Sub) } }
    H("Últimos Lançamentos"); Grid(sec["hip hop"].orEmpty(), open)
}

@Composable fun Radio(sec: Sec, open: (Album) -> Unit, mix: (List<Album>) -> Unit) = Scr("Rádio", { Av() }) {
    Pick("Music 1", "Ao vivo, 24 horas por dia", bg(0xFFFF3B5C, 0xFF7A0618), 340, 200, 38, Modifier.enter(0)) { mix(sec["pop"].orEmpty() + sec["hip hop"].orEmpty()) }
    H("Estações")
    sec["r&b"].orEmpty().take(6).forEachIndexed { k, a ->
        Row(Modifier.fillMaxWidth().enter(k).tap { open(a) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Img(a.art, Modifier.size(54.dp)); Column(Modifier.padding(start = 14.dp).weight(1f)) { ATs(a.artist, "", a.artistId, a.art, 17); T("Ao vivo agora", 12, 400, Sub) }
            Icon(Icons.Filled.ChevronRight, null, tint = Sub)
        }
    }
}

@Composable fun Lib(sec: Sec, open: (Album) -> Unit) = Scr("Biblioteca", { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.MoreHoriz, null, tint = Red); Spacer(Modifier.width(12.dp)); Av() } }) {
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
            Img(a.art, Modifier.size(54.dp)); Column(Modifier.padding(start = 14.dp)) { T(a.name, 16, 600); ATs(a.artist, "", a.artistId, a.art, 13, 400, Sub) }
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
                    Column(Modifier.padding(start = 12.dp).weight(1f)) { T(s.name, 16, 600); ATs(s.sub, s.name, 0, s.img, 13, 400, Sub) }
                    T(tempo(s.secs), 12, 500, Sub)
                }
            }
        }
    }
    IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Red) }
}

// altura do degradê da capa do álbum: ela some atrás do título e termina exatamente nos botões (a partir dali é só o fundo desfocado)
val ZONA_ALBUM = 260.dp   // altura da sombra atrás do título e dos ícones

// Capa nítida + uma cópia DESFOCADA que invade a capa em degradê, sem nenhuma borda reta:
//  - `invade`: quanto acima do fim da capa o blur começa a aparecer
//  - `cheio`: daqui até o começo da dissolução o blur é 100% (fica atrás do título/ícones)
//  - `funde`: nos últimos `funde` dp tudo se dissolve devagar no fundo desfocado da tela (que é a mesma capa)
@Composable fun CapaBlur(u: String, escuro: Color, invade: Dp, cheio: Dp, funde: Dp, m: Modifier = Modifier.fillMaxSize()) {
    Box(m.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val f = funde.toPx()
        // curva suave (sem "quina"): 1 → 0 em vários passos
        drawRect(Brush.verticalGradient(0f to Color.Black, .3f to Color.Black.copy(alpha = .85f), .6f to Color.Black.copy(alpha = .45f), .85f to Color.Black.copy(alpha = .12f), 1f to Color.Transparent, startY = size.height - f, endY = size.height), blendMode = BlendMode.DstIn)
    }) {
        Img(hq(u), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val a = size.height - invade.toPx(); val b = size.height - cheio.toPx()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, .25f to Color.Black.copy(alpha = .1f), .5f to Color.Black.copy(alpha = .4f), .75f to Color.Black.copy(alpha = .8f), 1f to Color.Black, startY = a, endY = b), blendMode = BlendMode.DstIn)
        }) {
            AsyncImage(model = hq(u), contentDescription = null, modifier = Modifier.fillMaxSize().blur(60.dp), contentScale = ContentScale.Crop)
            // escurece aos poucos (igual ao fundo da tela), em vez de uma faixa escura
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, escuro.copy(alpha = .30f), escuro.copy(alpha = .55f)))))
        }
    }
}

@Composable fun AlbumS(al: Album, pl: Pl, back: () -> Unit) {
    val ctx = LocalContext.current
    val (c1, _) = rememberPal(al.art); var tr by remember { mutableStateOf(listOf<Track>()) }
    LaunchedEffect(al.id) { tr = tracks(al) }
    val escuro = lerp(c1, Color.Black, .6f)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // fundo fixo: a própria capa bem desfocada, escurecida com a cor dela (é o que aparece dos botões para baixo)
        AsyncImage(model = hq(al.art), contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }.blur(60.dp), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .15f), escuro.copy(alpha = .45f), escuro.copy(alpha = .80f)))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // capa de ponta a ponta; o blur invade a capa e fica por baixo do título E dos ícones (aleatório | Reproduzir | favorita)
            Box(Modifier.fillMaxWidth().aspectRatio(.70f)) {
                CapaBlur(al.art, escuro, invade = 360.dp, cheio = 250.dp, funde = 110.dp)
                Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent))))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(al.name, 28, 800, Color.White, ml = 2, a = TextAlign.Center)
                    ATs(al.artist, "", al.artistId, al.art, 20, 600, lerp(c1, Color.White, .55f))
                    T("${al.genre} · ${al.year}", 12, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
                    Acoes(tr, pl)   // aleatório | Reproduzir (pílula) | favorita
                    Spacer(Modifier.height(44.dp))   // os ícones ficam acima da dissolução final
                }
            }
            tr.forEachIndexed { k, t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).enter(k + 1).tap { pl.play(tr, k) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${k + 1}", 15, 400, Color.White.copy(alpha = .6f), Modifier.width(30.dp)); Column(Modifier.weight(1f)) { T(t.name, 16, 500, Color.White); if (creditos(t.artist, t.name).size > 1) ATs(t.artist, t.name, t.artistId, t.art, 12, 400, Color.White.copy(alpha = .6f)) }; Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White.copy(alpha = .7f))
                }
                HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = Color.White.copy(alpha = .15f))
            }
        }
        // voltar, compartilhar e mais, em círculos de vidro por cima da capa (como no Apple Music)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Glass(Icons.Filled.ArrowBackIosNew) { back() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Glass(Icons.Filled.Share) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Ouça ${al.name} de ${al.artist} no beats") }, null)) }
                Glass(Icons.Rounded.MoreHoriz) { }
            }
        }
    }
}

@Composable fun Glass(i: ImageVector, f: () -> Unit) =
    Box(Modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = .3f)).pointerInput(Unit) { detectTapGestures { f() } }, Alignment.Center) { Icon(i, null, Modifier.size(18.dp), Color.White) }

@Composable fun PlaylistS(pd: PlDef, pl: Pl, openBeats: () -> Unit, back: () -> Unit) {
    var tr by remember { mutableStateOf(listOf<Track>()) }; var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(pd.id) { tr = playlistTracks(pd.id); loaded = true }
    val c1 = Color(pd.c1)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, lerp(c1, Color.Black, .55f), Color.Black)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 56.dp).enter(0).size(250.dp).shadow(28.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(bg(pd.c1, pd.c2)).padding(16.dp)) {
                Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(16.dp), Color.White); T(" beats", 13, 600, Color.White) }
                T(pd.title, 34, 800, Color.White, Modifier.align(Alignment.BottomStart), 2)
            }
            T(pd.title, 22, 700, Color.White, Modifier.padding(top = 18.dp), 2, TextAlign.Center)
            T("beats", 19, 600, lerp(c1, Color.White, .55f), Modifier.pointerInput(Unit) { detectTapGestures { openBeats() } })
            T(if (tr.isEmpty()) (if (loaded) "Playlist indisponível no momento" else "Carregando…") else "Playlist · ${tr.size} músicas", 12, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
            Acoes(tr, pl)   // aleatório | Reproduzir | favorita
            tr.forEachIndexed { k, t ->
                Row(Modifier.fillMaxWidth().enter(k + 1).tap { pl.play(tr, k) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${k + 1}", 15, 400, Color.White.copy(alpha = .6f), Modifier.width(30.dp)); Img(t.art, Modifier.size(44.dp), RoundedCornerShape(6.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) { T(t.name, 16, 500, Color.White); ATs(t.artist, t.name, t.artistId, t.art, 13, 400, Color.White.copy(alpha = .6f)) }
                    Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White.copy(alpha = .7f))
                }
                HorizontalDivider(color = Color.White.copy(alpha = .15f))
            }
        }
        IconButton(back, Modifier.statusBarsPadding().padding(start = 6.dp, top = 6.dp)) { Icon(Icons.Filled.ArrowBackIosNew, null, tint = Color.White) }
    }
}

// Perfil da plataforma: só playlists disponíveis
@Composable fun BeatsS(open: (PlDef) -> Unit, back: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 200.dp)) {
            Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(Color(0xFFFA243C), Color(0xFF5A0A18), Color.Black)))) {
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("beats", 48, 800, Color.White, a = TextAlign.Center); T("Playlists oficiais", 14, 500, Color.White.copy(alpha = .7f), Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.padding(horizontal = 18.dp).padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { T("Playlists", 19, 700, Color.White); Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(alpha = .6f)) }
            PLS.chunked(2).forEachIndexed { r, row ->
                Row(Modifier.padding(horizontal = 18.dp).padding(bottom = 12.dp), Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { k, p -> PlCard(p, Modifier.weight(1f).enter(r * 2 + k)) { open(p) } }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Box(Modifier.statusBarsPadding().padding(start = 14.dp, top = 8.dp)) { Glass(Icons.Filled.ArrowBackIosNew) { back() } }
    }
}

// Perfil do artista (estilo Apple Music): foto de fundo + blur em degradê atrás dos botões
@Composable fun ArtistS(p: Page.Art, pl: Pl, open: (Album) -> Unit, mine: Boolean = false, edit: () -> Unit = {}, back: () -> Unit) {
    val ctx = LocalContext.current
    val auth = LocalAuth.current
    var d by remember(p) { mutableStateOf<ArtistData?>(null) }
    var claimedProf by remember(p) { mutableStateOf<Prof?>(null) }
    LaunchedEffect(p) {
        // artista criado no app (sem iTunes): não busca por nome, para não cair no perfil errado
        if (p.app && !(mine && p.id != 0L)) d = ArtistData(p.art, null, 0, "", emptyList())
        else {
            d = artistData(p.name, p.id)
            if (!mine && p.id != 0L) claimedProf = profileByItunes(p.id)   // foto e música em destaque escolhidas pelo dono do perfil reivindicado
        }
    }
    // lançamentos feitos no iStudio por esse artista
    var rels by remember(p) { mutableStateOf(listOf<Rel>()) }
    val relUid = if (mine) auth?.uid else (p.uid ?: claimedProf?.id)
    LaunchedEffect(relUid) { if (relUid != null) releasesLoad(relUid)?.let { rels = it } }
    val relTracks = rels.flatMap { it.tracks }
    val minhaFoto = if (mine) auth?.prof?.photo else null
    val photo = minhaFoto ?: claimedProf?.photo ?: d?.let { it.photo ?: p.art }
    val dest: Track? = if (mine) auth?.prof?.featured else (claimedProf?.featured ?: p.feat)   // música em destaque do artista
    val base = rememberMuted(photo)
    val top = lerp(base, Color.Black, .3f); val bot = lerp(base, Color.Black, .8f)
    val songs = d?.top.orEmpty().ifEmpty { relTracks }   // artista só do app: mostra as músicas lançadas
    val lt = d?.latest
    Box(Modifier.fillMaxSize().background(top)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(560.dp)) {
                if (photo != null) {
                    AsyncImage(model = photo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    // cópia desfocada da foto, revelada em degradê de baixo para cima (sem linha de divisão)
                    Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.Transparent, .5f to Color.Transparent, .85f to Color.Black), blendMode = BlendMode.DstIn) }
                        .blur(32.dp)) {
                        AsyncImage(model = photo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    }
                }
                Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent, .8f to top.copy(alpha = .6f), 1f to top)))
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .28f)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarToday, null, Modifier.size(12.dp), Color.White); T("  Próximos Shows", 11, 600, Color.White)
                    }
                    T(p.name, 36, 700, Color.White, Modifier.padding(top = 8.dp), 2, TextAlign.Center)
                    if (mine) Pilula("Editar perfil", Modifier.padding(top = 14.dp), icon = Icons.Filled.Edit, on = edit)
                    else Row(Modifier.fillMaxWidth().padding(top = 14.dp), Arrangement.Center, Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(24.dp), Color.White.copy(alpha = .85f)); Spacer(Modifier.width(30.dp))
                        Box(Modifier.size(66.dp).tap { if (songs.isNotEmpty()) pl.play(songs, 0) }.clip(CircleShape).background(Color.White), Alignment.Center) { IcPlay(36.dp, Color.Black) }
                        Spacer(Modifier.width(30.dp)); Icon(Icons.Rounded.StarBorder, null, Modifier.size(26.dp), Color.White.copy(alpha = .85f))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(top, bot))).padding(bottom = 200.dp)) {
                if (dest != null) Row(Modifier.padding(horizontal = 18.dp).padding(top = 4.dp).fillMaxWidth().enter(0).tap { pl.play(listOf(dest), 0) }.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .14f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Img(dest.art, Modifier.size(54.dp), RoundedCornerShape(6.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        T("Em destaque", 11, 500, Color.White.copy(alpha = .6f)); T(dest.name, 16, 700, Color.White)
                        T(if (dest.album.isNotEmpty()) dest.album else dest.artist, 12, 400, Color.White.copy(alpha = .6f))
                    }
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)), Alignment.Center) { IcPlay(18.dp, Color.White) }
                } else if (lt != null) Row(Modifier.padding(horizontal = 18.dp).padding(top = 4.dp).fillMaxWidth().enter(0).tap { open(lt) }.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .14f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Img(lt.art, Modifier.size(54.dp), RoundedCornerShape(6.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        T(d?.latestDate.orEmpty(), 11, 500, Color.White.copy(alpha = .6f)); T(lt.name, 16, 700, Color.White)
                        T("${d?.latestCount ?: 0} ${if ((d?.latestCount ?: 0) == 1) "música" else "músicas"}", 12, 400, Color.White.copy(alpha = .6f))
                    }
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)), Alignment.Center) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp), Color.White) }
                }
                if (rels.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 18.dp).padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { T("Lançamentos", 19, 700, Color.White); Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(alpha = .6f)) }
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(rels) { k, r -> RelCard(r, Modifier.enter(k), Color.White, Color.White.copy(alpha = .6f), "${r.label} · ${r.date.take(4)}", 130) }
                    }
                }
                Row(Modifier.padding(horizontal = 18.dp).padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { T(if (d?.top.isNullOrEmpty() && relTracks.isNotEmpty()) "Músicas" else "Músicas Mais Tocadas", 19, 700, Color.White); Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(alpha = .6f)) }
                songs.forEachIndexed { k, t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).enter(k).tap { pl.play(songs, k) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Img(t.art, Modifier.size(46.dp), RoundedCornerShape(6.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T(t.name, 16, 600, Color.White, Modifier.weight(1f, false))
                                if (t.explicit) Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .3f)).padding(horizontal = 4.dp)) { T("E", 10, 700, Color.White) }
                            }
                            if (creditos(t.artist, t.name).size > 1) ATs(t.artist, t.name, t.artistId, t.art, 13, 400, Color.White.copy(alpha = .6f)) else T(if (t.year.isNotEmpty()) "${t.album} · ${t.year}" else t.album, 13, 400, Color.White.copy(alpha = .6f))
                        }
                        Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White.copy(alpha = .7f))
                    }
                }
                if (d == null) T("Carregando…", 13, 400, Color.White.copy(alpha = .6f), Modifier.padding(18.dp))
                else if (songs.isEmpty() && p.app && rels.isEmpty()) T("Ainda sem músicas por aqui.", 13, 400, Color.White.copy(alpha = .6f), Modifier.padding(18.dp))
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Glass(Icons.Filled.ArrowBackIosNew) { back() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Glass(Icons.Filled.Share) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Ouça ${p.name} no beats") }, null)) }
                Glass(Icons.Rounded.MoreHoriz) { }
            }
        }
    }
}

// ---------- Tela de letra (como no Apple Music) ----------
const val ATRASO_TOQUE = 450L   // compensa o tempo de reação de quem toca na linha para marcar o trecho

class LyUi { var on by mutableStateOf(false); var trans by mutableStateOf(false) }

// guarda no celular onde a prévia começa em cada música (marca uma vez e pronto)
class SyncStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lyrics_sync", Context.MODE_PRIVATE)
    fun get(k: String): Long? = if (sp.contains(k)) sp.getLong(k, 0L) else null
    fun set(k: String, v: Long) { sp.edit().putLong(k, v).apply() }
    fun clear(k: String) { sp.edit().remove(k).apply() }
}

@Composable fun LBtn(on: Boolean, f: () -> Unit, c: @Composable () -> Unit) =
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = if (on) .92f else .18f)).clickable(onClick = f), Alignment.Center) { c() }

@Composable fun LinhaLetra(ln: Ly, tr: String?, alvo: Float, desf: Dp, onTap: () -> Unit) {
    val a by animateFloatAsState(alvo, tween(350), label = "la")
    val bl by animateDpAsState(desf, tween(350), label = "lb")
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).graphicsLayer { alpha = a }.blur(bl).pointerInput(ln) { detectTapGestures { onTap() } }) {
        T(ln.txt, 28, 800, Color.White, ml = 6)
        if (!tr.isNullOrBlank() && norm(tr) != norm(ln.txt)) T(tr, 17, 600, Color.White.copy(alpha = .75f), Modifier.padding(top = 4.dp), 4)
    }
}

@Composable fun Letra(pl: Pl, t: Track, ui: LyUi) {
    val ctx = LocalContext.current
    val store = remember { SyncStore(ctx.applicationContext) }
    val key = remember(t) { norm(t.name) + "|" + norm(t.artist) }
    var lrc by remember(key) { mutableStateOf<Lrc?>(null) }
    var feito by remember(key) { mutableStateOf(false) }
    var off by remember(key) { mutableStateOf(store.get(key)) }
    LaunchedEffect(key) { lrc = lrcDe(t); feito = true }

    val durMs = (pl.dur * 1000).toLong()
    val temSync = lrc?.synced?.isNotEmpty() == true
    val o = off
    val modoSync = temSync && o != null          // trecho já marcado: só as linhas dos 30 s e acompanhando o áudio
    val modoMarcar = temSync && o == null        // ainda não marcou: mostra a letra toda para tocar na linha atual

    val shown = remember(lrc, o, durMs) {
        val l = lrc
        when {
            l == null -> emptyList<Ly>()
            l.synced.isEmpty() -> l.plain.map { Ly(0L, it) }
            o == null -> l.synced
            else -> l.synced.filter { it.ms >= o - 1500 && it.ms <= o + durMs + 500 }
        }
    }
    // linha atual = última cujo tempo já passou (com 0,3 s de antecedência)
    val cur by remember(shown, o, modoSync) { derivedStateOf { if (!modoSync || o == null) -1 else { val g = pl.posMs + o + 300L; var k = -1; for (i in shown.indices) { if (shown[i].ms <= g) k = i else break }; k } } }

    // tradução (Google Tradutor) só das linhas que estão na tela
    var trad by remember(shown) { mutableStateOf<List<String>?>(null) }
    var traduzindo by remember(shown) { mutableStateOf(false) }
    LaunchedEffect(ui.trans, shown) {
        if (ui.trans && trad == null && shown.isNotEmpty()) {
            traduzindo = true
            trad = traduzir(shown.map { it.txt }, Locale.getDefault().language)
            traduzindo = false
        }
    }

    val ls = rememberLazyListState()
    LaunchedEffect(key, modoSync) { ls.scrollToItem(0) }
    LaunchedEffect(cur) { if (modoSync && cur >= 0) ls.animateScrollToItem(cur, -(ls.layoutInfo.viewportSize.height / 7)) }

    fun tapLine(ln: Ly) {
        if (lrc?.synced?.isNotEmpty() != true) return
        val a = off
        if (a == null) { val v = (ln.ms - pl.nowMs() + ATRASO_TOQUE).coerceAtLeast(0L); off = v; store.set(key, v) }
        else pl.seekMs((ln.ms - a).toInt().coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Img(t.art, Modifier.size(52.dp), RoundedCornerShape(6.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                T(t.name, 17, 600, Color.White)
                ATs(t.artist, t.name, t.artistId, t.art, 15, 400, Color.White.copy(alpha = .7f))
            }
            if (feito && shown.isNotEmpty()) {
                LBtn(ui.trans, { ui.trans = !ui.trans }) {
                    if (ui.trans && traduzindo) CircularProgressIndicator(Modifier.size(18.dp), Color.Black, 2.dp)
                    else Icon(Icons.Filled.Translate, null, Modifier.size(19.dp), if (ui.trans) Color.Black else Color.White)
                }
                Spacer(Modifier.width(8.dp))
            }
            LBtn(false, {}) { Icon(Icons.Rounded.StarBorder, null, Modifier.size(19.dp), Color.White) }
            Spacer(Modifier.width(8.dp))
            LBtn(false, {}) { Icon(Icons.Rounded.MoreHoriz, null, Modifier.size(19.dp), Color.White) }
        }
        if (modoMarcar) T("Toque na linha que está tocando agora para marcar onde a prévia começa na música.", 12, 500, Color.White.copy(alpha = .7f), Modifier.padding(top = 10.dp), 2)
        else if (feito && lrc != null && !temSync) T("Esta letra não tem tempos, então não acompanha a música.", 12, 500, Color.White.copy(alpha = .7f), Modifier.padding(top = 10.dp), 2)

        Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.Transparent, .06f to Color.Black, .86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) }) {
            when {
                !feito -> T("Carregando letra…", 15, 500, Color.White.copy(alpha = .7f), Modifier.align(Alignment.Center))
                shown.isEmpty() -> T(if (lrc == null) "Letra indisponível para esta música" else "Sem letra neste trecho da prévia", 15, 500, Color.White.copy(alpha = .7f), Modifier.align(Alignment.Center), 3, TextAlign.Center)
                else -> LazyColumn(state = ls, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp)) {
                    itemsIndexed(shown) { k, ln ->
                        val alvo = if (!modoSync) .85f else if (cur < 0) .5f else if (k == cur) 1f else (.45f - .08f * (Math.abs(k - cur) - 1)).coerceAtLeast(.18f)
                        val desf = if (modoSync && cur >= 0 && k != cur) (minOf(Math.abs(k - cur), 4) * 1.3f).dp else 0.dp
                        LinhaLetra(ln, if (ui.trans) trad?.getOrNull(k) else null, alvo, desf) { tapLine(ln) }
                    }
                }
            }
        }
        if (modoSync) Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.Center) {
            Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .18f)).clickable { store.clear(key); off = null }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp), Color.White); T("  Trecho da prévia · Resincronizar", 12, 600, Color.White)
            }
        }
    }
}

// ---------- Ícones desenhados à mão (iguais aos do Apple Music) ----------
// polígono com cantos arredondados: preenche + contorno redondo (os pontos já vêm recuados em k)
private fun DrawScope.polyR(c: Color, k: Float, q: List<Offset>) {
    val p = Path(); p.moveTo(q[0].x, q[0].y)
    for (i in 1 until q.size) p.lineTo(q[i].x, q[i].y)
    p.close()
    drawPath(p, c)
    drawPath(p, c, style = Stroke(width = k * 2, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// triângulo arredondado que cabe exatamente na caixa l,t,r,b (dir = true aponta para a direita)
private fun DrawScope.triR(l: Float, t: Float, r: Float, b: Float, dir: Boolean, c: Color, k: Float) {
    val ym = (t + b) / 2
    if (dir) polyR(c, k, listOf(Offset(l + k, t + k), Offset(r - k, ym), Offset(l + k, b - k)))
    else polyR(c, k, listOf(Offset(r - k, t + k), Offset(l + k, ym), Offset(r - k, b - k)))
}

@Composable fun IcPlay(sz: Dp, c: Color = Color.White) = Canvas(Modifier.size(sz)) {
    val s = size.minDimension
    triR(s * .16f, s * .08f, s * .90f, s * .92f, true, c, s * .09f)
}

@Composable fun IcPause(sz: Dp, c: Color = Color.White) = Canvas(Modifier.size(sz)) {
    val s = size.minDimension
    val w = s * .25f; val g = s * .17f; val h = s * .84f
    val x = (s - (2 * w + g)) / 2; val y = (s - h) / 2
    drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(w * .42f))
    drawRoundRect(c, Offset(x + w + g, y), Size(w, h), CornerRadius(w * .42f))
}

// voltar / avançar: dois triângulos arredondados colados
@Composable fun IcSkip(sz: Dp, fwd: Boolean, c: Color = Color.White) = Canvas(Modifier.size(sz)) {
    val s = size.minDimension
    val h = s * .62f; val t = (s - h) / 2; val w = s * .52f; val k = s * .07f
    triR(0f, t, w, t + h, fwd, c, k)
    triR(s - w, t, s, t + h, fwd, c, k)
}

// lista (fila de reprodução)
@Composable fun IcQueue(sz: Dp, c: Color = Color.White) = Canvas(Modifier.size(sz)) {
    val u = size.minDimension / 24f
    listOf(6f, 12f, 18f).forEach { y ->
        drawCircle(c, 1.5f * u, Offset(4.2f * u, y * u))
        drawLine(c, Offset(9f * u, y * u), Offset(20.5f * u, y * u), 2f * u, StrokeCap.Round)
    }
}

// AirPlay de áudio: círculo, dois arcos e triângulo embaixo
@Composable fun IcAirplay(sz: Dp, c: Color = Color.White) = Canvas(Modifier.size(sz)) {
    val u = size.minDimension / 24f
    val st = Stroke(width = 1.9f * u, cap = StrokeCap.Round)
    fun arco(r: Float) = drawArc(c, 140f, 260f, false, Offset((12f - r) * u, (10f - r) * u), Size(2 * r * u, 2 * r * u), style = st)
    arco(4.6f); arco(8.6f)
    drawCircle(c, 1.7f * u, Offset(12f * u, 10f * u))
    val k = .8f * u
    polyR(c, k, listOf(Offset(12f * u, 14.6f * u + k), Offset(7.4f * u + k, 21.6f * u - k), Offset(16.6f * u - k, 21.6f * u - k)))
}

// letra: balão de fala com aspas
@Composable fun IcLyrics(sz: Dp, c: Color = Color.White) = Canvas(Modifier.size(sz)) {
    val u = size.minDimension / 24f
    val p = Path()
    p.moveTo(11.2f * u, 17.4f * u); p.lineTo(16.6f * u, 17.4f * u)
    p.quadraticBezierTo(20.6f * u, 17.4f * u, 20.6f * u, 13.4f * u); p.lineTo(20.6f * u, 7.4f * u)
    p.quadraticBezierTo(20.6f * u, 3.4f * u, 16.6f * u, 3.4f * u); p.lineTo(7.4f * u, 3.4f * u)
    p.quadraticBezierTo(3.4f * u, 3.4f * u, 3.4f * u, 7.4f * u); p.lineTo(3.4f * u, 13.4f * u)
    p.quadraticBezierTo(3.4f * u, 17.4f * u, 7.4f * u, 17.4f * u)
    p.lineTo(6.2f * u, 21.2f * u); p.close()
    drawPath(p, c, style = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    listOf(9.4f, 14.6f).forEach { x ->
        drawCircle(c, 1.35f * u, Offset(x * u, 9.2f * u))
        drawLine(c, Offset(x * u, 9.2f * u), Offset((x - 1.2f) * u, 12.3f * u), 1.7f * u, StrokeCap.Round)
    }
}

// barra bem arredondada (progresso e volume): engorda enquanto o dedo está nela
// onDrag = valor ao vivo enquanto arrasta | onDone = valor final (toque ou soltar o dedo)
@Composable fun RBar(frac: Float, onDrag: (Float) -> Unit, onDone: (Float) -> Unit, m: Modifier = Modifier) {
    var down by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(0f) }
    val h by animateDpAsState(if (down) 14.dp else 8.dp, tween(150), label = "rb")
    Box(m.height(28.dp)
        .pointerInput(Unit) { detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { onDone((it.x / size.width).coerceIn(0f, 1f)) }) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { down = true; last = (it.x / size.width).coerceIn(0f, 1f); onDrag(last) },
                onDragEnd = { down = false; onDone(last) },
                onDragCancel = { down = false }
            ) { ch, _ -> last = (ch.position.x / size.width).coerceIn(0f, 1f); onDrag(last) }
        }, Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(Color.White.copy(alpha = .28f)))
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(h).clip(CircleShape).background(Color.White.copy(alpha = .9f)))
    }
}

// botão redondo das telas de álbum/playlist (aleatório, favorita)
@Composable fun CBtn(i: ImageVector, tint: Color = Color.White, f: () -> Unit) =
    Box(Modifier.size(48.dp).tap(f).clip(CircleShape).background(Color.White.copy(alpha = .2f)), Alignment.Center) { Icon(i, null, Modifier.size(22.dp), tint) }

// aleatório | Reproduzir (em ordem) | favorita
@Composable fun Acoes(tr: List<Track>, pl: Pl) {
    var fav by remember { mutableStateOf(false) }
    Row(Modifier.padding(vertical = 18.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        CBtn(Icons.Rounded.Shuffle) { if (tr.isNotEmpty()) pl.play(tr.shuffled(), 0) }
        Row(Modifier.height(48.dp).tap { if (tr.isNotEmpty()) pl.play(tr, 0) }.clip(CircleShape).background(Color.White).padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            IcPlay(20.dp, Color.Black); T(" Reproduzir", 17, 600, Color.Black)
        }
        CBtn(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (fav) Red else Color.White) { fav = !fav }
    }
}

@Composable fun NowBtn(i: ImageVector) =
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f)), Alignment.Center) { Icon(i, null, Modifier.size(18.dp), Color.White) }

// controle de volume (volume de mídia do aparelho), com alto-falante nas duas pontas como no Apple Music
@Composable fun VolumeBar() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var v by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { if (!dragging) v = am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat(); delay(400) } }
    fun set(f: Float) { v = f.coerceIn(0f, 1f); am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeMute, null, Modifier.size(20.dp), Color.White.copy(alpha = .7f))
        Spacer(Modifier.width(12.dp))
        RBar(v, { dragging = true; set(it) }, { set(it); dragging = false }, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Rounded.VolumeUp, null, Modifier.size(20.dp), Color.White.copy(alpha = .7f))
    }
}

// altura do degradê em que a capa nítida vira o fundo desfocado: termina embaixo do nome da música (aumente para o blur começar mais cedo)
val ZONA_BLUR = 120.dp

// pede a capa em alta resolução (a capa é esticada na tela inteira)
fun hq(u: String) = u.replace("300x300", "1000x1000").replace("600x600", "1000x1000")

@Composable fun Now(pl: Pl, ui: LyUi, close: () -> Unit) {
    val t = pl.cur ?: return
    val (c1, _) = rememberPal(t.art)
    val tint = lerp(c1, Color.White, .6f)
    val escuro = lerp(c1, Color.Black, .6f)
    var scrub by remember { mutableStateOf<Float?>(null) }   // posição enquanto o dedo arrasta a barra
    val alvo = scrub ?: (if (pl.dur > 0) pl.pos / pl.dur else 0f)
    val fr by animateFloatAsState(alvo, if (scrub != null) snap<Float>() else tween(220, easing = LinearEasing), label = "f")
    var drag by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (drag > 160) close(); drag = 0f }) { _, dy -> drag += dy } }) {
        // fundo: a própria capa, bem desfocada e escurecida com a cor dela (é o que aparece embaixo do nome da música)
        Crossfade(t.art, Modifier.fillMaxSize(), label = "bg") { u ->
            AsyncImage(model = hq(u), contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }.blur(60.dp), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .10f), escuro.copy(alpha = .40f), escuro.copy(alpha = .78f)))))
        Column(Modifier.fillMaxSize()) {
            Crossfade(ui.on, Modifier.weight(1f).fillMaxWidth(), label = "ly") { l ->
                if (l) Box(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 26.dp).padding(top = 30.dp)) { Letra(pl, t, ui) }
                else Box(Modifier.fillMaxSize()) {
                    // capa nítida em cima; o blur invade a capa e fica 100% atrás do nome da música, dissolvendo no fundo desfocado
                    Crossfade(t.art, Modifier.fillMaxSize(), label = "cv") { u -> CapaBlur(u, escuro, invade = 340.dp, cheio = 160.dp, funde = 110.dp) }
                    Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent))))
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 26.dp).padding(bottom = 46.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        AnimatedContent(t, Modifier.weight(1f), transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) }, label = "tt") { x ->
                            Column { T(x.name, 22, 700, Color.White); ATs(x.artist, x.name, x.artistId, x.art, 18, 400, tint) }
                        }
                        Spacer(Modifier.width(12.dp)); NowBtn(Icons.Rounded.StarBorder); Spacer(Modifier.width(10.dp)); NowBtn(Icons.Rounded.MoreHoriz)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 26.dp).padding(bottom = 10.dp)) {
                RBar(fr, { scrub = it }, { pl.seek(it); scrub = null }, Modifier.padding(top = 14.dp).fillMaxWidth())
                val sc = scrub
                val ps = if (sc != null) sc * pl.dur else pl.pos
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    T(fmt(ps.toInt()), 12, 500, Color.White.copy(alpha = .7f))
                    T("Mixing", 12, 600, Color.White.copy(alpha = .7f))
                    T("-" + fmt((pl.dur - ps).toInt().coerceAtLeast(0)), 12, 500, Color.White.copy(alpha = .7f))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                    Box(Modifier.size(76.dp).tap { pl.prev() }, Alignment.Center) { IcSkip(44.dp, false) }
                    Box(Modifier.size(84.dp).tap { pl.toggle() }, Alignment.Center) { Crossfade(pl.playing, label = "pp") { p -> if (p) IcPause(54.dp) else IcPlay(54.dp) } }
                    Box(Modifier.size(76.dp).tap { pl.next() }, Alignment.Center) { IcSkip(44.dp, true) }
                }
                VolumeBar()
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (ui.on) Color.White.copy(alpha = .28f) else Color.Transparent).clickable { ui.on = !ui.on }, Alignment.Center) {
                        IcLyrics(24.dp, Color.White.copy(alpha = if (ui.on) 1f else .85f))
                    }
                    Box(Modifier.size(44.dp), Alignment.Center) { IcAirplay(24.dp, Color.White.copy(alpha = .85f)) }
                    Box(Modifier.size(44.dp), Alignment.Center) { IcQueue(24.dp, Color.White.copy(alpha = .85f)) }
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp).size(42.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = .45f)).clickable(onClick = close))
    }
}

@Composable fun App(stats: Stats, auth: Auth) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val bgc = if (dark) Color.Black else Color.White; val tx = if (dark) Color.White else Color.Black
    val sf = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val pl = remember { Pl(ctx.applicationContext, stats) }
    DisposableEffect(Unit) { onDispose { pl.release() } }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }; var np by remember { mutableStateOf(false) }   // np = false: o player completo começa fechado
    val ly = remember { LyUi() }
    val stack = remember { mutableStateListOf<Page>() }
    val sec = remember { mutableStateMapOf<String, List<Album>>() }
    val feat = remember { mutableStateListOf<Feat>().apply { addAll(FEAT.map { Feat(it.first, it.second, null) }) } }
    LaunchedEffect(Unit) { FEAT.forEachIndexed { k, f -> launch { feat[k] = featured(f.first, f.second) } } }
    LaunchedEffect(Unit) { listOf("pop", "hip hop", "r&b", "rock", "electronic").forEach { k -> launch { sec[k] = albums(k) } } }
    // artistas criados no app: aparecem para todos e atualizam quando alguém edita o perfil
    var appArt by remember { mutableStateOf(listOf<Prof>()) }
    LaunchedEffect(auth.prof) { appArt = appArtists() }
    // lista "Lançamentos" da Início: recarrega ao voltar de outra tela (assim aparece o que acabou de ser publicado)
    var rels by remember { mutableStateOf(listOf<Rel>()) }
    LaunchedEffect(auth.prof, stack.size) { releasesLoad()?.let { rels = it } }
    // "Os Melhores": quem reivindicou o perfil escolhe a foto, que vale mais que a do iTunes
    var claimed by remember { mutableStateOf(mapOf<Long, Prof>()) }
    LaunchedEffect(auth.prof) { claimed = claimedByItunes(FEAT.map { it.first }) }
    val featShown = feat.map { f -> claimed[f.id]?.photo?.let { f.copy(photo = it) } ?: f }
    BackHandler(np || stack.isNotEmpty()) { if (np) np = false else stack.removeAt(stack.lastIndex) }
    val open: (Album) -> Unit = { stack.add(Page.Alb(it)) }
    val mix: (List<Album>) -> Unit = { l -> scope.launch { val t = l.shuffled().take(3).flatMap { tracks(it) }.shuffled(); if (t.isNotEmpty()) pl.play(t, 0) } }
    val openArtist: (String, Long, String?) -> Unit = { n, id, art ->
        np = false
        val t = stack.lastOrNull()
        if (!(t is Page.Art && t.name == n)) {
            val ap = if (id == 0L) appArt.firstOrNull { it.name == n } else null   // artista criado no app: abre o perfil dele, não o do iTunes
            stack.add(if (ap != null) Page.Art(ap.name, ap.itunesId, ap.photo, ap.itunesId == 0L, ap.featured, ap.id) else Page.Art(n, id, art))
        }
    }
    val openPl: (PlDef) -> Unit = { stack.add(Page.Plst(it)) }
    val openBeats: () -> Unit = { if (stack.lastOrNull() !is Page.Beats) stack.add(Page.Beats) }
    val openMe: () -> Unit = { np = false; if (stack.lastOrNull() !is Page.Me) stack.add(Page.Me) }
    val openApp: (Prof) -> Unit = { p -> np = false; stack.add(Page.Art(p.name, p.itunesId, p.photo, p.itunesId == 0L, p.featured, p.id)) }
    val sub = { stack.isNotEmpty() }

    // barra de navegação: tocando música, ela encolhe (ícone da aba + mini-player + busca) e fica na cor da capa em versão escura
    val tabs = listOf(Icons.Filled.Home to "Início", Icons.Filled.GridView to "Novidades", Icons.Filled.Sensors to "Rádio", Icons.Filled.LibraryMusic to "Biblioteca")
    val (pc1, _) = rememberPal(pl.cur?.art)
    val navBg by animateColorAsState(if (pl.cur != null) lerp(pc1, Color.Black, .68f) else sf, tween(500), label = "nb")
    var navOpen by remember { mutableStateOf(false) }   // toque no ícone da aba reabre as 4 abas
    val compact = pl.cur != null && !navOpen
    LaunchedEffect(pl.cur == null) { if (pl.cur == null) navOpen = false }

    CompositionLocalProvider(LocalContentColor provides tx, LocalArtist provides openArtist, LocalAuth provides auth, LocalMe provides openMe, LocalRel provides { r: Rel -> np = false; stack.add(Page.Lan(r)) }) {
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
                        Page.Me -> {
                            val pr = auth.prof
                            // artista: a mesma tela de perfil de artista, mas editável; ouvinte: tela da conta
                            if (pr != null && pr.artist) ArtistS(Page.Art(pr.name, pr.itunesId, pr.photo, true, pr.featured, pr.id), pl, open, true, { stack.add(Page.Edit) }) { back() }
                            else AccountS(auth) { back() }
                        }
                        Page.Edit -> EditarS(auth, { stack.add(Page.IStudio) }) { back() }
                        Page.IStudio -> IStudioS(
                            auth.prof?.name ?: "",
                            { auth.jwt },
                            { auth.uid }
                        ) { back() }
                        is Page.Lan -> RelS(pg.r, pl) { back() }
                        Page.Rep -> Replay(stats) { back() }
                        is Page.Plst -> PlaylistS(pg.p, pl, openBeats) { back() }
                        Page.Beats -> BeatsS(openPl) { back() }
                        null -> when (d.tab) { 0 -> Home(sec, open, { stack.add(Page.Rep) }, mix, openPl, featShown, appArt, openApp, rels); 1 -> NewS(sec, open); 2 -> Radio(sec, open, mix); 3 -> Lib(sec, open); else -> SearchS(open) }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
                AnimatedContent(compact, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "navmode") { cp ->
                    if (cp) Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        // esquerda: ícone da aba atual (toque para escolher outra aba)
                        val ic = tabs[tab.coerceAtMost(3)].first
                        Box(Modifier.size(54.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(navBg).clickable { navOpen = true }, Alignment.Center) {
                            Icon(ic, null, Modifier.size(24.dp), if (tab < 4 && !sub()) Red else Color.White)
                        }
                        // meio: mini-player (toque abre o player completo)
                        pl.cur?.let { t ->
                            Row(Modifier.weight(1f).height(54.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(navBg).clickable { np = true }.padding(start = 7.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Img(t.art, Modifier.size(40.dp), RoundedCornerShape(10.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    T(t.name, 14, 700, Color.White)
                                    T(t.artist, 12, 400, Color.White.copy(alpha = .6f))
                                }
                                Box(Modifier.size(44.dp).clip(CircleShape).clickable { pl.toggle() }, Alignment.Center) { Crossfade(pl.playing, label = "m") { p -> if (p) IcPause(24.dp) else IcPlay(24.dp) } }
                            }
                        }
                        // direita: busca
                        Box(Modifier.size(54.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(navBg).clickable { stack.clear(); tab = 4 }, Alignment.Center) { Icon(Icons.Filled.Search, null, Modifier.size(24.dp), Red) }
                    } else Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).height(60.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(navBg).padding(3.dp)) {
                            tabs.forEachIndexed { k, (i, n) ->
                                val on = tab == k && !sub()
                                val col by animateColorAsState(if (on) Red else Sub, label = "ic"); val bk by animateColorAsState(if (on) Color.Gray.copy(alpha = .2f) else Color.Transparent, label = "bk")
                                Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bk).clickable { stack.clear(); tab = k; navOpen = false }, Arrangement.Center, Alignment.CenterHorizontally) {
                                    Icon(i, null, Modifier.size(22.dp), col); T(n, 9, 600, col)
                                }
                            }
                        }
                        Box(Modifier.size(54.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(navBg).clickable { stack.clear(); tab = 4; navOpen = false }, Alignment.Center) { Icon(Icons.Filled.Search, null, tint = if (tab == 4 && !sub()) Red else Sub) }
                    }
                }
            }
            AnimatedVisibility(np && pl.cur != null, enter = slideInVertically(spring(.85f, 300f)) { it } + fadeIn(), exit = slideOutVertically(spring(1f, 400f)) { it } + fadeOut()) { Now(pl, ly) { np = false } }
        }
    }
}
