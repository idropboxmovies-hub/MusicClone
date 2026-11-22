package com.musicclone

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import androidx.compose.ui.text.style.TextAlign

data class LabelArtist(val id: String, val name: String, val genre: String = "", val provider: String = "itunes", val link: String = "")
data class LabelData(
    val id: String, val ownerId: String, val name: String,
    val instagram: String = "", val tiktok: String = "", val xUrl: String = "",
    val squareLogo: String? = null, val wideLogo: String? = null,
    val verified: Boolean = false, val artists: List<LabelArtist> = emptyList()
)

private fun spotifyArtistId(raw: String): String? = Regex("(?:https?://)?open\\.spotify\\.com/artist/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
    .find(raw.trim())?.groupValues?.getOrNull(1)

private fun spotifyArtistName(raw: String): String = raw.substringBefore("|").trim()
    .takeIf { it.isNotBlank() && !it.startsWith("http", true) } ?: "Artista do Spotify"

private fun validSocialUrl(raw: String, hosts: List<String>): Boolean {
    if (raw.isBlank()) return true
    val u = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return false
    return u.scheme == "https" && !u.host.isNullOrBlank() &&
        hosts.any { h -> u.host.equals(h, true) || u.host!!.endsWith(".$h", true) }
}

private suspend fun labelUpload(auth: Auth, bytes: ByteArray, filename: String): String? {
    val uid = auth.uid ?: return null
    val path = "labels/$uid/$filename"
    val r = sb("POST", "/storage/v1/object/label-assets/$path", bytes = bytes, token = auth.jwt,
        ctype = "image/jpeg", headers = mapOf("x-upsert" to "true"))
    return if (r.ok) "$SB_URL/storage/v1/object/public/label-assets/$path?v=${System.currentTimeMillis()}" else null
}

private suspend fun saveLabel(auth: Auth, data: LabelData, square: ByteArray?, wide: ByteArray?): String? {
    val uid = auth.uid ?: return "Sessão não encontrada. Entre novamente."
    val squareUrl = square?.let { labelUpload(auth, it, "logo-square.jpg") } ?: data.squareLogo
    if (square != null && squareUrl == null) return "Não foi possível enviar o logotipo quadrado."
    val wideUrl = wide?.let { labelUpload(auth, it, "logo-wide.jpg") } ?: data.wideLogo
    if (wide != null && wideUrl == null) return "Não foi possível enviar o logotipo horizontal."
    val body = JSONObject()
        .put("owner_id", uid).put("name", data.name)
        .put("instagram_url", data.instagram).put("tiktok_url", data.tiktok).put("x_url", data.xUrl)
        .put("logo_square_url", squareUrl ?: JSONObject.NULL).put("logo_wide_url", wideUrl ?: JSONObject.NULL)
        .put("verified", data.verified)
    val existing = sb("GET", "/rest/v1/labels?owner_id=eq.$uid&select=id&limit=1", token = auth.jwt)
    val id = existing.arr()?.optJSONObject(0)?.optString("id").orEmpty()
    val res = if (id.isNotBlank()) sb("PATCH", "/rest/v1/labels?id=eq.$id", body.put("id", id).toString(), auth.jwt,
        mapOf("Prefer" to "return=representation"))
    else sb("POST", "/rest/v1/labels", body.toString(), auth.jwt, mapOf("Prefer" to "return=representation"))
    if (!res.ok) return "Não foi possível salvar a gravadora (${res.code}). Verifique a migração SQL e as permissões do Supabase."
    val savedId = res.arr()?.optJSONObject(0)?.optString("id").orEmpty().ifBlank { id }
    if (savedId.isBlank()) return "Gravadora salva, mas não foi possível confirmar seu identificador."
    sb("DELETE", "/rest/v1/label_artists?label_id=eq.$savedId", token = auth.jwt)
    val arr = JSONArray()
    data.artists.distinctBy { it.provider + ":" + it.id }.forEach { a ->
        arr.put(JSONObject().put("label_id", savedId).put("provider", a.provider)
            .put("external_artist_id", a.id.toString()).put("artist_name", a.name).put("artist_url", a.link))
    }
    if (arr.length() > 0) {
        val ar = sb("POST", "/rest/v1/label_artists", arr.toString(), auth.jwt,
            mapOf("Prefer" to "return=minimal"))
        if (!ar.ok) return "Gravadora salva, mas não foi possível salvar a lista de artistas (${ar.code})."
    }
    return null
}

private suspend fun loadLabel(auth: Auth): LabelData? {
    val uid = auth.uid ?: return null
    val r = sb("GET", "/rest/v1/labels?owner_id=eq.$uid&select=*&limit=1", token = auth.jwt)
    val j = r.arr()?.optJSONObject(0) ?: return null
    val id = j.optString("id")
    val a = sb("GET", "/rest/v1/label_artists?label_id=eq.$id&select=*&order=artist_name.asc", token = auth.jwt).arr()
    val artists = (0 until (a?.length() ?: 0)).mapNotNull { k ->
        a?.optJSONObject(k)?.let { x ->
            LabelArtist(x.optString("external_artist_id"),
                x.optString("artist_name"), provider = x.optString("provider", "itunes"), link = x.optString("artist_url"))
        }
    }
    return LabelData(id, uid, j.optString("name"), j.optString("instagram_url"),
        j.optString("tiktok_url"), j.optString("x_url"),
        j.optString("logo_square_url").takeIf { it.startsWith("http") },
        j.optString("logo_wide_url").takeIf { it.startsWith("http") },
        j.optBoolean("verified"), artists)
}

private suspend fun lookupLabelArtistSongs(id: Long): List<Track> = withContext(Dispatchers.IO) {
    if (id <= 0L) return@withContext emptyList()
    runCatching {
        val url = "https://itunes.apple.com/lookup?id=$id&entity=song&limit=10&country=BR"
        val c = URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val a = j.optJSONArray("results") ?: return@runCatching emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("trackId") }
            .map { x -> Track(x.optString("trackName"), x.optString("artistName"),
                x.optString("artworkUrl100").replace("100x100", "600x600"),
                x.optString("trackViewUrl"), x.optString("collectionName"),
                x.optLong("artistId"), x.optString("releaseDate").take(4),
                x.optBoolean("trackExplicitness").let { it }, x.optInt("trackTimeMillis")) }
    }.getOrDefault(emptyList())
}


private suspend fun lookupLabelCatalog(artists: List<LabelArtist>): List<Track> = coroutineScope {
    artists.mapNotNull { artist ->
        val appleId = if (artist.provider == "itunes") artist.id.toLongOrNull() else null
        async(Dispatchers.IO) {
            val id = appleId ?: if (artist.name.isNotBlank() && artist.name != "Artista do Spotify") resolveId(artist.name) else 0L
            lookupLabelArtistSongs(id)
        }
    }
        .flatMap { deferred -> runCatching { deferred.await() }.getOrDefault(emptyList()) }
        .distinctBy { it.url.ifBlank { it.name + "|" + it.artist } }
        .take(30)
}

@Composable fun WizardGravadora(auth: Auth, sair: () -> Unit, fim: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var instagram by remember { mutableStateOf("") }
    var tiktok by remember { mutableStateOf("") }
    var xUrl by remember { mutableStateOf("") }
    var squareUri by remember { mutableStateOf<Uri?>(null) }
    var wideUri by remember { mutableStateOf<Uri?>(null) }
    var squareBytes by remember { mutableStateOf<ByteArray?>(null) }
    var wideBytes by remember { mutableStateOf<ByteArray?>(null) }
    var search by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var selected by remember { mutableStateOf(listOf<LabelArtist>()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var checkingAccount by remember { mutableStateOf(false) }
    var celebration by remember { mutableStateOf(false) }

    val squarePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { squareUri = u; preparing = true; scope.launch { squareBytes = prepararFoto(ctx, u); preparing = false } }
    }
    val widePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { wideUri = u; preparing = true; scope.launch { wideBytes = prepararFoto(ctx, u); preparing = false } }
    }
    LaunchedEffect(search) {
        if (search.isBlank() || spotifyArtistId(search) != null) { hits = emptyList(); searching = false }
        else { searching = true; delay(450); hits = buscarArtistas(search.substringBefore("|").trim()); searching = false }
    }
    fun next() {
        error = null
        when (step) {
            0 -> if (name.isBlank()) error = "Informe o nome da gravadora." else if (
                !validSocialUrl(instagram, listOf("instagram.com")) ||
                !validSocialUrl(tiktok, listOf("tiktok.com")) ||
                !validSocialUrl(xUrl, listOf("x.com", "twitter.com"))
            ) error = "Confira os links. Use URLs HTTPS válidas das respectivas redes."
            1 -> if (squareBytes == null) error = "Escolha o logotipo quadrado 1:1." else step++
            2 -> step++
            3 -> if (!emailOk(email) || password.length < 6) error = "Informe um email válido e uma senha com pelo menos 6 caracteres." else step++
        }
    }
    fun create() {
        if (busy || preparing) return
        busy = true; error = null
        scope.launch {
            checkingAccount = true
            val meta = JSONObject().put("role", "label").put("display_name", name.trim())
                .put("label_name", name.trim())
            val authRes = auth.signUp(email, password, meta)
            if (authRes.err != null) { checkingAccount = false; busy = false; error = authRes.err; return@launch }
            if (authRes.confirm) {
                checkingAccount = false
                busy = false
                fim("Conta criada! Confirme seu email e entre novamente. Depois será possível concluir o cadastro da gravadora.")
                return@launch
            }
            delay(1100)
            val data = LabelData("", auth.uid.orEmpty(), name.trim(), instagram.trim(), tiktok.trim(), xUrl.trim(),
                verified = true, artists = selected)
            val e = saveLabel(auth, data, squareBytes, wideBytes)
            checkingAccount = false
            busy = false
            if (e != null) { error = e; return@launch }
            verified = true; celebration = true; Sfx.sucesso()
            delay(1800)
            fim("Gravadora verificada e cadastrada com sucesso!")
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Glass(Icons.Filled.ArrowBackIosNew) { if (!busy) { if (step == 0) sair() else step-- } }
            Spacer(Modifier.width(18.dp))
            repeat(4) { k -> Traco(k < step, k == step, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(28.dp))
        AnimatedContent(step, label = "labelWizard", transitionSpec = {
            (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
        }) { current ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (current) {
                    0 -> {
                        T("Sua gravadora no beats", 30, 800, Color.White, ml = 3)
                        T("Comece pela identidade e pelos canais oficiais.", 14, 500, Sub, Modifier.padding(top = 8.dp, bottom = 24.dp))
                        Campo(name, { name = it.take(60) }, "Nome da gravadora", Icons.Filled.Business)
                        Spacer(Modifier.height(12.dp))
                        Campo(instagram, { instagram = it }, "Link do Instagram (opcional)", Icons.Filled.CameraAlt, kb = KeyboardType.Uri)
                        Spacer(Modifier.height(12.dp))
                        Campo(tiktok, { tiktok = it }, "Link do TikTok (opcional)", Icons.Filled.VideoLibrary, kb = KeyboardType.Uri)
                        Spacer(Modifier.height(12.dp))
                        Campo(xUrl, { xUrl = it }, "Link do X/Twitter (opcional)", Icons.Filled.AlternateEmail, kb = KeyboardType.Uri)
                    }
                    1 -> {
                        T("Identidade visual", 30, 800, Color.White)
                        T("Logo quadrada obrigatória e versão horizontal opcional.", 14, 500, Sub, Modifier.padding(top = 8.dp, bottom = 24.dp))
                        Box(Modifier.size(168.dp).clip(RoundedCornerShape(24.dp)).background(Surf).border(1.dp, Line, RoundedCornerShape(24.dp))
                            .clickable { squarePicker.launch("image/*") }, Alignment.Center) {
                            if (squareUri != null) AsyncImage(squareUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(Icons.Filled.AddPhotoAlternate, null, Modifier.size(46.dp), Color.White)
                        }
                        T("Logo 1:1 · obrigatório", 13, 600, Color.White, Modifier.padding(top = 10.dp))
                        Spacer(Modifier.height(24.dp))
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Surf).border(1.dp, Line, RoundedCornerShape(18.dp))
                            .clickable { widePicker.launch("image/*") }, Alignment.Center) {
                            if (wideUri != null) AsyncImage(wideUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.AddPhotoAlternate, null, tint = Sub); T("Adicionar logo 16:9 (opcional)", 13, 600, Sub, Modifier.padding(start = 8.dp)) }
                        }
                    }
                    2 -> {
                        T("Artistas da gravadora", 30, 800, Color.White, ml = 3)
                        T("Busque no catálogo Apple/iTunes e selecione vários artistas.", 14, 500, Sub, Modifier.padding(top = 8.dp, bottom = 18.dp))
                        Campo(search, { search = it }, "Nome do artista ou link Spotify", Icons.Filled.Search, kb = KeyboardType.Uri)
                        val spotifyId = spotifyArtistId(search)
                        if (spotifyId != null) {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                                .background(Surf2).clickable {
                                    val artistName = spotifyArtistName(search)
                                    if (selected.none { it.provider == "spotify" && it.id == spotifyId })
                                        selected = selected + LabelArtist(spotifyId, artistName, provider = "spotify", link = "https://open.spotify.com/artist/$spotifyId")
                                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.MusicNote, null, tint = Color.White)
                                T("Adicionar artista do Spotify · ${spotifyArtistName(search)}", 13, 600, Color.White, Modifier.weight(1f).padding(start = 8.dp), 2)
                                Icon(Icons.Filled.AddCircleOutline, null, tint = Color.White)
                            }
                            T("Para identificar o nome, use: Nome do artista | link do Spotify.", 11, 400, Sub, Modifier.padding(top = 5.dp))
                        }
                        if (searching) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), Color.White, 2.dp)
                        hits.forEach { h ->
                            val isSelected = selected.any { it.id == h.id.toString() && it.provider == "itunes" }
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp))
                                .background(Surf).border(1.dp, if (isSelected) Color.White else Line, RoundedCornerShape(14.dp))
                                .clickable {
                                    selected = if (isSelected) selected.filterNot { it.id == h.id.toString() && it.provider == "itunes" }
                                    else selected + LabelArtist(h.id.toString(), h.name, h.genre)
                                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { T(h.name, 15, 700, Color.White); T(h.genre, 12, 500, Sub) }
                                Icon(if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline, null, tint = Color.White)
                            }
                        }
                        if (selected.isNotEmpty()) {
                            T("${selected.size} artistas selecionados", 13, 600, Color.White, Modifier.padding(top = 16.dp))
                            selected.forEach { a -> T("• ${a.name}", 12, 500, Sub, Modifier.align(Alignment.Start).padding(top = 3.dp)) }
                        }
                        T("Apple/iTunes: busca direta por nome. Spotify: você pode vincular o perfil colando Nome do artista | link do Spotify; as músicas são cruzadas pelo nome no catálogo Apple enquanto a API Spotify não estiver configurada.", 11, 400, Sub, Modifier.padding(top = 12.dp), 4)
                    }
                    else -> {
                        T("Crie seu acesso", 30, 800, Color.White)
                        T("Seu email e senha serão usados para entrar na conta da gravadora.", 14, 500, Sub, Modifier.padding(top = 8.dp, bottom = 22.dp))
                        Campo(email, { email = it }, "Email", Icons.Filled.Email, kb = KeyboardType.Email)
                        Spacer(Modifier.height(12.dp))
                        Campo(password, { password = it }, "Senha (mínimo 6 caracteres)", Icons.Filled.Lock, senha = true)
                        Forca(password)
                    }
                }
            }
        }
        error?.let { T(it, 13, 600, Color(0xFFFF6B6B), Modifier.padding(top = 14.dp), 4, TextAlign.Center) }
        Spacer(Modifier.height(24.dp))
        Pilula(if (step == 3) "Criar gravadora" else "Continuar", Modifier.fillMaxWidth(), loading = busy || preparing,
            icon = if (step == 3) Icons.Filled.Verified else Icons.Filled.ArrowForward) {
            if (step == 3) create() else next()
        }
        Spacer(Modifier.height(40.dp))
        if (checkingAccount) {
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF17171D)).border(1.dp, Line, RoundedCornerShape(28.dp)).padding(28.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val spin by rememberInfiniteTransition(label = "labelVerify").animateFloat(
                            0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "labelVerifySpin"
                        )
                        Box(Modifier.size(82.dp).border(2.dp, Color.White.copy(alpha = .25f), CircleShape), Alignment.Center) {
                            Icon(Icons.Filled.Verified, null, Modifier.size(42.dp).graphicsLayer { rotationZ = spin }, Color.White)
                        }
                        T("Verificando sua conta", 22, 800, Color.White, Modifier.padding(top = 22.dp), a = TextAlign.Center)
                        T("Estamos validando os dados da gravadora e preparando seu perfil.", 13, 500, Sub,
                            Modifier.padding(top = 8.dp), 3, TextAlign.Center)
                    }
                }
            }
        }
        if (celebration) Celebra("Cadastro concluído", "Sua gravadora está verificada e pronta para gerenciar artistas")
    }
}

@Composable fun GravadoraS(auth: Auth, back: () -> Unit, abrirArtista: (LabelArtist) -> Unit = {}, editar: () -> Unit) {
    var label by remember { mutableStateOf<LabelData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var hits by remember { mutableStateOf(emptyList<Track>()) }
    var artistPhotos by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(auth.uid) {
        label = loadLabel(auth)
        val artists = label?.artists.orEmpty()
        artistPhotos = artists.mapNotNull { a ->
            val appleId = a.id.toLongOrNull() ?: if (a.name != "Artista do Spotify") resolveId(a.name) else 0L
            val photo = if (appleId > 0L) fotoArtista(a.name, appleId) else null
            if (photo.isNullOrBlank()) null else a.id to photo
        }.toMap()
        hits = lookupLabelCatalog(artists)
        loading = false
    }
    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())
        .statusBarsPadding().padding(bottom = 150.dp)) {
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            label?.wideLogo?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
            Glass(Icons.Filled.ArrowBackIosNew) { back() }
        }
        Row(Modifier.padding(horizontal = 22.dp).offset(y = (-34).dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(18.dp)).background(Surf).border(1.dp, Line, RoundedCornerShape(18.dp))) {
                label?.squareLogo?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                T(label?.name ?: auth.prof?.name ?: "Gravadora", 23, 800, Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (label?.verified == true) {
                        Icon(Icons.Filled.Verified, null, Modifier.size(15.dp), Color(0xFF58A6FF))
                        T("  Gravadora verificada", 12, 600, Sub)
                    } else {
                        Icon(Icons.Filled.Schedule, null, Modifier.size(15.dp), Sub)
                        T("  Perfil cadastrado", 12, 600, Sub)
                    }
                }
            }
            Glass(Icons.Filled.MoreHoriz) { editar() }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            T("Artistas da gravadora", 20, 800, Color.White)
            if (label?.artists.isNullOrEmpty()) T("Adicione artistas em Editar perfil.", 13, 500, Sub, Modifier.padding(top = 10.dp))
            else Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                label!!.artists.forEach { a ->
                    Column(Modifier.width(104.dp).clickable { abrirArtista(a) }) {
                        Box(Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(Surf), Alignment.Center) {
                            val artistPhoto = artistPhotos[a.id]
                            if (!artistPhoto.isNullOrBlank()) AsyncImage(artistPhoto, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else T(a.name.take(1).uppercase(), 28, 800, Color.White)
                        }
                        T(a.name, 13, 600, Color.White, Modifier.padding(top = 7.dp), 2)
                    }
                }
            }
            T("Maiores Sucessos da gravadora", 20, 800, Color.White, Modifier.padding(top = 28.dp, bottom = 12.dp))
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), Color.White, 2.dp)
            else if (hits.isEmpty()) T("Ainda não encontramos músicas para os artistas vinculados.", 13, 500, Sub)
            else hits.take(5).forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${i + 1}", 14, 600, Sub, Modifier.width(24.dp))
                    Img(t.art, Modifier.size(48.dp), RoundedCornerShape(7.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) { T(t.name, 14, 700, Color.White); T(t.artist, 12, 500, Sub) }
                }
            }
            Spacer(Modifier.height(20.dp))
            listOf("Instagram" to label?.instagram, "TikTok" to label?.tiktok, "X / Twitter" to label?.xUrl).forEach { (n, u) ->
                if (!u.isNullOrBlank()) T("$n  ·  $u", 12, 500, Sub, Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable fun EditarGravadoraS(auth: Auth, back: () -> Unit) {
    var data by remember { mutableStateOf<LabelData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var instagram by remember { mutableStateOf("") }
    var tiktok by remember { mutableStateOf("") }
    var xUrl by remember { mutableStateOf("") }
    var squareBytes by remember { mutableStateOf<ByteArray?>(null) }
    var wideBytes by remember { mutableStateOf<ByteArray?>(null) }
    var squarePreview by remember { mutableStateOf<Uri?>(null) }
    var widePreview by remember { mutableStateOf<Uri?>(null) }
    var preparingLogo by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var selected by remember { mutableStateOf(listOf<LabelArtist>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val squarePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            squarePreview = uri; preparingLogo = true
            scope.launch { squareBytes = prepararFoto(ctx, uri); preparingLogo = false }
        }
    }
    val widePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            widePreview = uri; preparingLogo = true
            scope.launch { wideBytes = prepararFoto(ctx, uri); preparingLogo = false }
        }
    }
    LaunchedEffect(auth.uid) {
        data = loadLabel(auth)
        name = data?.name.orEmpty(); instagram = data?.instagram.orEmpty()
        tiktok = data?.tiktok.orEmpty(); xUrl = data?.xUrl.orEmpty()
        selected = data?.artists.orEmpty(); loading = false
    }
    LaunchedEffect(search) {
        if (search.isBlank() || spotifyArtistId(search) != null) hits = emptyList()
        else { delay(400); hits = buscarArtistas(search.substringBefore("|").trim()) }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Glass(Icons.Filled.Close) { back() }
            T("Editar perfil", 20, 800, Color.White, Modifier.weight(1f), a = TextAlign.Center)
            Pilula("Salvar", loading = busy || preparingLogo, compacta = true) {
                val d = data ?: return@Pilula
                if (preparingLogo) return@Pilula
                if (name.isBlank() || !validSocialUrl(instagram, listOf("instagram.com")) ||
                    !validSocialUrl(tiktok, listOf("tiktok.com")) ||
                    !validSocialUrl(xUrl, listOf("x.com", "twitter.com"))) {
                    error = "Confira o nome e os links das redes sociais."
                } else {
                    busy = true
                    scope.launch {
                        error = saveLabel(auth, d.copy(name = name.trim(), instagram = instagram.trim(),
                            tiktok = tiktok.trim(), xUrl = xUrl.trim(), artists = selected), squareBytes, wideBytes)
                        busy = false
                        if (error == null) back()
                    }
                }
            }
        }
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), Color.White, 2.dp)
        else {
            T("Nome da gravadora", 13, 600, Sub, Modifier.padding(bottom = 8.dp))
            Campo(name, { name = it }, "Nome", Icons.Filled.Business)
            T("Identidade visual", 13, 600, Sub, Modifier.padding(top = 22.dp, bottom = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                        .background(Surf).border(1.dp, Line, RoundedCornerShape(16.dp))
                        .clickable { squarePicker.launch("image/*") }, Alignment.Center) {
                        when {
                            squarePreview != null -> AsyncImage(squarePreview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            !data?.squareLogo.isNullOrBlank() -> AsyncImage(data?.squareLogo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else -> Icon(Icons.Filled.AddPhotoAlternate, null, tint = Color.White)
                        }
                    }
                    T("Logo 1:1", 12, 600, Color.White, Modifier.padding(top = 7.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                        .background(Surf).border(1.dp, Line, RoundedCornerShape(16.dp))
                        .clickable { widePicker.launch("image/*") }, Alignment.Center) {
                        when {
                            widePreview != null -> AsyncImage(widePreview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            !data?.wideLogo.isNullOrBlank() -> AsyncImage(data?.wideLogo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else -> Icon(Icons.Filled.AddPhotoAlternate, null, tint = Color.White)
                        }
                    }
                    T("Logo 16:9 (opcional)", 12, 600, Color.White, Modifier.padding(top = 7.dp))
                }
            }
            if (preparingLogo) T("Preparando logotipo…", 11, 500, Sub, Modifier.padding(top = 8.dp))
            T("Redes sociais (opcionais)", 13, 600, Sub, Modifier.padding(top = 22.dp, bottom = 8.dp))
            Campo(instagram, { instagram = it }, "Instagram", Icons.Filled.CameraAlt, kb = KeyboardType.Uri)
            Spacer(Modifier.height(10.dp)); Campo(tiktok, { tiktok = it }, "TikTok", Icons.Filled.VideoLibrary, kb = KeyboardType.Uri)
            Spacer(Modifier.height(10.dp)); Campo(xUrl, { xUrl = it }, "X/Twitter", Icons.Filled.AlternateEmail, kb = KeyboardType.Uri)
            T("Adicionar artistas", 13, 600, Sub, Modifier.padding(top = 22.dp, bottom = 8.dp))
            Campo(search, { search = it }, "Nome do artista ou link Spotify", Icons.Filled.Search, kb = KeyboardType.Uri)
            val spotifyIdEdit = spotifyArtistId(search)
            if (spotifyIdEdit != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surf2)
                    .clickable {
                        val artistName = spotifyArtistName(search)
                        if (selected.none { it.provider == "spotify" && it.id == spotifyIdEdit })
                            selected = selected + LabelArtist(spotifyIdEdit, artistName, provider = "spotify", link = "https://open.spotify.com/artist/$spotifyIdEdit")
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MusicNote, null, tint = Color.White)
                    T("Adicionar ${spotifyArtistName(search)} do Spotify", 13, 600, Color.White, Modifier.weight(1f).padding(start = 8.dp))
                    Icon(Icons.Filled.AddCircleOutline, null, tint = Color.White)
                }
            }
            hits.forEach { h ->
                val exists = selected.any { it.id == h.id.toString() && it.provider == "itunes" }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp).clip(RoundedCornerShape(12.dp)).background(Surf)
                    .clickable { selected = if (exists) selected.filterNot { it.id == h.id.toString() && it.provider == "itunes" } else selected + LabelArtist(h.id.toString(), h.name, h.genre) }
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(h.name, 14, 600, Color.White, Modifier.weight(1f))
                    Icon(if (exists) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline, null, tint = Color.White)
                }
            }
            T("Artistas vinculados", 13, 600, Sub, Modifier.padding(top = 20.dp, bottom = 8.dp))
            selected.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(a.name, 14, 500, Color.White, Modifier.weight(1f))
                    Icon(Icons.Filled.Close, null, Modifier.clickable { selected = selected.filterNot { it.id == a.id && it.provider == a.provider } }, tint = Sub)
                }
            }
            error?.let { T(it, 13, 600, Color(0xFFFF6B6B), Modifier.padding(top = 12.dp), 4) }
        }
        Spacer(Modifier.height(40.dp))
    }
}
