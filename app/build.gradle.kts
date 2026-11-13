import java.net.URL

plugins{
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android{
 namespace="com.musicclone"
 compileSdk=34
 defaultConfig{applicationId="com.musicclone";minSdk=26;targetSdk=34;versionCode=3;versionName="3.0-LiquidGlass"}
 buildFeatures{compose=true}
 composeOptions{kotlinCompilerExtensionVersion="1.5.14"}
 compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlinOptions{jvmTarget="17"}
}
val baixarFonte=tasks.register("baixarFonte"){
 val f=layout.projectDirectory.file("src/main/res/font/inter.ttf").asFile
 outputs.file(f)
 doLast{
  if(!f.exists()||f.length()<1000){
   f.parentFile.mkdirs()
   URL("https://github.com/google/fonts/raw/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf").openStream().use{i->f.outputStream().use{o->i.copyTo(o)}}
  }
 }
}
tasks.matching{it.name=="preBuild"}.configureEach{dependsOn(baixarFonte)}
dependencies{
 implementation(platform("androidx.compose:compose-bom:2024.06.00"))
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-graphics")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.activity:activity-compose:1.9.0")
 implementation("androidx.core:core-ktx:1.13.1")
 implementation("androidx.palette:palette:1.0.0")
 implementation("io.coil-kt:coil-compose:2.6.0")
 // Bibliotecas reais de Liquid Glass / Blur / Real-time Backdrop
 implementation("dev.chrisbanes.haze:haze:0.7.3")
 implementation("dev.chrisbanes.haze:haze-materials:0.7.3")
}
