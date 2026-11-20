plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "local.acetv"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "local.acetv"
        minSdk = 22
        targetSdk = 28
        versionCode = 11
        versionName = "0.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Listas de canales incluidas en la app: lista.json y lista.m3u de la carpeta del proyecto
val copiarListas = tasks.register<Sync>("copiarListas") {
    from(rootProject.file("lista.json"))
    from(rootProject.file("lista.m3u"))
    into(layout.buildDirectory.dir("generated/listas"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/listas"))
tasks.named("preBuild") { dependsOn(copiarListas) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copiarListas) }
