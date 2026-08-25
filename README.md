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
    implementation("com.umer_tf.ads:ads:2.0.0")
}
```

Sync your Gradle project, and the library will be downloaded and ready to use!

### 4. Remove any `play-services-ads` from your app

**Required.** From `2.0.0` this library uses the **GMA Next-Gen SDK**
(`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk`), which repackages the
mediation-facing `com.google.android.gms.ads.*` classes into its own artifact. If the legacy SDK is
still anywhere on your app's classpath you will get a **duplicate class** failure at dex time.

The library already excludes it from everything it pulls in, but it cannot exclude a dependency
your app declares itself. If your app (or another SDK it uses) still brings in the legacy artifact,
add this to your app-level `build.gradle.kts`:

```kotlin
configurations.all {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}
```

Check with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep play-services-ads`.

## 🚀 Basic Usage

Once the library is synced, you can access the ad management classes under the `com.umer_tf.ads` package.

*(Note: Depending on how your AdManager classes are specifically structured, you will typically initialize the Ads SDK in your Application class and then use your AdLoaders in your Activities/Fragments).*

### 1. Declare your AdMob app id

**Required.** In your app's `AndroidManifest.xml`, inside `<application>`:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

This is the same tag the legacy SDK used, so no change is needed if you already have it. The
library reads the id from here. Without it **no ads will load**, and you will see this in logcat:

```
AdsInitializer: Monetization :- no AdMob application id. …  Ads cannot load.
```

Alternatively supply it at runtime, before the first ad request, with
`AdMobManager.getInstance(app).setApplicationId("ca-app-pub-…~…")`.

### 2. Initialize

In your `Application` class:

```kotlin
import com.umer_tf.ads.domain.core.AdMobManager

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AdMobManager.getInstance(this)
            .setInterstitialAdMinTime(15)
            .setInterstitialCounter(2)
            .initialize {
                // SDK ready — safe to request ads.
            }
    }
}
```

> **Changed in 2.0.0.** `initialize()` used to be a deprecated no-op because the legacy SDK
> self-initialized. The Next-Gen SDK does not, and it *drops* any request made before
> initialization completes, so calling this at startup is now the recommended path. Every loader
> also defers its own request behind initialization as a safety net, so an existing host that never
> calls `initialize()` keeps working — it just pays the initialization latency on its first
> placement instead of at startup.

### Load and Show Ads
Depending on the ad type, use the corresponding loader provided by the library.

Loaders are not constructed directly — take them from the `AdMobManager` singleton. Ad unit ids are
validated and **throw `IllegalArgumentException`** unless they match `ca-app-pub-<16+ digits>/<10 digits>`.

**Banner Ad Example:**
```kotlin
val ads = AdMobManager.getInstance(application)

ads.bannerAdLoader.showAdaptiveBanner(
    activity = this,
    shimmerFrameLayout = binding.shimmer,   // optional, may be null
    frameLayout = binding.adContainer,
    adUnitId = "ca-app-pub-…/…",
)
```

**Interstitial Ad Example:**
```kotlin
val ads = AdMobManager.getInstance(application)

// Preload, e.g. on screen entry.
ads.interstitialAdLoader.loadAd("ca-app-pub-…/…") { loaded ->
    // loaded == true when an ad is ready
}

// Show later, e.g. on a navigation event.
ads.interstitialAdLoader.showAd(this, "ca-app-pub-…/…") { shown ->
    // Always fires — false when nothing was ready or the show was refused.
    proceedToNextScreen()
}
```

Both callbacks are `OnSuccessListener<Boolean>` and always fire exactly once, so it is safe to put
navigation in them.

### Watching the ad lifecycle during QA

Each format logs `REQUEST` / `LOAD` / `SHOW` / `DISMISS` under its own tag:

```bash
adb logcat -s QA-Inter:D QA-Native:D QA-Open-Start:D QA-Open-Resume:D
```

Turn the stream off in release with `AdEventLog.qaLogging = false` (likewise `verboseLogging` and
`simpleLogging` for the other two streams). `AdEventLog.namePlacements(mapOf(unitId to "home-inter"))`
makes the lines read by placement name instead of ad unit id.

## 🛠 Publishing Updates

When you make changes to this library and want to publish a new version:
1. Make your changes in the `adsManager/` module.
2. Ensure you have the `write:packages` scope enabled on your GitHub Personal Access Token.
3. Run the `./publish.sh` script (or run the Gradle task `:adsManager:publishReleasePublicationToMavenRepository` manually). The script will automatically bump the patch version, commit the changes, and push the new version to GitHub Packages.
