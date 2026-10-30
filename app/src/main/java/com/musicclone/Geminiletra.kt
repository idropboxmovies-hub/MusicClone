package com.musicclone

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// Cole aqui a chave do Gemini (Google AI Studio). Não deixe a chave em repositório público.
const val GEMINI_KEY = "AQ.Ab8RN6IgQF1pkSWcRbWEnp6UG1lBVB_OXESQcGq8XV-6rKnU-Q"
const val GEMINI_MODELO = "gemini-2.5-flash"

private const val LIMITE_BYTES = 14_000_000   // o envio direto aceita até ~20 MB (o base64 aumenta 33%)

private const val PROMPT = "Transcribe the sung or spoken lyrics of this song, line by line, in the original language (do not translate). " +
    "For each line give the exact time in seconds (decimal, like 12.34) at which that line starts, counted from the very beginning of the audio file. " +
    "Return ONLY a JSON array ordered by time, like [{\"t\":12.34,\"l\":\"first line\"},{\"t\":16.8,\"l\":\"second line\"}]. " +
    "Do not include section labels such as [Chorus] or [Verse]. If the track is instrumental, return []."

// Recebe o arquivo de áudio escolhido e devolve a letra em formato LRC ("[01:23.45] texto"), ou null se falhar.
// Use no iStudio: val lrc = geminiLetraSync(ctx, uriDoAudio)  e guarde o texto em release_tracks.lyrics_lrc
suspend fun geminiLetraSync(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        if (bytes.size > LIMITE_BYTES) return@runCatching null
        val mime = ctx.contentResolver.getType(uri) ?: "audio/mpeg"

        val audio = JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        val texto = JSONObject().put("text", PROMPT)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(audio).put(texto))))
            .put("generationConfig", JSONObject().put("response_mime_type", "application/json").put("temperature", 0))

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODELO:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = 180000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", GEMINI_KEY)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) return@runCatching null
        val resp = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val saida = resp.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val arr = JSONArray(saida.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

        val linhas = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val txt = o.optString("l").trim()
            if (txt.isEmpty()) continue
            val cs = (o.optDouble("t", 0.0) * 100).toLong().coerceAtLeast(0)
            linhas.add(String.format(Locale.US, "[%02d:%02d.%02d] %s", cs / 6000, (cs / 100) % 60, cs % 100, txt))
        }
        if (linhas.isEmpty()) null else linhas.joinToString("\n")
    }.getOrNull()
}
