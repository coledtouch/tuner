# Tuner

IPTV player for Android TV and Google TV. Cable-style Live TV guide, live preview, movies, series, search, and themes. Version 1.1.0 adds a clearer remote experience, responsive layouts, richer libraries, and USA / English content filtering.

## Install on Google TV

1. On the TV: Settings > System > About, click "Android TV OS build" 7 times to unlock Developer options.
2. Settings > System > Developer options: turn on USB debugging (and Network debugging if listed).
3. Find the TV's IP under Settings > Network & Internet.
4. From Windows PowerShell, with the Android platform tools installed:

```powershell
adb connect 192.168.1.50:5555
adb install -r .\Tuner-1.1.0.apk
```

Accept the debugging prompt on the TV the first time. Tuner shows up in your Apps row.

No PC handy? Install the "Downloader" app on the TV, or use "Send Files to TV", and open the APK from there.

## First run

Choose **Xtream Codes** and enter the server URL, username and password from your provider. Tuner signs in, shows your account status, and loads channels, the guide, movies and series.

If you only have a link like `http://host:port/get.php?username=...&password=...&type=m3u_plus`, pick **M3U URL** and paste it. Tuner spots the credentials and switches to Xtream mode so you still get movies and series. Plain M3U links work too.

## Remote controls

Use Left / Right in the top navigation, then OK to open Live TV, Movies, Series, Search, or Settings. Moving across the navigation keeps the current page open until you select a destination. In Movies and Series, select a category shortcut to jump straight to its titles; returning from details restores your place.

| Where | Key | Does |
|---|---|---|
| Guide | Up / Down | Move between channels (hold to fly) |
| Guide | Left / Right | Move through programs; the grid scrolls in time |
| Guide | OK | Watch now, or show details for a future program |
| Guide | Hold OK | Add or remove the channel from Favorites |
| Guide | Channel +/- | Page up / down |
| Guide | FF / Rewind | Jump the guide 2 hours |
| Guide | Back | Return the grid to "now" |
| Guide | 0-9 | Type a channel number |
| Live TV | Up / Down, Channel +/- | Change channel |
| Live TV | OK | Info banner, press again for the channel list |
| Live TV | Left | Channel list |
| Live TV | Back | Back to the guide (keeps playing in the preview window) |
| Movies / episodes | Left / Right | Seek 10s (hold to go faster) |
| Movies / episodes | OK | Play / pause |
| Movies / episodes | Down | Audio, subtitles, next episode |

## Guide data

1. Your provider's XMLTV guide (or the EPG URL you set).
2. Free regional guides from epgshare01.online for any channel still missing listings, matched by channel name. Pick regions in Settings > Guide. Defaults: US national, US sports, Canada, UK.
3. Xtream's per-channel short EPG for anything left, fetched as rows scroll into view.

## USA / English content

The catalog is filtered before it reaches the guide, libraries, search, Favorites, or the live channel picker. Live TV requires a positive U.S. country label and excludes explicitly non-English feeds. Movies and Series require a positive English language or an eligible English-speaking provider label. The policy reads country/language metadata, category labels, tagged names, and U.S. EPG identifiers. Plain M3U playlists can use `tvg-country="US"` and `tvg-language="English"`.

Unlabelled entries and entries with conflicting country/language metadata are hidden. Incorrect provider labels can still misidentify a stream; title spelling alone is never used to guess the language. Empty libraries explain the labeling requirement. Continue Watching only includes movies still present in the filtered catalog; old episode shortcuts are hidden because they do not record the parent series.

## Logos

Every channel shows a logo. Tuner tries the provider's logo, then the guide's icon, then the iptv-org open logo database, and finally draws a lettered tile.

## Settings

Themes (Midnight, Carbon, Ocean, Ember, Cable Blue, Daylight), multiple playlists, live format (TS or HLS), guide preview on/off, free guide regions, refresh, clear cache.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```powershell
.\gradlew.bat assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk` when the external release key is available. Without that key, Gradle produces `app-release-unsigned.apk`; CI never invents a replacement release key.

The shared release key is stored outside this source folder, at `../signing/tuner-release.jks`, with `../signing/keystore.properties`. Copy those same two files to the other build machine and place `signing` beside its source checkout. You can instead set `TUNER_SIGNING_DIR` to that directory, or pass `-PtunerSigningProperties=/absolute/path/keystore.properties`. Keep both files outside Git and source archives. The properties contain `storeFile`, `keyAlias`, `storePassword`, and `keyPassword`; a relative `storeFile` resolves beside the properties file.

The release certificate SHA-256 is `EC:6F:ED:26:F3:B6:88:C4:6A:CB:1A:55:2B:D0:B9:EE:8C:E8:C9:62:BA:B4:DB:74:51:DF:3F:35:A3:95:45:86`. Keep a private backup of the signing folder; all future release updates must use this key.

The installed 1.0.2 uses the old debug certificate `63:1E:66:57:D0:2D:8B:E2:97:09:50:1D:E6:69:1A:1B:4C:4F:1D:C2:90:B2:B5:F2:8A:63:9C:4A:B3:0D:82:A5`. Android cannot install the new release key over that version. A one-time migration/reinstall is needed; removing 1.0.2 clears its locally saved playlists and settings. After moving to the release-signed version, builds on both machines update it normally.

Tests (parsers, content policy, name matching, remote navigation, and Robolectric screenshots written to `app\build\screens`):

```powershell
.\gradlew.bat testDebugUnitTest
```

Screenshot coverage includes Live TV, Movies, Series, playback controls, themes, and a compact 720 × 405 dp layout. Robolectric's native dependencies require a supported host architecture; on Windows ARM, use an x64 JDK under Windows emulation for these tests.

## Notes

- Playlist logins are stored in the app's private storage on the TV.
- Most IPTV servers use plain http, so cleartext traffic is allowed.
- Built with Kotlin, Jetpack Compose, Media3 ExoPlayer, OkHttp and Coil.
