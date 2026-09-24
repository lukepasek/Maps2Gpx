# Google Play Publication Guide

Last reviewed: **24 September 2026**

This document records the current Google Play publication requirements and the
project-specific work needed to publish Maps2Gpx. Google Play policies change, so recheck the
linked official sources immediately before each release.

This is an engineering and publication-readiness checklist, not legal advice.

## Current Project State

| Item | Current state | Publication state |
| --- | --- | --- |
| Application ID | `pl.net.xtech.maps2gpx` | Suitable, but permanent once registered in Play Console |
| Version | `versionCode 1`, `versionName 1.0.0` | Suitable for the first upload |
| Minimum Android | API 21 | Acceptable |
| Compile/target SDK | API 34 | Blocking: new submissions require API 36 |
| Android Gradle Plugin | 8.4.2 | Blocking: update for API 36 and reliable 16 KB packaging |
| Gradle wrapper | 9.1.0 | Select a version supported by the upgraded AGP |
| Release shrinking | R8 and resource shrinking enabled | Good; release build still needs testing |
| Release signing | No repository signing configuration | Upload key/signing workflow required |
| Publication artifact | Build helper creates APKs only | Signed AAB workflow required |
| Privacy policy | Not present | Public URL and in-app link required |
| Store artwork | Launcher vectors only | Play icon, feature graphic, and screenshots required |
| Ads/analytics/accounts | None detected | Declare accurately in Play Console |
| Native code | MapLibre `libmaplibre.so` for four ABIs | 64-bit present; verify 16 KB compatibility |

Relevant project files:

- [`app/build.gradle`](app/build.gradle)
- [`build.gradle`](build.gradle)
- [`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties)
- [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml)
- [`app/src/main/java/pl/net/xtech/maps2gpx/TrackMapView.java`](app/src/main/java/pl/net/xtech/maps2gpx/TrackMapView.java)
- [`app/src/main/java/pl/net/xtech/maps2gpx/AboutActivity.java`](app/src/main/java/pl/net/xtech/maps2gpx/AboutActivity.java)
- [`__ai_scripts/build_and_deploy.py`](__ai_scripts/build_and_deploy.py)

## Blocking Engineering Work

### 1. Target Android 16

Starting 31 August 2026, new phone/tablet apps and app updates submitted to Google Play must
target Android 16, API level 36.

Required work:

- Install Android SDK Platform 36 and current 36.x build tools.
- Change `compileSdk` and `targetSdkVersion` from 34 to 36.
- Upgrade AGP from 8.4.2 to a current stable version supporting API 36. Google's Android 16
  setup documentation specifies at least AGP 8.9.0-rc01.
- Select a Gradle wrapper version supported by that AGP release.
- Update AndroidX and MapLibre dependencies where needed.
- Run all unit tests and perform an Android 16 runtime regression pass.

Android 16 behavior requiring explicit review:

- **Edge-to-edge:** apps targeting API 36 cannot opt out on Android 16. The saved-route detail
  screen handles system-bar insets, but all activities must be checked.
- **Large screens:** portrait orientation and resizability restrictions are ignored on displays
  with `smallestWidth >= 600dp`. Make layouts adaptive, or use Android 16's temporary
  `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` opt-out while migrating. The opt-out will not
  apply when targeting API 37.
- **Predictive back:** verify activity navigation under API 36. No custom `onBackPressed()` usage
  was found, but runtime testing is still required.

Official references:

- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Set up the Android 16 SDK](https://developer.android.com/about/versions/16/setup-sdk)
- [Android 16 behavior changes for apps targeting API 36](https://developer.android.com/about/versions/16/behavior-changes-16)

### 2. Support 16 KB Memory Pages

The built APK contains:

```text
lib/arm64-v8a/libmaplibre.so
lib/armeabi-v7a/libmaplibre.so
lib/x86/libmaplibre.so
lib/x86_64/libmaplibre.so
```

Therefore, the app is affected by the native-code 16 KB page-size requirement. Google states
that apps targeting API 35 or higher must support 16 KB page sizes on 64-bit devices. Beginning
1 February 2027, incompatible updates cannot be released.

Required work:

- Upgrade AGP to at least 8.5.1; the API 36 upgrade should move beyond this anyway.
- Confirm the selected MapLibre release ships 16 KB-aligned ARM64 and x86-64 binaries.
- Build the final AAB and verify it requests `PAGE_ALIGNMENT_16K` with `bundletool`.
- Run `zipalign -c -P 16 -v 4` against APKs generated from the AAB.
- Verify ELF `LOAD` segment alignment for every 64-bit `.so`.
- Test on an Android 15/16 16 KB emulator or compatible physical device.

AGP 8.3 through 8.5 can produce artifacts that appear aligned locally but are not correctly
aligned after Play generates APKs from the bundle. Do not release using the current AGP 8.4.2.

Official reference:

- [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)

### 3. Create a Signed Android App Bundle

New Play apps must be distributed as Android App Bundles. AGP already provides the
`bundleRelease` task; no custom `bundle {}` block is inherently required. The repository helper
currently invokes `assembleDebug`/`assembleRelease` and expects an APK, so it does not yet cover
the Play artifact workflow.

Required work:

- Create a permanent **upload key** in a keystore.
- Keep the keystore outside the repository and maintain encrypted offline backups.
- Never commit store passwords, key passwords, or private keys.
- Supply signing values through ignored properties or environment variables.
- Configure release signing or provide an equivalent secure CI/Android Studio signing flow.
- Extend `__ai_scripts/build_and_deploy.py` with a bundle-only publication mode, or create a
  dedicated repository release workflow under the same script policy.
- Produce and validate `app-release.aab`.
- Enroll in Play App Signing. Google holds the distribution signing key; the developer signs
  uploads with the upload key.
- Save the Play App Signing and upload-key certificates for integrations and recovery.

Every subsequent upload must have a `versionCode` greater than every artifact previously
uploaded to that Play application.

Official reference:

- [About Android App Bundles](https://developer.android.com/guide/app-bundle)

## Privacy And Data Safety

### Verified Data Behavior

The app requests foreground coarse and fine location. It does not request background location.

Live device location is used locally to place the current-position marker on the route. No code
was found that directly transmits live `LocationManager` updates to an app-owned server.

Separately, route and GPX coordinates are sent over HTTPS to third-party services for app
functionality:

| Service | Data sent | Purpose |
| --- | --- | --- |
| Nominatim | Place queries and route/stop coordinates | Geocoding and reverse geocoding |
| FOSSGIS OSRM | Ordered route coordinates | Road/path routing |
| Project OSRM demo fallback | Ordered driving coordinates | Fallback routing |
| FOSSGIS Valhalla | Stops or track coordinates and routing preferences | Routing, elevation, and surface analysis |
| Mapbox | Tile/style requests, map viewport information, IP/network metadata, and public token | Online basemap and offline tile cache |
| BRouter | Route inputs through a separately installed local app/service | Optional offline routing |

No Firebase, analytics SDK, advertising SDK, account system, crash uploader, or diagnostic
telemetry was found in the project.

GPX files are stored in a user-selected folder through Android's Storage Access Framework.
Settings are stored in local `SharedPreferences`. Map tiles are cached locally and removed when
the associated GPX is deleted through the app.

### Privacy Policy

A privacy policy is required at a stable, public, non-geoblocked HTTPS URL. Because the app uses
sensitive location data, Google requires the policy link both on the Play listing and inside the
app. Add it to the About page.

The policy should state:

- The developer/publisher identity and contact address.
- What location and route data is accessed.
- Which processing remains on-device.
- Which data is transmitted to each external operator and why.
- The operators' privacy-policy links and known retention behavior.
- That GPX files and preferences are stored locally and how users delete them.
- Whether Android cloud backup includes preferences; configure backup rules consistently.
- That no accounts, ads, analytics, or app-owned telemetry are used.
- How users can ask privacy questions or request deletion where applicable.
- Security practices, including HTTPS transport.
- Effective date and change-notification approach.

Consider showing a concise in-app rationale before the first location permission request. It
should explain that location powers the live route marker and distinguish that local use from
the route-coordinate requests sent to routing/map services.

### Data Safety Form

All apps on closed, open, or production tracks must complete Data safety. Internal-test-only apps
are currently exempt.

Likely declarations, subject to final legal/operator review:

- **Precise location:** collected/transmitted for app functionality when coordinates are sent to
  routing, geocoding, elevation, surface, and map services.
- **Other user-generated content:** assess GPX/route geometry transmitted for rerouting and
  analysis.
- **Encryption in transit:** yes; reviewed endpoints use HTTPS.
- **Ads:** no.
- **Analytics:** no.
- **Accounts:** none.
- **Data deletion:** local routes are user-controlled files and can be deleted by the user.

Do not decide the form's **shared** versus **collected** or **ephemeral processing** answers only
from the source code. Confirm each operator's retention policy and whether it qualifies as a
service provider under Google's definitions. Public community endpoints may not have a
developer-specific data-processing agreement.

Official references:

- [Prepare an app for review and privacy-policy requirements](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Provide information for Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)

### Backup Rules

The manifest currently uses `android:allowBackup="true"` without explicit data extraction rules.
This is not necessarily a Play blocker, but it should be an intentional release decision:

- Add Android 12+ `dataExtractionRules` and legacy `fullBackupContent` rules, or
- Set `allowBackup="false"` if restoring preferences is not useful.

Document the selected behavior in the privacy policy.

## External Service Readiness

### Nominatim And FOSSGIS

The public Nominatim service imposes an absolute maximum of one request per second across the
whole website/application, not independently for every installation. It requires:

- A valid identifying User-Agent or Referer.
- Clear OpenStreetMap attribution.
- Moderate end-user-triggered use only.
- Caching where appropriate.
- The ability to switch providers at the operator's request without publishing an app update.

The current client-side throttle cannot enforce an application-wide limit across many installed
phones. Before broad distribution, use a production geocoding provider or controlled proxy with
central rate limiting, caching, monitoring, and remotely configurable endpoints.

FOSSGIS OSRM similarly documents a one-request-per-second limit, no scraping/heavy use, required
attribution, and server logging of route requests. Treat public OSRM and Valhalla endpoints as
community/demo infrastructure without a production SLA. Confirm the current Valhalla operator
policy directly before launch.

References:

- [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/)
- [FOSSGIS OSRM server usage and privacy](https://map.project-osrm.org/about.html)

### Mapbox

The app compiles the Mapbox public token into `BuildConfig`; this is expected for a client-side
`pk` token even though its source value is kept in ignored `local.properties`. Public tokens are
extractable from a shipped app.

Before release:

- Create a separate production public token rather than using the default development token.
- Grant only the public read scopes needed for styles, fonts, and tiles.
- Never use a secret `sk` token in the app.
- Monitor token statistics, usage, pricing, and billing alerts.
- Confirm the intended Mapbox plan permits the production use case.
- Keep a token-rotation procedure.

Mapbox Outdoors rendered through MapLibre still requires attribution on the map itself. Verify
that the visible map includes:

- The Mapbox logo.
- Linked `© Mapbox` attribution.
- Linked `© OpenStreetMap` attribution.
- An `Improve this map` link using the current map position where required.

The About-page entry alone may not meet map-level attribution requirements. Because MapLibre is
not the Mapbox SDK, do not assume it inserts all required Mapbox controls automatically.

References:

- [Mapbox access tokens](https://docs.mapbox.com/help/dive-deeper/access-tokens/)
- [Using Mapbox securely](https://docs.mapbox.com/help/troubleshooting/how-to-use-mapbox-securely/)
- [Mapbox attribution](https://docs.mapbox.com/help/getting-started/attribution/)
- [Mapbox Terms of Service](https://www.mapbox.com/legal/tos/)

## Open-Source Notices

The About page identifies the principal libraries and links to their licenses. Before release,
also include the copyright notices and license text required for binary redistribution. A link to
a generic license page may not satisfy every BSD, MIT, or Apache notice obligation.

At minimum, audit notices for:

- AndroidX and transitive Android support libraries: Apache-2.0.
- MapLibre Native and modules: BSD-2-Clause.
- Kotlin and coroutines: Apache-2.0.
- OkHttp and Okio: Apache-2.0.
- Timber: Apache-2.0.
- OSRM: BSD-2-Clause.
- Valhalla and BRouter: MIT.
- Nominatim software: GPL-3.0; service/data terms are separate.
- OpenStreetMap data: ODbL-1.0 and attribution guidelines.
- Mapbox services: proprietary terms, not an open-source license.

JUnit is test-only and is not shipped in the runtime artifact.

## Play Console Account

Create or use a Play Console developer account and complete:

- Developer Distribution Agreement acceptance.
- The one-time registration payment shown for the account's country.
- Personal or organization account selection.
- Legal identity, address, contact email, and contact phone verification.
- Public developer contact details.
- Android-device verification through the Play Console mobile app for a new personal account.
- For an organization account: organization website, phone, and matching D-U-N-S profile.

Google displays different legal/contact details depending on account type and monetization. Review
what will be public before completing verification.

References:

- [Get started with Play Console](https://support.google.com/googleplay/android-developer/answer/6112435)
- [Required developer-account information](https://support.google.com/googleplay/android-developer/answer/13628312)
- [Device verification](https://support.google.com/googleplay/android-developer/answer/14316361)

## Create The Play Application

Before reserving the application in Play Console, confirm:

- Package name: `pl.net.xtech.maps2gpx`. Package names are unique, permanent, and cannot be
  deleted and reused.
- Default language.
- Public app name.
- App rather than game.
- Free or paid distribution. A published paid app can become free, but a free app generally
  cannot later become paid under the same package name.
- Support email and optional website/phone.
- Play App Signing acceptance.
- Intended countries/regions.

## Store Listing

Prepare the following assets outside the runtime Android resource set where appropriate:

- App name: maximum 30 characters.
- Short description: maximum 80 characters.
- Full description: maximum 4,000 characters.
- High-resolution Play icon: 512 x 512 PNG.
- Feature graphic: 1,024 x 500 PNG or JPEG.
- At least two representative phone screenshots.
- Additional tablet screenshots if tablets remain supported.
- Category and tags; `Maps & Navigation` is the likely category.
- Support email and privacy-policy URL.

Screenshots and descriptions must represent the current app accurately. Avoid implying that
Maps2Gpx is affiliated with Google, Mapbox, OpenStreetMap, or a routing operator. Explain that
routes are reconstructed by the selected routing engine and can differ from Google Maps.

Official reference:

- [Play preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151)

## App Content Declarations

Complete every applicable Play Console item, including:

- Privacy policy.
- Data safety.
- Ads: declare **No** while the code remains ad-free.
- App access: no login or restricted content; provide special instructions only if review cannot
  reach a feature normally.
- Target audience and content.
- IARC content-rating questionnaire.
- News/magazine declaration: no.
- Government-app declaration: no, unless ownership changes.
- Financial-features declaration: no.
- Health-app declaration: no.
- Permission declarations shown after AAB analysis.

Foreground coarse/fine location does not normally require the special background-location
approval flow. A high-risk permission form is not expected from the currently declared
permissions, but Play Console's result after AAB upload is authoritative.

## Testing And Production Access

Recommended release progression:

1. Unit tests and release compilation.
2. Test a signed bundle locally with `bundletool`-generated APKs.
3. Internal testing track.
4. Play pre-launch report on available Android versions and form factors.
5. Closed testing.
6. Production rollout, preferably staged.

For personal developer accounts created after 13 November 2023, production access requires:

- A closed test with at least 12 testers.
- All 12 remaining opted in continuously for at least 14 days.
- Meaningful tester engagement and a record of feedback.
- An application for production access describing testing, feedback, fixes, and readiness.

Google states that production-access review usually takes seven days or less but can take longer.

Official reference:

- [Testing requirements for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465)

## Release Verification Checklist

Before uploading a release candidate:

- [ ] `compileSdk` and `targetSdk` are 36 or higher.
- [ ] AGP and Gradle are a supported pair.
- [ ] Unit tests pass.
- [ ] Release R8/minification build succeeds without missing-class warnings.
- [ ] Signed AAB is generated with the correct upload key.
- [ ] AAB and generated APKs pass 16 KB alignment checks.
- [ ] ARM64 and x86-64 MapLibre binaries are 16 KB compatible.
- [ ] No secret token, keystore, or signing password is committed.
- [ ] Production Mapbox token has minimum scopes and billing alerts.
- [ ] Privacy policy is live in a browser and linked from the app.
- [ ] Data safety answers match actual service behavior.
- [ ] Required Mapbox and OpenStreetMap attribution is visible on the map.
- [ ] Open-source notices contain required copyright/license text.
- [ ] Service endpoints and rate limits are production-ready.
- [ ] Edge-to-edge layouts work on Android 16.
- [ ] Phone portrait layout works.
- [ ] Tablet/foldable behavior is adaptive or temporarily opted out intentionally.
- [ ] Location denial and approximate-only permission paths work.
- [ ] Offline/no-network and service-failure paths remain usable.
- [ ] Share, open, reroute, reverse, delete, and SAF folder flows work in the release build.
- [ ] Store screenshots match the release candidate.
- [ ] Play pre-launch report has no unresolved blockers.
- [ ] `versionCode` is unique and greater than all prior uploads.

## Recommended Implementation Order

1. Upgrade to API 36, AGP, Gradle, AndroidX, and a verified MapLibre version.
2. Fix edge-to-edge and large-screen behavior introduced by API 36.
3. Add signed AAB support and 16 KB validation to the release workflow.
4. Decide on production routing/geocoding infrastructure and configurable endpoints.
5. Finalize Mapbox production terms, token, billing, and map-level attribution.
6. Write and host the privacy policy; add its in-app link and permission rationale.
7. Complete full open-source notices and backup rules.
8. Produce Play listing artwork, screenshots, and copy.
9. Create the Play application and complete all App content declarations.
10. Run internal/closed tests, resolve pre-launch findings, and apply for production access.

## Policy Recheck Before Upload

Immediately before uploading, revisit at least:

- [Google Play Policy Center](https://play.google.com/about/developer-content-policy/)
- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Data safety requirements](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Prepare an app for review](https://support.google.com/googleplay/android-developer/answer/9859455)
- [16 KB page-size support](https://developer.android.com/guide/practices/page-sizes)
- [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/)
- [Mapbox legal terms](https://www.mapbox.com/legal/tos/)