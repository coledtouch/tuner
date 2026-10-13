# Tuner

IPTV player for Android TV and Google TV. Cable-style Live TV guide, live preview, movies, series, search, and themes. Version 1.3.1 improves navigation, playback recovery and Favorites, with unrestricted audio and an integrated Streaming tab.

## Install on Google TV

1. On the TV: Settings > System > About, click "Android TV OS build" 7 times to unlock Developer options.
2. Settings > System > Developer options: turn on USB debugging (and Network debugging if listed).
3. Find the TV's IP under Settings > Network & Internet.
4. From Windows PowerShell, with the Android platform tools installed:

```powershell
adb connect 192.168.1.50:5555
adb install -r .\Tuner-1.3.1.apk
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

## Content and Favorites

Live TV keeps U.S. channels using provider country labels, category labels, tagged names and U.S. EPG identifiers. Unknown or conflicting countries are hidden. Movies and Series show the complete provider library, including foreign and unlabelled titles. Audio language never blocks playback; English is preferred when available and other supported tracks remain selectable.

Save channels with the guide Favorite button, Hold OK in the guide, a program's detail dialog, the Search result star, the full-screen player star or Hold OK, and the in-player channel list. The saved state updates immediately everywhere and persists separately for each playlist. Removing a Favorite preserves focus on a nearby channel in the Favorites list.

Continue Watching requires the current movie URL to match the saved entry. Duplicate channel copies are folded into one entry with playback backups, retaining Favorites and guide data. Empty and out-of-window event slots are hidden. Channels that fail on every backup with a missing-channel response are hidden for three days; Settings can restore them immediately. Authentication, connection-limit and provider-outage responses do not hide channels. Reuploaded movies with matching titles and years are merged into their newest copy, keeping earlier uploads as playback alternatives.

## Logos

Every channel shows a logo. Tuner tries the provider's logo, then the guide's icon, then the iptv-org open logo database, and finally draws a lettered tile.

## Settings

Themes (Midnight, Carbon, Ocean, Ember, Cable Blue, Daylight), multiple playlists, live format (TS or HLS), guide preview on/off, free guide regions, refresh, clear cache.

## Build

The **Streaming** tab, Settings > Torrentio, and the add-playlist screen all open the same integrated player. Paste the configured manifest link copied from Torrentio once. It is encrypted with an Android Keystore key and persists across app restarts. Movies and series load automatically from Stremio's official Cinemeta catalog. Browse poster cards or search by title. Select a card to see its description, year, rating, runtime, genres and cast when available, then choose Play movie or an episode; IMDb IDs and manual stream choice are available under Advanced options but are not required.

Tuner tries the addon's streams in configured order and automatically moves to the next candidate on playback failure, preserving the playback position. The configured sort, quality exclusions and size limits apply. Stream lookups are cached in memory for five minutes, and signed stream URLs are not written to Continue Watching. Returning from playback restores the selected title, episode, search and scroll position.

HTTP(S) streams use your optional debrid account. With Debrid Provider set to None, the embedded jlibtorrent engine downloads the requested torrent file progressively into private temporary storage. Only verified pieces are passed to Media3; seek positions get priority over intervening pieces. Free playback depends on available peers and requires enough storage for the selected file plus a 512MB reserve. The torrent pauses in the background, and exiting playback stops it and removes temporary files. External links and streams requiring custom headers are excluded. Untagged and foreign audio are allowed without a verification step. Provider outages, absent peers, unsupported device codecs and unavailable streams can still prevent playback; Retry and available backups provide recovery.

Both build types now use the main application ID `ai.ciprari.tuner`. Debug builds use the shared release key when available so device tests can update the same installation without clearing its data. Install the signed release APK for normal use. jlibtorrent JNI libraries for ARM, ARM64, x86 and x86_64 are obtained from FrostWire's official Maven repository and unpacked automatically during the build.

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

Torrentio debrid status videos are detected before playback. A blocked AllDebrid connection shows account/email approval instructions instead of looping through audio checks; downloading and expired-account notices are also explained.

The Streaming page has separate Movies and Series views with automatic US discovery rows: New Releases (recent films newly added to subscription streaming in the past seven days), New Series, New Episodes, Popular Now, and Top Netflix, Paramount+, AMC+, Prime Video, Disney+, HBO Max, Hulu, Apple TV+ and Peacock. Provider names are resolved from JustWatch's current US provider list, and Top means JustWatch popularity within the provider catalog, not the provider's official viewing chart. New Episodes uses Cinemeta air dates for recently added and popular US series, excludes future/undated episodes, and links directly to the correct episode. Discovery listings do not guarantee a working Torrentio stream.

Rows refresh every six hours while browsing, and expired rows refresh when the page opens. Public catalog metadata is cached in private cache storage so returning to the page is fast. Each row shows its update time, stale data is marked Cached, failed updates show a retry message, and Refresh requests fresh data. Discovery calls never include the Torrentio manifest or debrid key. The public JustWatch GraphQL service is undocumented and may change; errors are surfaced rather than replaced with fabricated lists.
