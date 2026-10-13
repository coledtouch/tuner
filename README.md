# Tuner

IPTV player for Android TV and Google TV. Cable-style grid guide, live preview, movies, series, search, themes.

## Install on Google TV

1. On the TV: Settings > System > About, click "Android TV OS build" 7 times to unlock Developer options.
2. Settings > System > Developer options: turn on USB debugging (and Network debugging if listed).
3. Find the TV's IP under Settings > Network & Internet.
4. From Windows PowerShell, with the Android platform tools installed:

```powershell
adb connect 192.168.1.50:5555
adb install -r .\Tuner-1.0.0.apk
```

Accept the debugging prompt on the TV the first time. Tuner shows up in your Apps row.

No PC handy? Install the "Downloader" app on the TV, or use "Send Files to TV", and open the APK from there.

## First run

Choose **Xtream Codes** and enter the server URL, username and password from your provider. Tuner signs in, shows your account status, and loads channels, the guide, movies and series.

If you only have a link like `http://host:port/get.php?username=...&password=...&type=m3u_plus`, pick **M3U URL** and paste it. Tuner spots the credentials and switches to Xtream mode so you still get movies and series. Plain M3U links work too.

## Remote controls

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

## Logos

Every channel shows a logo. Tuner tries the provider's logo, then the guide's icon, then the iptv-org open logo database, and finally draws a lettered tile.

## Settings

Themes (Midnight, Carbon, Ocean, Ember, Cable Blue, Daylight), multiple playlists, live format (TS or HLS), guide preview on/off, free guide regions, refresh, clear cache.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```powershell
.\gradlew.bat assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`. The release build is signed with the debug key so it installs directly; add your own keystore in `app\build.gradle.kts` before distributing.

Tests (parsers, name matching, and Robolectric screenshots of the guide written to `app\build\screens`):

```powershell
.\gradlew.bat testDebugUnitTest
```

## Notes

- Playlist logins are stored in the app's private storage on the TV.
- Most IPTV servers use plain http, so cleartext traffic is allowed.
- Built with Kotlin, Jetpack Compose, Media3 ExoPlayer, OkHttp and Coil.
