@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicclone

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// =====================================================================
// Valor pago por stream (referência: média do Spotify, que não publica tarifa fixa; costuma ficar entre US$ 0,003 e 0,005)
// =====================================================================
const val PAGA_POR_STREAM = 0.004

// token do usuário logado, para o player registrar streams sem depender de tela
object Sess { var tok: () -> String? = { null } }

// =====================================================================
// HTTP simples para o Supabase (REST, RPC e Storage)
// =====================================================================
class RespHttp(val code: Int, val corpo: String) { val ok get() = code in 200..299 }

suspend fun supaReq(metodo: String, caminho: String, token: String?, corpo: String? = null, prefer: String? = null): RespHttp = withContext(Dispatchers.IO) {
    val c = URL(Studio.SUPA_URL + caminho).openConnection() as HttpURLConnection
    try {
        c.requestMethod = metodo
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("apikey", Studio.SUPA_KEY)
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        if (corpo != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        RespHttp(code, txt)
    } finally { c.disconnect() }
}

// o Supabase devolve erros como {"message": "..."}; mostra só a mensagem quando der
private fun msgErro(corpo: String): String =
    runCatching { JSONObject(corpo).optString("message") }.getOrDefault("").ifBlank { corpo.take(200) }

// =====================================================================
// Músicas do iTunes que o artista dono excluiu: somem da plataforma para todo mundo
// =====================================================================
fun chaveDe(t: Track) = norm(t.name) + "|" + norm(t.artist)

object Oculto {
    private val set = HashSet<String>()
    private val mx = Mutex()
    private var ultima = 0L
    fun has(k: String) = synchronized(set) { set.contains(k) }
    fun add(k: String) { synchronized(set) { set.add(k) } }
    suspend fun garantir() = mx.withLock {
        val agora = System.currentTimeMillis()
        if (agora - ultima < 60_000) return@withLock
        val r = runCatching { supaReq("GET", "/rest/v1/hidden_tracks?select=track_key&limit=5000", null) }.getOrNull()
        val a = if (r != null && r.ok) runCatching { JSONArray(r.corpo) }.getOrNull() else null
        if (a == null) { ultima = agora - 50_000; return@withLock }   // falhou: tenta de novo em ~10 s
        val novo = HashSet<String>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.optString("track_key")?.takeIf { it.isNotEmpty() }?.let { novo.add(it) }
        synchronized(set) { set.clear(); set.addAll(novo) }
        ultima = agora
    }
}

suspend fun semOcultas(l: List<Track>): List<Track> {
    if (l.isEmpty()) return l
    Oculto.garantir()
    return l.filter { it.tid.isNotEmpty() || !Oculto.has(chaveDe(it)) }   // músicas do banco nunca entram nessa lista
}

// =====================================================================
// Streams reais (uma reprodução conta depois de 30 s ouvidos, como no Spotify)
// O app não grava mais direto na tabela: chama a função register_stream do Supabase.
//  - música do iStudio: só manda o id da faixa; o servidor busca nome, artista e capa no banco
//  - música do iTunes: manda os dados da faixa (o servidor não consegue confirmar esses)
// O servidor também recusa mais de 1 stream a cada 25 s por conta (devolve false, sem erro).
// =====================================================================
suspend fun registrarStream(t: Track) {
    val tok = Sess.tok() ?: return
    val o = JSONObject()
    if (t.tid.isNotEmpty()) {
        o.put("p_tid", t.tid)
    } else {
        val cr = creditos(t.artist, t.name).filter { it.isNotBlank() }.distinct()   // nomes crus; o servidor normaliza
        o.put("p_name", t.name).put("p_artist", t.artist).put("p_art", t.art)
            .put("p_credits", JSONArray(cr))
        if (t.artistId != 0L) o.put("p_itunes", t.artistId)
    }
    runCatching { supaReq("POST", "/rest/v1/rpc/register_stream", tok, o.toString()) }
        .onSuccess { if (!it.ok) Log.w("STREAM", "HTTP ${it.code}: ${it.corpo}") }
        .onFailure { Log.w("STREAM", "falhou: ${it.message}") }
}

// =====================================================================
// Excluir música
// =====================================================================
fun podeExcluir(t: Track, auth: Auth?): Boolean {
    if (auth == null) return false
    val uid = auth.uid ?: return false
    if (t.tid.isNotEmpty()) return t.uid == uid                          // música do banco: só quem lançou
    val p = auth.prof ?: return false
    return p.artist && p.itunesId != 0L && t.artistId == p.itunesId      // música do iTunes: só o artista dono do perfil
}

private fun caminhoStorage(url: String, bucket: String): String? =
    url.substringAfter("/object/public/$bucket/", "").takeIf { it.isNotEmpty() }

suspend fun apagarFaixa(token: String, t: Track, meuItunes: Long) {
    if (t.tid.isNotEmpty()) {
        val r = supaReq("DELETE", "/rest/v1/release_tracks?id=eq.${t.tid}", token, null, "return=representation")
        if (!r.ok) error("Não deu para excluir (${r.code}): ${msgErro(r.corpo)}")
        val n = runCatching { JSONArray(r.corpo).length() }.getOrDefault(0)
        if (n == 0) error("Sem permissão para excluir essa música.")
        caminhoStorage(t.url, "audio")?.let { runCatching { supaReq("DELETE", "/storage/v1/object/audio/$it", token) } }
        // se não sobrou nenhuma faixa no lançamento, apaga o lançamento (e a capa)
        val resto = supaReq("GET", "/rest/v1/release_tracks?release_id=eq.${t.rid}&select=id&limit=1", token)
        if (resto.ok && runCatching { JSONArray(resto.corpo).length() }.getOrDefault(1) == 0) {
            runCatching { supaReq("DELETE", "/rest/v1/releases?id=eq.${t.rid}", token) }
            caminhoStorage(t.art, "covers")?.let { runCatching { supaReq("DELETE", "/storage/v1/object/covers/$it", token) } }
            if (t.video.isNotBlank()) caminhoStorage(t.video, "covers")?.let { runCatching { supaReq("DELETE", "/storage/v1/object/covers/$it", token) } }
        }
    } else {
        if (meuItunes == 0L || t.artistId != meuItunes) error("Só o artista dono dessa música pode excluí-la.")
        val o = JSONObject().put("track_key", chaveDe(t)).put("track_name", t.name).put("artist", t.artist).put("itunes_artist_id", t.artistId)
        val r = supaReq("POST", "/rest/v1/hidden_tracks?on_conflict=track_key", token, o.toString(), "resolution=ignore-duplicates,return=minimal")
        if (!r.ok) error("Não deu para excluir (${r.code}): ${msgErro(r.corpo)}")
        Oculto.add(chaveDe(t))
    }
}

// os 3 pontinhos de uma música: só abre o menu "Excluir música" se quem está logado é o dono dela
@Composable fun MenuFaixa(t: Track, aoApagar: (Track) -> Unit, pad: Dp = 4.dp, conteudo: @Composable () -> Unit) {
    val auth = LocalAuth.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dono = podeExcluir(t, auth)
    var menu by remember { mutableStateOf(false) }
    var confirma by remember { mutableStateOf(false) }
    var apagando by remember { mutableStateOf(false) }
    val vermelho = Color(0xFFFF453A)
    Box {
        Box(Modifier.clip(CircleShape).then(if (dono) Modifier.clickable { menu = true } else Modifier).padding(pad), Alignment.Center) { conteudo() }
        DropdownMenu(menu, { menu = false }, Modifier.background(Color(0xFF2C2C2E))) {
            DropdownMenuItem(
                text = { Text("Excluir música", color = vermelho) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = vermelho) },
                onClick = { menu = false; confirma = true }
            )
        }
    }
    if (confirma) AlertDialog(
        onDismissRequest = { if (!apagando) confirma = false },
        containerColor = Color(0xFF2C2C2E), titleContentColor = Color.White, textContentColor = Color.White.copy(alpha = .8f),
        title = { Text("Excluir \"${t.name}\"?") },
        text = {
            Text(
                if (t.tid.isEmpty()) "Essa música veio do iTunes. Ao excluir, ela some da plataforma para todos os usuários."
                else "A música será apagada do banco de dados e vai sumir para todos os ouvintes."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                apagando = true
                scope.launch {
                    val tok = auth?.jwt
                    val r = runCatching {
                        if (tok == null) error("Você precisa estar logado.")
                        apagarFaixa(tok, t, auth?.prof?.itunesId ?: 0L)
                    }
                    apagando = false
                    r.onSuccess {
                        confirma = false
                        Toast.makeText(ctx.applicationContext, "Música excluída", Toast.LENGTH_SHORT).show()
                        aoApagar(t)
                    }.onFailure { Toast.makeText(ctx.applicationContext, it.message ?: "Erro ao excluir", Toast.LENGTH_LONG).show() }
                }
            }, enabled = !apagando) { Text(if (apagando) "Excluindo…" else "Excluir", color = vermelho) }
        },
        dismissButton = { TextButton(onClick = { confirma = false }, enabled = !apagando) { Text("Cancelar", color = Color.White) } }
    )
}

// =====================================================================
// Estatísticas do artista
// =====================================================================
class FaixaEst(val nome: String, val artista: String, val capa: String, val total: Int, val mes: Int)
class EstData(val ouvintes: Int, val total: Int, val mes: Int, val faixas: List<FaixaEst>)
class Posicao(val playlist: PlDef, val pos: Int, val nome: String, val capa: String)

// artist_stats não recebe parâmetro: o servidor descobre quem é pelo token
suspend fun estLoad(token: String): EstData {
    val r = supaReq("POST", "/rest/v1/rpc/artist_stats", token, "{}")
    if (!r.ok) {
        val dica = if (r.code == 404) " (rode o arquivo stats_supabase.sql no Supabase)" else ""
        error("Erro ${r.code}$dica: ${msgErro(r.corpo)}")
    }
    val j = JSONObject(r.corpo)
    val fa = j.optJSONArray("faixas") ?: JSONArray()
    val faixas = (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }.map {
        FaixaEst(it.optString("nome"), it.optString("artista"), js(it, "capa"), it.optInt("total"), it.optInt("mes"))
    }
    return EstData(j.optInt("ouvintes"), j.optInt("streams_total"), j.optInt("streams_mes"), faixas)
}

// em quais playlists do beats o artista está agora (só vale para artista do iTunes; música do app não entra nas paradas)
suspend fun posicoesPlaylists(nome: String, itunes: Long): List<Posicao> = coroutineScope {
    if (itunes == 0L) return@coroutineScope emptyList<Posicao>()
    PLS.map { pd ->
        async {
            val tr = runCatching { playlistTracks(pd.id) }.getOrDefault(emptyList())
            tr.mapIndexedNotNull { k, t -> if (envolve(t, nome, itunes)) Posicao(pd, k + 1, t.name, t.art) else null }
        }
    }.awaitAll().flatten()
}

private fun num(n: Int) = String.format(ptBR, "%,d", n)
private fun dinheiro(v: Double) = if (v < 1.0) String.format(ptBR, "US$ %.4f", v) else String.format(ptBR, "US$ %,.2f", v)

@Composable private fun CartaoEst(m: Modifier, titulo: String, valor: String, sub: String, grande: Boolean = false) {
    Column(m.clip(RoundedCornerShape(16.dp)).background(Color.Gray.copy(alpha = .15f)).padding(16.dp)) {
        T(titulo, 13, 600, Sub)
        T(valor, if (grande) 40 else 24, 800, m = Modifier.padding(top = 4.dp))
        T(sub, 12, 400, Sub, Modifier.padding(top = 2.dp), 2)
    }
}

@Composable fun EstatisticasS(auth: Auth, back: () -> Unit) {
    val pr = auth.prof
    var dados by remember { mutableStateOf<EstData?>(null) }
    var pos by remember { mutableStateOf<List<Posicao>?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var tentativa by remember { mutableStateOf(0) }
    LaunchedEffect(tentativa) {
        erro = null; dados = null
        val tok = auth.jwt
        if (tok == null || pr == null) { erro = "Você precisa estar logado como artista."; return@LaunchedEffect }
        runCatching { estLoad(tok) }.onSuccess { dados = it }.onFailure { erro = it.message ?: "Algo deu errado" }
    }
    LaunchedEffect(tentativa) {
        pos = null
        if (pr != null) pos = runCatching { posicoesPlaylists(pr.name, pr.itunesId) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { back() }, Alignment.Center) { Icon(Icons.Filled.ArrowBackIosNew, null, Modifier.size(20.dp), Red) }
            T("Estatísticas", 24, 700, m = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 200.dp)) {
            val d = dados
            when {
                erro != null -> {
                    T(erro!!, 14, 500, Red, Modifier.padding(top = 20.dp), 8)
                    Box(Modifier.padding(top = 16.dp).height(44.dp).clip(CircleShape).background(Red).clickable { tentativa++ }, Alignment.Center) {
                        T("Tentar de novo", 15, 600, Color.White, Modifier.padding(horizontal = 22.dp))
                    }
                }
                d == null -> T("Carregando…", 14, 400, Sub, Modifier.padding(top = 20.dp))
                else -> {
                    CartaoEst(Modifier.fillMaxWidth(), "Ouvintes mensais", num(d.ouvintes), "ouvintes únicos nos últimos 28 dias", grande = true)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CartaoEst(Modifier.weight(1f), "Streams", num(d.total), "${num(d.mes)} nos últimos 28 dias")
                        CartaoEst(Modifier.weight(1f), "Lucro estimado", dinheiro(d.total * PAGA_POR_STREAM), "${dinheiro(PAGA_POR_STREAM)} por stream")
                    }

                    H("Por música")
                    if (d.faixas.isEmpty()) T("Ainda não há streams. Cada reprodução de 30 segundos ou mais aparece aqui.", 13, 400, Sub, Modifier.padding(vertical = 6.dp), 4)
                    d.faixas.forEachIndexed { k, f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            T("${k + 1}", 15, 600, Sub, Modifier.width(26.dp))
                            Img(f.capa.ifBlank { null }, Modifier.size(46.dp), RoundedCornerShape(6.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                T(f.nome, 16, 600)
                                T("${num(f.total)} ${if (f.total == 1) "stream" else "streams"} · ${num(f.mes)} no mês", 12, 400, Sub)
                            }
                            T(dinheiro(f.total * PAGA_POR_STREAM), 13, 600, Red)
                        }
                    }

                    H("Playlists")
                    val ps = pos
                    when {
                        ps == null -> T("Verificando as playlists…", 13, 400, Sub, Modifier.padding(vertical = 6.dp))
                        ps.isEmpty() -> T("Você ainda não entrou em nenhuma playlist do beats.", 13, 400, Sub, Modifier.padding(vertical = 6.dp), 3)
                        else -> ps.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Img(p.capa, Modifier.size(46.dp), RoundedCornerShape(6.dp))
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    T(p.nome, 16, 600)
                                    T(p.playlist.title, 12, 400, Sub)
                                }
                                T("#${p.pos}", 18, 800, Red)
                            }
                        }
                    }

                    T("Estimativa com US$ ${String.format(ptBR, "%.3f", PAGA_POR_STREAM)} por stream, valor médio usado como referência do Spotify (ele não publica uma tarifa fixa). O app não faz pagamentos.", 11, 400, Sub, Modifier.padding(top = 26.dp), 6)
                }
            }
        }
    }
}
