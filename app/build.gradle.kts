import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.example.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.assistant"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.ExperimentalStdlibApi",
            "-opt-in=kotlin.experimental.ExperimentalTypeInference"
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt"
            )
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    // Add downloadFonts task
    tasks.register("downloadFonts") {
        doLast {
            val fontsDir = file("src/main/res/font")
            fontsDir.mkdirs()

            val fonts = listOf(
                "Regular" to "regular",
                "Medium" to "medium",
                "SemiBold" to "semibold"
            )

            fonts.forEach { (weight, filename) ->
                // Using Inter v3.19 font files direct from GitHub releases
                val fontUrl = "https://github.com/rsms/inter/raw/v3.19/docs/font-files/Inter-$weight.ttf"
                val fontFile = file("${fontsDir}/inter_${filename.lowercase()}.ttf")
                
                if (!fontFile.exists()) {
                    println("Downloading Inter-$weight font...")
                    URL(fontUrl).openStream().use { input ->
                        fontFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    // Run downloadFonts before preBuild
    tasks.named("preBuild") {
        dependsOn("downloadFonts")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.api-client:google-api-client-android:1.35.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    
    // Protocol Buffers
    implementation("com.google.protobuf:protobuf-kotlin:3.24.0")
    implementation("com.google.protobuf:protobuf-java:3.24.0")
    
    // LZ4 compression
    implementation("org.lz4:lz4-java:1.8.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.24.0"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}