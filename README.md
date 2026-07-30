# AdsManager

An AdMob wrapper for Android. One `AdMobManager` entry point covering banner, interstitial, rewarded,
native, full-screen native and app-open ads, with shimmer placeholders, GDPR consent gating and a
single listener for every ad event.

- **minSdk** 24 · **compileSdk** 37 · **Java** 11 · Kotlin, Java-friendly
- `groupId` `com.umer_tf.ads` · `artifactId` `ads` · `2.0.0`

Every format except app open can be driven from a ViewModel as a `StateFlow` — see
[Driving ads from a ViewModel](#16-driving-ads-from-a-viewmodel). **2.0.0 rewrites `AdViewModel`;**
see [Migrating to 2.0.0](#migrating-to-200).

---

## Contents

1. [Install](#1-install)
2. [Required app setup](#2-required-app-setup)
3. [Initialize](#3-initialize)
4. [Ad formats](#4-ad-formats)
   - [Banner](#41-banner)
   - [Interstitial](#42-interstitial)
   - [Rewarded](#43-rewarded)
   - [Native](#44-native)
   - [Native ads in a list](#45-native-ads-in-a-list)
   - [Full-screen native](#46-full-screen-native)
   - [App open](#47-app-open)
5. [Shimmer placeholders](#5-shimmer-placeholders)
6. [Theming native ads](#6-theming-native-ads)
7. [The loading dialog](#7-the-loading-dialog)
8. [Ad events and revenue analytics](#8-ad-events-and-revenue-analytics)
9. [Consent (GDPR / UMP)](#9-consent-gdpr--ump)
10. [Audience tagging and test devices](#10-audience-tagging-and-test-devices)
11. [Premium users](#11-premium-users)
12. [Frequency capping](#12-frequency-capping)
13. [Ad expiry](#13-ad-expiry)
14. [Logging](#14-logging)
15. [Lifecycle: what you must call](#15-lifecycle-what-you-must-call)
16. [Driving ads from a ViewModel](#16-driving-ads-from-a-viewmodel)
17. [Known gaps](#17-known-gaps)
18. [Publishing](#18-publishing)

---

## 1. Install

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

Put your credentials in `local.properties` (never commit them):

```properties
gpr.user=your-github-username
gpr.key=your-personal-access-token
```

Then depend on it:

```kotlin
dependencies {
    implementation("com.umer_tf.ads:ads:2.0.0")
    implementation("com.facebook.shimmer:shimmer:0.5.0")
}
```

The AdMob SDK and the lifecycle/ViewModel artifacts come through as `api` dependencies — you don't
declare them separately. Shimmer is `implementation` inside the library, so
declare it yourself if you reference `ShimmerFrameLayout` in your own layouts (you will).

## 2. Required app setup

**AdMob application id.** In your app's `AndroidManifest.xml`, inside `<application>`:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

Missing or malformed, the AdMob SDK crashes on initialization. Note the `~` — the application id uses
a tilde, ad unit ids use a slash.

**Firebase.** The library logs ad analytics to Firebase Analytics, so the host app needs the Google
Services plugin and a `google-services.json`:

```kotlin
// root build.gradle.kts
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// app/build.gradle.kts
plugins {
    id("com.google.gms.google-services")
}
```

Without it, the built-in analytics silently no-op. Ads still serve normally and nothing crashes -
you just get no Firebase data. Your own [AdEventListener](#8-ad-events-and-revenue-analytics) is
unaffected either way, so if you route events to your own analytics you may not need Firebase at all.

**Permissions** (`INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID`) are declared by the library and merge in
automatically.

## 3. Initialize

Once, from `Application.onCreate` or your launcher Activity. `AdMobManager` is a singleton keyed on the
`Application`.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AdMobManager.getInstance(this)
            .setAppOpenAdStartId(BuildConfig.AD_UNIT_APP_OPEN)
            .setInterstitialCounter(3)
            .setInterstitialAdMinTime(30)
            .setAdEventListener(MyAdAnalytics)
            .initialize()
    }
}
```

**If you serve the EEA, use `gatherConsent` instead of `initialize`** — it gathers consent, then
initializes, in that order. See [Consent](#9-consent-gdpr--ump).

Every setter returns `AdMobManager`, so they chain. `initialize()` is safe to call repeatedly; only the
first call does work.

> **`initialize()` is not optional.** `getInstance()` only builds the facade — nothing else in the
> library starts `MobileAds`. Skip it and every request fails, and the AdMob SDK reports it as
> **"Network error"**, which sends you checking connectivity, the emulator and your ad unit ids
> instead of this line. The library logs an explicit error once when an ad is requested before
> `initialize()`; if you see ads failing, check logcat for it first.

The loaders live on the manager:

```kotlin
val ads = AdMobManager.getInstance(application)
ads.bannerAdLoader
ads.interstitialAdLoader
ads.rewardedAdLoader
ads.nativeAdLoader
ads.appOpenAdLoader
ads.consentManager
```

### Every request passes one gate

All formats short-circuit on `Utilities.shouldShowAd`, which is false when **any** of these hold:

| Condition | Set by |
|---|---|
| User is premium | `setPremium` / `setPremiumProvider` |
| No network | Device state |
| Consent not granted (once enforced) | `gatherConsent` |

When the gate blocks a request, your callback fires with `false`/failure — it never hangs.

## 4. Ad formats

### 4.1 Banner

```xml
<FrameLayout
    android:id="@+id/adContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />

<com.facebook.shimmer.ShimmerFrameLayout
    android:id="@+id/adShimmer"
    android:layout_width="match_parent"
    android:layout_height="50dp" />
```

```kotlin
ads.bannerAdLoader.showAdaptiveBanner(
    activity = this,
    shimmerFrameLayout = binding.adShimmer,
    frameLayout = binding.adContainer,
    adUnitId = BANNER_UNIT
)
```

Also available:

```kotlin
// 300x250 medium rectangle
showMemRecBanner(activity, frameLayout, shimmerFrameLayout, adUnitId)

// collapsible; isTop = true anchors the expanded area to the top
showCollapsableBanner(activity, frameLayout, shimmerFrameLayout, adUnitId, isTop = false)
```

**You must forward the lifecycle**, or the banner keeps refreshing off-screen (billing impressions
nobody sees) and holds your Activity alive:

```kotlin
override fun onPause()   { ads.bannerAdLoader.pause();   super.onPause() }
override fun onResume()  { super.onResume();             ads.bannerAdLoader.resume() }
override fun onDestroy() { ads.bannerAdLoader.destroy(); super.onDestroy() }
```

`destroyFor(frameLayout)` releases a single slot when one container goes away but the screen stays.

### 4.2 Interstitial

Preload, then show:

```kotlin
ads.interstitialAdLoader.loadAd(INTERSTITIAL_UNIT) { loaded -> }

// later
ads.interstitialAdLoader.showAd(
    activity = this,
    adUnitId = INTERSTITIAL_UNIT,
    onAdDismissed = { goToNextScreen() },
    onAdFailedToShow = { reason -> goToNextScreen() }
)
```

Load-and-show behind a loading dialog, for a tap that must produce an ad:

```kotlin
ads.interstitialAdLoader.loadAndShowAd(
    activity = this,
    adUnitId = INTERSTITIAL_UNIT,
    showDialog = true,
    onAdLoaded = { success -> },
    onAdDismissed = { goToNextScreen() }
)
```

#### The 1.5 s dialog applies to cached ads too

Both entry points put the loading dialog up for **1.5 s** before the ad appears, so the ad never lands
under a finger still mid-tap:

| Call | Ad already cached | Nothing cached |
|---|---|---|
| `showAd` | dialog 1.5 s → show | fails fast, `onAdFailedToShow("Ad is null")` |
| `loadAndShowAd` | dialog 1.5 s → **shows the cached ad** | dialog → load → 1.5 s → show |

Two things worth knowing:

- `showAd` is **no longer instant**. A cached ad used to appear the moment you called it, which is how a
  tap ends up landing on the ad itself.
- `loadAndShowAd` now **reuses a cached ad** instead of discarding it and requesting a new one, so a
  preloaded fill is no longer wasted.

Tune the pause globally with `setInterstitialDialogDelay(ms)`, or per loader:

```kotlin
ads.interstitialAdLoader.adShowDelay = 1_000L        // shorter pause
ads.interstitialAdLoader.showLoadingDialog = false   // keep the pause, drop the dialog

// show cached ads instantly, as before
ads.interstitialAdLoader.adShowDelay = 0L
ads.interstitialAdLoader.showLoadingDialog = false
```

`onAdDismissed` is invoked **exactly once on every path** — dismissal, show failure, load failure,
blocked request, or the Activity dying during the delay. Navigation gated on it cannot stall.

Other entry points:

```kotlin
// respects the counter + time caps from section 13
showAdWithTimeAndCounter(activity, adUnitId, showForcefully = false, onAdDismissed, onAdFailedToShow)

// gives up after timeOut ms if nothing fills
loadAdWithTimeOut(adUnitId, timeOut = 8_000L) { loaded -> }
```

### 4.3 Rewarded

```kotlin
ads.rewardedAdLoader.loadAd(this, REWARDED_UNIT) { loaded -> }

ads.rewardedAdLoader.showAd(
    activity = this,
    onRewardEarned = { earned -> if (earned) grantReward() },
    onAdDismissed = { }
)
```

Or in one call:

```kotlin
ads.rewardedAdLoader.loadAndShowAd(
    activity = this,
    adUnitId = REWARDED_UNIT,
    showDialog = true,
    onRewardEarned = { earned -> if (earned) grantReward() }
)
```

The rewarded loader behaves exactly like the interstitial one: both `showAd` and `loadAndShowAd` show
the loading dialog for 1.5 s first, including when the ad is already cached, and `loadAndShowAd` reuses
a cached ad rather than re-requesting. Same knobs — `adShowDelay` and `showLoadingDialog` on
`rewardedAdLoader`, or `setInterstitialDialogDelay(ms)` for both at once.

`onRewardEarned` fires exactly once, with `false` on every failure path.

### 4.4 Native

The shortest path is [`AdViewModel.bindNativeAd`](#161-native--the-whole-integration), which takes a
shape code (`"4a"`) and an ad unit and handles loading, shimmer, rendering and teardown. The rest of
this section is the loader API underneath it.

The one-liner: give it a container, a shimmer host and a layout, and it wires the matching shimmer for
you.

```xml
<FrameLayout
    android:id="@+id/adContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />

<!-- empty; the library inflates the right shimmer into it -->
<FrameLayout
    android:id="@+id/shimmerHost"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
val builder = binding.adContainer.createNativeAdBuilderAutoShimmer(
    shimmerHost = binding.shimmerHost,
    layoutResId = R.layout.layout_native_ad_small_1a
)
ads.nativeAdLoader.loadAndShow(NATIVE_UNIT, builder) { success -> }
```

**Bundled layouts.** Pick by shape; every one has a matching shimmer.

| Layout | Shape |
|---|---|
| `layout_native_ad_banner` | 130 dp, icon + text + CTA |
| `layout_native_ad_small_1a` | media left, text right |
| `layout_native_ad_small_1b` | icon + text, no media |
| `layout_native_ad_small_1c` / `_1d` | media left, icon + headline + body |
| `layout_native_ad_small_3a` / `_3b` | icon left, CTA right |
| `layout_native_ad_small_4` | media + icon header |
| `layout_native_ad_small_7a` / `_7b` / `_7c` | compact text-forward variants |
| `layout_native_ad_large` | icon header, body, media, CTA |
| `layout_native_ad_large_5a` / `_6a` / `_6b` | large media variants |
| `layout_native_ad_large_v2` / `_v3` | media-first with rating row |
| `admob_small_native_media` | library default |
| `admob_large_native_media` | large default |
| `admob_native_fullscreen` | for `FullScreenNativeAdActivity` |
| `admob_native_banner_type` | banner-shaped native |

**Full control** via the builder:

```kotlin
val builder = NativeAdBuilder.Builder(
    R.layout.layout_native_ad_large_5a,
    binding.adContainer,
    shimmerFrameLayout
)
    .setShowMedia(true)
    .setShowBody(true)
    .setShowCallToAction(true)
    .setIconEnabled(true)
    .setShowRating(false)
    .setShowPrice(false)
    .setShowStore(false)
    .setShowAdvertiser(false)
    .setTheme(NativeAdTheme.auto(this))
    .setCtaRadius(16)
    .setAdCornerRadius(12)
    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
    .build()
```

**Load now, show later** — useful when you want the ad ready before the screen appears:

```kotlin
ads.nativeAdLoader.loadAd(NATIVE_UNIT) { success, nativeAd -> }
// then, when the view exists
ads.nativeAdLoader.showLoadedAd(builder, NATIVE_UNIT)
```

**Exit-dialog ad** — a separate cache slot so it doesn't compete with your in-screen native ad:

```kotlin
ads.nativeAdLoader.loadExitNativeAd(NATIVE_UNIT)
ads.nativeAdLoader.showExitNativeAd(builder, NATIVE_UNIT)
```

The `"Ad"` attribution label and the AdChoices overlay are applied and forced visible by the library.
They are not configurable — AdMob policy requires both on every native ad. Only their colours can be
changed, via `setBadgeColors(...)` or a theme.

### 4.5 Native ads in a list

`nativeAdLoader` holds one ad, so it cannot serve a RecyclerView. Use `NativeAdPool`, which keeps
several ads and — importantly — remembers which ad a position was given, so rebinding a recycled row
doesn't consume a new ad or re-bill an impression.

```kotlin
private val adPool = NativeAdPool(context, NATIVE_UNIT, size = 3).also { it.preload() }

override fun onBindViewHolder(holder: VH, position: Int) {
    val ad = adPool.acquire(position)
    if (ad != null) holder.render(ad) else holder.showShimmer()
}

override fun onViewRecycled(holder: VH) {
    // only when the row is genuinely gone, not on every scroll
}

fun onDestroyView() = adPool.destroy()
```

`acquire` returns `null` when nothing is ready yet — show a placeholder; the next rebind will have one.
Keep `size` small: unshown ads still count against your request-to-impression ratio.

### 4.6 Full-screen native

A dedicated Activity that presents a native ad full-screen with a delayed close button.

```kotlin
FullScreenNativeAdActivity.start(
    context = this,
    adUnitId = NATIVE_UNIT,
    theme = NativeAdTheme.auto(this),
    closeButtonDelayMs = 2_000L,
    layoutResId = R.layout.admob_native_fullscreen,
    loadTimeoutMs = FullScreenNativeAdActivity.DEFAULT_LOAD_TIMEOUT_MS,  // 15 s
    onAdDismissed = { proceed() }
)
```

System back is disabled by design, and the close button appears only `closeButtonDelayMs` after the ad
loads. `loadTimeoutMs` is the safety valve: if the ad never resolves, the Activity closes itself and
calls `onAdDismissed`, so the user is never trapped on a blank screen. `onAdDismissed` fires exactly
once on every path.

The Activity is `exported="false"` and validates a single-use launch token, so it cannot be started by
an external intent.

### 4.7 App open

Driven by `ProcessLifecycleOwner` — the library observes foreground/background itself.

```kotlin
// once, at startup
AdMobManager.getInstance(this)
    .setAppOpenAdStartId(APP_OPEN_UNIT)
    .setAppOpenAdResumeId(APP_OPEN_UNIT)
    .setOpenAdResumeTime(5)     // seconds backgrounded before a resume ad may show
    .setShouldShowResumeAd(true)
    .setSplash(true)            // suppress resume ads while the splash is up

// splash / cold start
ads.appOpenAdLoader.loadAppOpenAd(applicationContext) { loaded ->
    if (loaded) ads.appOpenAdLoader.showAppOpenAdIfAvailable { goToMain() } else goToMain()
}
```

Set `setSplash(false)` once the splash is gone, or resume ads stay suppressed.

Resume ads are loaded and shown automatically. `isStartAdAvailable()` accounts for the 4-hour expiry.
Call `destroyAds()` when you want to detach the lifecycle observer.

## 5. Shimmer placeholders

**The library owns the placeholder end to end.** Declare *one* empty `FrameLayout` per ad slot: the
library inflates the shimmer matching the ad shape into it, sizes it, themes it, starts it, and
replaces or hides it on *every* terminal outcome — loaded, no fill, malformed ad unit id, blocked by
consent, offline, premium user, exception. App code never calls `startShimmer`, `stopShimmer` or
toggles visibility. A premium user does not even get a flash: the placeholder is skipped entirely
rather than shown and then resolved to an empty slot.

That applies to native slots (`NativeAdShimmer`, keyed off the ad layout) and to banners
(`BannerShimmer`, keyed off `BannerAdType`). Both also accept a `ShimmerFrameLayout` you supply
yourself, and drive that instead.

`NativeAdShimmer` is the single source of truth pairing an ad layout with a shimmer of the same shape.
Use `createNativeAdBuilderAutoShimmer` (above) and you never touch it.

For your own layouts, register the pairing once — everything in the library picks it up:

```kotlin
NativeAdShimmer.register(R.layout.my_native_ad, R.layout.my_native_ad_shimmer)
```

```kotlin
NativeAdShimmer.hasShimmerFor(layoutRes)     // is it paired?
NativeAdShimmer.resolveShimmerLayout(layoutRes)
NativeAdShimmer.attachTo(host, layoutRes, theme)   // inflate, theme, start; returns the shimmer
shimmer.stopAndHide()                        // stop + GONE in one call
```

An unregistered layout falls back to the small-media shimmer and logs a warning. Shimmer placeholder
blocks are tinted from the active theme's `shimmerBaseColor` and have `values-night` variants, so dark
mode works without extra work.

Banner shimmers are sized automatically to the resolved ad height.

## 6. Theming native ads

`NativeAdTheme` is programmatic — no XML colour resources needed.

```kotlin
NativeAdTheme.light()
NativeAdTheme.dark()
NativeAdTheme.auto(context)          // picks light/dark from the system

// customise anything
NativeAdTheme.auto(
    context,
    lightTheme = NativeAdTheme.light().copy(ctaBgColor = "#DC2626"),
    darkTheme  = NativeAdTheme.dark().copy(ctaBgColor = "#E11D48")
)
```

Fields: `adBgColor`, `adTitleColor`, `adBodyColor`, `ctaBgColor`, `ctaTextColor`, `strokeColor`,
`showBgStroke`, `strokeWidth`, `ctaRadius`, `adCornerRadius`, `badgeTextColor`, `badgeStrokeColor`,
`shimmerBaseColor`, `shimmerHighlightColor`. All colours are `#RRGGBB` / `#AARRGGBB` strings.

Apply with `builder.setTheme(theme)`, or pass it to the extension functions.

## 7. The loading dialog

Three levels of customisation, cheapest first.

```kotlin
// 1. built-in layout, different copy
ads.setLoadingDialog(AdLoadingDialogConfig(message = "Almost there…"))

// 2. your own layout
ads.setLoadingDialog(
    AdLoadingDialogConfig(
        layoutResId = R.layout.my_loader,
        messageViewId = R.id.tvMessage,     // library sets the text here
        message = "Loading",
        dimAmount = 0.7f
    )
)

// 3. your own Dialog entirely — library only calls show()/dismiss()
ads.setLoadingDialog(
    AdLoadingDialogConfig(dialogProvider = { activity -> MyBrandedLoader(activity) })
)
```

Per-call override:

```kotlin
ads.interstitialAdLoader.loadAndShowAdWithDialog(
    activity = this,
    adUnitId = INTERSTITIAL_UNIT,
    dialogConfig = AdLoadingDialogConfig.withLayout(R.layout.other_loader),
    onAdDismissed = { proceed() }
)
```

`cancelable` defaults to **false**: the dialog auto-dismisses when the ad resolves, and letting the
user dismiss it early only hides the fact that a full-screen ad is about to appear.

## 8. Ad events and revenue analytics

One interface for every event on every format, with the ad unit id and `AdType` on each callback.
Register it once.

```kotlin
AdMobManager.getInstance(this).setAdEventListener(object : AdEventListener {

    override fun onAdLoaded(adUnitId: String, adType: AdType) { }

    override fun onAdFailedToLoad(adUnitId: String, adType: AdType, failure: AdLoadFailure) {
        Log.w("Ads", "${adType.revenueName} failed: ${failure.code} ${failure.message}")
    }

    override fun onAdImpression(adUnitId: String, adType: AdType) { }
    override fun onAdClicked(adUnitId: String, adType: AdType) { }
    override fun onAdShowed(adUnitId: String, adType: AdType) { }
    override fun onAdDismissed(adUnitId: String, adType: AdType) { }

    override fun onAdRevenuePaid(info: AdRevenueInfo) {
        AppsFlyerAdRevenue.logAdRevenue(
            "admob",
            MediationNetwork.GOOGLE_ADMOB,
            Currency.getInstance(info.currencyCode),
            info.value,
            mapOf("ad_format" to info.adType.revenueName)
        )
    }
})
```

Every method has a no-op default — implement only what you need.

**`AdType`** is the single ad-type identifier: `BANNER`, `BANNER_MEDIUM_RECTANGLE`,
`BANNER_COLLAPSIBLE`, `INTERSTITIAL`, `REWARDED`, `NATIVE`, `NATIVE_EXIT`, `APP_OPEN_START`,
`APP_OPEN_RESUME`. Use `revenueName` as your reporting dimension.

**`AdRevenueInfo`** carries `adUnitId`, `adType`, `valueMicros`, `value`, `currencyCode` (the currency
AdMob actually reported — not assumed USD) and `precision` (`ESTIMATED` / `PUBLISHER_PROVIDED` /
`PRECISE`). Don't mix precisions in one total without distinguishing them.

**`AdLoadFailure`** has `code`, `message`, `domain`. `code == AdLoadFailure.CODE_LIBRARY` (-1) means the
library refused the request before it reached the SDK — malformed ad unit id, consent gate, kill
switch.

### Counting impressions

Never count `onAdLoaded` — a loaded ad has not necessarily been seen.

| Format | Impression signal |
|---|---|
| Banner, native | `onAdImpression` |
| Interstitial, rewarded, app open | `onAdShowed` |

The two never both fire for the same ad, so impressions = `onAdImpression` + `onAdShowed`.

Threading: lifecycle callbacks arrive on the main thread, `onAdRevenuePaid` may arrive on a background
thread. Don't block, and don't throw — exceptions are caught and logged, but a swallowed callback is
lost data.

The library also logs each event to Firebase Analytics itself, under the event names it has always
used, so existing dashboards keep working.

## 9. Consent (GDPR / UMP)

If you serve the EEA, this is mandatory. One call gathers consent, then initializes the SDK — in that
order, so no request escapes before the user answers.

```kotlin
AdMobManager.getInstance(application).gatherConsent(activity) { canRequestAds ->
    if (canRequestAds) startLoadingAds()
}
```

Safe to call on every launch: UMP only shows a form when one is actually required. Pass `isTest = true`
to force the EEA debug geography and exercise the form outside Europe — the device's hashed id is
printed to logcat.

Until consent settles, `shouldShowAd` reports false and every loader short-circuits.

**Enforcement is opt-in.** `AdsConsentGate` starts unenforced, because UMP reports "cannot request ads"
until an update completes — gating on that unconditionally would mean zero ads for an app with no
consent flow. Calling `gatherConsent` switches enforcement on. An app that never calls it keeps working
but logs a warning on its first ad request.

```kotlin
AdsConsentGate.allowsAdRequests()   // current answer
AdsConsentGate.isEnforced
AdsConsentGate.canRequestAds
ads.consentManager.reset()          // testing: back to a first-install state
```

## 10. Audience tagging and test devices

Mandatory for anything targeting Play Families. Set it **before** `initialize()`.

```kotlin
AdMobManager.getInstance(this)
    .setRequestConfig(
        AdsRequestConfig(
            tagForChildDirectedTreatment = true,           // COPPA
            tagForUnderAgeOfConsent = true,                // GDPR
            maxAdContentRating = AdsRequestConfig.RATING_G,
            testDeviceIds = listOf("33BE2250B43518CCDA7DE426D04EE231")
        )
    )
    .initialize()
```

Or the preset:

```kotlin
.setRequestConfig(AdsRequestConfig.forChildDirectedApp())
```

Ratings: `RATING_G`, `RATING_PG`, `RATING_T`, `RATING_MA`. To find your device's hash:

```kotlin
AdsRequestConfig.logTestDeviceId(context)
```

Google's test ad unit ids are detected and warned about if they reach a release build.

## 11. Premium users

`setPremium(true)` is a snapshot and defaults to false, so between process start and your app setting
it, a paying user can be served ads. Prefer a provider, read at request time:

```kotlin
AdMobManager.getInstance(this).setPremiumProvider { billingRepo.isSubscribed }
```

The provider wins whenever it's registered. Keep it cheap — no disk or network reads.

## 12. Frequency capping

For `showAdWithTimeAndCounter` only; the other entry points show unconditionally.

```kotlin
ads.setInterstitialCounter(3)       // show on the 3rd eligible call…
   .setInterstitialAdMinTime(30)    // …but only if 30 s have passed
   .setInterstitialAdMaxTime(120)   // …or force it once 120 s have passed
```

The rule is `(counter reached AND min time passed) OR max time passed`. The clock starts at
`initialize()` and resets on every interstitial dismissal.

## 13. Ad expiry

Cached ads go stale — a stale ad tends to fail at show time or fill at a lower value. `isAdLoaded()`
treats an expired ad as absent and discards it, so the next load fetches a replacement instead of your
user's tap landing on a failure.

| Format | Default TTL | `AdController` field |
|---|---|---|
| App open | 4 h *(documented by Google)* | `appOpenAdTtlMs` |
| Native | 1 h *(documented by Google)* | `nativeAdTtlMs` |
| Interstitial | 1 h *(conservative default, not an AdMob limit)* | `interstitialAdTtlMs` |
| Rewarded | 1 h *(conservative default, not an AdMob limit)* | `rewardedAdTtlMs` |

Tune the interstitial and rewarded values against your own fill and show-rate data.

## 14. Logging

Routine ad events log at DEBUG and are silent unless enabled; genuine failures always log at ERROR.

```kotlin
AdsLog.isEnabled = true   // defaults to BuildConfig.DEBUG
```

Tags: `AdsManager_Banner`, `AdsManager_Interstitial`, `AdsManager_Rewarded`, `AdsManager_Native`,
`AdsManager_NativePool`, `AdsManager_FullScreen`, `AdsManager_AppOpen*`, `AdsManager_Events`,
`AdsManager_Consent`, `AdsManager_Shimmer`, `AdsManager_AdUnitId`.

Malformed ad unit ids throw in debug and log-and-skip in release. Flip with
`AdUnitIdValidator.strictMode`.

## 15. Lifecycle: what you must call

This table applies when you drive the loaders directly. If you use
[`AdViewModel`](#16-driving-ads-from-a-viewmodel), `bindBanner` and `bindNativeAd` handle all of it.

| Where | Call | Why |
|---|---|---|
| `Activity.onPause` | `bannerAdLoader.pause()` | Stops off-screen refresh |
| `Activity.onResume` | `bannerAdLoader.resume()` | |
| `Activity.onDestroy` | `bannerAdLoader.destroy()` | Destroys AdViews; otherwise they leak |
| Screen teardown | `nativeAdLoader.destroy()` | Releases cached native ads |
| Screen teardown | `NativeAdPool.destroy()` | Releases pooled ads |
| App teardown | `interstitialAdLoader.destroy()`, `rewardedAdLoader.destroy()` | Cancels timers, dismisses dialogs |
| Splash finished | `setSplash(false)` | Re-enables resume ads |

Calling `destroy()` on a loader does not make it unusable — it cancels in-flight work and clears
caches, then the loader is ready again.

## 16. Driving ads from a ViewModel

`AdViewModel` owns every format **except app open** and exposes each as a `StateFlow`. App open is
excluded on purpose: it is driven by `ProcessLifecycleOwner` inside the library and belongs to the
process, not to a screen. Keep using `appOpenAdLoader` for it — see [4.7](#47-app-open).

It is an `AndroidViewModel`, so there is no factory and it finds `AdMobManager` itself:

```kotlin
private val ads: AdViewModel by viewModels()
```

### 16.1 Native — the whole integration

**One empty `FrameLayout`** and a shape code. The layout, the matching shimmer, the load, the state
collection and the teardown are all derived:

```xml
<FrameLayout android:id="@+id/adSlot"
    android:layout_width="match_parent" android:layout_height="wrap_content" />
```

```kotlin
ads.bindNativeAd(this, NATIVE_UNIT, "4a", binding.adSlot)
```

The shimmer is inflated into that container and replaced by the ad when it arrives. One view means
there is no second visibility to keep in sync — a loaded ad can't end up sitting below a leftover
placeholder's reserved height.

Pass a separate `shimmerHost` only when the placeholder genuinely belongs elsewhere in the layout:

```kotlin
ads.bindNativeAd(this, NATIVE_UNIT, "4a", binding.adContainer, binding.shimmerHost)
```

From a Fragment pass `viewLifecycleOwner`, not the Fragment, so the binding dies with the view.

**Layout codes.** `NativeAdLayout` maps a short code to a bundled layout, so `R.layout` constants no
longer appear in app code. Matching ignores case, separators and prefixes — `"4a"`, `"SMALL_4"` and
`"layout_native_ad_small_4"` are the same request.

| Code | Shape |
|---|---|
| `1a` | media left, text right |
| `1b` | icon + text, no media |
| `1c` / `1d` | media left, icon + headline + body |
| `3a` / `3b` | icon left, CTA right |
| `4a` | media + icon header (also accepts `4`) |
| `7a` / `7b` / `7c` | compact, text-forward |
| `large` | icon header, body, media, CTA |
| `5a` / `6a` / `6b` | large media variants |
| `v2` / `v3` | media-first with a rating row |
| `banner` | 130 dp banner shape |
| `small` / `largemedia` / `bannermedia` | the library defaults |
| `fullscreen` | for `FullScreenNativeAdActivity` |

An unrecognised code logs a warning and falls back to `1a` rather than showing nothing. Pass the
enum directly (`NativeAdLayout.SMALL_4`) if you prefer compile-time checking.

**Observing it yourself** instead of using `bindNativeAd`:

```kotlin
ads.nativeState(key).collect { state ->
    when (state) {
        is NativeAdUiState.Idle    -> ads.loadNative(NATIVE_UNIT, key)
        is NativeAdUiState.Loading -> Unit
        is NativeAdUiState.Loaded  -> ads.renderNative(key, builder)
        is NativeAdUiState.Failed  -> hideAdSlot()
    }
}
```

### 16.2 Slots

Native and banner state is **keyed**, because a screen really can host several at once. The key
defaults to the ad unit id, and the binding helpers default it to the container's view id — so two
placements sharing one ad unit still get independent state:

```kotlin
ads.bindNativeAd(this, NATIVE_UNIT, "1a", binding.topAd, binding.topShimmer)
ads.bindNativeAd(this, NATIVE_UNIT, "6a", binding.bottomAd, binding.bottomShimmer)
```

A slot that already holds an ad is left alone, so the ad survives a rotation instead of costing a
second request. Pass `forceRefresh = true` to replace it deliberately.

Interstitial and rewarded are **not** keyed — the loaders each cache exactly one ad, so pretending
otherwise would hand two callers the same ad under different names. They are plain
`ads.interstitialState` / `ads.rewardedState`.

For lists, `ads.nativeAdPool(NATIVE_UNIT, size = 3)` returns a `NativeAdPool` owned and destroyed by
the ViewModel — see [4.5](#45-native-ads-in-a-list).

### 16.3 Banner

Same deal — one empty `FrameLayout`:

```xml
<FrameLayout android:id="@+id/bannerSlot"
    android:layout_width="match_parent" android:layout_height="wrap_content" />
```

```kotlin
ads.bindBanner(this, this, BANNER_UNIT, binding.bannerSlot)
```

`bindBanner` forwards pause / resume / destroy for you, so a screen using it needs **no banner
lifecycle boilerplate** — the table in [15](#15-lifecycle-what-you-must-call) is already handled.
Shape comes from `BannerAdType`: `ADAPTIVE`, `MEDIUM_RECTANGLE`, `COLLAPSIBLE_TOP`,
`COLLAPSIBLE_BOTTOM`.

The `shimmer` argument defaults to the container. Pass a separate container to put the placeholder
elsewhere, your own `ShimmerFrameLayout` to keep control of it, or null for none.

`ads.showBanner(activity, adUnitId, container, shimmer, type)` is the lower-level call when you need
to re-request on demand; it publishes to `ads.bannerState(key)` either way.

### 16.4 Full screen: state versus events

```kotlin
ads.loadInterstitial(INTERSTITIAL_UNIT)
ads.showInterstitial(this, INTERSTITIAL_UNIT)

ads.loadRewarded(this, REWARDED_UNIT)
ads.showRewarded(this, REWARDED_UNIT)

// or in one call, behind the loading dialog
ads.loadAndShowInterstitial(this, INTERSTITIAL_UNIT)
ads.loadAndShowRewarded(this, REWARDED_UNIT)
```

`interstitialState` and `rewardedState` are `FullScreenAdUiState`: `Idle`, `Loading`,
`Loaded`, `Showing`, `Failed`.

**There is deliberately no `Dismissed` state.** A terminal outcome held in a `StateFlow` is
re-delivered to every new collector, so a screen that navigates on dismissal would navigate again
after every rotation. Dismissal and reward arrive **once**, on `ads.events`:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        ads.events.collect { event ->
            when (event) {
                is AdEvent.RewardEarned -> grantReward()
                is AdEvent.ShowFailed   -> log(event.failure.message)
                is AdEvent.Dismissed    -> goToNextScreen()
            }
        }
    }
}
```

`AdEvent.Dismissed` is emitted **exactly once per show attempt**, including when the ad failed to
show or was blocked — the same guarantee the callback API makes, so navigation gated on it cannot
stall. `RewardEarned` fires only when the reward was actually granted, and always before
`Dismissed`. Events are buffered until collected, so one that fires while the screen is stopped is
not lost. Collect them from one place.

### 16.5 Ownership

Native ads loaded through the ViewModel are owned by it and destroyed in `onCleared` — do not call
`destroy()` on an ad from `NativeAdUiState.Loaded`. The ViewModel never retains a View: methods that
take a container use it for the duration of the call only. Call every method from the main thread.

## 17. Known gaps

Be aware of these before building on it:

- **No mediation or waterfall.** One ad unit per format, no high/low floor units, no fallback on
  no-fill.
- **No remote configuration or kill switch.** Ad unit ids, frequency caps and expiry windows are set
  in code, so changing any of them needs a release. There is no way to disable ads remotely.
- **Firebase Analytics must be configured by the host app.** Without the Google Services plugin and
  `google-services.json` the built-in analytics no-op; ads still serve.
- **No instrumentation tests.** Unit tests cover the pure logic (ad unit validation, shimmer registry,
  consent gate, `AdType` mapping). Nothing automated verifies what actually renders — shimmer/ad shape
  matching, badge visibility, dark mode, the loading dialog.
- **`NativeAdPool` is untested.** It can now be obtained from `AdViewModel.nativeAdPool(...)`, which
  at least ties its lifetime to the screen, but nothing automated exercises it.
- **`AdEvents` holds a single global listener.** No per-screen registration; clear it with `null`.
- **Strings are default-locale only** — no translations for `loading_ad`, `ads_close_ad`,
  `ads_sponsored`.
- **R8 is not proven.** The library ships minimal consumer rules and the sample app enables
  minification, but the new code paths aren't exercised by the sample yet.

### Migrating to 2.0.0

`AdViewModel` was rewritten; nothing else changed. The loader APIs, `AdMobManager` and
`AdEventListener` are untouched, so an app that does not use `AdViewModel` can upgrade as-is.

| Removed / changed | Replacement |
|---|---|
| `AdViewModel` took `AdMobManager` on every method | It is now an `AndroidViewModel` and resolves the manager itself — `by viewModels()`, no arguments |
| `nativeAdState` (single slot) | `nativeState(key)`; keys default to the container's view id |
| `NativeAdUiState.Success(ad, id)` | `NativeAdUiState.Loaded(ad, id)` |
| `NativeAdUiState.Error(message)` | `NativeAdUiState.Failed(adUnitId, failure)`, carrying an `AdLoadFailure` |
| `loadNativeAd(manager, id)` / `showNativeAd(manager, builder, id)` | `loadNative(id, key)` / `renderNative(key, builder)`, or just `bindNativeAd(...)` |
| `InterstitialAdUiState` / `RewardedAdUiState` | one `FullScreenAdUiState` for both |
| `InterstitialAdUiState.Dismissed`, `RewardedAdUiState.Shown(rewardEarned)` | `AdEvent.Dismissed` / `AdEvent.RewardEarned` on `ads.events` — states no longer replay a terminal outcome after rotation |
| No banner support in the ViewModel | `bindBanner(...)` / `showBanner(...)` / `bannerState(key)` |
| Native layouts referenced as `R.layout.layout_native_ad_*` | `NativeAdLayout` codes — `"4a"`, `"v2"`, `"large"` |
| `bannerAdLoader.showAdaptiveBanner` / `showMemRecBanner` / `showCollapsableBanner` | Still present and unchanged; `showBanner(..., type, onResult)` collapses all three and reports the outcome |

### Migrating from 1.0.x

| Removed / changed | Replacement |
|---|---|
| `AdsAnalytics.logAppsFlyerRevenue` (never touched AppsFlyer, always reported USD) | `AdEventListener.onAdRevenuePaid`, correct currency |
| `AdFormat`, `AdRevenueTracker`, `AdRevenueListener` | `AdType`, `AdEvents`, `AdEventListener` |
| `AppOpenAdLoader.isPremium` was a settable field | now read-only; use `setPremium` / `setPremiumProvider` |
| `adShowDelay` was a `@JvmField` | now a property (Java: `setAdShowDelay`), defaults to 1500 ms |
| `showAd` showed a cached interstitial/rewarded instantly | now preceded by the 1.5 s loading dialog; set `adShowDelay = 0` and `showLoadingDialog = false` to restore |
| `loadAndShowAd` always requested a new ad | now reuses a cached ad if one is available |
| `FullScreenNativeAdActivity.start(...)` | gained `loadTimeoutMs` before `onAdDismissed`; use named arguments |
| `initialize()` was `@Deprecated` | no longer deprecated — it is required |
| `LoadingDialogUtil.customLoadingLayoutResId` | `AdMobManager.setLoadingDialog(AdLoadingDialogConfig)` |

## 18. Publishing

```bash
./gradlew :adsManager:publishReleasePublicationToMavenRepository
```

Bump `version` in `adsManager/build.gradle.kts` first. Credentials come from `gpr.user` / `gpr.key` in
`local.properties` or as Gradle properties.

Build and test locally:

```bash
./gradlew :adsManager:testReleaseUnitTest :adsManager:assembleRelease :app:assembleRelease
```
