import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.umer_tf.ads"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// The GMA Next-Gen SDK repackages the mediation-facing `com.google.android.gms.ads.*` types into
// its own artifact. Any transitive `play-services-ads` / `-lite` would land those same classes on
// the classpath a second time and fail the build with duplicate symbols.
configurations.all {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Firebase BOM
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlatics)

    // GMA Next-Gen SDK
    api(libs.ads.mobile.sdk)

    // UMP arrives transitively with the SDK, but AdsConsentManager exposes its types (FormError)
    // in its public API, so it is declared explicitly and as `api`.
    api(libs.user.messaging.platform)

    //Shimmer
    implementation (libs.shimmer)

    // sdp
    implementation (libs.sdp.android)
    implementation (libs.ssp.android)

}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components.getByName("release"))
                groupId = "com.umer_tf.ads"
                artifactId = "ads"
                version = "1.2.1"
            }
        }
        repositories {
            maven {
                url = uri("https://maven.pkg.github.com/umerrjaved1/AdsManager")
                credentials {
                    val localProps = Properties()
                    val localPropsFile = rootProject.file("local.properties")
                    if (localPropsFile.exists()) {
                        localProps.load(localPropsFile.inputStream())
                    }
                    username = localProps.getProperty("gpr.user") ?: providers.gradleProperty("gpr.user").orNull
                    password = localProps.getProperty("gpr.key") ?: providers.gradleProperty("gpr.key").orNull
                }
            }
        }
    }
}