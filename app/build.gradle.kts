plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace  = "com.oscar.detectornfc"
    compileSdk = 36

    defaultConfig {
        applicationId         = "com.oscar.detectornfc"
        minSdk                = 31
        targetSdk             = 36
        versionCode           = 1
        versionName           = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // ── Build nativo: OpenJPEG 2.5.2 (decodificador JP2/J2K) ─────────────
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Excluir recursos duplicados que trae el AAR de dniedroid
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/AL2.0",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "AndroidManifest.xml"
            )
        }
    }
}

dependencies {
    // --- AndroidX / UI ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)

    // --- dniedroid (librería oficial CNP-FNMT para acceso al DNIe por NFC) ---
    // Contiene: MrtdKeyStoreImpl, DnieKeyStore, DG1_Dnie, DG11, DG13,
    //           DnieProvider y todas las clases jmulticard necesarias.
    implementation(files("libs/dniedroid-release.aar"))

    // --- BouncyCastle ---
    // dniedroid-release.aar referencia clases ASN.1, PKIX y CMS en tiempo de ejecución.
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.mail)
    implementation(libs.bouncycastle.tls)

    // --- Fallback ICAO/JMRTD para documentos no españoles ---
    implementation("org.jmrtd:jmrtd:0.7.31") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
    }
    implementation("net.sf.scuba:scuba-sc-android:0.0.23")
    implementation("com.github.mjdev:libaums:0.6.0")

    // --- JSON ---
    implementation(libs.gson)

    // --- OpenJPEG (JP2/JPEG-2000 de documentos ICAO) ---
    // Librería nativa compilada desde código fuente OpenJPEG 2.5.2 via CMake (src/main/cpp)
    // No se necesita ninguna dependencia Maven externa para JP2.

    // --- Tests ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}