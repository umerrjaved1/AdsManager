# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Android **library** (AAR) that wraps the **GMA Next-Gen SDK** (`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk`). It was migrated off the legacy `com.google.android.gms:play-services-ads` in 2.0.0. It is published to GitHub Packages as `com.umer_tf.ads:ads`. Gradle root project name is `TeraFortAdManager`; only `:adsManager` is included in `settings.gradle.kts`. The `app/` directory on disk is a leftover — it has no sources and is **not** part of the build.

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

**`core/AdMobManager`** is the single public entry point. It is a `@Volatile`/double-checked singleton (`AdMobManager.getInstance(application)`) that eagerly constructs one loader per ad format and exposes them as `@JvmField` properties (`appOpenAdLoader`, `bannerAdLoader`, `interstitialAdLoader`, `nativeAdLoader`, `rewardedAdLoader`). Its `setXxx()` methods are a fluent chain that write into a shared `AdController`. `initialize()` is **no longer deprecated**: the Next-Gen SDK does not self-initialize and drops any request made before initialization completes.

**`core/AdsInitializer`** owns that initialization. It reads the AdMob app id from the host manifest's `com.google.android.gms.ads.APPLICATION_ID` meta-data (the same tag the legacy SDK used, still required by UMP) or from `AdMobManager.setApplicationId(...)`, runs `MobileAds.initialize` on a background thread, and parks callers until it settles. **Every loader routes its request through `AdsInitializer.runWhenInitialized(context) { … }`** rather than calling the SDK directly, so a host that never calls `initialize()` still gets ads on the first placement. Waiters are also released when no app id can be found, so a misconfigured host sees failed loads rather than hung placements.

**Callback threading.** Next-Gen delivers **every** ad callback on a background thread; the legacy SDK used the main thread. All loader state (slot maps, waiter lists, the native cache) and every View touch is main-thread-only, so each SDK callback body is wrapped in `utils/onMain { }`. New callbacks must do the same — this is the single most likely source of crashes when extending a loader.

**`utils/AdController`** is the mutable config object shared by reference across all loaders: ad unit IDs for app-open, interstitial min/max time and counter thresholds, resume-ad delay, `isSplash`, and the cross-loader `shouldShowOpenAd` flag. Fullscreen loaders flip `shouldShowOpenAd` to `false` in `onAdShowedFullScreenContent` and back to `true` on dismissal — that is how an interstitial suppresses a spurious app-open ad. Changing this flag's lifecycle affects every ad format at once.

**Per-format packages** each pair an `IXxxAdLoader` interface with its implementation (`ads/banner`, `ads/interstitial`, `ads/rewarded`, `ads/native_ad`, `ads/app_open`). `core/BaseAdLoader` is a minimal shared interface but is not implemented by most loaders — the `IXxx` interfaces are the real contracts.

**App-open ads** are the most involved subsystem. `AppOpenAdLoader` extends `BaseObserver` (an `Application.ActivityLifecycleCallbacks` that tracks `currentActivity`) *and* registers itself with `ProcessLifecycleOwner` for foreground/background transitions. It owns no ad itself; it delegates to two `internal` classes, `StartAdManager` (first-launch ad) and `ResumeAdManager` (return-from-background ad), and coordinates a single `isShowingAd` flag between them. Resume ads are gated on elapsed background time vs. `AdController.openAdResumeTime`, suppressed while `isSplash` is true, and suppressed when the current activity is the SDK's own `com.google.android.libraries.ads.mobile.sdk.common.AdActivity`.

**Interstitial gating** — `showAdWithTimeAndCounter` fires when `(counter >= interstitialCounter && elapsed >= minTime) || elapsed >= maxTime`, using the global `TimeManager` clock. `TimeManager` is an object singleton with a `ReentrantReadWriteLock`; it is reset on every interstitial dismissal.

**Native ads** use a `NativeAdBuilder.Builder` to declare which asset views to bind (headline/body/icon/media/rating/price/store/CTA/AdChoices) plus colors, stroke, and CTA radius, and a `FrameLayout` + optional `ShimmerFrameLayout` for the placeholder. Four bundled layouts (`admob_small_native_media`, `admob_large_native_media`, `admob_native_banner_type`, `admob_native_fullscreen`) are the defaults when `layout == 0`. Colors are passed as hex **strings** and applied at runtime via `GradientDrawable`, not via themes.

Under Next-Gen the whole `AdLoader.Builder(...).forNativeAd(...).withAdListener(...)` chain collapses into `NativeAdLoader.load(NativeAdRequest, NativeAdLoaderCallback)`, and `NativeAdOptions` is gone — video options live on the request. Clicks, impressions and revenue are no longer loader-wide: they sit on each ad's `adEventCallback`, which is why `attachEventCallback` runs the moment an ad arrives rather than at bind time — a preloaded native rendered later would otherwise report no impression. Binding is `adView.registerNativeAd(ad, mediaView)`; `NativeAdView.mediaView` is read-only and `setNativeAd` no longer exists.

**Banner lifecycle (1.0.8, revised in 2.0.0).** `attachAdView` destroys the AdView a frame previously held and binds destroy to the host Activity's lifecycle. Before 1.0.8 each reload only *detached* the old AdView — which keeps its own refresh timer — so every reload left another invisible AdView requesting ads forever. The pause/resume half of that fix is gone in 2.0.0: Next-Gen's `AdView` has no `pause()`/`resume()`, because refresh is tied to the Activity passed to `AdView.registerBannerAd(ad, activity)`.

Next-Gen also splits banner loading into three steps that used to be one: the ad unit and `AdSize` move onto a `BannerAdRequest`, load outcomes arrive on an `AdLoadCallback<BannerAd>`, and **the ad is not on screen until `registerBannerAd` runs**. All three banner types funnel through the private `loadInto(...)` helper so that sequence exists in exactly one place. Collapsible banners use `BannerAdRequest.Builder.setGoogleExtrasBundle(extras)` in place of `addNetworkExtrasBundle(AdMobAdapter::class.java, extras)`.

**Native cache semantics (1.0.8).** `NativeAd` keeps one preload slot, and three rules make it safe to share across placements. It is **unit-tagged** (`loadedNativeAdUnitId`): `loadAd`, `loadAndShow` and `showLoadedAd` only reuse the cached ad when it was loaded for the same ad unit, otherwise they request — reusing another unit's ad renders the wrong creative and bills the impression to the wrong unit. It is **consumed on display**: one preloaded ad shows exactly once, so the next placement requests its own instead of re-rendering the same object (which produced no request and no impression). And each host frame's previous ad is destroyed when that frame is rebound, via the `adsByFrame` weak map — only the ad being replaced, never one still visible elsewhere. `isAdLoaded(adUnitId)` answers "is there an unused preload for *this* unit"; the no-arg overload cannot distinguish units. Before 1.0.8 all three rules were absent, which silently capped native show rate in host apps.

**Cross-cutting gates.** `Utilities.shouldShowAd(context)` = `!AdMobManager.isPremium && isNetworkAvailable(context)`. Every loader must call it before loading or showing; premium users get zero ads with no other flag needed.

**Ad unit ID validation.** Public entry points annotate params with `@ValidateAdUnitId` (documentation only) and call `AdUnitIdValidator.validateAdUnitId()`, which **throws `IllegalArgumentException`** unless the ID matches `^ca-app-pub-\d{16,}/\d{10}$`. New loader methods taking an ad unit ID should follow the same annotate-and-validate pair.

**Analytics** goes through `utils/AnalyticsManager` (Firebase Analytics singleton) with event names from `utils/AnalyticsConstants`. Paid-event revenue is forwarded by `apps_flyer/AdsAnalytics.logAppsFlyerRevenue` — despite the package name there is no AppsFlyer dependency; it logs an `ad_revenue_sdk` Firebase event.

**Diagnostics** (`diagnostics/AdEventLog`) is the single emission point for ad lifecycle events; loaders call `emit(format, adUnitId, event, state, reason)` and hosts can `subscribe` to forward them onward. It writes three independent logcat streams off that one call, each switchable at runtime:

| Stream | Tag(s) | Flag | Purpose |
| --- | --- | --- | --- |
| Detailed | `AdEventLog` | `verboseLogging` | All ~18 events, load durations, loss reasons |
| Simple | `mona` (`simpleLogTag`) | `simpleLogging` | Five words: request · load · loaded · show · fail |
| QA | `QA-Inter`, `QA-Native`, `QA-Open-Start`, `QA-Open-Resume` (`AdFormat.qaTag`) | `qaLogging` | Four stages per format: `REQUEST` / `LOAD` / `SHOW` / `DISMISS` plus `LOAD-FAIL` / `SHOW-FAIL` |

The QA stream is split **by format tag** precisely so one placement can be watched alone — `adb logcat -s QA-Inter:D`. Interstitial, native and app-open drive all four stages; `QA-Banner` and `QA-Reward` exist but stay silent because those two loaders never call `emit`. Interstitial and app-open emit both `LOAD_SUCCESS` and `READY` for one fill, so the simple and QA streams each keep their **own** dedupe map — sharing one would make whichever stream logged first swallow the other's line.

**Consent** (`consent/AdsConsentManager`) wraps Google's UMP SDK (`com.google.android.ump:user-messaging-platform`), which arrives transitively with the Next-Gen SDK and is also declared explicitly as `api` because `FormError` appears in this class's public API. Its API was unchanged by the migration. Test mode forces EEA geography and derives a hashed test device ID from `Settings.Secure.ANDROID_ID`.

## Conventions

- All logs are prefixed `Monetization :-` and use a per-class `TAG`. Keep this prefix — it is how ad behavior is filtered in logcat.
- The library is consumed from Java as well as Kotlin: public state uses `@JvmField`, companion functions use `@JvmStatic`, and callbacks are the Java SAM interfaces `OnSuccessListener<T>` / `OnSuccessListenerNative` in `ads/listeners`. Do not replace these with Kotlin lambdas or convert `@JvmField` properties to accessors without accepting a source-breaking change for Java callers.
- Loaders return failure by invoking `onSuccessListener?.onSuccess(false)` rather than throwing; the only throwing path is ad unit ID validation.
- `ads-mobile-sdk` is exposed via `api(...)` so consumers get the ad types transitively; everything else except UMP is `implementation`.
- `configurations.all` excludes `play-services-ads` and `play-services-ads-lite` globally. The Next-Gen artifact repackages the mediation-facing `com.google.android.gms.ads.*` classes, so any transitive copy of those artifacts fails the build with duplicate symbols.
- The SDK's own interfaces (`AdLoadCallback`, `NativeAdLoaderCallback`, `OnUserEarnedRewardListener`, the `*AdEventCallback`s) are written as explicit `object : … { }` expressions, not lambdas — they are Kotlin interfaces and not all of them are `fun interface`, so SAM conversion is not guaranteed to compile.
- `minSdk = 24`, `compileSdk = 37`, Java 11 source/target.
