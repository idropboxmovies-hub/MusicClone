package com.musicclone

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// =====================================================================
// CONFIG do Supabase (a chave "publishable" foi feita para ficar no app;
// a segurança vem das regras RLS do arquivo istudio_supabase.sql)
// =====================================================================
object Studio {
    const val SUPA_URL = "https://qimhsxuvuqpsybcbpuwq.supabase.co"
    const val SUPA_KEY = "sb_publishable_cW0GTnKr50A4pYrbEtlhmg_HDthwwy7"
    val GENEROS = listOf(
        "Pop", "Hip Hop", "Rap", "Trap", "R&B", "Rock", "Eletrônica", "Funk", "Sertanejo", "Pagode",
        "Samba", "MPB", "Gospel", "Reggae", "Lo-fi", "Indie", "Jazz", "Clássica", "Reggaeton", "K-Pop"
    )
    const val MAX_GENEROS = 3
}

// =====================================================================
// Modelo
// =====================================================================
class StudioTrack(val key: Int) {
    var uri by mutableStateOf<Uri?>(null)
    var arquivo by mutableStateOf("")
    var titulo by mutableStateOf("")
    var dur by mutableStateOf(0)
    var explicito by mutableStateOf(false)
}

class StudioEstado {
    var enviando by mutableStateOf(false)
    var msg by mutableStateOf("")
    var prog by mutableStateOf(0f)
    var erro by mutableStateOf<String?>(null)
    var pronto by mutableStateOf(false)
}

// =====================================================================
// Rede (Supabase Storage + REST), sem biblioteca extra
// =====================================================================
private fun nomeDoArquivo(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "arquivo"

private fun duracaoSeg(ctx: Context, uri: Uri): Int = runCatching {
    val r = MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    r.release()
    (ms / 1000).toInt()
}.getOrDefault(0)

private fun extensao(mime: String, padrao: String): String =
    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: padrao

private fun StudioTrack.escolher(ctx: Context, u: Uri, tituloPadrao: Boolean) {
    uri = u; arquivo = nomeDoArquivo(ctx, u); dur = duracaoSeg(ctx, u)
    if (tituloPadrao && titulo.isBlank()) titulo = arquivo.substringBeforeLast('.')
}

private suspend fun studioUpload(ctx: Context, token: String, bucket: String, path: String, uri: Uri, extPadrao: String): String =
    withContext(Dispatchers.IO) {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val size = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        val caminho = "$path.${extensao(mime, extPadrao)}"
        val c = URL("${Studio.SUPA_URL}/storage/v1/object/$bucket/$caminho").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 180000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("apikey", Studio.SUPA_KEY)
            c.setRequestProperty("Content-Type", mime)
            c.setRequestProperty("x-upsert", "true")
            if (size > 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(0)
            val ins = ctx.contentResolver.openInputStream(uri) ?: error("Não consegui abrir o arquivo")
            ins.use { i -> c.outputStream.use { o -> i.copyTo(o) } }
            val code = c.responseCode
            if (code !in 200..299) error("Upload falhou ($code): ${c.errorStream?.bufferedReader()?.readText()}")
        } finally { c.disconnect() }
        "${Studio.SUPA_URL}/storage/v1/object/public/$bucket/$caminho"
    }

private suspend fun studioInsert(token: String, tabela: String, corpo: String) = withContext(Dispatchers.IO) {
    val c = URL("${Studio.SUPA_URL}/rest/v1/$tabela").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("apikey", Studio.SUPA_KEY)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Prefer", "return=minimal")
        c.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) error("Erro ao salvar ($code): ${c.errorStream?.bufferedReader()?.readText()}")
    } finally { c.disconnect() }
}

private suspend fun studioPublicar(
    ctx: Context, token: String, userId: String, tipo: String, titulo: String, capa: Uri,
    generos: List<String>, faixas: List<StudioTrack>, onProgress: (String, Float) -> Unit
) {
    val relId = UUID.randomUUID().toString()
    val total = faixas.size + 2f
    onProgress("Enviando a capa…", 0f)
    val capaUrl = studioUpload(ctx, token, "covers", "$userId/$relId/capa", capa, "jpg")

    val urls = ArrayList<String>()
    faixas.forEachIndexed { k, f ->
        onProgress(if (faixas.size == 1) "Enviando o áudio…" else "Enviando faixa ${k + 1} de ${faixas.size}…", (k + 1) / total)
        urls.add(studioUpload(ctx, token, "audio", "$userId/$relId/faixa_${"%02d".format(k + 1)}", f.uri!!, "mp3"))
    }

    onProgress("Publicando…", (faixas.size + 1) / total)
    val rel = JSONObject()
        .put("id", relId).put("artist_id", userId).put("type", tipo).put("title", titulo)
        .put("cover_url", capaUrl).put("genres", JSONArray(generos))
        .put("explicit", faixas.any { it.explicito })
    studioInsert(token, "releases", rel.toString())

    val arr = JSONArray()
    faixas.forEachIndexed { k, f ->
        arr.put(JSONObject()
            .put("release_id", relId).put("track_number", k + 1)
            .put("title", if (tipo == "single") titulo else f.titulo.trim())
            .put("audio_url", urls[k]).put("duration_sec", f.dur).put("explicit", f.explicito))
    }
    studioInsert(token, "release_tracks", arr.toString())
    onProgress("Pronto!", 1f)
}

private fun studioIniciar(
    scope: CoroutineScope, ctx: Context, token: String?, userId: String?, tipo: String, titulo: String,
    capa: Uri, generos: List<String>, faixas: List<StudioTrack>, st: StudioEstado
) {
    if (token == null || userId == null) { st.erro = "Você precisa estar logado para publicar."; return }
    st.erro = null; st.enviando = true; st.prog = 0f
    scope.launch {
        runCatching { studioPublicar(ctx, token, userId, tipo, titulo, capa, generos, faixas) { m, p -> st.msg = m; st.prog = p } }
            .onSuccess { st.pronto = true }
            .onFailure { st.erro = it.message ?: "Algo deu errado" }
        st.enviando = false
    }
}

// =====================================================================
// Componentes compartilhados
// =====================================================================
@Composable private fun StBtn(texto: String, ativo: Boolean = true, cheio: Boolean = true, m: Modifier = Modifier, f: () -> Unit) {
    val corFundo = if (cheio) (if (ativo) Red else Red.copy(alpha = .35f)) else Color.Gray.copy(alpha = .18f)
    Box(m.height(50.dp).clip(CircleShape).background(corFundo).clickable(enabled = ativo) { f() }, Alignment.Center) {
        T(texto, 16, 600, if (cheio) Color.White else LocalContentColor.current, Modifier.padding(horizontal = 22.dp))
    }
}

@Composable private fun StCampo(valor: String, mudou: (String) -> Unit, dica: String, m: Modifier = Modifier) {
    Box(m.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Gray.copy(alpha = .15f)).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (valor.isEmpty()) T(dica, 17, 400, Sub)
        BasicTextField(
            valor, mudou, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = LocalContentColor.current, fontSize = 17.sp, fontFamily = Sf),
            cursorBrush = SolidColor(Red)
        )
    }
}

@Composable private fun StOpcao(titulo: String, sub: String, icone: ImageVector, f: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(Color.Gray.copy(alpha = .15f)).clickable { f() }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icone, null, Modifier.size(30.dp), Red)
        Column(Modifier.padding(start = 16.dp).weight(1f)) { T(titulo, 18, 700); T(sub, 13, 400, Sub, ml = 2) }
        Icon(Icons.Filled.ChevronRight, null, tint = Sub)
    }
}

@Composable private fun StTopo(titulo: String, direita: String = "", ativo: Boolean = true, voltar: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = ativo) { voltar() }, Alignment.Center) {
            Icon(Icons.Filled.ArrowBackIosNew, null, Modifier.size(20.dp), Red)
        }
        T(titulo, 24, 700, m = Modifier.weight(1f).padding(start = 4.dp))
        if (direita.isNotEmpty()) T(direita, 13, 500, Sub, Modifier.padding(end = 12.dp))
    }
}

@Composable private fun StGeneros(sel: MutableList<String>) {
    T("Gêneros", 18, 700, m = Modifier.padding(bottom = 4.dp))
    T("Escolha até ${Studio.MAX_GENEROS} (${sel.size} selecionado${if (sel.size == 1) "" else "s"})", 13, 400, Sub, Modifier.padding(bottom = 12.dp))
    Studio.GENEROS.chunked(3).forEach { linha ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            linha.forEach { g ->
                val on = g in sel
                Box(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(if (on) Red else Color.Gray.copy(alpha = .18f))
                    .clickable { if (on) sel.remove(g) else if (sel.size < Studio.MAX_GENEROS) sel.add(g) }, Alignment.Center) {
                    T(g, 14, 600, if (on) Color.White else LocalContentColor.current)
                }
            }
            repeat(3 - linha.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable private fun StEnviando(st: StudioEstado) {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T(st.msg, 18, 600, a = TextAlign.Center, ml = 2)
        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.Gray.copy(alpha = .2f))) {
            Box(Modifier.fillMaxWidth(st.prog.coerceIn(0f, 1f)).fillMaxHeight().background(Red))
        }
        T("Não feche o app até terminar.", 13, 400, Sub, Modifier.padding(top = 14.dp))
    }
}

@Composable private fun StSucesso(titulo: String) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, null, Modifier.size(80.dp), Red)
        T("Lançamento publicado!", 24, 700, m = Modifier.padding(top = 16.dp), a = TextAlign.Center, ml = 2)
        T("\"$titulo\" já está no ar para os seus ouvintes.", 15, 400, Sub, Modifier.padding(top = 8.dp), 3, TextAlign.Center)
    }
}

@Composable private fun StFaixaCard(
    f: StudioTrack, n: Int?, pedeNome: Boolean, escolher: () -> Unit, remover: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(Color.Gray.copy(alpha = .12f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n != null) T("$n", 18, 700, Red, Modifier.width(26.dp)) else Icon(Icons.Filled.AudioFile, null, Modifier.padding(end = 10.dp).size(28.dp), Red)
            Column(Modifier.weight(1f)) {
                T(if (f.uri == null) "Nenhum arquivo" else f.arquivo, 14, 500, if (f.uri == null) Sub else LocalContentColor.current)
                if (f.uri != null && f.dur > 0) T(fmt(f.dur), 12, 400, Sub)
            }
            Box(Modifier.clip(CircleShape).background(Red).clickable { escolher() }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                T(if (f.uri == null) "Escolher" else "Trocar", 13, 600, Color.White)
            }
            if (remover != null) Box(Modifier.padding(start = 6.dp).size(32.dp).clip(CircleShape).clickable { remover() }, Alignment.Center) {
                Icon(Icons.Filled.Close, null, Modifier.size(18.dp), Sub)
            }
        }
        if (pedeNome) StCampo(f.titulo, { f.titulo = it }, "Nome da faixa${if (n != null) " $n" else ""}", Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 10.dp).clickable { f.explicito = !f.explicito }, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (f.explicito) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null, Modifier.size(22.dp), if (f.explicito) Red else Sub)
            T("  Conteúdo explícito", 13, 400, Sub)
        }
    }
}

// barra de botões com folga para a barra de navegação do app
@Composable private fun StRodape(c: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 130.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = c)

// =====================================================================
// Tela 1: escolher Single ou Álbum
// =====================================================================
@Composable fun IStudioS(artistName: String, token: () -> String?, userId: () -> String?, back: () -> Unit) {
    var modo by remember { mutableStateOf<String?>(null) }
    when (modo) {
        "single" -> StudioSingleS(artistName, token, userId) { modo = null }
        "album" -> StudioAlbumS(artistName, token, userId) { modo = null }
        else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            StTopo("iStudio", voltar = back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp)) {
                T("O que você vai lançar?", 26, 700, ml = 2, m = Modifier.padding(bottom = 6.dp))
                T("Escolha o formato do lançamento.", 14, 400, Sub, Modifier.padding(bottom = 20.dp))
                StOpcao("Single", "Uma música só, tudo em uma tela", Icons.Filled.MusicNote) { modo = "single" }
                StOpcao("Álbum", "Várias faixas, passo a passo", Icons.Filled.Album) { modo = "album" }
            }
        }
    }
}

// =====================================================================
// Tela 2: SINGLE (tela única, rápida)
// =====================================================================
@Composable fun StudioSingleS(artistName: String, token: () -> String?, userId: () -> String?, voltar: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { StudioEstado() }
    var capa by remember { mutableStateOf<Uri?>(null) }
    var titulo by remember { mutableStateOf("") }
    val generos = remember { mutableStateListOf<String>() }
    val faixa = remember { StudioTrack(0) }

    val pickCapa = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) capa = u }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { faixa.escolher(ctx, u, false); if (titulo.isBlank()) titulo = faixa.arquivo.substringBeforeLast('.') }
    }
    BackHandler(!st.enviando) { voltar() }

    val ok = capa != null && titulo.isNotBlank() && generos.isNotEmpty() && faixa.uri != null

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        StTopo("Novo single", ativo = !st.enviando, voltar = voltar)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
            when {
                st.pronto -> StSucesso(titulo)
                st.enviando -> StEnviando(st)
                else -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(130.dp).clip(RoundedCornerShape(14.dp)).background(Color.Gray.copy(alpha = .15f)).clickable { pickCapa.launch("image/*") }, Alignment.Center) {
                            if (capa != null) AsyncImage(capa, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Image, null, Modifier.size(34.dp), Sub); T("Capa", 13, 500, Sub)
                            }
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            StCampo(titulo, { titulo = it }, "Nome da música")
                            T(artistName, 13, 400, Sub, Modifier.padding(top = 10.dp, start = 4.dp))
                        }
                    }
                    T("Áudio", 18, 700, m = Modifier.padding(top = 24.dp, bottom = 10.dp))
                    StFaixaCard(faixa, null, false, { pickAudio.launch("audio/*") })
                    Spacer(Modifier.height(10.dp))
                    StGeneros(generos)
                    st.erro?.let { T(it, 13, 500, Red, Modifier.padding(top = 10.dp), 6) }
                }
            }
        }
        if (!st.enviando) StRodape {
            if (st.pronto) StBtn("Concluir", m = Modifier.weight(1f)) { voltar() }
            else StBtn(if (st.erro != null) "Tentar de novo" else "Publicar single", ok, m = Modifier.weight(1f)) {
                studioIniciar(scope, ctx, token(), userId(), "single", titulo.trim(), capa!!, generos.toList(), listOf(faixa), st)
            }
        }
    }
}

// =====================================================================
// Tela 3: ÁLBUM (assistente passo a passo)
// =====================================================================
@Composable fun StudioAlbumS(artistName: String, token: () -> String?, userId: () -> String?, voltar: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { StudioEstado() }
    var passo by remember { mutableStateOf(0) }            // 0 capa · 1 nome · 2 gêneros · 3 faixas · 4 revisar
    var capa by remember { mutableStateOf<Uri?>(null) }
    var titulo by remember { mutableStateOf("") }
    val generos = remember { mutableStateListOf<String>() }
    var contador by remember { mutableStateOf(1) }
    val faixas = remember { mutableStateListOf(StudioTrack(0)) }
    var alvo by remember { mutableStateOf(0) }

    val pickCapa = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) capa = u }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null && alvo in faixas.indices) faixas[alvo].escolher(ctx, u, true)
    }
    BackHandler(!st.enviando && !st.pronto) { if (passo > 0) passo-- else voltar() }

    val podeAvancar = when (passo) {
        0 -> capa != null
        1 -> titulo.isNotBlank()
        2 -> generos.isNotEmpty()
        3 -> faixas.size >= 2 && faixas.all { it.uri != null && it.titulo.isNotBlank() }
        else -> true
    }
    val nomes = listOf("Capa", "Nome", "Gêneros", "Faixas", "Revisar")

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        StTopo("Novo álbum", if (st.pronto) "" else "${passo + 1}/5 · ${nomes[passo]}", !st.enviando) { if (passo > 0 && !st.pronto) passo-- else voltar() }
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.Gray.copy(alpha = .2f))) {
            Box(Modifier.fillMaxWidth(if (st.pronto) 1f else (passo + 1) / 5f).fillMaxHeight().background(Red))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp)) {
            when {
                st.pronto -> StSucesso(titulo)
                st.enviando -> StEnviando(st)
                passo == 0 -> {
                    T("Capa do álbum", 26, 700, m = Modifier.padding(bottom = 6.dp))
                    T("Use uma imagem quadrada, de preferência 3000 x 3000.", 14, 400, Sub, Modifier.padding(bottom = 18.dp), 2)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.Gray.copy(alpha = .15f)).clickable { pickCapa.launch("image/*") }, Alignment.Center) {
                        if (capa != null) AsyncImage(capa, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Image, null, Modifier.size(48.dp), Sub); T("Toque para escolher", 15, 500, Sub, Modifier.padding(top = 8.dp))
                        }
                    }
                    if (capa != null) StBtn("Trocar imagem", cheio = false, m = Modifier.padding(top = 14.dp)) { pickCapa.launch("image/*") }
                }
                passo == 1 -> {
                    T("Nome do álbum", 26, 700, m = Modifier.padding(bottom = 18.dp))
                    StCampo(titulo, { titulo = it }, "Título do álbum")
                    T("Artista: $artistName", 13, 400, Sub, Modifier.padding(top = 12.dp))
                }
                passo == 2 -> {
                    T("Gêneros do álbum", 26, 700, m = Modifier.padding(bottom = 16.dp), ml = 2)
                    StGeneros(generos)
                }
                passo == 3 -> {
                    T("Faixas do álbum", 26, 700, m = Modifier.padding(bottom = 6.dp))
                    T("Mínimo de 2 faixas. Envie um áudio e um nome para cada uma (MP3, M4A, WAV ou FLAC).", 14, 400, Sub, Modifier.padding(bottom = 16.dp), 3)
                    faixas.forEachIndexed { k, f ->
                        StFaixaCard(f, k + 1, true, { alvo = k; pickAudio.launch("audio/*") }, if (faixas.size > 2) ({ faixas.removeAt(k) }) else null)
                    }
                    StBtn("Adicionar faixa", cheio = false) { contador++; faixas.add(StudioTrack(contador)) }
                }
                else -> {
                    T("Revisar", 26, 700, m = Modifier.padding(bottom = 16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(capa, null, Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 14.dp)) {
                            T(titulo, 20, 700, ml = 2); T(artistName, 14, 400, Sub)
                            T("Álbum · ${generos.joinToString(", ")}", 12, 400, Sub, ml = 2)
                        }
                    }
                    T("Faixas", 16, 700, m = Modifier.padding(top = 20.dp, bottom = 6.dp))
                    faixas.forEachIndexed { k, f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            T("${k + 1}", 14, 600, Sub, Modifier.width(26.dp))
                            T(f.titulo, 15, 500, m = Modifier.weight(1f))
                            if (f.dur > 0) T(fmt(f.dur), 13, 400, Sub)
                        }
                    }
                    st.erro?.let { T(it, 13, 500, Red, Modifier.padding(top = 14.dp), 6) }
                }
            }
        }

        if (!st.enviando) StRodape {
            when {
                st.pronto -> StBtn("Concluir", m = Modifier.weight(1f)) { voltar() }
                passo < 4 -> StBtn("Continuar", podeAvancar, m = Modifier.weight(1f)) { passo++ }
                else -> StBtn(if (st.erro != null) "Tentar de novo" else "Publicar álbum", m = Modifier.weight(1f)) {
                    studioIniciar(scope, ctx, token(), userId(), "album", titulo.trim(), capa!!, generos.toList(), faixas.toList(), st)
                }
            }
        }
    }
}
