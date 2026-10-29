package com.musicclone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

// ======================================================================
//  SUPABASE (REST direto, sem biblioteca extra)
// ======================================================================
const val SB_URL = "https://qimhsxuvuqpsybcbpuwq.supabase.co"
const val SB_KEY = "sb_publishable_cW0GTnKr50A4pYrbEtlhmg_HDthwwy7"

// paleta minimalista das telas de entrada e cadastro: preto, branco e cinza
val Surf = Color(0xFF1C1C1E)    // cartões e campos
val Surf2 = Color(0xFF2C2C2E)   // ícones e superfícies em destaque
val Line = Color(0xFF3A3A3C)    // bordas

class SbRes(val code: Int, val body: String) {
    val ok get() = code in 200..299
    fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    fun arr(): JSONArray? = runCatching { JSONArray(body) }.getOrNull()
}

suspend fun sb(method: String, path: String, body: String? = null, token: String? = null, headers: Map<String, String> = emptyMap(), bytes: ByteArray? = null, ctype: String = "application/json"): SbRes =
    withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(SB_URL + path).openConnection() as java.net.HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10000; c.readTimeout = 25000
            c.setRequestProperty("apikey", SB_KEY)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val out = bytes ?: body?.toByteArray()
            if (out != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (bytes != null) ctype else "application/json")
                c.outputStream.use { it.write(out) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            SbRes(code, txt)
        }.getOrElse { SbRes(0, "") }
    }

private fun pick(j: JSONObject?, vararg ks: String): String = ks.firstNotNullOfOrNull { k -> j?.optString(k)?.takeIf { it.isNotEmpty() } } ?: ""

fun erroPt(r: SbRes): String {
    if (r.code == 0) return "Sem conexão com a internet."
    val j = r.json()
    val ec = pick(j, "error_code", "code").lowercase()
    val m = pick(j, "msg", "error_description", "message").lowercase()
    return when {
        ec == "user_already_exists" || m.contains("already registered") -> "Este email já tem uma conta."
        ec == "invalid_credentials" || m.contains("invalid login") -> "Email ou senha incorretos."
        ec == "email_not_confirmed" || m.contains("not confirmed") -> "Confirme seu email antes de entrar."
        ec == "weak_password" || (m.contains("password") && m.contains("character")) -> "A senha precisa ter pelo menos 6 caracteres."
        ec.contains("rate") || m.contains("rate limit") || m.contains("too many") -> "Muitas tentativas. Espere um pouco e tente de novo."
        ec == "validation_failed" || m.contains("valid email") || m.contains("invalid email") -> "Email inválido."
        ec == "signup_disabled" || m.contains("signups not allowed") -> "Cadastros estão desativados no Supabase (Authentication > Sign In / Providers > permitir novos usuários)."
        ec == "email_address_invalid" || m.contains("is invalid") -> "Este email não é aceito. Use um email real."
        ec == "email_address_not_authorized" -> "O envio de emails do Supabase só aceita emails da equipe. Desligue Confirm email ou configure um SMTP."
        ec == "over_email_send_rate_limit" || ec == "over_request_rate_limit" -> "Muitas tentativas. Espere um pouco e tente de novo."
        m.contains("database error") -> "Não foi possível criar o perfil. Esse artista pode já ter sido reivindicado."
        // mostra o motivo real que o servidor devolveu, para dar para diagnosticar
        else -> "Algo deu errado (${r.code}${if (ec.isNotEmpty()) " · $ec" else ""})${if (m.isNotEmpty()) ": " + m.take(140) else ""}"
    }
}

class Prof(val id: String, val role: String, val name: String, val photo: String?, val category: String?, val itunesId: Long, val claimed: Boolean, val featured: Track? = null) {
    val artist get() = role == "artist"
}

fun JSONObject.toProf() = Prof(
    optString("id"), optString("role"), optString("display_name"),
    js(this, "photo_url").ifEmpty { null }, js(this, "artist_category").ifEmpty { null },
    optLong("itunes_artist_id", 0L), optBoolean("claimed", false),
    optJSONObject("featured")?.toTrackOrNull()
)

// música em destaque do perfil (guardada como JSON na coluna "featured")
fun JSONObject.toTrackOrNull(): Track? {
    val n = optString("name"); val u = optString("url")
    if (n.isEmpty() || u.isEmpty()) return null
    return Track(n, optString("artist"), optString("art"), u, optString("album"), optLong("artistId", 0L), optString("year"), optBoolean("explicit", false), optInt("dur", 0))
}

fun Track.toJson(): JSONObject = JSONObject().put("name", name).put("artist", artist).put("art", art).put("url", url)
    .put("album", album).put("artistId", artistId).put("year", year).put("explicit", explicit).put("dur", dur)

// o perfil de quem já reivindicou esse artista do iTunes (null = livre)
suspend fun profileByItunes(id: Long): Prof? {
    if (id == 0L) return null
    val r = sb("GET", "/rest/v1/profiles?itunes_artist_id=eq.$id&claimed=eq.true&select=*&limit=1")
    return if (r.ok) r.arr()?.optJSONObject(0)?.toProf() else null
}

// artistas criados no app (aparecem para todos os usuários)
suspend fun appArtists(): List<Prof> {
    val r = sb("GET", "/rest/v1/profiles?role=eq.artist&select=*&order=created_at.desc&limit=20")
    val a = (if (r.ok) r.arr() else null) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toProf() }
}

val LocalAuth = staticCompositionLocalOf<Auth?> { null }
val LocalMe = staticCompositionLocalOf<() -> Unit> { {} }

class Auth(ctx: Context) {
    private val sp = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
    var uid by mutableStateOf(sp.getString("uid", null))
    var email by mutableStateOf(sp.getString("email", "") ?: "")
    var prof by mutableStateOf<Prof?>(null)
    var ready by mutableStateOf(false)   // já checou a sessão salva
    var hold by mutableStateOf(false)    // segura na tela de cadastro enquanto a animação de boas-vindas toca
    private var token: String? = sp.getString("token", null)
    private var refresh: String? = sp.getString("refresh", null)
    val logged get() = uid != null
    val jwt: String? get() = token   // usado pelo iStudio para enviar capas, áudios e lançamentos

    class UpRes(val err: String? = null, val confirm: Boolean = false)

    private fun keep(j: JSONObject): Boolean {
        val t = j.optString("access_token")
        if (t.isEmpty()) return false
        val u = j.optJSONObject("user")
        token = t
        refresh = j.optString("refresh_token")
        uid = u?.optString("id") ?: uid
        email = u?.optString("email") ?: email
        sp.edit().putString("token", token).putString("refresh", refresh).putString("uid", uid).putString("email", email).apply()
        return true
    }

    private fun wipe() {
        token = null; refresh = null
        sp.edit().clear().apply()
        uid = null; email = ""; prof = null
    }

    private suspend fun renew(): Boolean {
        val r = refresh ?: return false
        val res = sb("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", r).toString())
        if (res.ok) return res.json()?.let { keep(it) } ?: false
        if (res.code in 400..403) wipe()   // refresh inválido: a sessão acabou
        return false
    }

    suspend fun start() {
        if (uid != null) {
            renew()                         // o token dura 1 hora, então renova ao abrir
            if (uid != null) loadProfile()
        }
        ready = true
    }

    private suspend fun authed(method: String, path: String, body: String? = null, bytes: ByteArray? = null, ctype: String = "application/json", headers: Map<String, String> = emptyMap()): SbRes {
        var r = sb(method, path, body, token, headers, bytes, ctype)
        if (r.code in listOf(400, 401, 403) && (r.body.contains("JWT", true) || r.body.contains("exp", true)) && renew()) r = sb(method, path, body, token, headers, bytes, ctype)
        return r
    }

    suspend fun loadProfile() {
        val id = uid ?: return
        val r = authed("GET", "/rest/v1/profiles?id=eq.$id&select=*")
        if (r.ok) r.arr()?.optJSONObject(0)?.let { prof = it.toProf() }
    }

    suspend fun signIn(mail: String, pass: String): String? {
        val r = sb("POST", "/auth/v1/token?grant_type=password", JSONObject().put("email", mail.trim()).put("password", pass).toString())
        if (!r.ok) return erroPt(r)
        if (r.json()?.let { keep(it) } != true) return "Não foi possível entrar."
        loadProfile()
        return null
    }

    suspend fun signUp(mail: String, pass: String, meta: JSONObject): UpRes {
        val body = JSONObject().put("email", mail.trim()).put("password", pass).put("data", meta).toString()
        val r = sb("POST", "/auth/v1/signup", body)
        if (!r.ok) return UpRes(erroPt(r))
        val j = r.json()
        if (j == null || !keep(j)) return UpRes(confirm = true)   // projeto exige confirmar o email
        loadProfile()
        return UpRes()
    }

    private suspend fun upload(bytes: ByteArray): String? {
        val id = uid ?: return null
        val r = authed("POST", "/storage/v1/object/avatars/$id/avatar.jpg", bytes = bytes, ctype = "image/jpeg", headers = mapOf("x-upsert" to "true"))
        return if (r.ok) "$SB_URL/storage/v1/object/public/avatars/$id/avatar.jpg?v=${System.currentTimeMillis()}" else null
    }

    // salva nome (ignorado pelo servidor em perfil reivindicado) e/ou foto
    // featured: nova música em destaque; limpar = true remove a que está
    suspend fun save(name: String?, photo: ByteArray?, featured: Track? = null, limpar: Boolean = false): String? {
        var url: String? = null
        if (photo != null) url = upload(photo) ?: return "Não foi possível enviar a foto."
        val b = JSONObject().put("p_name", name ?: JSONObject.NULL).put("p_photo", url ?: JSONObject.NULL)
            .put("p_featured", featured?.toJson() ?: JSONObject.NULL).put("p_clear_featured", limpar)
        val r = authed("POST", "/rest/v1/rpc/update_my_profile", b.toString())
        if (!r.ok) return erroPt(r)
        loadProfile()
        return null
    }

    suspend fun newPassword(p: String): String? {
        val r = authed("PUT", "/auth/v1/user", JSONObject().put("password", p).toString())
        return if (r.ok) null else erroPt(r)
    }

    fun signOut() {
        val t = token
        wipe(); hold = false
        if (t != null) CoroutineScope(Dispatchers.IO).launch { sb("POST", "/auth/v1/logout", null, t) }
    }
}

// lê a foto da galeria, corrige a rotação e reduz para no máximo ~1200 px (JPEG)
suspend fun prepararFoto(ctx: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val cr = ctx.contentResolver
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1200) s *= 2
        val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
        var bmp = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o2) } ?: return@runCatching null
        val rot = runCatching { cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrNull() ?: 1
        val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray()
    }.getOrNull()
}

// ======================================================================
//  SONS (gerados no código, sem arquivos de áudio)
// ======================================================================
object Sfx {
    private const val SR = 22050
    private fun gen(ms: Int, f: (Float, Float) -> Float): ShortArray {
        val n = SR * ms / 1000
        return ShortArray(n) { i -> (f(i / SR.toFloat(), i / n.toFloat()).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }
    // seno que desliza de f0 para f1 com queda suave
    private fun sweep(ms: Int, f0: Float, f1: Float, amp: Float, dec: Float): ShortArray {
        val dur = ms / 1000f
        return gen(ms) { t, p ->
            val fase = 2.0 * Math.PI * (f0 * t + (f1 - f0) * t * t / (2f * dur))
            Math.sin(fase).toFloat() * amp * Math.exp((-dec * p).toDouble()).toFloat() * minOf(1f, t * 500f)
        }
    }
    private fun mix(a: ShortArray, b: ShortArray): ShortArray {
        val n = maxOf(a.size, b.size)
        return ShortArray(n) { i -> ((if (i < a.size) a[i].toInt() else 0) + (if (i < b.size) b[i].toInt() else 0)).coerceIn(-32000, 32000).toShort() }
    }

    private val aPop by lazy { sweep(130, 950f, 240f, .9f, 5f) }
    private val aTick by lazy { sweep(45, 2400f, 2000f, .45f, 6f) }
    private val aErro by lazy { sweep(220, 230f, 110f, .8f, 4f) }
    private val aWhoosh by lazy {
        val rnd = java.util.Random(5)
        var lp = 0f
        mix(gen(560) { _, p ->
            val s = Math.sin(Math.PI * p).toFloat()
            lp += (.04f + .5f * p) * ((rnd.nextFloat() * 2f - 1f) - lp)
            lp * s * s * 2.4f
        }, sweep(560, 200f, 1500f, .16f, 2.5f))
    }
    private val aOk by lazy {
        val out = ShortArray(SR * 720 / 1000)
        listOf(523.25f, 659.25f, 783.99f, 1046.5f).forEachIndexed { k, f ->
            val s = sweep(380, f, f, .45f, 4f)
            val off = k * SR * 90 / 1000
            for (i in s.indices) if (off + i < out.size) out[off + i] = (out[off + i] + s[i]).coerceIn(-32000, 32000).toShort()
        }
        out
    }

    private fun tocar(pcm: ShortArray) {
        Thread {
            runCatching {
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                at.write(pcm, 0, pcm.size)
                at.play()
                Thread.sleep(pcm.size * 1000L / SR + 200)
                at.release()
            }
        }.start()
    }
    fun pop() = tocar(aPop)
    fun tick() = tocar(aTick)
    fun whoosh() = tocar(aWhoosh)
    fun sucesso() = tocar(aOk)
    fun erro() = tocar(aErro)
}

// ======================================================================
//  COMPONENTES VISUAIS
// ======================================================================
// igual ao tap(), mas sempre chama a versão mais recente da função
@Composable fun Modifier.toca(f: () -> Unit): Modifier {
    val fa by rememberUpdatedState(f)
    var d by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (d) .95f else 1f, spring(.5f, 500f), label = "toca")
    return graphicsLayer { scaleX = s; scaleY = s }.pointerInput(Unit) { detectTapGestures(onPress = { d = true; tryAwaitRelease(); d = false }, onTap = { fa() }) }
}

// fundo cinematográfico: gradiente que respira + orbes + partículas flutuando
// fundo liso e preto (os parâmetros ficam só por compatibilidade)
@Composable fun Fundo(c1: Color = Color.Black, c2: Color = Color.Black) {
    Box(Modifier.fillMaxSize().background(Color.Black))
}

@Composable fun Campo(v: String, on: (String) -> Unit, label: String, icon: ImageVector, m: Modifier = Modifier, senha: Boolean = false, kb: KeyboardType = KeyboardType.Text) {
    var foco by remember { mutableStateOf(false) }
    var ver by remember { mutableStateOf(false) }
    val borda by animateColorAsState(if (foco) Color.White else Line, tween(200), label = "cb")
    Row(m.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surf)
        .border(1.dp, borda, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), if (foco) Color.White else Sub)
        Spacer(Modifier.width(12.dp))
        BasicTextField(v, on, Modifier.weight(1f).onFocusChanged { foco = it.isFocused }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = Sf), cursorBrush = SolidColor(Color.White),
            visualTransformation = if (senha && !ver) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (senha) KeyboardType.Password else kb),
            decorationBox = { inner -> if (v.isEmpty()) T(label, 16, 400, Sub); inner() })
        if (senha) Icon(if (ver) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(20.dp).clickable { ver = !ver }, Sub)
    }
}

@Composable fun Pilula(txt: String, m: Modifier = Modifier, claro: Boolean = true, loading: Boolean = false, icon: ImageVector? = null, compacta: Boolean = false, on: () -> Unit) {
    val cor = if (claro) Color.Black else Color.White
    Row(m.toca { if (!loading) { Sfx.tick(); on() } }.clip(CircleShape)
        .background(if (claro) Color.White else Surf)
        .then(if (claro) Modifier else Modifier.border(1.dp, Line, CircleShape))
        .padding(horizontal = if (compacta) 20.dp else 36.dp, vertical = if (compacta) 10.dp else 15.dp), Arrangement.Center, Alignment.CenterVertically) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), cor, 2.dp)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp), cor); Spacer(Modifier.width(8.dp)) }
            T(txt, if (compacta) 15 else 17, 700, cor)
        }
    }
}

// uma das 3 linhas finas do topo: apagada, pulsando (passo atual) ou preenchida (concluído)
@Composable fun Traco(feita: Boolean, atual: Boolean, m: Modifier) {
    val inf = rememberInfiniteTransition(label = "tr")
    val brilho by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "tb")
    val f by animateFloatAsState(if (feita) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "tf")
    val at by animateFloatAsState(if (atual) 1f else 0f, tween(400), label = "ta")
    Box(m.height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f + .2f * brilho * at))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
    }
}

@Composable fun FotoRedonda(u: Any?, tam: Dp, m: Modifier = Modifier, on: () -> Unit) {
    val pulso by rememberInfiniteTransition(label = "fr").animateFloat(1f, 1.03f, infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fpz")
    Box(m.size(tam + 24.dp).toca { Sfx.pop(); on() }, Alignment.Center) {
        // anel fino e cinza que respira de leve
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = pulso; scaleY = pulso }) {
            drawCircle(Color.White.copy(alpha = .28f), size.minDimension / 2f - 2.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
        Box(Modifier.size(tam).clip(CircleShape).background(Surf), Alignment.Center) {
            if (u != null) AsyncImage(model = u, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.AddAPhoto, null, Modifier.size(44.dp), Color.White)
                T("Escolher da galeria", 13, 600, Color.White.copy(alpha = .8f), Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable fun Celebra(txt: String, sub: String) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, spring(.5f, 180f)) }
    val inf = rememberInfiniteTransition(label = "cel")
    val onda by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "co")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .96f * a.value.coerceIn(0f, 1f))).pointerInput(Unit) { detectTapGestures { } }, Alignment.Center) {
        Canvas(Modifier.size(300.dp)) {
            for (k in 0..1) {
                val p = (onda + k / 2f) % 1f
                drawCircle(Color.White.copy(alpha = (1f - p) * .18f), size.minDimension / 2f * p, center, style = Stroke(1.5f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 30.dp)) {
            Box(Modifier.size(96.dp).graphicsLayer { scaleX = a.value; scaleY = a.value }.clip(CircleShape).background(Color.White), Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(52.dp), Color.Black)
            }
            T(txt, 28, 800, Color.White, Modifier.padding(top = 24.dp), 3, TextAlign.Center)
            T(sub, 15, 500, Color.White.copy(alpha = .7f), Modifier.padding(top = 6.dp), 3, TextAlign.Center)
        }
    }
}

@Composable fun AvisoLock(fechar: () -> Unit) {
    val a = remember { Animatable(0f) }
    val tremor = remember { Animatable(0f) }
    LaunchedEffect(Unit) { Sfx.erro(); a.animateTo(1f, spring(.6f, 240f)) }
    LaunchedEffect(Unit) {
        delay(250)
        for (i in 0..3) tremor.animateTo(if (i % 2 == 0) 14f else -14f, tween(60))
        tremor.animateTo(0f, spring(.4f, 400f))
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f * a.value.coerceIn(0f, 1f))).pointerInput(Unit) { detectTapGestures { fechar() } }, Alignment.Center) {
        Column(Modifier.padding(horizontal = 26.dp).fillMaxWidth().graphicsLayer { val s = .7f + .3f * a.value; scaleX = s; scaleY = s; alpha = a.value.coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(24.dp)).background(Surf).border(1.dp, Line, RoundedCornerShape(24.dp))
            .pointerInput(Unit) { detectTapGestures { } }.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).graphicsLayer { rotationZ = tremor.value }.clip(CircleShape).background(Surf2), Alignment.Center) {
                Icon(Icons.Filled.Lock, null, Modifier.size(36.dp), Color.White)
            }
            T("Perfil reivindicado", 24, 800, Color.White, Modifier.padding(top = 18.dp), 2, TextAlign.Center)
            T("Este perfil foi reivindicado do iTunes, então o nome não pode ser alterado. Você pode mudar apenas a foto.", 15, 500, Color.White.copy(alpha = .75f), Modifier.padding(top = 8.dp, bottom = 20.dp), 6, TextAlign.Center)
            Pilula("Entendi", Modifier.fillMaxWidth(), on = fechar)
        }
    }
}

// ======================================================================
//  TELA DE ENTRADA + POP-UP "OUVINTE OU ARTISTA"
// ======================================================================
@Composable fun Entrada(auth: Auth, aviso: String?, criar: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mail by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val pulso by rememberInfiniteTransition(label = "lg").animateFloat(1f, 1.08f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lp")

    fun entrar() {
        if (busy) return
        if (mail.isBlank() || pass.isEmpty()) { err = "Preencha o email e a senha."; Sfx.erro(); return }
        busy = true; err = null
        scope.launch {
            val e = auth.signIn(mail, pass)
            busy = false
            if (e != null) { err = e; Sfx.erro() } else Sfx.sucesso()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(56.dp))
        Box(Modifier.enter(0).size(88.dp).graphicsLayer { scaleX = pulso; scaleY = pulso }.clip(RoundedCornerShape(26.dp)).background(Color.White), Alignment.Center) {
            Icon(Icons.Filled.MusicNote, null, Modifier.size(48.dp), Color.Black)
        }
        T("beats", 52, 800, Color.White, Modifier.enter(1).padding(top = 16.dp))
        T("Toda a música. Todos os artistas.", 15, 500, Color.White.copy(alpha = .7f), Modifier.enter(2).padding(top = 4.dp), 2, TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        aviso?.let { T(it, 14, 600, Color.White, Modifier.padding(bottom = 14.dp), 3, TextAlign.Center) }
        Campo(mail, { mail = it }, "Email", Icons.Filled.Email, Modifier.enter(3), kb = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        Campo(pass, { pass = it }, "Senha", Icons.Filled.Lock, Modifier.enter(4), senha = true)
        err?.let { T(it, 13, 600, Color(0xFFFF6B6B), Modifier.padding(top = 10.dp), 3, TextAlign.Center) }
        Spacer(Modifier.height(22.dp))
        Pilula("Entrar", Modifier.enter(5).fillMaxWidth(), loading = busy) { entrar() }
        T("ou", 13, 500, Color.White.copy(alpha = .5f), Modifier.padding(vertical = 14.dp))
        Pilula("Criar conta", Modifier.enter(6).fillMaxWidth(), claro = false, icon = Icons.Filled.PersonAdd) { criar() }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable fun CardTipo(titulo: String, sub: String, icon: ImageVector, b: Brush, i: Int, m: Modifier, on: () -> Unit) {
    val e = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(280L + i * 140L); Sfx.tick(); e.animateTo(1f, spring(.55f, 260f)) }
    val inf = rememberInfiniteTransition(label = "ct")
    val flut by inf.animateFloat(-5f, 5f, infiniteRepeatable(tween(1800 + i * 300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "cf")
    Column(m.graphicsLayer { alpha = e.value.coerceIn(0f, 1f); translationY = (1 - e.value) * 160f + flut * e.value; rotationZ = (1 - e.value) * (if (i == 0) -10f else 10f) }
        .toca { Sfx.pop(); on() }.heightIn(min = 190.dp).clip(RoundedCornerShape(20.dp)).background(Surf).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp), Arrangement.SpaceBetween) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White), Alignment.Center) { Icon(icon, null, Modifier.size(26.dp), Color.Black) }
        Column {
            T(titulo, 22, 800, Color.White)
            T(sub, 12, 500, Color.White.copy(alpha = .85f), Modifier.padding(top = 4.dp), 4)
        }
    }
}

@Composable fun Escolha(fechar: () -> Unit, pick: (Boolean) -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, spring(.62f, 220f)) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .66f * a.value.coerceIn(0f, 1f))).pointerInput(Unit) { detectTapGestures { fechar() } }, Alignment.Center) {
        Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth()
            .graphicsLayer { val s = .6f + .4f * a.value; scaleX = s; scaleY = s; alpha = a.value.coerceIn(0f, 1f); translationY = (1 - a.value) * 120f }
            .clip(RoundedCornerShape(28.dp)).background(Color(0xFF111111)).border(1.dp, Line, RoundedCornerShape(28.dp))
            .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T("Como você quer criar sua conta?", 24, 800, Color.White, ml = 3, a = TextAlign.Center)
            T("Escolha o seu perfil no beats", 14, 400, Sub, Modifier.padding(top = 6.dp, bottom = 18.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(14.dp)) {
                CardTipo("Ouvinte", "Ouça tudo e monte seu Replay", Icons.Filled.Headphones, SolidColor(Surf), 0, Modifier.weight(1f)) { pick(false) }
                CardTipo("Artista", "Tenha seu perfil, sua foto e suas músicas", Icons.Filled.Mic, SolidColor(Surf), 1, Modifier.weight(1f)) { pick(true) }
            }
        }
    }
}

// ======================================================================
//  CADASTRO DE OUVINTE
// ======================================================================
fun emailOk(s: String) = s.contains("@") && s.substringAfter("@").contains(".") && !s.contains(" ")

fun forca(p: String): Int = (if (p.length >= 6) 1 else 0) + (if (p.length >= 10) 1 else 0) + (if (p.any { it.isDigit() }) 1 else 0) + (if (p.any { !it.isLetterOrDigit() || it.isUpperCase() }) 1 else 0)

@Composable fun Forca(p: String) {
    val f = forca(p)
    val cores = listOf(Color(0xFF6B6B70), Color(0xFF9A9AA0), Color(0xFFCFCFD4), Color.White)   // quanto mais clara, mais forte
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.spacedBy(6.dp)) {
        for (k in 0..3) {
            val c by animateColorAsState(if (p.isNotEmpty() && k < f) cores[(f - 1).coerceIn(0, 3)] else Line, tween(300), label = "fo$k")
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(c))
        }
    }
}

@Composable fun FormOuvinte(auth: Auth, voltar: () -> Unit, fim: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var nome by remember { mutableStateOf("") }
    var mail by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var festa by remember { mutableStateOf<String?>(null) }

    fun criar() {
        if (busy) return
        if (nome.isBlank() || !emailOk(mail) || pass.length < 6) { err = "Preencha o nome, um email válido e uma senha com 6+ caracteres."; Sfx.erro(); return }
        busy = true; err = null; auth.hold = true
        scope.launch {
            val meta = JSONObject().put("role", "listener").put("display_name", nome.trim())
            val r = auth.signUp(mail, pass, meta)
            if (r.err != null) { busy = false; auth.hold = false; err = r.err; Sfx.erro(); return@launch }
            if (r.confirm) { busy = false; auth.hold = false; Sfx.sucesso(); fim("Conta criada! Confirme seu email e depois entre."); return@launch }
            festa = nome.trim(); Sfx.sucesso()
            delay(2000)
            auth.hold = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp)) {
            Row(Modifier.padding(top = 12.dp)) { Glass(Icons.Filled.ArrowBackIosNew) { if (!busy) voltar() } }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.enter(0).size(64.dp).clip(CircleShape).background(Color.White), Alignment.Center) { Icon(Icons.Filled.Headphones, null, Modifier.size(32.dp), Color.Black) }
            T("Criar conta de ouvinte", 32, 800, Color.White, Modifier.enter(1).padding(top = 16.dp), 3)
            T("Ouça todas as músicas e acompanhe seu Replay.", 15, 500, Color.White.copy(alpha = .7f), Modifier.enter(2).padding(top = 6.dp, bottom = 28.dp), 3)
            Campo(nome, { nome = it.take(40) }, "Nome de exibição", Icons.Filled.Person, Modifier.enter(3))
            Spacer(Modifier.height(12.dp))
            Campo(mail, { mail = it }, "Email", Icons.Filled.Email, Modifier.enter(4), kb = KeyboardType.Email)
            Spacer(Modifier.height(12.dp))
            Campo(pass, { pass = it }, "Senha (mínimo 6 caracteres)", Icons.Filled.Lock, Modifier.enter(5), senha = true)
            Forca(pass)
            err?.let { T(it, 13, 600, Color(0xFFFF6B6B), Modifier.padding(top = 12.dp), 4, TextAlign.Center) }
            Spacer(Modifier.height(24.dp))
            Pilula("Criar conta", Modifier.enter(6).fillMaxWidth(), loading = busy) { criar() }
            Spacer(Modifier.height(40.dp))
        }
        festa?.let { Celebra("Bem-vindo, $it!", "Sua conta foi criada") }
    }
}

// ======================================================================
//  CADASTRO DE ARTISTA (3 passos)
// ======================================================================
data class Hit(val id: Long, val name: String, val genre: String)
class Tipo(val nome: String, val icon: ImageVector, val desc: String, val c1: Long, val c2: Long)

val TIPOS = listOf(
    Tipo("Artista Roblox", Icons.Filled.SportsEsports, "Música feita para os mundos do Roblox", 0xFFFF3B5C, 0xFF7A0618),
    Tipo("Artista Meta", Icons.Filled.Public, "Criador do metaverso e da realidade virtual", 0xFF2A7BFF, 0xFF0B2C8C),
    Tipo("Artista Sims", Icons.Filled.Face, "Música no universo do The Sims", 0xFF19C75A, 0xFF0A6B3A),
    Tipo("Artista IA", Icons.Filled.AutoAwesome, "Música criada com inteligência artificial", 0xFF7A3DFF, 0xFFE04CFF)
)

suspend fun buscarArtistas(q: String): List<Hit> {
    val a = get("https://itunes.apple.com/search?entity=musicArtist&limit=8&country=BR&term=${Uri.encode(q)}")?.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optLong("artistId") != 0L }
        .map { Hit(it.optLong("artistId"), it.optString("artistName"), it.optString("primaryGenreName")) }.distinctBy { it.id }
}

suspend fun fotoArtista(name: String, id: Long): String? = photoOf(name) ?: latest(id)?.album?.art

@Composable fun Titulo(t: String, sub: String) {
    Spacer(Modifier.height(24.dp))
    T(t, 30, 800, Color.White, Modifier.enter(0), 3, TextAlign.Center)
    T(sub, 15, 500, Color.White.copy(alpha = .7f), Modifier.enter(1).padding(top = 8.dp, bottom = 26.dp), 4, TextAlign.Center)
}

@Composable fun PassoFoto(uri: Uri?, pronto: Boolean, falhou: Boolean, escolher: () -> Unit, continuar: () -> Unit, jaTenho: () -> Unit) {
    Titulo("Escolha sua foto de perfil", "É ela que seus fãs vão ver no beats")
    Spacer(Modifier.height(6.dp))
    FotoRedonda(uri, 200.dp, Modifier.enter(2), escolher)
    Spacer(Modifier.height(26.dp))
    if (falhou) T("Não consegui ler essa foto. Tente outra.", 14, 600, Color(0xFFFF6B6B), Modifier.padding(bottom = 12.dp))
    AnimatedVisibility(pronto, enter = scaleIn(spring(.55f, 300f)) + fadeIn() + slideInVertically { -it }, exit = fadeOut()) {
        Pilula("Continuar", Modifier.padding(bottom = 14.dp), on = continuar)
    }
    Pilula("Já tenho perfil na plataforma", Modifier.enter(3), claro = false, icon = Icons.Filled.Verified, on = jaTenho)
    Spacer(Modifier.height(30.dp))
}

@Composable fun LinhaArtista(h: Hit, sel: Boolean, k: Int, on: (String?) -> Unit) {
    val foto by produceState<String?>(null, h.id) { value = fotoArtista(h.name, h.id) }
    val borda by animateColorAsState(if (sel) Color.White else Line, tween(250), label = "lb2")
    Row(Modifier.enter(k).fillMaxWidth().padding(vertical = 5.dp).toca { on(foto) }.clip(RoundedCornerShape(14.dp))
        .background(if (sel) Surf2 else Surf).border(1.dp, borda, RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Surf2), Alignment.Center) {
            T(h.name.take(1).uppercase(), 20, 800, Color.White)
            if (foto != null) AsyncImage(model = foto, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            T(h.name, 16, 700, Color.White)
            T(h.genre, 12, 500, Color.White.copy(alpha = .65f))
        }
        if (sel) Icon(Icons.Filled.CheckCircle, null, Modifier.size(24.dp), Color.White)
    }
}

@Composable fun PassoClaim(q: String, onQ: (String) -> Unit, res: List<Hit>, buscando: Boolean, sel: Hit?, jaTem: Boolean, checando: Boolean, onSel: (Hit, String?) -> Unit, continuar: () -> Unit) {
    Titulo("Qual é o seu perfil?", "Digite o nome da sua conta de artista. Buscamos no iTunes e trazemos sua foto e suas músicas.")
    Campo(q, onQ, "Nome de artista", Icons.Filled.Search, Modifier.enter(2))
    Spacer(Modifier.height(14.dp))
    if (buscando) CircularProgressIndicator(Modifier.size(24.dp), Color.White, 2.dp)
    res.forEachIndexed { k, h -> LinhaArtista(h, sel?.id == h.id, k) { f -> onSel(h, f) } }
    if (!buscando && q.isNotBlank() && res.isEmpty()) T("Nenhum artista encontrado.", 14, 500, Sub)
    if (sel != null && jaTem) T("Este perfil já foi reivindicado por outra conta.", 14, 600, Color(0xFFFF6B6B), Modifier.padding(top = 10.dp), 3, TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    AnimatedVisibility(sel != null && !jaTem && !checando, enter = scaleIn(spring(.55f, 300f)) + fadeIn(), exit = fadeOut()) {
        Pilula("Continuar", on = continuar)
    }
    Spacer(Modifier.height(30.dp))
}

@Composable fun PassoCreds(mail: String, onMail: (String) -> Unit, pass: String, onPass: (String) -> Unit, continuar: () -> Unit) {
    Titulo("Crie seu acesso", "Use seu email e uma senha com 6 ou mais caracteres")
    Campo(mail, onMail, "Email", Icons.Filled.Email, Modifier.enter(2), kb = KeyboardType.Email)
    Spacer(Modifier.height(12.dp))
    Campo(pass, onPass, "Senha", Icons.Filled.Lock, Modifier.enter(3), senha = true)
    Forca(pass)
    Spacer(Modifier.height(24.dp))
    AnimatedVisibility(emailOk(mail) && pass.length >= 6, enter = scaleIn(spring(.55f, 300f)) + fadeIn(), exit = fadeOut()) {
        Pilula("Continuar", on = continuar)
    }
    Spacer(Modifier.height(30.dp))
}

@Composable fun OpcaoTipo(x: Tipo, sel: Boolean, k: Int, on: () -> Unit) {
    val e = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(k * 60L); e.animateTo(1f, spring(.7f, 300f)) }
    val borda by animateColorAsState(if (sel) Color.White else Line, tween(250), label = "ob")
    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = e.value.coerceIn(0f, 1f); translationX = (1 - e.value) * 80f }.toca(on)
        .clip(RoundedCornerShape(16.dp)).background(Surf).border(1.dp, borda, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Surf2), Alignment.Center) { Icon(x.icon, null, Modifier.size(24.dp), Color.White) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            T(x.nome, 17, 700, Color.White)
            T(x.desc, 12, 500, Color.White.copy(alpha = .85f), Modifier.padding(top = 2.dp), 2)
        }
        if (sel) Icon(Icons.Filled.CheckCircle, null, Modifier.size(24.dp), Color.White)
    }
}

@Composable fun PassoNome(nome: String, onNome: (String) -> Unit, bloqueado: Boolean, tipo: Int, onTipo: (Int) -> Unit, busy: Boolean, err: String?, criar: () -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    val giro by animateFloatAsState(if (aberto) 180f else 0f, spring(.7f, 300f), label = "pg")
    Titulo("Quem é você no beats?", "Escolha seu nome artístico e o tipo de artista")
    if (bloqueado) {
        Row(Modifier.enter(2).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surf)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, null, Modifier.size(20.dp), Sub)
            Spacer(Modifier.width(12.dp))
            T(nome, 16, 500, Color.White.copy(alpha = .7f), Modifier.weight(1f))
            Icon(Icons.Filled.Lock, null, Modifier.size(20.dp), Color.White.copy(alpha = .8f))
        }
        T("Nome vinculado ao seu perfil do iTunes. Ele não poderá ser alterado depois.", 12, 500, Sub, Modifier.padding(top = 8.dp), 3, TextAlign.Center)
    } else Campo(nome, onNome, "Nome de artista", Icons.Filled.Person, Modifier.enter(2))

    Column(Modifier.enter(3).fillMaxWidth().padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surf)
            .border(1.dp, if (aberto) Color.White else Line, RoundedCornerShape(14.dp))
            .clickable { aberto = !aberto; Sfx.tick() }.padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = TIPOS.getOrNull(tipo)
            Icon(t?.icon ?: Icons.Filled.Mic, null, Modifier.size(20.dp), if (t != null) Color.White else Sub)
            Spacer(Modifier.width(12.dp))
            T(t?.nome ?: "Que tipo de artista você é?", 16, if (t != null) 600 else 400, if (t != null) Color.White else Sub, Modifier.weight(1f))
            Icon(Icons.Filled.ExpandMore, null, Modifier.size(24.dp).graphicsLayer { rotationZ = giro }, Color.White)
        }
        AnimatedVisibility(aberto, enter = expandVertically(spring(.8f, 300f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TIPOS.forEachIndexed { k, x -> OpcaoTipo(x, k == tipo, k) { onTipo(k); aberto = false; Sfx.pop() } }
            }
        }
    }
    err?.let { T(it, 13, 600, Color(0xFFFF6B6B), Modifier.padding(top = 14.dp), 4, TextAlign.Center) }
    Spacer(Modifier.height(22.dp))
    AnimatedVisibility(nome.isNotBlank() && tipo >= 0, enter = scaleIn(spring(.55f, 300f)) + fadeIn(), exit = fadeOut()) {
        Pilula("Criar perfil de artista", loading = busy, icon = Icons.Filled.Mic, on = criar)
    }
    Spacer(Modifier.height(30.dp))
}

@Composable fun WizardArtista(auth: Auth, sair: () -> Unit, tema: (Pair<Color, Color>) -> Unit, fim: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tela by remember { mutableStateOf(0) }   // 0 foto · 1 já tenho perfil · 2 email e senha · 3 nome e categoria
    var uri by remember { mutableStateOf<Uri?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var prep by remember { mutableStateOf(false) }
    var hit by remember { mutableStateOf<Hit?>(null) }
    var hitFoto by remember { mutableStateOf<String?>(null) }
    var jaTem by remember { mutableStateOf(false) }
    var checando by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf(listOf<Hit>()) }
    var buscando by remember { mutableStateOf(false) }
    var mail by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var nome by remember { mutableStateOf("") }
    var tipo by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var festa by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            uri = u; bytes = null; prep = true; Sfx.pop()
            scope.launch { bytes = prepararFoto(ctx, u); prep = false }
        }
    }

    LaunchedEffect(tela) {
        tema(when (tela) {
            0 -> Color(0xFF7A3DFF) to Color(0xFF1A0B4A)
            1 -> Color(0xFF19C7A5) to Color(0xFF053A44)
            2 -> Color(0xFF2A7BFF) to Color(0xFF071A55)
            else -> Color(0xFFFF3B5C) to Color(0xFF4A0612)
        })
        if (tela != 0) Sfx.whoosh()
    }
    LaunchedEffect(q) {
        if (q.isBlank()) { res = emptyList(); buscando = false }
        else { buscando = true; delay(450); res = buscarArtistas(q.trim()); buscando = false }
    }

    fun voltar() {
        if (festa != null || busy) return
        err = null
        when (tela) {
            0 -> sair()
            1 -> tela = 0
            2 -> tela = if (hit != null) 1 else 0
            else -> tela = 2
        }
    }
    BackHandler(festa == null) { voltar() }

    fun escolherHit(h: Hit, foto: String?) {
        hit = h; hitFoto = foto; jaTem = false; checando = true; Sfx.pop()
        scope.launch {
            jaTem = profileByItunes(h.id) != null
            checando = false
            if (jaTem) Sfx.erro()
        }
    }

    fun criar() {
        if (busy) return
        val h = hit
        if (nome.isBlank() || tipo < 0) return
        busy = true; err = null; auth.hold = true
        scope.launch {
            val meta = JSONObject().put("role", "artist").put("display_name", nome.trim()).put("artist_category", TIPOS[tipo].nome)
            if (h != null) {
                val foto = hitFoto ?: fotoArtista(h.name, h.id)
                meta.put("itunes_artist_id", h.id).put("claimed", true).put("photo_url", foto ?: "")
            }
            val r = auth.signUp(mail, pass, meta)
            if (r.err != null) { busy = false; auth.hold = false; err = r.err; Sfx.erro(); return@launch }
            if (r.confirm) { busy = false; auth.hold = false; Sfx.sucesso(); fim("Conta criada! Confirme seu email e depois entre."); return@launch }
            val b = bytes
            if (h == null && b != null) auth.save(null, b)   // perfil novo: envia a foto escolhida
            festa = nome.trim(); Sfx.sucesso()
            delay(2300)
            auth.hold = false
        }
    }

    val feitos = if (festa != null) 3 else when (tela) { 3 -> 2; 2 -> 1; else -> 0 }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Glass(Icons.Filled.ArrowBackIosNew) { voltar() }
                Spacer(Modifier.width(16.dp))
                Row(Modifier.weight(1f), Arrangement.spacedBy(8.dp)) {
                    for (k in 0..2) Traco(k < feitos, k == feitos && festa == null, Modifier.weight(1f))
                }
                Spacer(Modifier.width(16.dp))
                Spacer(Modifier.size(38.dp))
            }
            AnimatedContent(tela, Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
                if (targetState > initialState) (slideInHorizontally(spring(.85f, 300f)) { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 2 } + fadeOut())
                else (slideInHorizontally { -it / 2 } + fadeIn()) togetherWith (slideOutHorizontally(spring(.85f, 300f)) { it } + fadeOut())
            }, label = "wz") { t ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (t) {
                        0 -> PassoFoto(uri, bytes != null, !prep && uri != null && bytes == null, { picker.launch("image/*") },
                            { hit = null; hitFoto = null; jaTem = false; err = null; tela = 2 }, { err = null; tela = 1 })
                        1 -> PassoClaim(q, { q = it }, res, buscando, hit, jaTem, checando, { h, f -> escolherHit(h, f) }) {
                            val h = hit
                            if (h != null) { nome = h.name; tela = 2 }
                        }
                        2 -> PassoCreds(mail, { mail = it }, pass, { pass = it }) { err = null; tela = 3 }
                        else -> PassoNome(nome, { nome = it.take(40) }, hit != null, tipo, { tipo = it }, busy, err) { criar() }
                    }
                }
            }
        }
        festa?.let { Celebra("Bem-vindo, $it!", "Seu perfil de artista está no ar") }
    }
}

// ======================================================================
//  MINHA CONTA (ouvinte) + EDITAR PERFIL (artista)
// ======================================================================
@Composable fun LinhaInfo(rotulo: String, valor: String, k: Int, extra: @Composable () -> Unit = {}) {
    Row(Modifier.enter(k).fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Surf).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(rotulo, 12, 600, Sub)
            T(valor, 17, 500, Color.White, Modifier.padding(top = 2.dp))
        }
        extra()
    }
}

@Composable fun AccountS(auth: Auth, back: () -> Unit) {
    val p = auth.prof
    val scope = rememberCoroutineScope()
    var abrir by remember { mutableStateOf(false) }
    var nova by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var okMsg by remember { mutableStateOf(false) }
    val nome = p?.name ?: auth.email.substringBefore("@")
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Box(Modifier.enter(0).size(120.dp).clip(CircleShape).background(Surf2), Alignment.Center) {
                    T(nome.take(1).uppercase(), 48, 800, Color.White)
                    if (p?.photo != null) AsyncImage(model = p?.photo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                T(nome, 28, 800, Color.White, Modifier.enter(1).padding(top = 14.dp), 2, TextAlign.Center)
                T("Conta de ouvinte", 14, 500, Sub, Modifier.enter(1).padding(top = 2.dp, bottom = 16.dp))
                LinhaInfo("Nome de exibição", nome, 2)
                LinhaInfo("Email", auth.email, 3)
                LinhaInfo("Senha", "••••••••", 4) { Pilula(if (abrir) "Fechar" else "Alterar", claro = false, compacta = true) { abrir = !abrir; msg = null } }
                AnimatedVisibility(abrir, enter = expandVertically(spring(.8f, 300f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Campo(nova, { nova = it }, "Nova senha (mínimo 6 caracteres)", Icons.Filled.Lock, senha = true)
                        Pilula("Salvar nova senha", Modifier.fillMaxWidth().padding(top = 10.dp), loading = busy) {
                            if (nova.length < 6) { msg = "A senha precisa ter pelo menos 6 caracteres."; okMsg = false; Sfx.erro() }
                            else {
                                busy = true
                                scope.launch {
                                    val e = auth.newPassword(nova)
                                    busy = false
                                    if (e == null) { msg = "Senha alterada!"; okMsg = true; nova = ""; abrir = false; Sfx.sucesso() }
                                    else { msg = e; okMsg = false; Sfx.erro() }
                                }
                            }
                        }
                    }
                }
                msg?.let { T(it, 13, 600, if (okMsg) Color.White else Color(0xFFFF6B6B), Modifier.padding(top = 10.dp), 3, TextAlign.Center) }
                T("Por segurança a senha nunca é exibida; ela fica guardada de forma protegida.", 12, 500, Sub, Modifier.padding(top = 12.dp), 3, TextAlign.Center)
                Pilula("Sair da conta", Modifier.padding(top = 28.dp).fillMaxWidth(), claro = false, icon = Icons.Filled.PowerSettingsNew) { auth.signOut() }
            }
            Box(Modifier.statusBarsPadding().padding(start = 14.dp, top = 8.dp)) { Glass(Icons.Filled.ArrowBackIosNew) { back() } }
        }
    }
}

@Composable fun EditarS(auth: Auth, onStudio: () -> Unit = {}, back: () -> Unit) {
    val p = auth.prof
    if (p == null) { LaunchedEffect(Unit) { back() }; return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var nome by remember { mutableStateOf(p.name) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var prep by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf(false) }
    var dest by remember { mutableStateOf(p.featured) }          // música em destaque escolhida
    var destMudou by remember { mutableStateOf(false) }
    var buscaAberta by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var achados by remember { mutableStateOf(listOf<Track>()) }
    var buscando by remember { mutableStateOf(false) }

    LaunchedEffect(q) {
        if (q.isBlank()) { achados = emptyList(); buscando = false }
        else { buscando = true; delay(450); achados = searchSongs(q.trim(), "BR").take(12); buscando = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            uri = u; bytes = null; prep = true
            scope.launch { bytes = prepararFoto(ctx, u); prep = false }
        }
    }

    fun salvar() {
        if (busy || prep) return
        val novo = nome.trim()
        val mudou = !p.claimed && novo.isNotEmpty() && novo != p.name
        val b = bytes
        if (!mudou && b == null && !destMudou) { back(); return }
        busy = true; err = null
        scope.launch {
            val e = auth.save(if (mudou) novo else null, b, if (destMudou) dest else null, destMudou && dest == null)
            busy = false
            if (e != null) { err = e; Sfx.erro() } else { Sfx.sucesso(); back() }
        }
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glass(Icons.Filled.Close) { back() }
                    T("Editar perfil", 20, 800, Color.White, Modifier.weight(1f), a = TextAlign.Center)
                    Pilula("Salvar", loading = busy || prep, compacta = true) { salvar() }
                }
                Spacer(Modifier.height(26.dp))
                FotoRedonda(uri ?: p.photo, 190.dp, Modifier.enter(0)) { picker.launch("image/*") }
                T("Toque na foto para trocar", 13, 500, Color.White.copy(alpha = .7f), Modifier.enter(1).padding(top = 4.dp))
                T("Nome de artista", 13, 600, Sub, Modifier.align(Alignment.Start).padding(top = 28.dp, bottom = 8.dp))
                if (p.claimed) {
                    Row(Modifier.fillMaxWidth().toca { aviso = true }.clip(RoundedCornerShape(14.dp)).background(Surf)
                        .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, null, Modifier.size(20.dp), Sub)
                        Spacer(Modifier.width(12.dp))
                        T(p.name, 16, 500, Color.White.copy(alpha = .7f), Modifier.weight(1f))
                        Icon(Icons.Filled.Lock, null, Modifier.size(22.dp).clickable { aviso = true }, Color.White.copy(alpha = .8f))
                    }
                } else Campo(nome, { nome = it.take(40) }, "Nome de artista", Icons.Filled.Person)

                // ---- música em destaque (o cartão que aparece no seu perfil) ----
                T("Música em destaque", 13, 600, Sub, Modifier.align(Alignment.Start).padding(top = 26.dp, bottom = 8.dp))
                val d0 = dest
                if (d0 != null) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surf).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Img(d0.art, Modifier.size(54.dp), RoundedCornerShape(8.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            T(d0.name, 16, 700, Color.White)
                            T(d0.artist, 13, 400, Sub)
                        }
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Surf2).clickable { dest = null; destMudou = true; Sfx.tick() }, Alignment.Center) {
                            Icon(Icons.Filled.Close, null, Modifier.size(18.dp), Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Pilula(if (buscaAberta) "Fechar busca" else if (d0 != null) "Trocar música" else "Escolher música", Modifier.align(Alignment.Start), claro = false, icon = Icons.Filled.MusicNote, compacta = true) { buscaAberta = !buscaAberta }
                AnimatedVisibility(buscaAberta, enter = expandVertically(spring(.8f, 300f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Campo(q, { q = it }, "Buscar música", Icons.Filled.Search)
                        if (buscando) CircularProgressIndicator(Modifier.padding(top = 14.dp).size(22.dp), Color.White, 2.dp)
                        achados.forEach { t ->
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp).toca { dest = t; destMudou = true; buscaAberta = false; q = ""; Sfx.pop() }.clip(RoundedCornerShape(14.dp)).background(Surf).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Img(t.art, Modifier.size(46.dp), RoundedCornerShape(6.dp))
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    T(t.name, 15, 600, Color.White)
                                    T(t.artist, 12, 400, Sub)
                                }
                            }
                        }
                        if (!buscando && q.isNotBlank() && achados.isEmpty()) T("Nenhuma música encontrada.", 13, 500, Sub, Modifier.padding(top = 12.dp))
                    }
                }
                if (p.artist) {
                    T("iStudio", 13, 600, Sub, Modifier.align(Alignment.Start).padding(top = 26.dp, bottom = 8.dp))
                    Pilula("Lançar música ou álbum", Modifier.fillMaxWidth(), claro = false, icon = Icons.Filled.MusicNote) { onStudio() }
                }
                err?.let { T(it, 13, 600, Color(0xFFFF6B6B), Modifier.padding(top = 14.dp), 4, TextAlign.Center) }
                Spacer(Modifier.height(40.dp))
            }
            if (aviso) AvisoLock { aviso = false }
        }
    }
}

// ======================================================================
//  RAIZ: carregando -> entrar/cadastrar -> app
// ======================================================================
@Composable fun AuthFlow(auth: Auth) {
    val scope = rememberCoroutineScope()
    var tela by remember { mutableStateOf(0) }   // 0 entrar · 1 cadastro de ouvinte · 2 cadastro de artista
    var popup by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var alvo by remember { mutableStateOf(Color(0xFF8A1020) to Color(0xFF2A0509)) }
    val c1 by animateColorAsState(alvo.first, tween(900), label = "a1")
    val c2 by animateColorAsState(alvo.second, tween(900), label = "a2")
    LaunchedEffect(tela) {
        if (tela == 0) alvo = Color(0xFF8A1020) to Color(0xFF2A0509)
        else if (tela == 1) alvo = Color(0xFF2A7BFF) to Color(0xFF071A55)
    }
    BackHandler(popup || tela == 1) { if (popup) popup = false else tela = 0 }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Fundo(c1, c2)
            AnimatedContent(tela, Modifier.fillMaxSize(), transitionSpec = {
                if (targetState > initialState) (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            }, label = "af") { t ->
                when (t) {
                    0 -> Entrada(auth, aviso) { popup = true; Sfx.whoosh(); scope.launch { delay(170); Sfx.pop() } }
                    1 -> FormOuvinte(auth, { tela = 0 }) { aviso = it; tela = 0 }
                    else -> WizardArtista(auth, { tela = 0 }, { alvo = it }) { aviso = it; tela = 0 }
                }
            }
            AnimatedVisibility(popup, enter = fadeIn(), exit = fadeOut(tween(220))) {
                Escolha({ popup = false }) { art -> popup = false; Sfx.whoosh(); aviso = null; tela = if (art) 2 else 1 }
            }
        }
    }
}

@Composable fun Root(stats: Stats, auth: Auth) {
    LaunchedEffect(Unit) { auth.start() }
    val s = if (!auth.ready) 0 else if (!auth.logged || auth.hold) 1 else 2
    Crossfade(s, Modifier.fillMaxSize().background(Color.Black), label = "root") { x ->
        when (x) {
            0 -> Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { Icon(Icons.Filled.MusicNote, null, Modifier.size(48.dp), Color.White) }
            1 -> AuthFlow(auth)
            else -> App(stats, auth)
        }
    }
}
