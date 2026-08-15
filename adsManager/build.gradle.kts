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

    // AdMob SDK
    api(libs.play.services.ads)

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
                version = "1.0.13"
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