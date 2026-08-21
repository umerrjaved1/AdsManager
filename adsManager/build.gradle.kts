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

    testOptions {
        unitTests {
            // The pure logic under test routes its logging through AdsLog -> android.util.Log, which
            // throws "not mocked" in a plain JVM test. Returning defaults keeps these tests
            // dependency-free instead of pulling in Robolectric just to swallow log calls.
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// The Next-Gen SDK cannot coexist with the legacy one - both carry the same internal symbols, so a
// build that resolves both fails on duplicate classes. Excluding it here covers every transitive
// edge at once (mediation adapters and older Google SDKs still declare play-services-ads), instead
// of chasing them one dependency at a time.
configurations.all {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.process)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)

    // Declared explicitly rather than relied on as a transitive of the lifecycle artifacts. The
    // public API exposes StateFlow and Flow (AdViewModel), so this belongs on the compile classpath
    // of every consumer by contract - and an undeclared transitive breaks at runtime, in every app
    // at once, the day AndroidX changes that edge or a host app forces a different coroutines version.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Firebase BOM
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlatics)

    // Google Mobile Ads SDK (Next-Gen). Kept as `api` rather than `implementation` because the
    // public surface of this library hands SDK types back to callers - NativeAd through
    // OnSuccessListenerNative, AdValue through AdsAnalytics - so consumers must compile against it.
    // The User Messaging Platform SDK used by AdsConsentManager arrives transitively from here.
    api(libs.ads.mobile.sdk)

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
                version = "3.0.0"
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