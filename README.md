# Maps2Gpx

Android app that turns a shared Google Maps route link into a GPX file — and re-routes an
existing GPX file with your own routing engine and profile.

## Usage

Three entry points:

1. **Google Maps → Share → Maps2Gpx** (the main one). The app receives the link,
   converts immediately, and shows a running log.
2. **Launch the app** and either paste a link or pick a GPX to re-route — both live on the
   home screen.
3. **Share or open a `.gpx` file with Maps2Gpx** from any other app. The existing track is
   routed again with your own engine and profile — see
   [Re-routing an existing GPX](#re-routing-an-existing-gpx).

## Screens

| Screen | What it is |
| --- | --- |
| **Home** (launcher) | The route library: paste a link, pick a GPX to re-route, and every GPX already saved in your output folder |
| **Splash / summary** | Only while converting: engine prompts, progress, then the route summary |
| **Settings** | Output folder, routing engine, what happens after conversion, and the full conversion log |

Launching the app lands on the **library**, not on the settings — the settings are set once, the
library is what there is to come back to. Both ways of starting a conversion sit at the top of
it, because they are the same act with different input: one takes a link, the other a file.

`SavedRoutesActivity` is the launcher activity and holds **no conversion logic at all**. Pasting
a link or picking a file hands off to `MainActivity` as an `ACTION_SEND` or `ACTION_VIEW` intent
— the same door Google Maps comes through — so the engine prompts, the splash, the summary and
the log behave identically however the route arrived, and none of it is duplicated. `MainActivity`
keeps every intent filter other apps see, which is also why it keeps the app name as its label
while its title bar reads "Settings".

Note for anyone upgrading rather than installing fresh: the `MAIN`/`LAUNCHER` filter moved from
`MainActivity` to `SavedRoutesActivity`, so an existing home-screen shortcut may need to be
re-added.

Every conversion **autosaves** into your chosen folder and then performs your chosen
post-conversion action — no further taps.

### Configuration screen

Reached with **Settings** at the foot of the home screen:

- **Output folder** — a folder in shared storage, chosen through the system picker. The
  grant is a persisted SAF tree permission, so no storage permission is required and the
  choice survives reboots.
- **Routing engine** — OSRM or Valhalla, see [Routing engines](#routing-engines).
- **After conversion** — one of:

| Setting | What happens |
| --- | --- |
| Share to another app (Send) | `ACTION_SEND` chooser — messaging, mail, cloud storage |
| Ask which app to open it in | `ACTION_VIEW` chooser — GPX importers (default) |
| Open directly in one app | `ACTION_VIEW` straight into one remembered app, no chooser |

For **Open directly**, the app lists everything installed that can open a `.gpx` — with
icons and app names, sorted by name — and you pick one. Picking from that dialog only
records the choice; unlike the system chooser, it does not also launch the app with a
placeholder file. Listing those apps (and resolving their names and icons rather than
showing bare package names) needs the `<queries>` declaration in the manifest, otherwise
Android 11+ package visibility hides them. If the remembered app is later uninstalled, the
launch falls back to the chooser and the setting is cleared.

The same screen carries the conversion log and **Share** / **Open** buttons that repeat either
action on whatever it last converted.

### The route library

The home screen lists every GPX in the chosen folder, newest first, with its date and size. Tap
one to open it in a GPX app, or **Share** to send it on.

**The folder is the record.** There is no database of past conversions and no need for one:
the files are already there, already named after their endpoints, and an index maintained on
the side would drift out of step with the folder the first time anything was moved or deleted
elsewhere. Listing goes through the same persisted tree grant used for saving, so this screen
can only ever see the one folder the user pointed at.

Three details:

- **Files are matched by name, not by MIME type.** Android has no `.gpx` entry in its MIME
  map, so providers report these as `application/octet-stream` about as often as
  `application/gpx+xml`; filtering on the reported type would either drop every file or admit
  every file.
- **Names are made readable** — underscores back to spaces, and this app's own trailing
  `_yyyyMMdd-HHmm` dropped, because the row already shows the file's real date. A file this
  app did not write keeps its name and only loses the extension.
- **Tapping honours "open directly in one app"** but never *sets* it. Unlike the
  post-conversion hand-off, picking an app here is not reported back to
  `ChosenAppReceiver` — browsing old files is not the place to silently reconfigure which app
  everything opens in. If a remembered app has gone, this screen falls back to the chooser
  rather than clearing the setting.

Both actions go through the same FileProvider hand-off as a fresh conversion (`GpxFiles`), so
the SAF-document-URI problem described below is not re-introduced by a second code path.

### File name

```
<start>_to_<end>_<yyyyMMdd-HHmm>.gpx
Ludwika_Kondratowicza_to_Nieporet_20260817-1756.gpx
```

Endpoint labels come from the link where it has them. Google shares "Your location" as
bare coordinates, so a stop with a position but no name is **reverse geocoded** to the
nearest named thing — most specific first: street (`road`, `pedestrian`, `footway`,
`cycleway`, `path`), then `neighbourhood`/`suburb`/`hamlet`/`quarter`, then
`village`/`town`/`city`. If Nominatim offers nothing, the coordinates are kept (4
decimals, ~11 m). Stops Google *did* name keep Google's label — no lookup is wasted.

Two file-name rules, both learned from real breakage:

- **Only one dot, the extension.** Apps that infer a type from the last dot mis-handle
  `52.2918-21.0507_to_X.gpx`, and OneLap's `/.*\.gpx` path glob matches most simply.
- **Diacritics are transliterated, not stripped.** Filtering to ASCII turned `Nieporęt`
  into `Nieport` — a lost letter mid-word. Names are now decomposed with
  `Normalizer.Form.NFD` and their combining marks removed, plus explicit mappings for
  letters Unicode will not decompose (`ł`, `ø`, `æ`, `đ`, `ß`). So `Nieporęt` → `Nieporet`,
  `Łódź` → `Lodz`, `København` → `Kobenhavn`, `Ærøskøbing` → `AEroskobing`.

### What gets handed to other apps

Two details here were learned the hard way, and both matter.

**VIEW, not just SEND.** GPX importers typically register for `ACTION_VIEW` on a `.gpx`
URI and have *no* `ACTION_SEND` filter at all, so they never appear in a share sheet. For
example OneLap Fitness declares:

```
com.onelap.fitness/com.kai.app_route.ui.ImportGpxActivity
  Action: android.intent.action.VIEW
  Scheme: "file", "content"
  */* with Path GLOB: /.*\.gpx
```

No MIME type on a SEND intent can reach that. The `com.appurls` proxy app exists purely to
convert a SEND into a VIEW.

Both rules live in `GpxFiles`, which is the single place a `.gpx` crosses between this app and
another - the route summary and the saved-routes screen both go through it. They were separate
copies until the second screen existed, and two copies of a hard-won rule means fixing the next
failure in only one of them.

Maps2Gpx now answers both VIEW and SEND on a `.gpx` itself, for re-routing, so it **excludes
itself** from its own choosers (`EXTRA_EXCLUDE_COMPONENTS`, API 24+). Handing a file back to the
app it came from is not a choice worth offering, and re-routing already has two entry points of
its own.

**A FileProvider URI, not the SAF document URI of the saved file.** A SAF document URI ends
in the document id — `…/document/primary%3AFolder%2Fname.gpx` — which breaks receivers two
ways: apps that build a temp path from the last path segment try to write into a
non-existent `primary:Folder` directory and fail with `ENOENT`, and the path does not look
like a plain `*.gpx` to a path-glob intent filter. A FileProvider URI ends in a real
filename (this is what WhatsApp hands on, which is why a WhatsApp round-trip used to "fix"
the file). The permanent copy still lives in your chosen folder.

The MIME type is `application/gpx+xml`, declared both on the intent and on its `ClipData` —
Android's MIME map has no `.gpx` entry, so a receiver that asks the provider for the
stream's type would otherwise get `application/octet-stream`.

## How it works

A Google Maps share link does **not** contain the route geometry — only the stops.
So the track is reconstructed:

```
shared text
  → first URL extracted
  → short link expanded          (maps.app.goo.gl → www.google.com/maps/dir/…)
  → stops parsed                 (data= blob, path segments, or api=1 params)
  → named stops geocoded         (Nominatim, ≤1 req/s per their policy)
  → unnamed stops reverse-geocoded  (street/place name instead of bare numbers)
  → geometry from OSRM           (FOSSGIS, per-profile instance, polyline6)
  → GPX 1.1                      (<wpt> per stop + <trk> for the track)
```

Because the geometry comes from OSRM rather than Google, the track follows real
roads but may differ slightly from the exact path Google displayed.

### Link forms understood

| Form | Where the stops come from |
| --- | --- |
| `maps.app.goo.gl/…`, `goo.gl/maps/…` | expanded first, then as below |
| `/maps/dir/A/B/C/@…/data=…` | exact coordinates from the `data=` blob, labels from the path |
| `/maps/dir/?api=1&origin=&destination=&waypoints=` | query parameters, geocoded by name |
| `/maps/place/Name/@lat,lon/…!3d<lat>!4d<lon>` | single waypoint, no track |
| `?q=lat,lon`, `/maps/@lat,lon,z` | single waypoint |

Inside `data=`, Google uses **two** coordinate encodings and mixes them in one URL:

| Encoding | Meaning | Order |
| --- | --- | --- |
| `!2m2!1d<lon>!2d<lat>` | stop given as a raw dropped point | lon, lat |
| `!8m2!3d<lat>!4d<lon>` | stop resolved to a place (has a feature id) | lat, lon |

Both are scanned in a single left-to-right pass so stops keep their route order. Google
only writes a `data=` entry for stops it had to resolve — stops typed as raw coordinates
already sit in the URL path — so when the coordinate count matches the number of stops
still missing coordinates, that is the correct pairing. Getting this wrong is what made
`KMD Poland` geocode to the Kraków office instead of using the Warsaw coordinates that
were sitting in the link.

## Re-routing an existing GPX

The other direction: hand the app a GPX someone else produced and get the same journey
routed with *your* engine, profile and surface preference.

```
GPX file
  → geometry read              (<trk>, all segments; else <rte>)
  → travel mode worked out     (<type> → implied speed → assumed)
  → reduced to via points      (stated <rtept>/<wpt>, else Douglas-Peucker)
  → routed again               (same engine chain as a link)
  → GPX 1.1 + both tracks on the summary
```

Feeding a router every recorded point would just hand back the original line, so the
track is reduced to the points that actually define its shape. Which points those are
depends on what the file says:

| The file has | Via points come from |
| --- | --- |
| `<rte>` with ≥2 `<rtept>` | those points — the file *is* a route |
| ≥3 `<wpt>` lying within 250 m of the track | those, put into track order |
| anything else | Douglas-Peucker over the track itself |

**Two `<wpt>`s are not a via chain.** A track's only waypoints are very often just its
start and finish — that is exactly what this app writes — and routing between those two
alone throws away the shape the other 749 points spent the file describing. Three or more
is someone deliberately marking a way through.

**GPX does not promise `<wpt>` order matches route order**, and a file's waypoints may be
unrelated points of interest, so they are sorted by where the track passes them and any
that sit more than 250 m off it are dropped.

**Stated points and recorded points are thinned differently**, which is not a detail:

- A *recording* is reduced at a 250 m tolerance — enough to drop GPS jitter and the dozens
  of points a router emits along one straight road, while keeping every real turn.
- *Stated* points are already sparse, so there is no level of detail to impose: the only
  reason to drop any is the 25-point cap. Running them through the 250 m tolerance instead
  threw away three quarters of a real 46-point route (`fells_loop.gpx` came back as 9
  points), which is not thinning but rewriting.

Either way the cap is met by coarsening the whole shape at one tolerance, never by
truncating a finer result — and the tolerance is bisected rather than doubled. Doubling
overshoots hard: a measured zigzag needing 26 points at one tolerance collapsed to **2** at
twice that, losing every turn while still honouring the cap. The cap is also a limit, not a
target, so a shape held by six points comes back as six.

### Travel mode, which GPX does not record

Getting this wrong changes the route rather than just labelling it — a hiking loop routed
as a bike ride came back **158 % longer**. Three sources, in order:

1. `<trk><type>` and this app's own `m2g:profile` extension, matched on the words the
   common exporters use (`bike`, `bicycle`, `cycling`, `Ride`, `gravel`, `mtb`, `trekking`
   → cycling; `walk`, `hik`, `foot`, `run` → walking; `car`, `driv`, `auto`, `motor` →
   driving).
2. The **speed the file's own timestamps imply** — under 7 km/h is a walk, under 30 a ride,
   above that a drive. Elapsed time includes stops, so the thresholds are deliberately low.
3. Failing both, cycling — and the log and the summary both say it was assumed.

Because a guess can be wrong, **Reroute asks the travel mode first** for a GPX source, then
the engine, then that engine's own option. A Maps link states its own mode, so there
Reroute still starts at the engine.

### Both tracks on the summary

The summary draws the original in grey behind the new route, in one shared projection and
one shared bounding box — scaling them separately would make two different routes look
identical.

**The grey line is deliberately wider than the blue one, not thinner.** A re-route usually
follows most of the original, and a thin grey line under a thicker blue one is simply
invisible for the whole shared stretch: on a real 18 km re-route it could not be seen at
all. As a casing it reads as a halo where the two agree and as a separate line where they
part, which is the comparison the page exists to show.

The totals compare **distance only**. Ascent looks like the obvious second number, but the
two figures come from different elevation sources — the file's own heights against this
app's DEM lookup — and on a flat 18 km re-route that difference alone accounted for 57 m
against 167 m. Comparing them would say more about the DEM than about the route.

### How the file arrives

Declared the same way GPX importers declare themselves, and for the same reason: apps hand
a file on with `ACTION_VIEW` far more often than with `ACTION_SEND`, and Android's MIME map
has no `.gpx` entry, so a `*/*` filter with a `.*\.gpx` path pattern is what actually
matches most senders. A `SEND` filter for `application/gpx+xml` is there too; a share that
carries both a stream and `text/plain` is treated as a link.

Two consequences worth knowing:

- **The bytes are cached, not the URI.** A re-route happens minutes later, by which time the
  URI grant that came with the intent may be gone. Bytes rather than a String, because the
  XML declaration may name an encoding other than UTF-8 and only the parser can honour it.
- **Maps2Gpx now answers `VIEW` on a `.gpx`, including its own output.** So it excludes
  itself from the "Open in" chooser (`EXTRA_EXCLUDE_COMPONENTS`, API 24+) — offering to
  reopen the file in the app that just produced it is not a choice worth showing.

### Routing engines

Selectable on the config screen:

| Engine | Network | Trade-off |
| --- | --- | --- |
| **OSRM** (default) | online | Fast. Surface preference is compiled into the Lua profile at graph-build time and cannot be changed over HTTP. |
| **Valhalla** | online | Accepts a surface preference per request, so unpaved roads can be allowed or avoided. |
| **BRouter** | **offline** | Routes through the separate BRouter app. No network at all, once its rd5 segments are downloaded. |

If the chosen engine fails, the others are tried in turn before falling back to straight
lines. BRouter is only included as an automatic fallback when its app is actually installed.

### When routing degrades, the summary says so

Falling back keeps a conversion alive, but it also means the user asked for one thing and got
another — an *offline* route that quietly came from an online server, or a track that stopped
following roads at all. That used to be reported only in the progress log, which is the wrong
place for it: the log scrolls, and the splash shows just its last line, so the one moment the
warning mattered was the moment it was invisible.

So these now survive to the summary, in amber above the track outline, and are repeated at
the end of the log marked `WARNING:`:

| What happened | What is said |
| --- | --- |
| BRouter had no rd5 tile for the area | names the tiles, e.g. `Download W75_N40.rd5`, and offers a button that opens BRouter |
| BRouter selected but not installed | said before routing, since the cause is a missing app, not a missing road |
| BRouter failed for any other reason | its own message, because offline was chosen deliberately |
| a fallback engine produced the track | which engine actually ran, since the GPX carries the *chosen* engine's label |
| every router failed | that the track is straight lines and does not follow roads |

Only the engine that was actually **requested** produces a notice. A fallback that also failed
is already covered by whichever one worked — and telling someone who chose OSRM to download
rd5 tiles would be pure noise.

The missing-tile case is a **typed exception** carrying the tile names
(`BRouterRouter.MissingSegmentsException`) rather than an `IOException` with a well-worded
message. It is the one routing failure the user can actually fix, and fixing it means being
told which files to fetch — so the names have to survive the throw instead of being buried in
prose the caller would have to parse back out. Maps2Gpx cannot download them itself: they are
5°×5° tiles managed inside BRouter, so the button opens that app.

**A warning also cancels "close after opening".** Closing behind the target app is right when
the conversion went as asked; when it did not, closing would destroy the only place that says
so, so the summary is left standing and the log notes why.

### BRouter (offline)

BRouter is a **runtime** dependency, not a Gradle one. Its service is declared
`android:exported="true"` with no intent-filter, so it is bound by explicit component:

```java
Intent i = new Intent();
i.setClassName("btools.routingapp", "btools.routingapp.BRouterService");
bindService(i, conn, BIND_AUTO_CREATE);
```

The only thing compiled in is a copy of its one-method AIDL at
`app/src/main/aidl/btools/routingapp/IBRouterService.aidl` — the package path must match
BRouter's exactly, since AIDL identity is by fully qualified name. AGP 8 disables AIDL by
default, hence `buildFeatures { aidl true }`.

Three things that are easy to get wrong:

- **Package visibility.** Without `<package android:name="btools.routingapp" />` in
  `<queries>`, the bind fails silently on Android 11+.
- **Failures arrive as the success value.** `getTrackFromParams` returns the error text in
  the same String as a track, so success is detected by looking for `<trkpt`.
- **`pathToFileResult` is deliberately unset**, which makes BRouter return the GPX itself
  rather than writing a file we would then have to locate and read.

### BRouter profiles

`BRouterService` picks a profile by three routes, in priority order: `remoteProfile` (the
profile body inline) beats `profile` (a named `.brf`) beats `v` + `fast`.

**We name a profile, because `v`/`fast` does not work.** Those two are resolved indirectly —
`BRouterService` builds the key `<mode>_<fast|short>` and looks it up in BRouter's own config
— and measured on the test device it changed nothing at all: `fast=1` and `fast=0` both
returned an *identical* 24.2 km / 876-point route. Passing `profile` instead gives real
control, verified on the same stops:

| Profile shown | `.brf` sent | Result |
| --- | --- | --- |
| Trekking (default) | `trekking` | mixed surface |
| Fast bike | `fastbike` | 17.6 km, 664 pts |
| Gravel | `gravel` | 18.6 km, 749 pts |
| MTB | `mtb` | 18.4 km, 588 pts |
| Shortest | `shortest` | — |
| Hiking (mountain) | `hiking-mountain` | — |
| Car | `car-vario` | — |

Fast bike's 17.6 km matches what Valhalla and OSRM's bike profile give for the same link,
which is a useful cross-check that the plumbing is right.

Every one of those files ships with BRouter, so none depend on the user adding a custom
profile. The name is sent without the suffix — BRouter resolves it to
`<baseDir>/brouter/profiles2/<profile>.brf` itself.

The profile is chosen on the splash screen before each conversion, in place of the surface
prompt. If the link's travel mode and the chosen profile disagree (a driving link with the
Gravel profile, say), the explicit choice wins and the log says so rather than quietly
substituting something else.

**If BRouter is not installed** and you select it, the app offers to open its Play Store
listing (falling back to the browser if there is no Play app). Declining reverts the setting
to OSRM, so the app stays usable. Selecting it while uninstalled never hard-fails a
conversion — routing just falls back to an online engine and says so in the log.

When Valhalla is selected the **splash screen asks for the surface before converting**, and
when BRouter is selected it asks for the **profile** instead —
it has to be settled up front, because it changes the route rather than just labelling it.
The choice is remembered and pre-selected, so the common case is one tap. Presets map onto
Valhalla's bicycle costing options:

| Preset | `bicycle_type` | `avoid_bad_surfaces` |
| --- | --- | --- |
| Paved only | `Road` | 1.0 |
| Mostly paved | `Hybrid` | 0.7 |
| Gravel and unpaved (default) | `Cross` | 0.0 |
| Tracks and trails | `Mountain` | 0.0 |

Surface applies to cycling only; Valhalla's `auto` and `pedestrian` costings ignore it, and
the log says so rather than implying otherwise. With OSRM selected there is no prompt, so
the splash is held for up to 3 s — `max(0, 3s − conversion time)` — to keep the disclaimer
readable without adding latency when routing is already slow. If the chosen engine fails, the other one is
tried before falling back to straight lines.

Valhalla needs a **POST** body — the `?json=` GET form returns
`"Failed to parse json request"` from the FOSSGIS instance.

### Travel mode and the routing profile

Travel mode comes from `!3e0/1/2` or `travelmode=`, and selects the routing profile.

**Use a per-profile endpoint.** `router.project-osrm.org` hosts *only* the car profile and
silently ignores the profile in the URL path, so a cycling link comes back as a driving
route with no error. FOSSGIS runs one instance per profile instead:

| Mode | `!3e` | Endpoint |
| --- | --- | --- |
| driving | `0` | `routing.openstreetmap.de/routed-car` |
| cycling | `1` | `routing.openstreetmap.de/routed-bike` |
| walking | `2` | `routing.openstreetmap.de/routed-foot` |

Measured for one real Warsaw → Nieporęt cycling link (`52.2918736,21.0507618` →
`52.4324301,21.0308267`):

| Endpoint | Result |
| --- | --- |
| `project-osrm` with `/bike/` | 23.8 km / 24 min |
| FOSSGIS `routed-car` | 23.8 km / 24 min — *identical, i.e. the profile was ignored* |
| FOSSGIS `routed-bike` | 17.6 km / 68 min |

24 minutes for 23.8 km is ~59 km/h, which is the tell: it was a car route wearing a `bike`
label. The car-only server is kept only as a fallback, and using it logs a loud warning
because a driving track is not what a cycling link asked for. The routed profile is
recorded in the log, the GPX `<type>` and the `<desc>`.

Failures degrade instead of aborting: if routing fails, straight lines between the
stops are written; if a stop cannot be geocoded, it is skipped.

## Build

Settings mirror the sibling `PushWebView` project: Groovy DSL, AGP 8.4.2,
Gradle 9.1, `compileSdk 34`, `minSdk 21`, Java 21, plain Java + XML views
(no Kotlin, no Compose, no AppCompat).

```sh
./gradlew.bat :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` points at `C:/Users/Z6LGI/AppData/Local/Android/Sdk`.

Note: `:app:assembleRelease` has `minifyEnabled true` but **no signing config**, so
it produces an unsigned APK. Only the debug build is installable as-is.

## Verified

- Polyline decoder checked against live OSRM output; decoded endpoints match the
  requested stops to 4 decimal places over a 1369-point route.
- End-to-end on an emulator with a 3-stop `/maps/dir/` link: stops and travel mode
  parsed from the `data=` blob, OSRM returned 120.9 km / 1675 points.
- Generated GPX validates against the **official GPX 1.1 XSD** (element order in
  GPX 1.1 is a strict sequence, and `metadata → wpt → trk` is respected).
- End-to-end on a physical Galaxy S23 (Android 16) with a real `maps.app.goo.gl`
  link: short link expanded, both `data=` encodings parsed, `!3e1` read as bike, and
  the GPX autosaved to the chosen folder. The saved track ends at
  `52.256258, 20.994774` — exactly the `!3d`/`!4d` pair in the link (KMD Poland,
  Warsaw), confirming the Kraków mis-geocode is gone.

- Share → `com.appurls` proxy → `com.onelap.fitness`: works (this was the `ENOENT`
  failure, fixed by the FileProvider URI).
- Direct open: app picker lists GPX-capable apps including OnelapFit, the choice persists
  to `direct_component` in preferences, and a subsequent conversion logs
  `Opened in com.onelap.fitness` and lands in OneLap with **no chooser** and no errors.

- The imported track **renders correctly** in OneLap: a screenshot of its import screen
  showed the road-following route from the east-Warsaw start marker across the Vistula to
  the KMD Poland finish marker.
- Cycling profile fix, same link before and after: 23.8 km / 24 min / 507 points (car)
  became 17.6 km / 68 min / 861 points (bike).
- Reverse geocoding on-device: `52.291874,21.050762` became `Ludwika Kondratowicza`,
  turning `52_2919-21_0508_to_Nieport…` into `Ludwika_Kondratowicza_to_Nieporet…`.
- Transliteration checked on the JVM over 14 Polish, Danish and German place names plus
  empty, CJK and coordinate inputs.
- Valhalla surface options change the geometry where unpaved alternatives exist (Kampinos
  forest: `Cross` at `avoid_bad_surfaces` 0.0 → 13.3 km, at 1.0 → 13.6 km with a different
  shape; `Mountain` → 14.6 km). On the paved Nieporęt route the parameter correctly makes
  no difference, while `bicycle_type` still does.
- Engine selection, the splash surface prompt and Valhalla routing verified on-device: the
  prompt appeared, the choice was applied, and the result reported an implied 20 km/h —
  a cycling speed, not the 59 km/h that exposed the old car-route bug.
- Dark theme: with the device in dark mode the app renders dark, `DeviceDefault` night
  variant applied.

- BRouter end-to-end on-device: bind, `getTrackFromParams`, GPX parsing and its own `<ele>`
  values all work (20.5 km / 727 points on the first run, opened in OneLap).
- BRouter profile selection changes the route, measured across three profiles on identical
  stops — see the table above. This also exposed that `v`/`fast` did nothing.
- Sunrise/sunset checked against sunrise-sunset.org: 03:22:41/17:59:56 UTC computed against
  03:20:22/17:59:34 reference, i.e. within ~2 minutes. Equator-at-equinox and polar-day
  (no sunrise) also behave.

### GPX re-routing

Verified end-to-end on the physical S23 through real `content://` URIs, on real files that
were already on the device rather than fixtures written to match the parser:

- **`fells_loop.gpx`** — 46 `<rtept>`, no `<type>`, no trackpoint timestamps. Read as a
  route, thinned 46 → 25 by the cap, mode correctly reported as *assumed*. As cycling:
  11.2 km → 28.8 km (+158 %). Rerouted through the new travel-mode prompt as walking:
  16.9 km (+51 %), OSRM `foot` profile, implied 6 km/h. That +158 % → +51 % is the whole
  case for asking about the mode.
- **Round-trip of this app's own output** — 749 `<trkpt>`, `<type>BRouter gravel</type>`,
  two waypoints that are just the endpoints. Mode read from the type as cycling, the two
  waypoints correctly *not* treated as a via chain, Douglas-Peucker gave 6 points, and OSRM
  bike returned 18.0 km against the file's 18.6 km — **−3 %**, i.e. the same journey on
  slightly different roads. The 18.6 km also cross-checks the BRouter gravel figure measured
  earlier for the same pair.
- **`Lasy Legionowskie Nieporęt.gpx`** — a 712 KB, 1513-point forest loop with no waypoints,
  type or timestamps. Reduced to 25 significant points, 54.9 km → 61.4 km (+12 %) on OSRM
  bike, and 60.7 km (+11 %) on **BRouter offline** with the Gravel profile, which brought its
  own elevation (ascent 205 m, against the 568 m the DEM lookup produced for the OSRM version
  of the same loop — an independent reason not to compare ascents across sources).
- **The engine fallback chain works from the GPX path too**: `fells_loop.gpx` is in
  Massachusetts, BRouter correctly named the segment it wanted (`W75_N40.rd5`), reported it
  missing, and the conversion continued on OSRM rather than failing.

### Missing-segment warning

- The same Massachusetts route with BRouter selected produced both notices on the summary —
  `Download W75_N40.rd5 in the BRouter app to route it offline` and `Routed with OSRM (bike
  profile) instead of BRouter (Gravel, offline)` — in amber, with the warning triangle tinted
  to match, and repeated in the log as `WARNING:` lines.
- **Open BRouter to download the maps** was tapped and checked against
  `dumpsys activity activities`: `topResumedActivity` went from
  `pl.net.xtech.maps2gpx/.MainActivity` to `btools.routingapp/.BRouterActivity`.
- The warning colour is a per-theme resource, not one hex value. The first attempt used the
  default text colour and was indistinguishable from the summary above it — a warning that
  looks like a statistic is not a warning.

### Saved routes

- Listed 61 real files from the user's own `GPXShare` folder, newest first, with correct dates
  and sizes, and names rendered readably.
- **Share** produced a share sheet carrying the right file name
  (`Jakuszyce_t…18-1448.gpx`), which is also proof the FileProvider cache copy was made — the
  sheet reads the name from that URI, not from the list.
- **Tapping a row** opened `com.onelap.fitness/…ImportGpxActivity` directly, honouring the
  "open directly" setting with no chooser, and the route **rendered correctly** in OneLap with
  its name intact (`Ludwika Kondratowicza to Nieporęt (gravel)`). The import was cancelled
  rather than saved, so no duplicate was added.
- The first share sheet listed **Maps2Gpx itself**, because the app now answers SEND on a
  `.gpx`. Fixed by excluding our own component from both choosers, then re-checked: it is gone,
  and a GPX viewer takes its place in the row.
- **The config screen had to become scrollable.** Adding two buttons to a fixed
  `LinearLayout` pushed the paste field, Convert button and log off the bottom of a 6.1"
  screen entirely — the log's `layout_weight` collapsed to zero and everything after it was
  clipped. It is now a `ScrollView` with a fixed-height log box, verified by scrolling to the
  Convert button. (Moving both inputs to the home screen afterwards relieved most of the
  pressure, but the guard stays: this screen only ever grows.)

### The library as home screen

- The launcher entry resolves to `SavedRoutesActivity`, checked with
  `cmd package resolve-activity -c android.intent.category.LAUNCHER`, and launching the way the
  icon does lands on the library.
- **Settings** opens the settings screen titled "Settings" with a working Up arrow back to the
  library; the paste field is gone from it and Share / Open / the log remain.
- Typing a link into the home screen and pressing **Convert to GPX** handed off to
  `MainActivity`, showed the BRouter profile prompt, and produced 18.6 km / 749 points — the
  same figures as the same link converted through a share, i.e. the hand-off changes nothing.
- Returning to the library refreshed it: the just-converted route appeared at the top of the
  list, and the count went 61 → 62.
- No regression on the conversion path after extracting `GpxFiles`: the same `api=1` link
  routed through BRouter to 18.6 km / 749 points, matching the figure recorded above.
- **The byte cache works**: the walking reroute above logged no second read of the file, so
  it did not depend on the intent's URI grant still being alive.
- **The summary was eyeballed for the first time** (see below), on both a re-route and a
  link conversion. Grey casing and legend appear only for a re-route, the elevation profile
  and duration estimate render, and the divergence between the two tracks is visible
  directly — the +158 % cycling route bulges well outside the grey loop.
- **No regression on the link path**: the same `api=1` link came back as 17.6 km / 68 min /
  861 points, matching the figure recorded above, with no grey line, legend or comparison
  on the summary. This also exercised the `api=1` + reverse-geocoding path on-device, which
  was previously untested.
- `TrackSimplify` checked on the JVM over 14 assertions: an L-shape reduces to its corner,
  30 m of jitter on a straight line to 2 points, a 50-turn zigzag stays a zigzag under the
  cap, an out-and-back spur keeps its far end (the reason the segment projection is
  clamped), a closed loop survives its own degenerate first segment, waypoints are
  reordered and off-track ones dropped, and stated points are thinned only by the cap.

**Not verified:**

- Whether BRouter's `hiking-mountain`, `shortest` and `car-vario` profiles behave sensibly
  here. Only `fastbike`, `mtb` and `gravel` were actually exercised.
- That a warning cancels "close after opening". The branch is small, but reaching it means
  driving a real hand-off into the configured target app, which writes a route into a
  third-party app's library, so it was left alone. Only the missing-segment path was checked,
  with auto-open off.
- Whether `looksLikeMissingSegments` covers every way BRouter words this. It matches on `rd5`,
  `datafile`, `data file`, `no data`, `segment` and `not found and no bounding box`; only
  `datafile <tile> not found` has actually been seen. A wording it does not match still warns,
  just as a generic BRouter failure without the tile names or the download button.
- The light-mode warning colour. Only the dark theme was on screen; the light value was chosen
  for contrast on white but not looked at. The same goes for the saved-routes screen, which has
  only been seen in dark mode.
- The saved-routes screen's chooser branch — what a tap does when the post-conversion setting is
  *not* "open directly". It calls the same helper the summary's Open button uses, but only the
  direct branch was driven.
- **Picking a file through "Re-route a GPX file…" after it moved to the home screen.** The
  chooser opens with the right title, but selecting a document in the system picker was not
  driven, so the `onActivityResult` hand-off is unproven. The intent it builds is an
  `ACTION_VIEW` on a `content://` URI, which is the shape `MainActivity` has been verified
  against repeatedly — but that is an argument, not a test.
- The saved-routes empty and error states. Both have strings and a code path, but the test
  device has a valid folder with 61 files in it, so neither was rendered.
- Behaviour with a very large folder. 61 files scroll fine; nothing is paged, so a folder with
  thousands would build one list in memory.
- GPX re-routing has been driven through OSRM and BRouter. Valhalla shares the same routing
  call, so it is expected to work, but that combination has not been run.
- The `≥3 <wpt>` branch — a file with three or more waypoints on its track — has not been
  exercised on a real file. The ordering and off-track filtering behind it are covered by the
  JVM checks; the on-device path through it is not.
- Whether a re-route reaching the 25-point cap is better or worse *as a route* than one with
  fewer via points. More via points hold the original shape more tightly but leave the router
  less freedom, and no judgement has been made about where that trade-off should sit.
- How a `.gpx` shared from a third-party app (rather than opened by URI) resolves through the
  manifest filters. The filters are declared and the `content://` read path is verified, but
  the share sheet itself was not driven.

- How closely a correct-profile OSRM route matches Google's own path. It follows sensible
  roads for the mode, but Google's routing is proprietary and the shared link carries only
  the stops — never the shape. Matching Google exactly would need the Directions API with
  an API key.
- The `api=1` + Nominatim geocoding path end-to-end on-device (the parsing side is
  exercised, the live geocoding path is not).

## Debugging on-device

The in-app log is mirrored to logcat, so the whole conversion trace is readable over
adb — useful because `uiautomator dump` fails on some devices (a Galaxy S23 returns
`ERROR: null root node returned by UiTestAutomationBridge`):

```sh
adb logcat -c
adb shell am start -S -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "'https://maps.app.goo.gl/…'" \
    -n pl.net.xtech.maps2gpx/.MainActivity
adb logcat -d -s Maps2Gpx:I
```

The trace lists every stop's final coordinates, so a stop that resolved to the wrong
place is visible immediately.

To drive the GPX path from adb, use a **MediaStore** URI and let the shell grant it:

```sh
adb shell content query --uri content://media/external/downloads \
    --projection _id:_display_name        # find the id of a .gpx already on the device
adb shell am start -S -a android.intent.action.VIEW --grant-read-uri-permission \
    -t application/gpx+xml -d content://media/external/downloads/<id> \
    -n pl.net.xtech.maps2gpx/.MainActivity
```

Two dead ends worth not rediscovering: `file:///sdcard/Android/data/<pkg>/files/…` fails with
`EACCES` even for the owning app when adb wrote the file, and
`--grant-read-uri-permission` on an `externalstorage` SAF document URI fails because the
shell does not hold that grant itself and so cannot pass it on.

## Third-party services

Both are free public endpoints with usage policies, called with an honest
`Maps2Gpx/1.0` User-Agent:

- [Nominatim](https://nominatim.openstreetmap.org) — forward and reverse geocoding. The
  client self-throttles to one request per second across both directions, so no caller can
  breach the usage policy by accident. Each unnamed stop therefore adds ~1 s.
- [FOSSGIS OSRM instances](https://routing.openstreetmap.de) — routing, one per profile
  (`routed-car` / `routed-bike` / `routed-foot`), no SLA. For heavy use, self-host.
- [FOSSGIS Valhalla](https://valhalla1.openstreetmap.de) — routing with per-request surface
  preference, no SLA.
- [OSRM demo server](https://router.project-osrm.org) — car-only fallback if the above is down.
- [BRouter](https://github.com/abrensch/brouter) — optional, user-installed, fully offline.
  Its rd5 segments are 5°×5° tiles [rebuilt weekly](https://zod.github.io/brouter/users/download_segments.html)
  and downloaded inside the BRouter app, not by Maps2Gpx.
