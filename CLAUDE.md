# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Android **library** (AAR) that wraps the Google Mobile Ads (AdMob) SDK. It is published to GitHub Packages as `com.umer_tf.ads:ads`. Gradle root project name is `TeraFortAdManager`; only `:adsManager` is included in `settings.gradle.kts`. The `app/` directory on disk is a leftover — it has no sources and is **not** part of the build.

There are no unit or instrumentation tests (the generated example tests were deleted). `testImplementation`/`androidTestImplementation` deps are still declared but unused.

## Commands

```bash
./gradlew :adsManager:assembleRelease
```

```bash
./gradlew :adsManager:lint
```

```bash
./gradlew :adsManager:publishReleasePublicationToMavenRepository
```

Publishing requires `gpr.user` / `gpr.key` (GitHub PAT with `write:packages`) in `local.properties` — the build reads credentials from there, falling back to Gradle properties.

`publish.sh` is a macOS-oriented convenience script: it bumps the patch version in `adsManager/build.gradle.kts`, commits, pushes to `master`, and publishes. It hardcodes an Android Studio JBR path and uses BSD `sed -i ''`, so it will not run as-is on Windows/Linux — bump the version manually and run the Gradle task instead.

CI (`.github/workflows/publish.yml`) runs `./gradlew clean publish` only on `v*` tag pushes.

## Versioning

The published version lives in **one place**: the `version = "x.y.z"` line inside the `afterEvaluate { publishing { ... } }` block of [adsManager/build.gradle.kts](adsManager/build.gradle.kts). Any release change must edit that line.

Note the build applies only `com.android.library` and `maven-publish` — Kotlin compiles via AGP 9's built-in Kotlin support, so there is no `kotlin-android` plugin block to touch.

## Architecture

Everything lives under `com.umer_tf.ads.domain.*`.

**`core/AdMobManager`** is the single public entry point. It is a `@Volatile`/double-checked singleton (`AdMobManager.getInstance(application)`) that eagerly constructs one loader per ad format and exposes them as `@JvmField` properties (`appOpenAdLoader`, `bannerAdLoader`, `interstitialAdLoader`, `nativeAdLoader`, `rewardedAdLoader`). Its `setXxx()` methods are a fluent chain that write into a shared `AdController`. `initialize()` is deprecated — AdMob self-initializes.

**`utils/AdController`** is the mutable config object shared by reference across all loaders: ad unit IDs for app-open, interstitial min/max time and counter thresholds, resume-ad delay, `isSplash`, and the cross-loader `shouldShowOpenAd` flag. Fullscreen loaders flip `shouldShowOpenAd` to `false` in `onAdShowedFullScreenContent` and back to `true` on dismissal — that is how an interstitial suppresses a spurious app-open ad. Changing this flag's lifecycle affects every ad format at once.

**Per-format packages** each pair an `IXxxAdLoader` interface with its implementation (`ads/banner`, `ads/interstitial`, `ads/rewarded`, `ads/native_ad`, `ads/app_open`). `core/BaseAdLoader` is a minimal shared interface but is not implemented by most loaders — the `IXxx` interfaces are the real contracts.

**App-open ads** are the most involved subsystem. `AppOpenAdLoader` extends `BaseObserver` (an `Application.ActivityLifecycleCallbacks` that tracks `currentActivity`) *and* registers itself with `ProcessLifecycleOwner` for foreground/background transitions. It owns no ad itself; it delegates to two `internal` classes, `StartAdManager` (first-launch ad) and `ResumeAdManager` (return-from-background ad), and coordinates a single `isShowingAd` flag between them. Resume ads are gated on elapsed background time vs. `AdController.openAdResumeTime`, suppressed while `isSplash` is true, and suppressed when the current activity is AdMob's own `AdActivity`.

**Interstitial gating** — `showAdWithTimeAndCounter` fires when `(counter >= interstitialCounter && elapsed >= minTime) || elapsed >= maxTime`, using the global `TimeManager` clock. `TimeManager` is an object singleton with a `ReentrantReadWriteLock`; it is reset on every interstitial dismissal.

**Native ads** use a `NativeAdBuilder.Builder` to declare which asset views to bind (headline/body/icon/media/rating/price/store/CTA/AdChoices) plus colors, stroke, and CTA radius, and a `FrameLayout` + optional `ShimmerFrameLayout` for the placeholder. Four bundled layouts (`admob_small_native_media`, `admob_large_native_media`, `admob_native_banner_type`, `admob_native_fullscreen`) are the defaults when `layout == 0`. Colors are passed as hex **strings** and applied at runtime via `GradientDrawable`, not via themes.

**Cross-cutting gates.** `Utilities.shouldShowAd(context)` = `!AdMobManager.isPremium && isNetworkAvailable(context)`. Every loader must call it before loading or showing; premium users get zero ads with no other flag needed.

**Ad unit ID validation.** Public entry points annotate params with `@ValidateAdUnitId` (documentation only) and call `AdUnitIdValidator.validateAdUnitId()`, which **throws `IllegalArgumentException`** unless the ID matches `^ca-app-pub-\d{16,}/\d{10}$`. New loader methods taking an ad unit ID should follow the same annotate-and-validate pair.

**Analytics** goes through `utils/AnalyticsManager` (Firebase Analytics singleton) with event names from `utils/AnalyticsConstants`. Paid-event revenue is forwarded by `apps_flyer/AdsAnalytics.logAppsFlyerRevenue` — despite the package name there is no AppsFlyer dependency; it logs an `ad_revenue_sdk` Firebase event.

**Consent** (`consent/AdsConsentManager`) wraps Google's UMP SDK, which ships inside `play-services-ads`. Test mode forces EEA geography and derives a hashed test device ID from `Settings.Secure.ANDROID_ID`.

## Conventions

- All logs are prefixed `Monetization :-` and use a per-class `TAG`. Keep this prefix — it is how ad behavior is filtered in logcat.
- The library is consumed from Java as well as Kotlin: public state uses `@JvmField`, companion functions use `@JvmStatic`, and callbacks are the Java SAM interfaces `OnSuccessListener<T>` / `OnSuccessListenerNative` in `ads/listeners`. Do not replace these with Kotlin lambdas or convert `@JvmField` properties to accessors without accepting a source-breaking change for Java callers.
- Loaders return failure by invoking `onSuccessListener?.onSuccess(false)` rather than throwing; the only throwing path is ad unit ID validation.
- `play-services-ads` is exposed via `api(...)` so consumers get AdMob types transitively; everything else is `implementation`.
- `minSdk = 24`, `compileSdk = 37`, Java 11 source/target.
