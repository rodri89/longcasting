import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/**
 * Credenciales de firma. Se leen de `keystore.properties` en la raiz del proyecto (ignorado
 * por git) o, si no existe, de variables de entorno para CI.
 *
 * Esto reemplaza la config `externalOverride` que inyecta Android Studio al usar el asistente
 * de "Generate Signed APK": esa vive fuera del proyecto y resuelve las rutas relativas contra
 * el directorio del daemon de Gradle, no contra el proyecto, que es de donde salia el error
 * "Keystore file '.../.gradle/daemon/9.1.0/longcasting_key' not found".
 */
val keystoreProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText.orNull
        ?.let { load(it.reader()) }
}

/**
 * El vacio se trata como ausente en cada nivel por separado: `keystore.properties` puede
 * existir con las claves en blanco (es como queda la plantilla), y en ese caso hay que seguir
 * de largo hacia la variable de entorno en vez de cortar ahi.
 */
fun signingValue(key: String, environmentVariable: String): String? =
    keystoreProperties.getProperty(key)?.trim()?.ifBlank { null }
        ?: providers.environmentVariable(environmentVariable).orNull?.trim()?.ifBlank { null }

/** Expande `~` a mano: `file("~/x")` crearia un directorio llamado literalmente "~". */
fun resolveKeystorePath(path: String): File =
    if (path.startsWith("~/")) {
        File(System.getProperty("user.home"), path.removePrefix("~/"))
    } else {
        rootProject.file(path)
    }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.sqldelight.driver.android)
    implementation(libs.sqldelight.runtime)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "ar.com.longcasting"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ar.com.longcasting"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 7
        versionName = "1.0.6"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    val storePath = signingValue("storeFile", "LONGCASTING_STORE_FILE")
    val storeSecret = signingValue("storePassword", "LONGCASTING_STORE_PASSWORD")
    val alias = signingValue("keyAlias", "LONGCASTING_KEY_ALIAS")
    val keySecret = signingValue("keyPassword", "LONGCASTING_KEY_PASSWORD")
    // Se exigen los cuatro valores: con credenciales a medias el build falla recien al firmar,
    // con un error mucho menos claro que un release sin firmar.
    val canSign = storePath != null && storeSecret != null && alias != null && keySecret != null

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = resolveKeystorePath(storePath!!)
                storePassword = storeSecret
                keyAlias = alias
                keyPassword = keySecret
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "full"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}