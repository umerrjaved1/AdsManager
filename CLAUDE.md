# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android AAR library (`com.umer_tf.ads:ads`) that wraps the AdMob SDK behind one `AdMobManager`
entry point: banner, interstitial, rewarded, native, full-screen native and app-open ads, plus
shimmer placeholders, UMP consent gating and a single event/revenue listener.

Two Gradle modules:

- `:adsManager` — the published library (`namespace com.umer_tf.ads`, minSdk 24, compileSdk 37, Java 11)
- `:app` — a sample host app (`com.example.admobmanager`) that exists to exercise the library, in
  particular to prove the AAR survives R8 (`isMinifyEnabled = true` in its release build)

`README.md` is the public API documentation and is long and current — read it before changing public
surface, and update it when you do.

## Commands

Build and run the unit tests:

```bash
./gradlew :adsManager:testReleaseUnitTest :adsManager:assembleRelease :app:assembleRelease
```

Single test class / method:

```bash
./gradlew :adsManager:testReleaseUnitTest --tests "com.umer_tf.ads.domain.consent.AdsConsentGateTest"
```

Lint:

```bash
./gradlew :adsManager:lintRelease
```

Publish to GitHub Packages (bump `version` in `adsManager/build.gradle.kts` first):

```bash
./gradlew :adsManager:publishReleasePublicationToMavenRepository
```

CI (`.github/workflows/publish.yaml`) runs `./gradlew clean publish` on pushes to `main` and
`feature/native-ads` and on `v*` tags. It does **not** run tests — they are commented out of the
workflow, so run them locally before tagging.

Publishing credentials resolve local-first: `local.properties` (`gpr.user` / `gpr.key`) → Gradle
properties → `GITHUB_USERNAME` / `GITHUB_TOKEN` env vars. The env fallback is what CI uses.

## Architecture

Everything lives under `com.umer_tf.ads.domain.*`.

**`core/AdMobManager`** — the singleton facade, keyed on `Application`. Holds one loader instance per
format as `@JvmField` properties (`bannerAdLoader`, `interstitialAdLoader`, `rewardedAdLoader`,
`nativeAdLoader`, `appOpenAdLoader`) plus a lazy `consentManager`. Every setter returns `this` so
they chain. All configuration is funnelled into a single `AdController` instance shared by the
loaders — that class is the mutable settings bag (ad unit ids, frequency caps, TTLs, dialog config).
`getInstance` calls `AdsEnvironment.detectFrom(application)` *before* returning, which must stay
first because `AdsLog.isEnabled` and `AdUnitIdValidator.strictMode` both default off it.
`getInstance` does **not** start the SDK — `initialize()` does, and skipping it makes every request
fail with AdMob's misleading "Network error", so `shouldShowAd` calls
`AdMobManager.warnIfNotInitialized()` to name the real cause once.

**The request gate.** Every loader short-circuits on `utils/Utilities.shouldShowAd(context)`, which
is false when the user is premium, offline, or blocked by `consent/AdsConsentGate`. A blocked
request always fires the caller's callback with failure — nothing hangs. Adding a new ad path means
routing it through this gate.

**Consent.** `AdsConsentManager` drives UMP and needs an `Activity`; `AdsConsentGate` is the
process-wide object that ad requests actually consult. Enforcement is **opt-in** — the gate starts
unenforced and only switches on when `gatherConsent` runs, because `canRequestAds()` is false until
UMP's update completes and gating on it unconditionally would mean zero ads for apps with no consent
flow.

**Events.** Loaders never call Firebase directly; they report to `analytics/AdEvents`, which logs to
Firebase Analytics *and* dispatches to the app's single `AdEventListener`. `AdType` is the one
ad-type identifier (`analyticsName` for Firebase, `revenueName` for revenue reporting) — do not
introduce ad-format strings anywhere else. Listener exceptions are caught and logged so a bad
listener cannot break ad delivery.

**Shimmer is the library's job, not the app's.** An ad slot is **one** `FrameLayout`: the placeholder
is inflated into it and replaced by the ad, so there is no second view whose visibility can drift out
of sync. Every entry point does the whole lifecycle — pick by shape, size, theme, start, and
stop-and-hide on *every* terminal path (loaded, no fill, invalid id, consent-blocked, offline,
premium, exception). Refusals are decided *before* attaching, or a blocked user sees a one-frame
flash. `NativeAdShimmer` maps ad layout → shimmer; `BannerShimmer` maps `BannerAdType` → shimmer; both
share `NativeAdShimmer.inflateInto`. Slot params take a `ViewGroup`: default is the ad container, a
separate container is an opt-in wrapper (which must then also be collapsed, or its height and margins
remain as a gap), and a caller-supplied `ShimmerFrameLayout` is used as-is. Banners are the awkward
case — the `AdView` must be attached *first* (`attachAdView` clears the container, and an AdView has
to be in the hierarchy to size), so the shimmer is layered on top and hidden rather than replaced.
Never add an ad path that leaves the placeholder animating over an empty slot.

**Native ad rendering** is a three-part system: `NativeAdBuilder` (which layout, which fields, theme,
radii), `NativeAdTheme` (programmatic colours, no XML resources, `light()`/`dark()`/`auto()`), and
`NativeAdShimmer` (a registry mapping each ad layout to the shimmer layout of the same shape).
**Any new `layout_native_ad_*` must be registered in `NativeAdShimmer.pairings` alongside a matching
`adlibrary_shimmer_*` layout**, otherwise it silently falls back to the small-media shimmer and
flashes a wrong-sized placeholder. It must also carry **`@id/clAd` as the root child** — that is the id
`populateNativeAdView` applies background colour, corner radius and stroke to, so a layout without it
renders fine and ignores `NativeAdTheme` entirely, which is how the banner shape went unthemeable
without anyone noticing. Never put `android:backgroundTint` on `@id/ad_call_to_action`: the runtime
themed `GradientDrawable` replaces the background but the tint survives and recolours it.

**Sizes come from `values/dimens_native_ad.xml`, never literals.** Text size and font are assigned by
the view's *role* (`ad_headline`, `ad_body`, `ad_call_to_action`, badge, metadata), not by whatever
number a layout happened to use — that is what stopped the 21 shapes drifting into five fonts and
eight text sizes. Fonts are always an `@font/inter*` weight, so `android:textStyle="bold"` on top of
one is a bug: it synthetically smears an already-weighted face. `NativeAdExtensions.createNativeAdBuilderAutoShimmer` is the
one-liner that wires all three. `nativeAdLoader` holds a single ad; lists use `NativeAdPool`, which
remembers position→ad so a recycled row doesn't re-bill an impression.

**Ad expiry** is enforced per format via `AdController.*TtlMs`; `isAdLoaded()` treats an expired ad
as absent and discards it.

**`viewmodel/AdViewModel`** is the recommended app-facing entry point (2.0.0) and covers every format
*except app open*, which stays on `AppOpenAdLoader` because `ProcessLifecycleOwner` drives it. It is
an `AndroidViewModel` that resolves `AdMobManager` itself. Native and banner state is keyed by slot
(`nativeState(key)` / `bannerState(key)`, key defaulting to the container's view id) because a screen
can host several; interstitial and rewarded are single flows because the loaders cache exactly one ad
each. **Terminal outcomes are events, not state** — there is no `Dismissed` state, because a
`StateFlow` replays it to every new collector and a screen navigating on dismissal would navigate
again after each rotation; `AdEvent.Dismissed`/`RewardEarned` go out on a buffered `Channel` exposed
as `events`. The ViewModel owns the native ads it loads (via `NativeAd.loadDetached`, which
deliberately does *not* use the loader's single internal cache) and destroys them in `onCleared`; it
never retains a View. `viewmodel/AdSlotBinding.kt` holds the one-line `bindNativeAd` / `bindBanner`
helpers that do the collecting and lifecycle forwarding.

**`NativeAdLayout`** maps short codes (`"4a"`, `"v2"`, `"large"`) to the bundled layouts so app code
never names an `R.layout` constant. A new `layout_native_ad_*` needs an entry here as well as in
`NativeAdShimmer.pairings` — `NativeAdLayoutTest` asserts every constant is paired.

## Conventions that matter here

- **Never branch on the library's own `BuildConfig.DEBUG`.** A published AAR is built in release, so
  it is permanently false in consumers. Use `AdsEnvironment.isHostDebuggable`, which reads
  `FLAG_DEBUGGABLE` from the host's `ApplicationInfo`.
- **Logging goes through `AdsLog`**, never `android.util.Log`. `d` is gated on `isEnabled`
  (defaults to host-debuggable); `e`/`w` always fire. Tags are `AdsManager_<Area>`.
- **Ad unit ids are validated via `AdUnitIdValidator`.** Malformed ids throw in `strictMode`
  (debug default) and log-and-skip otherwise; a caller seeing `false` must skip the request and
  report failure through its own callback rather than proceeding.
- **Terminal callbacks fire exactly once on every path** — dismissal, show failure, load failure,
  blocked request, Activity death during the show delay. Navigation gated on `onAdDismissed` /
  `onRewardEarned` must never be able to stall.
- **Java interop is part of the contract.** Public statics carry `@JvmStatic`, defaults carry
  `@JvmOverloads`, and `AdMobManager`'s loader fields are `@JvmField`.
- The library manifest deliberately declares no `android:allowBackup` — a library manifest merges
  application attributes into every host app.
- Unit tests run with `isReturnDefaultValues = true` so `AdsLog` → `android.util.Log` doesn't throw
  "not mocked"; there is no Robolectric. Tests cover pure logic only (ad unit validation, shimmer
  registry, consent gate, `AdType` mapping) — nothing verifies rendering.
