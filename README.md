# AdsManager

An AdMob wrapper for Android. It provides a single `AdMobManager` entry point covering banner, interstitial, rewarded, native, full-screen native, and app-open ads. It includes out-of-the-box shimmer placeholders, GDPR/UMP consent gating, and a unified listener for ad events and revenue reporting.

- **minSdk** 24 · **compileSdk** 37 · **Kotlin-first**
- `groupId` `com.umer_tf.ads` · `artifactId` `ads` · `2.0.0`

---

## 1. Install & Setup

**Dependency**
The library publishes to GitHub Packages. Add the repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/umerrjaved1/AdsManager")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

```kotlin
dependencies {
    implementation("com.umer_tf.ads:ads:2.0.0")
    implementation("com.facebook.shimmer:shimmer:0.5.0")
}
```

**AdMob App ID**
In your app's `AndroidManifest.xml` `<application>` block:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

*(Note: Use a `~` for the application ID. Use `/` for ad unit IDs later on.)*

## 2. Initialization & Consent

If you serve the EEA, you must gather consent via UMP before initializing the AdMob SDK. Do this once in your `Application.onCreate` or launcher Activity:

```kotlin
val ads = AdMobManager.getInstance(application)
    .setAppOpenAdStartId(BuildConfig.AD_UNIT_APP_OPEN)
    .setAppOpenAdResumeId(BuildConfig.AD_UNIT_APP_OPEN)
    .setInterstitialCounter(3)
    .setInterstitialAdMinTime(30)
    .setAdEventListener(MyAdAnalytics) // See 'Events & Revenue' below

ads.gatherConsent(activity) { canRequestAds ->
    if (canRequestAds) ads.initialize()
}
```

> **Warning**: Never skip `initialize()`. If you don't call it, every ad request will fail with a generic "Network error".

## 3. Implementing Ads (ViewModel Approach)

The recommended way to serve ads is by using `AdViewModel`, which exposes state as a `StateFlow` and handles all lifecycle constraints automatically.

```kotlin
private val ads: AdViewModel by viewModels()
```

### Native Ads
The library handles layout injection and shimmer matching automatically based on a shape code (e.g., `"4a"`).

```xml
<FrameLayout android:id="@+id/adSlot"
    android:layout_width="match_parent" android:layout_height="wrap_content" />
```

```kotlin
ads.bindNativeAd(this, NATIVE_UNIT, "4a", binding.adSlot)
```

**Available Layout Codes:** `"1a"` (small), `"4a"` (medium), `"large"` (large), `"banner"` (banner-sized).

### Banners
```kotlin
ads.bindBanner(this, this, BANNER_UNIT, binding.bannerSlot)
```
`bindBanner` forwards pause/resume/destroy for you automatically.

### Interstitial & Rewarded (Full Screen)
Full-screen ads cache a single ad per loader. You can load and show them behind a built-in 1.5s loading dialog to prevent accidental clicks:

```kotlin
// Load and show interstitial
ads.loadAndShowInterstitial(this, INTERSTITIAL_UNIT)

// Load and show rewarded
ads.loadAndShowRewarded(this, REWARDED_UNIT)
```

Interstitials are only shown while the host is resumed. If a show is requested — or the pre-show
delay elapses — while the Activity is paused, stopped, or the app is in the background, the show is
parked and goes up on the next `ON_RESUME` instead of being spent against a window that cannot
display it. It is dropped, with a failure reported to the caller, only if that host is finishing or
destroyed.

Dismissal events arrive exactly once on the `events` channel:
```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        ads.events.collect { event ->
            when (event) {
                is AdEvent.RewardEarned -> grantReward()
                is AdEvent.Dismissed -> goToNextScreen()
                is AdEvent.ShowFailed -> log(event.failure.message)
            }
        }
    }
}
```

### App Open Ads
App Open ads are tied to the `ProcessLifecycleOwner`, so they use their own loader instead of the ViewModel:

```kotlin
val loader = AdMobManager.getInstance(this).appOpenAdLoader

// On splash screen / cold start:
loader.loadAppOpenAd(applicationContext) { loaded ->
    if (loaded) loader.showAppOpenAdIfAvailable { goToMain() } else goToMain()
}
```
*(Don't forget to call `.setSplash(false)` when your splash screen finishes to re-enable resume ads).*

## 4. Events & Revenue Tracking

Implement `AdEventListener` and register it with `AdMobManager.setAdEventListener()`. This provides a single choke point for all formats:

```kotlin
object MyAdAnalytics : AdEventListener {
    override fun onAdRevenuePaid(info: AdRevenueInfo) {
        // Logs revenue to your preferred analytics provider (e.g., AppsFlyer)
        AppsFlyerAdRevenue.logAdRevenue(
            "admob",
            MediationNetwork.GOOGLE_ADMOB,
            Currency.getInstance(info.currencyCode),
            info.value,
            mapOf("ad_format" to info.adType.revenueName)
        )
    }

    override fun onAdFailedToLoad(adUnitId: String, adType: AdType, failure: AdLoadFailure) {
        Log.e("Ads", "${adType.revenueName} failed: ${failure.message}")
    }
    
    // Also available: onAdLoaded, onAdImpression, onAdClicked, onAdShowed, onAdDismissed
}
```
The library also natively logs basic events to Firebase Analytics.

## 5. Premium Users

You can globally disable ad requests for paying users. Provide a block that gets checked before any request:

```kotlin
AdMobManager.getInstance(this).setPremiumProvider { billingRepo.isSubscribed }
```
When a premium user hits an ad slot, the library won't even show a shimmer placeholder — it will immediately skip the request entirely.

## 6. Theming Native Ads
You can style native ads programmatically without XML:

```kotlin
val myTheme = NativeAdTheme.auto(
    context,
    lightTheme = NativeAdTheme.light().copy(ctaBgColor = "#DC2626"),
    darkTheme  = NativeAdTheme.dark().copy(ctaBgColor = "#E11D48")
)
```
Pass this theme into the lower-level NativeAdBuilder if you require deep visual customization.
