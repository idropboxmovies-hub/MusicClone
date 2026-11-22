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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.*
import java.net.URL

data class LabelArtist(val provider:String,val id:String,val name:String,val url:String="",val photo:String="")
private data class LabelInfo(val id:String,val name:String,val instagram:String,val tiktok:String,val x:String,val square:String?,val wide:String?)

private suspend fun labelSearch(q:String):List<LabelArtist> {
    if(q.isBlank()) return emptyList()
    val j=get("https://itunes.apple.com/search?entity=musicArtist&limit=12&country=BR&term=${Uri.encode(q)}") ?: return emptyList()
    val a=j.optJSONArray("results") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
        val id=o.optLong("artistId"); val name=o.optString("artistName")
        if(id==0L || name.isBlank()) null else LabelArtist("itunes",id.toString(),name,o.optString("artistLinkUrl"),o.optString("artworkUrl100"))
    }}
}
private fun validSocial(s:String):Boolean = s.isBlank() || runCatching { val u=java.net.URI(s.trim()); u.scheme in listOf("http","https") && u.host?.contains('.')==true }.getOrDefault(false)
private suspend fun labelUpload(uid:String,token:String?,bytes:ByteArray,aspect:String):String? {
    val path="labels/$uid/$aspect-${System.currentTimeMillis()}.jpg"
    val r=sb("POST","/storage/v1/object/label-assets/$path",bytes=bytes,token=token,ctype="image/jpeg",headers=mapOf("x-upsert" to "true"))
    return if(r.ok) "$SB_URL/storage/v1/object/public/label-assets/$path" else null
}
private suspend fun loadLabel(token:String?,uid:String):LabelInfo? {
    val r=sb("GET","/rest/v1/labels?owner_id=eq.$uid&select=*&limit=1",token=token)
    val o=r.arr()?.optJSONObject(0) ?: return null
    return LabelInfo(o.optString("id"),o.optString("name"),o.optString("instagram_url"),o.optString("tiktok_url"),o.optString("x_url"),o.optString("logo_square_url").takeIf{it.isNotBlank()},o.optString("logo_wide_url").takeIf{it.isNotBlank()})
}
private suspend fun loadLabelArtists(token:String?,labelId:String):List<LabelArtist> {
    val r=sb("GET","/rest/v1/label_artists?label_id=eq.$labelId&select=*&order=created_at",token=token)
    val a=r.arr() ?: return emptyList()
    return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { LabelArtist(it.optString("provider"),it.optString("external_artist_id"),it.optString("artist_name"),it.optString("artist_url")) } }
}

@Composable fun LabelPromoCard(onClick:()->Unit) {
    val inf=rememberInfiniteTransition(label="labelCard")
    val glow by inf.animateFloat(.12f,.28f,infiniteRepeatable(tween(2200,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="glow")
    Row(Modifier.fillMaxWidth().padding(top=24.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF28282B),Color(0xFF111113),Color(0xFF25252A))))
        .border(1.dp,Color.White.copy(alpha=.12f),RoundedCornerShape(24.dp)).clickable { onClick() }.padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("BEATS LABELS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Color.White.copy(alpha=.68f),letterSpacing=2.sp)
            Spacer(Modifier.height(10.dp)); Text("Sua gravadora.\nSeus artistas.",fontSize=24.sp,lineHeight=28.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
            Spacer(Modifier.height(8.dp)); Text("Gerencie artistas e descubra os maiores sucessos em um só lugar.",fontSize=12.sp,lineHeight=17.sp,color=Color.White.copy(alpha=.7f))
            Spacer(Modifier.height(15.dp)); Row(verticalAlignment=Alignment.CenterVertically){Text("Criar conta de gravadora",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Color.White); Spacer(Modifier.width(7.dp)); Icon(Icons.Filled.ArrowForward,null,tint=Color.White,modifier=Modifier.size(16.dp))}
        }
        Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha=glow)).border(1.dp,Color.White.copy(alpha=.2f),CircleShape),Alignment.Center){Icon(Icons.Filled.Album,null,tint=Color.White,modifier=Modifier.size(36.dp))}
    }
}

@Composable fun LabelWizard(auth:Auth,onBack:()->Unit,onDone:()->Unit) {
    val ctx= LocalContext.current; val scope= rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }; var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    var ig by remember { mutableStateOf("") }; var tt by remember { mutableStateOf("") }; var xx by remember { mutableStateOf("") }
    var sq by remember { mutableStateOf<ByteArray?>(null) }; var wide by remember { mutableStateOf<ByteArray?>(null) }
    var q by remember { mutableStateOf("") }; var hits by remember { mutableStateOf(listOf<LabelArtist>()) }; var selected by remember { mutableStateOf(listOf<LabelArtist>()) }
    var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }; var verifying by remember { mutableStateOf(false) }
    val squarePick=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){u-> if(u!=null) scope.launch { sq=prepararFoto(ctx,u) } }
    val widePick=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){u-> if(u!=null) scope.launch { wide=prepararFoto(ctx,u) } }
    LaunchedEffect(q){ if(q.isBlank()){hits=emptyList()}else{delay(400);hits=labelSearch(q)} }
    BackHandler { if(busy)return@BackHandler; if(step>0)step-- else onBack() }
    fun finish(){ if(busy)return
        if(name.isBlank()||!emailOk(email)||pass.length<6||sq==null||selected.isEmpty()||!validSocial(ig)||!validSocial(tt)||!validSocial(xx)){err="Confira nome, email, senha, logo, artistas e links das redes.";return}
        busy=true;err=null;verifying=true
        scope.launch {
            val reg=auth.signUp(email,pass,JSONObject().put("role","label").put("display_name",name.trim()))
            if(reg.err!=null||reg.confirm){busy=false;verifying=false;err=reg.err ?: "Confirme seu email e entre para concluir a gravadora.";return@launch}
            val uid=auth.uid ?: run {busy=false;verifying=false;err="Sessão não encontrada.";return@launch}
            val token=auth.jwt
            val logo=labelUpload(uid,token,sq!!,"square")
            val wideUrl=wide?.let{labelUpload(uid,token,it,"wide")}
            if(logo==null){busy=false;verifying=false;err="Não foi possível enviar a logo.";return@launch}
            val row=JSONObject().put("owner_id",uid).put("name",name.trim()).put("instagram_url",ig.trim()).put("tiktok_url",tt.trim()).put("x_url",xx.trim()).put("logo_square_url",logo).put("logo_wide_url",wideUrl ?: JSONObject.NULL)
            val created=sb("POST","/rest/v1/labels",row.toString(),token, mapOf("Prefer" to "return=representation"))
            val lid=created.arr()?.optJSONObject(0)?.optString("id")
            if(!created.ok||lid.isNullOrBlank()){busy=false;verifying=false;err="Não foi possível criar a gravadora. Verifique as políticas SQL.";return@launch}
            var failed=false
            selected.forEach { a ->
                val b=JSONObject().put("label_id",lid).put("provider",a.provider).put("external_artist_id",a.id).put("artist_name",a.name).put("artist_url",a.url)
                if(!sb("POST","/rest/v1/label_artists",b.toString(),token).ok) failed=true
            }
            auth.loadProfile();busy=false;verifying=false
            if(failed)err="Gravadora criada, mas alguns artistas não foram salvos. Edite o perfil para adicioná-los." else onDone()
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Glass(Icons.Filled.Close){onBack()};Spacer(Modifier.weight(1f));Text("${step+1} / 5",color=Sub,fontSize=12.sp)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){repeat(5){i->Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if(i<=step)Color.White else Line))}}
        AnimatedContent(step,transitionSpec={slideInHorizontally{it}+fadeIn() togetherWith slideOutHorizontally{-it/4}+fadeOut()},label="labelSteps") { s ->
            Column(Modifier.fillMaxWidth().padding(top=28.dp)) {
                Text(listOf("Vamos criar sua gravadora","Conecte suas redes","Identidade visual","Encontre seus artistas","Tudo pronto para verificar")[s],fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Color.White,lineHeight=32.sp)
                Text(listOf("Comece com os dados de acesso e o nome oficial.","Os links são opcionais. Quando preenchidos, precisam ser URLs válidas.","A logo quadrada é obrigatória. A horizontal é opcional.","Busque pelo nome do artista no catálogo Apple Music.","Revise os artistas selecionados antes de finalizar.")[s],fontSize=13.sp,color=Sub,modifier=Modifier.padding(top=8.dp,bottom=22.dp))
                when(s){
                    0->{Campo(name,{name=it},"Nome da gravadora",Icons.Filled.Business);Spacer(Modifier.height(12.dp));Campo(email,{email=it},"Email",Icons.Filled.Email,kb=KeyboardType.Email);Spacer(Modifier.height(12.dp));Campo(pass,{pass=it},"Senha (mínimo 6 caracteres)",Icons.Filled.Lock,senha=true)}
                    1->{Campo(ig,{ig=it},"Link do Instagram (opcional)",Icons.Filled.Link,kb=KeyboardType.Uri);Spacer(Modifier.height(12.dp));Campo(tt,{tt=it},"Link do TikTok (opcional)",Icons.Filled.Link,kb=KeyboardType.Uri);Spacer(Modifier.height(12.dp));Campo(xx,{xx=it},"Link do X/Twitter (opcional)",Icons.Filled.Link,kb=KeyboardType.Uri)}
                    2->{Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)).background(Surf).clickable{squarePick.launch("image/*")},Alignment.Center){if(sq!=null)Text("Logo quadrada selecionada ✓",color=Color.White)else Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Filled.AddPhotoAlternate,null,tint=Color.White,modifier=Modifier.size(34.dp));Text("Logo 1:1 · obrigatória",color=Color.White,modifier=Modifier.padding(top=8.dp))}};Spacer(Modifier.height(12.dp));OutlinedButton(onClick={widePick.launch("image/*")},modifier=Modifier.fillMaxWidth()){Text(if(wide==null)"Adicionar logo horizontal 16:9 (opcional)" else "Logo horizontal selecionada ✓")}}
                    3->{Campo(q,{q=it},"Buscar artista por nome",Icons.Filled.Search);hits.forEach{a->Row(Modifier.fillMaxWidth().padding(top=8.dp).clip(RoundedCornerShape(14.dp)).background(Surf).clickable{if(selected.none{it.id==a.id&&it.provider==a.provider})selected=selected+a}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(a.name,color=Color.White,fontWeight=FontWeight.Bold);Text("Apple Music · iTunes",color=Sub,fontSize=11.sp)};Icon(if(selected.any{it.id==a.id})Icons.Filled.CheckCircle else Icons.Filled.AddCircle,null,tint=Color.White)}};Text("Selecionados: ${selected.size}",color=Color.White,modifier=Modifier.padding(top=12.dp));selected.forEach{a->Text("✓ ${a.name}",color=Sub,fontSize=12.sp)}}
                    4->{selected.forEach{a->Row(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(12.dp)).background(Surf).padding(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Filled.Verified,null,tint=Color(0xFF55C6FF));Text(a.name,color=Color.White,modifier=Modifier.padding(start=10.dp).weight(1f));Text("Verificado",color=Color(0xFF55C6FF),fontSize=11.sp)}};Text("A verificação visual confirma que o artista foi localizado no catálogo; não comprova vínculo jurídico com a gravadora.",fontSize=11.sp,color=Sub,modifier=Modifier.padding(top=12.dp))}
                }
            }
        }
        err?.let{Text(it,color=Color(0xFFFF6B6B),fontSize=12.sp,modifier=Modifier.padding(top=12.dp))}
        Spacer(Modifier.height(24.dp));Pilula(if(step==4)"Finalizar e entrar" else "Continuar",Modifier.fillMaxWidth(),loading=busy){if(step<4){val ok=when(step){0->name.isNotBlank()&&emailOk(email)&&pass.length>=6;1->validSocial(ig)&&validSocial(tt)&&validSocial(xx);2->sq!=null;3->selected.isNotEmpty();else->true};if(ok){err=null;step++}else err="Preencha os campos obrigatórios desta etapa."}else finish()}
        if(busy&&verifying){Row(Modifier.fillMaxWidth().padding(top=14.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp),color=Color.White,strokeWidth=2.dp);Text(" Verificando e criando sua conta…",color=Sub,fontSize=12.sp)}}
        Spacer(Modifier.height(36.dp))
    }
}

@Composable fun GravadoraS(auth:Auth,back:()->Unit,abrirArtista:(LabelArtist)->Unit,editar:()->Unit) {
    var label by remember { mutableStateOf<LabelInfo?>(null) }; var artists by remember { mutableStateOf(listOf<LabelArtist>()) }; var loading by remember{mutableStateOf(true)}
    var hits by remember { mutableStateOf(listOf<Pair<LabelArtist,Track>>()) }
    LaunchedEffect(auth.uid){val uid=auth.uid;if(uid!=null){label=loadLabel(auth.jwt,uid);label?.let{artists=loadLabelArtists(auth.jwt,it.id)};hits=artists.flatMap { a -> topSongs(a.id.toLongOrNull() ?: 0L).take(2).map { a to it } }};loading=false}
    val l=label
    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom=36.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Glass(Icons.Filled.ArrowBackIosNew){back()};Spacer(Modifier.weight(1f));Glass(Icons.Filled.MoreHoriz){editar()}}
        if(l?.wide!=null)AsyncImage(l.wide,null,Modifier.fillMaxWidth().height(150.dp),contentScale=ContentScale.Crop)
        Box(Modifier.padding(horizontal=22.dp).size(112.dp).clip(RoundedCornerShape(22.dp)).background(Surf),Alignment.Center){if(l?.square!=null)AsyncImage(l.square,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Icon(Icons.Filled.Album,null,tint=Color.White,modifier=Modifier.size(44.dp))}
        Row(Modifier.padding(start=22.dp,top=14.dp),verticalAlignment=Alignment.CenterVertically){Text(l?.name ?: auth.prof?.name.orEmpty(),fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Color.White);Spacer(Modifier.width(7.dp));Icon(Icons.Filled.Verified,null,tint=Color(0xFF55C6FF),modifier=Modifier.size(20.dp))}
        Text("GRAVADORA  ·  ${artists.size} ARTISTAS",fontSize=10.sp,letterSpacing=2.sp,color=Sub,modifier=Modifier.padding(start=22.dp,top=5.dp))
        Text("Artistas da gravadora",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(start=22.dp,top=30.dp,bottom=12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){artists.forEach{a->Column(Modifier.width(112.dp).clickable{abrirArtista(a)} ,horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(88.dp).clip(CircleShape).background(Surf),Alignment.Center){Icon(Icons.Filled.Person,null,tint=Color.White,modifier=Modifier.size(34.dp))};Text(a.name,color=Color.White,fontSize=12.sp,maxLines=2,textAlign=TextAlign.Center,modifier=Modifier.padding(top=8.dp))}}}
        Text("Maiores sucessos da gravadora",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(start=22.dp,top=30.dp,bottom=12.dp))
        Text("Destaques do catálogo Apple Music · não representa ranking de streams.",fontSize=10.sp,color=Sub,modifier=Modifier.padding(horizontal=22.dp,bottom=8.dp))
        if(hits.isEmpty() && !loading) Text("Ainda não há faixas disponíveis no catálogo Apple Music para os artistas vinculados.",fontSize=12.sp,color=Sub,modifier=Modifier.padding(horizontal=22.dp))
        hits.forEach { (artist,track) -> Row(Modifier.fillMaxWidth().padding(horizontal=20.dp, vertical=6.dp).clip(RoundedCornerShape(14.dp)).background(Surf).padding(9.dp),verticalAlignment=Alignment.CenterVertically){AsyncImage(track.art,null,Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop);Column(Modifier.padding(start=11.dp).weight(1f)){Text(track.name,color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=1);Text(artist.name,color=Sub,fontSize=11.sp,maxLines=1)};Text("Apple Music",color=Sub,fontSize=9.sp)}}
        if(loading)CircularProgressIndicator(Modifier.padding(22.dp).size(22.dp),color=Color.White,strokeWidth=2.dp)
        if(artists.isEmpty()&&!loading)Text("Adicione artistas em Editar perfil para começar.",color=Sub,fontSize=13.sp,modifier=Modifier.padding(22.dp))
    }
}

@Composable fun EditarGravadoraS(auth:Auth,back:()->Unit){
    val ctx=LocalContext.current; val scope= rememberCoroutineScope()
    var l by remember{mutableStateOf<LabelInfo?>(null)};var artists by remember{mutableStateOf(listOf<LabelArtist>())}
    var name by remember{mutableStateOf("")};var ig by remember{mutableStateOf("")};var tt by remember{mutableStateOf("")};var xx by remember{mutableStateOf("")}
    var square by remember{mutableStateOf<ByteArray?>(null)};var wide by remember{mutableStateOf<ByteArray?>(null)}
    var q by remember{mutableStateOf("")};var hits by remember{mutableStateOf(listOf<LabelArtist>())};var busy by remember{mutableStateOf(false)};var msg by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(auth.uid){auth.uid?.let{l=loadLabel(auth.jwt,it);l?.let{v->artists=loadLabelArtists(auth.jwt,v.id);name=v.name;ig=v.instagram;tt=v.tiktok;xx=v.x}}}
    LaunchedEffect(q){if(q.isBlank())hits=emptyList() else {delay(350);hits=labelSearch(q)}}
    val squarePick=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){u->if(u!=null)scope.launch{square=prepararFoto(ctx,u)}}
    val widePick=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){u->if(u!=null)scope.launch{wide=prepararFoto(ctx,u)}}
    val label=l
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Glass(Icons.Filled.Close){back()};Text("Editar gravadora",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f),textAlign=TextAlign.Center);Pilula("Salvar",compacta=true,loading=busy){
            if(!validSocial(ig)||!validSocial(tt)||!validSocial(xx)||name.isBlank()){msg="Confira o nome e os links das redes."}else scope.launch{busy=true;val uid=auth.uid;val lid=label?.id;if(uid!=null&&lid!=null){val sqUrl=square?.let{labelUpload(uid,auth.jwt,it,"square")};val wideUrl=wide?.let{labelUpload(uid,auth.jwt,it,"wide")};val body=JSONObject().put("name",name.trim()).put("instagram_url",ig.trim()).put("tiktok_url",tt.trim()).put("x_url",xx.trim());if(sqUrl!=null)body.put("logo_square_url",sqUrl);if(wideUrl!=null)body.put("logo_wide_url",wideUrl);val r=sb("PATCH","/rest/v1/labels?id=eq.$lid",body.toString(),auth.jwt);if(r.ok)back()else msg="Não foi possível salvar as alterações."};busy=false}
        }}
        Text("Nome da gravadora",fontSize=13.sp,color=Sub,modifier=Modifier.padding(top=24.dp,bottom=7.dp));Campo(name,{name=it},"Nome",Icons.Filled.Business)
        Text("Redes sociais · links opcionais",fontSize=13.sp,color=Sub,modifier=Modifier.padding(top=18.dp,bottom=7.dp));Campo(ig,{ig=it},"Instagram",Icons.Filled.Link,kb=KeyboardType.Uri);Spacer(Modifier.height(9.dp));Campo(tt,{tt=it},"TikTok",Icons.Filled.Link,kb=KeyboardType.Uri);Spacer(Modifier.height(9.dp));Campo(xx,{xx=it},"X/Twitter",Icons.Filled.Link,kb=KeyboardType.Uri)
        Text("Logos",fontSize=13.sp,color=Sub,modifier=Modifier.padding(top=20.dp,bottom=8.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onClick={squarePick.launch("image/*")},modifier=Modifier.weight(1f)){Text(if(square==null)"Trocar logo 1:1" else "Logo 1:1 ✓",color=Color.White)};OutlinedButton(onClick={widePick.launch("image/*")},modifier=Modifier.weight(1f)){Text(if(wide==null)"Trocar 16:9" else "Logo 16:9 ✓",color=Color.White)}}
        Text("Artistas vinculados",fontSize=14.sp,color=Sub,modifier=Modifier.padding(top=24.dp,bottom=8.dp))
        artists.forEach{a->Row(Modifier.fillMaxWidth().padding(vertical=4.dp).clip(RoundedCornerShape(12.dp)).background(Surf).padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(a.name,color=Color.White,modifier=Modifier.weight(1f));Icon(Icons.Filled.Delete,null,tint=Color.White,modifier=Modifier.clickable{scope.launch{val lid=label?.id?:return@launch;val path="/rest/v1/label_artists?label_id=eq.$lid&provider=eq.${a.provider}&external_artist_id=eq.${Uri.encode(a.id)}";if(sb("DELETE",path,token=auth.jwt).ok)artists=artists.filterNot{it.id==a.id&&it.provider==a.provider}}})}}
        Text("Adicionar artista",fontSize=14.sp,color=Sub,modifier=Modifier.padding(top=20.dp,bottom=8.dp));Campo(q,{q=it},"Buscar no Apple Music",Icons.Filled.Search)
        hits.forEach{a->Text("+  ${a.name}",color=Color.White,modifier=Modifier.fillMaxWidth().clickable{scope.launch{val lid=label?.id?:return@launch;val b=JSONObject().put("label_id",lid).put("provider",a.provider).put("external_artist_id",a.id).put("artist_name",a.name).put("artist_url",a.url);if(sb("POST","/rest/v1/label_artists",b.toString(),auth.jwt).ok)artists=artists+a;q=""}}.padding(12.dp))}
        msg?.let{Text(it,color=Color(0xFFFF7777),modifier=Modifier.padding(top=12.dp))}
    }
}
