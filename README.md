# AdsManager Library

A centralized, reusable Android library module for easily integrating AdMob (and potentially other) ad networks into your Android applications.

## 📦 Installation

To use this library in your Android project, you need to pull it from the GitHub Packages Maven repository.

### 1. Add GitHub Credentials
To securely authenticate with GitHub Packages without exposing your token to version control, add your GitHub Personal Access Token to your project's `local.properties` file (this file is ignored by Git):

```properties
gpr.user=umerrjaved1
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```
*(Make sure the token has the `read:packages` scope).*

### 2. Configure Repositories
In your target app's `settings.gradle.kts` (or root `build.gradle.kts`), add the GitHub Maven repository inside the `dependencyResolutionManagement` block, and configure it to read your credentials from `local.properties`:

```kotlin
import java.util.Properties

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.google.com") }

        // AdsManager GitHub Packages Repository
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
            content {
                includeGroup("com.umer_tf.ads")
            }
        }
    }
}
```

### 3. Add the Dependency
Finally, in your app-level `build.gradle.kts` (e.g., `app/build.gradle.kts`), add the dependency:

```kotlin
dependencies {
    implementation("com.umer_tf.ads:ads:1.0.0")
}
```

Sync your Gradle project, and the library will be downloaded and ready to use!

## 🚀 Basic Usage

Once the library is synced, you can access the ad management classes under the `com.umer_tf.ads` package.

*(Note: Depending on how your AdManager classes are specifically structured, you will typically initialize the Ads SDK in your Application class and then use your AdLoaders in your Activities/Fragments).*

### Initialize AdMob
In your `Application` class (or main activity):
```kotlin
import com.umer_tf.ads.domain.core.AdMobManager

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the AdMob SDK
        AdMobManager.initialize(this)
    }
}
```

### Load and Show Ads
Depending on the ad type, use the corresponding loader provided by the library.

**Banner Ad Example:**
```kotlin
import com.umer_tf.ads.domain.ads.banner.BannerAdLoader

// In your Activity or Fragment
val bannerLoader = BannerAdLoader(context)
bannerLoader.loadAd("YOUR_AD_UNIT_ID", binding.adContainer)
```

**Interstitial Ad Example:**
```kotlin
import com.umer_tf.ads.domain.ads.interstitial.InterstitialAdLoader

val interstitialLoader = InterstitialAdLoader(context)
interstitialLoader.loadAd("YOUR_AD_UNIT_ID", object : OnSuccessListener {
    override fun onSuccess() {
        // Ad loaded successfully, you can now show it
        interstitialLoader.showAd(this@YourActivity)
    }
})
```

## 🛠 Publishing Updates

When you make changes to this library and want to publish a new version:
1. Make your changes in the `adsManager/` module.
2. Ensure you have the `write:packages` scope enabled on your GitHub Personal Access Token.
3. Run the `./publish.sh` script (or run the Gradle task `:adsManager:publishReleasePublicationToMavenRepository` manually). The script will automatically bump the patch version, commit the changes, and push the new version to GitHub Packages.
