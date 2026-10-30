@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicclone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// =====================================================================
// Modelo + rede (tabela "shows" do Supabase, veja shows.sql)
// Cada linha é UMA data. Se várias datas têm o mesmo "tour", elas aparecem agrupadas como turnê.
// =====================================================================
class Show(
    val id: String, val artistUid: String, val artistName: String, val artistPhoto: String?,
    val tour: String, val venue: String, val address: String, val city: String,
    val date: String, val time: String, val ticketUrl: String
)

val LocalShows = staticCompositionLocalOf<(String, String, String?) -> Unit> { { _, _, _ -> } }   // uid, nome, foto -> abre a lista de shows
val LocalShowsEdit = staticCompositionLocalOf<() -> Unit> { { } }                                  // abre a tela de adicionar shows

private val DIAS = listOf("domingo", "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado")

private fun calDe(iso: String): Calendar? = runCatching {
    val p = iso.take(10).split("-")
    Calendar.getInstance().apply { clear(); set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt()) }
}.getOrNull()

fun showDia(iso: String) = calDe(iso)?.get(Calendar.DAY_OF_MONTH)?.toString() ?: ""
fun showMes(iso: String) = calDe(iso)?.let { mesesCurtos[it.get(Calendar.MONTH)].uppercase() } ?: ""
fun showSemana(iso: String) = calDe(iso)?.let { DIAS[it.get(Calendar.DAY_OF_WEEK) - 1] } ?: ""
fun showExtenso(iso: String) = calDe(iso)?.let {
    "${DIAS[it.get(Calendar.DAY_OF_WEEK) - 1]}, ${it.get(Calendar.DAY_OF_MONTH)} de ${mesesLongos[it.get(Calendar.MONTH)]} de ${it.get(Calendar.YEAR)}"
} ?: ""

private fun hojeIso(): String {
    val c = Calendar.getInstance()
    return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

// só shows de hoje em diante; null = falhou a conexão
suspend fun showsLoad(uid: String, nome: String, foto: String?): List<Show>? {
    val r = sb("GET", "/rest/v1/shows?artist_id=eq.$uid&show_date=gte.${hojeIso()}&order=show_date.asc,show_time.asc&select=*")
    if (!r.ok) return null
    val a = r.arr() ?: return null
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        Show(o.optString("id"), uid, nome, foto, js(o, "tour"), o.optString("venue"), js(o, "address"), o.optString("city"),
            o.optString("show_date"), js(o, "show_time"), js(o, "ticket_url"))
    }
}

// devolve null se deu certo, ou a mensagem de erro
private suspend fun showsHttp(method: String, path: String, token: String?, body: String? = null): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("${Studio.SUPA_URL}/rest/v1/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("apikey", Studio.SUPA_KEY)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Prefer", "return=minimal")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code in 200..299) null else "Erro ($code): ${c.errorStream?.bufferedReader()?.readText()}"
        } finally { c.disconnect() }
    }.getOrElse { it.message ?: "Sem conexão" }
}

fun shareShow(s: Show) = ShareData("artist", s.artistName, "${showExtenso(s.date)} · ${s.venue}, ${s.city}", s.artistPhoto,
    if (s.artistUid.isNotEmpty()) "${ShareCfg.BASE}/a/${s.artistUid}" else ShareCfg.BASE, circle = true)

// =====================================================================
// Pedaços de UI
// =====================================================================
private val CINZA_TOPO = Color(0xFF48484A)
private val CINZA_BASE = Color(0xFF1C1C1E)
private val fundoCinza = Brush.verticalGradient(listOf(CINZA_TOPO, CINZA_BASE))

@Composable fun ShowBadge(iso: String, m: Modifier = Modifier, grande: Boolean = false) {
    val s: Dp = if (grande) 60.dp else 46.dp
    Column(m.size(s).clip(RoundedCornerShape(if (grande) 14.dp else 10.dp)).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
        T(showMes(iso), if (grande) 11 else 9, 700, Red)
        T(showDia(iso), if (grande) 24 else 18, 800, Color.Black)
    }
}

@Composable private fun ShCampo(valor: String, mudou: (String) -> Unit, dica: String, m: Modifier = Modifier) {
    Box(m.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .10f)).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (valor.isEmpty()) T(dica, 16, 400, Color.White.copy(alpha = .45f))
        BasicTextField(valor, mudou, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = Sf), cursorBrush = SolidColor(Color.White))
    }
}

@Composable private fun ShTopo(titulo: String, voltar: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)).clickable { voltar() }, Alignment.Center) {
            Icon(Icons.Filled.ArrowBackIosNew, null, Modifier.size(18.dp), Color.White)
        }
        T(titulo, 22, 700, Color.White, Modifier.padding(start = 12.dp))
    }
}

@Composable private fun ShowRow(s: Show, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .09f)).clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ShowBadge(s.date)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            T(s.venue, 16, 600, Color.White)
            T("${s.city} · ${showSemana(s.date)}${if (s.time.isNotBlank()) ", ${s.time}" else ""}", 13, 400, Color.White.copy(alpha = .6f), ml = 2)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(alpha = .5f))
    }
}

// botão para colocar dentro do EditarS (artistas)
@Composable fun BotaoAddShows() {
    val go = LocalShowsEdit.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Color.Gray.copy(alpha = .15f)).clickable { go() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Event, null, Modifier.size(24.dp), Red)
        Column(Modifier.padding(start = 14.dp).weight(1f)) { T("Shows e turnês", 17, 600); T("Adicione datas para os seus fãs", 12, 400, Sub) }
        Icon(Icons.Filled.ChevronRight, null, tint = Sub)
    }
}

// =====================================================================
// Tela: lista de shows do artista (quem visita o perfil e toca em "Próximos Shows")
// =====================================================================
@Composable fun ShowsS(uid: String, nome: String, foto: String?, open: (Show) -> Unit, back: () -> Unit) {
    var lista by remember(uid) { mutableStateOf<List<Show>?>(null) }
    LaunchedEffect(uid) { lista = showsLoad(uid, nome, foto) ?: emptyList() }
    val l = lista
    Box(Modifier.fillMaxSize().background(fundoCinza)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ShTopo("Shows", back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 200.dp)) {
                T(nome, 15, 500, Color.White.copy(alpha = .6f), Modifier.padding(bottom = 14.dp))
                when {
                    l == null -> T("Carregando…", 14, 400, Color.White.copy(alpha = .6f))
                    l.isEmpty() -> T("Nenhum show marcado por enquanto.", 14, 400, Color.White.copy(alpha = .6f))
                    else -> {
                        l.filter { it.tour.isNotBlank() }.groupBy { it.tour }.forEach { (tour, itens) ->
                            Row(Modifier.padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Public, null, Modifier.size(18.dp), Color.White.copy(alpha = .7f))
                                T("  Turnê $tour", 18, 700, Color.White)
                            }
                            itens.forEach { ShowRow(it) { open(it) } }
                        }
                        val avulsos = l.filter { it.tour.isBlank() }
                        if (avulsos.isNotEmpty()) {
                            T("Próximos shows", 18, 700, Color.White, Modifier.padding(top = 6.dp, bottom = 10.dp))
                            avulsos.forEach { ShowRow(it) { open(it) } }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// Tela: detalhe de um show (cinza, como no Apple Music)
// =====================================================================
@Composable fun ShowDetalheS(s: Show, back: () -> Unit) {
    val ctx = LocalContext.current
    val share = LocalShare.current
    Box(Modifier.fillMaxSize().background(fundoCinza)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp).padding(top = 56.dp, bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(170.dp)) {
                Img(s.artistPhoto, Modifier.fillMaxSize(), CircleShape)
                ShowBadge(s.date, Modifier.align(Alignment.BottomEnd), true)
            }
            T(s.artistName, 28, 800, Color.White, Modifier.padding(top = 16.dp), 2, TextAlign.Center)
            if (s.tour.isNotBlank()) T("Turnê ${s.tour}", 14, 500, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val temLink = s.ticketUrl.isNotBlank()
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = if (temLink) .16f else .06f))
                    .clickable(enabled = temLink) {
                        val u = if (s.ticketUrl.startsWith("http")) s.ticketUrl else "https://${s.ticketUrl}"
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    }, Arrangement.Center, Alignment.CenterVertically) {
                    Icon(Icons.Filled.ConfirmationNumber, null, Modifier.size(18.dp), Color.White); T("  Ingressos", 15, 600, Color.White)
                }
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .16f)).clickable { share(shareShow(s)) }, Arrangement.Center, Alignment.CenterVertically) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp), Color.White); T("  Compartilhar", 15, 600, Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .09f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(showExtenso(s.date), 15, 600, Color.White, ml = 2)
                    T(if (s.time.isNotBlank()) "Portas abrem às ${s.time}" else "Horário a confirmar", 13, 400, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp))
                }
                Icon(Icons.Filled.Event, null, tint = Color.White.copy(alpha = .6f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .09f))
                .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("${s.venue} ${s.address} ${s.city}")))) } }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(s.venue, 15, 600, Color.White, ml = 2)
                    T(if (s.address.isNotBlank()) "${s.address}, ${s.city}" else s.city, 13, 400, Color.White.copy(alpha = .6f), Modifier.padding(top = 2.dp), 3)
                }
                Icon(Icons.Filled.Place, null, tint = Color.White.copy(alpha = .6f))
            }
        }
        Box(Modifier.statusBarsPadding().padding(start = 14.dp, top = 8.dp)) { Glass(Icons.Filled.ArrowBackIosNew) { back() } }
    }
}

// =====================================================================
// Tela: o artista adiciona / remove seus shows (cinza neutra)
// =====================================================================
@Composable fun ShowsEditS(auth: Auth, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uid = auth.uid
    val nome = auth.prof?.name ?: ""
    val foto = auth.prof?.photo
    var lista by remember { mutableStateOf(listOf<Show>()) }
    var tour by remember { mutableStateOf("") }
    var venue by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var ticket by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }          // yyyy-MM-dd
    var pick by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }

    fun recarregar() { scope.launch { if (uid != null) showsLoad(uid, nome, foto)?.let { lista = it } } }
    LaunchedEffect(uid) { recarregar() }

    if (pick) {
        val st = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { pick = false }, confirmButton = {
            TextButton({
                st.selectedDateMillis?.let { ms ->
                    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
                    date = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
                }
                pick = false
            }) { Text("OK") }
        }) { DatePicker(st) }
    }

    val horaOk = time.isBlank() || Regex("^\\d{2}:\\d{2}$").matches(time)
    val ok = venue.isNotBlank() && city.isNotBlank() && date.isNotBlank() && horaOk && !saving

    Box(Modifier.fillMaxSize().background(fundoCinza)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ShTopo("Shows e turnês", back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 200.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .08f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    T("Adicionar data", 18, 700, Color.White)
                    ShCampo(tour, { tour = it }, "Nome da turnê (opcional)")
                    ShCampo(venue, { venue = it }, "Local (ex.: Qualistage)")
                    ShCampo(city, { city = it }, "Cidade (ex.: Rio de Janeiro, RJ)")
                    ShCampo(address, { address = it }, "Endereço (opcional)")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .10f)).clickable { pick = true }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Event, null, Modifier.size(20.dp), Color.White.copy(alpha = .7f))
                        T(if (date.isBlank()) "  Escolher data" else "  ${showExtenso(date)}", 16, 400, if (date.isBlank()) Color.White.copy(alpha = .45f) else Color.White)
                    }
                    ShCampo(time, { v -> time = v.filter { it.isDigit() || it == ':' }.take(5) }, "Horário (ex.: 20:00)")
                    ShCampo(ticket, { ticket = it }, "Link dos ingressos (opcional)")
                    T("Dica: para uma turnê, use o mesmo nome em várias datas. Elas aparecem agrupadas no seu perfil.", 12, 400, Color.White.copy(alpha = .5f), ml = 3)
                    erro?.let { T(it, 13, 500, Red, ml = 5) }
                    Box(Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(if (ok) Red else Red.copy(alpha = .35f)).clickable(enabled = ok) {
                        saving = true; erro = null
                        val corpo = JSONObject().put("artist_id", uid).put("venue", venue.trim()).put("city", city.trim()).put("show_date", date).apply {
                            if (tour.isNotBlank()) put("tour", tour.trim())
                            if (address.isNotBlank()) put("address", address.trim())
                            if (time.isNotBlank()) put("show_time", time)
                            if (ticket.isNotBlank()) put("ticket_url", ticket.trim())
                        }
                        scope.launch {
                            val e = showsHttp("POST", "shows", auth.jwt, corpo.toString())
                            saving = false
                            if (e == null) { venue = ""; city = ""; address = ""; time = ""; ticket = ""; date = ""; recarregar() } else erro = e
                        }
                    }, Alignment.Center) { T(if (saving) "Salvando…" else "Adicionar", 16, 600, Color.White) }
                }
                T("Suas datas", 18, 700, Color.White, Modifier.padding(top = 24.dp, bottom = 10.dp))
                if (lista.isEmpty()) T("Nenhum show futuro cadastrado.", 14, 400, Color.White.copy(alpha = .6f))
                lista.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .09f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShowBadge(s.date)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            T(s.venue, 16, 600, Color.White)
                            T(listOfNotNull(s.city, s.tour.takeIf { it.isNotBlank() }?.let { "Turnê $it" }).joinToString(" · "), 12, 400, Color.White.copy(alpha = .6f), ml = 2)
                        }
                        Box(Modifier.size(38.dp).clip(CircleShape).clickable {
                            scope.launch { showsHttp("DELETE", "shows?id=eq.${s.id}", auth.jwt); recarregar() }
                        }, Alignment.Center) { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), Color.White.copy(alpha = .7f)) }
                    }
                }
            }
        }
    }
}
