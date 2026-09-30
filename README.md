# ZekIPTV

A modern Android IPTV player built with Kotlin and Jetpack Compose, optimized for Fire TV and Android TV devices.

## Features

- **M3U Playlist Support**: Load and manage IPTV playlists in M3U format (incl. gzip,
  `#EXTGRP` and per-stream `#EXTVLCOPT` user agent / referrer)
- **Xtream Codes**: Movies, series, account info (expiry, connections) and catch-up for
  channels with a provider archive
- **XMLTV EPG Integration**: Electronic Program Guide support for channel scheduling
- **HLS Streaming**: Full support for HLS streams (.m3u8) via Media3/ExoPlayer
- **Player**: Aspect ratio modes, numeric channel input, audio/subtitle tracks, sleep timer,
  media session (remote media keys), picture-in-picture on phones
- **Channel Favorites**: Mark and quickly access your favorite channels
- **Parental PIN**: Lock hidden categories and backup import
- **TV-Optimized UI**: Designed for 10-foot interface with D-pad navigation
- **Background Refresh**: Automatic playlist and EPG updates via WorkManager
- **Channel Logos**: Automatic logo loading and caching with Coil
- **Multi-Device Support**: Works on phones, tablets, Fire TV, and Android TV

## Technical Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3
- **Video Playback**: Media3 (ExoPlayer) with HLS support
- **Persistence**: DataStore Preferences
- **Background Tasks**: WorkManager for periodic updates
- **Image Loading**: Coil Compose for channel logos
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 36 (Android 16)

## Building the App

### Prerequisites

- A recent Android Studio with Android Gradle Plugin 9.4 support
- JDK 17 or newer
- Gradle 9.8 (included via wrapper), Kotlin 2.4 (built-in Kotlin of AGP 9)

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/kaveyro/ZekIPTV.git
   cd ZekIPTV
   ```

2. Build the project:
   ```bash
   ./gradlew assembleRelease
   ```

3. The APK will be generated in `app/build/outputs/apk/release/`

### Signing

For release builds, create a `keystore/keystore.properties` file (gitignored) with:
```properties
storeFile=your-keystore.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

## Installation

1. Enable "Unknown Sources" or "Install Unknown Apps" in your Android/Fire TV settings
2. Transfer the APK to your device
3. Install the APK using a file manager or ADB:
   ```bash
   adb install app-release.apk
   ```

## Configuration

On first launch, configure your IPTV playlist:
- Enter your M3U playlist URL
- Optionally add an XMLTV EPG URL for program guide
- The app will automatically fetch and parse the channels

## Performance Optimizations

- R8 code shrinking and resource minification for smaller APK size
- Optimized for Fire TV's limited hardware resources
- Channel list stored in its own file (not in DataStore), so settings writes stay small
- Streaming XMLTV parser that only keeps programmes of the playlist's channels
- Efficient logo caching to minimize network usage
- Leanback support for Android TV launcher integration

## Screenshots

*(Add screenshots of your app here)*

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## License

*(Add your license here)*

## Version History

- **1.8.0**: Catch-up, account info, parental PIN, aspect ratio / numeric channel input,
  picture-in-picture, media session, backup via file picker, performance and stability fixes,
  targetSdk 36 and updated dependencies
- **1.7.1**: Optimized Fire TV support
- Previous versions available in releases

---

**Note**: This app requires an active IPTV subscription or playlist from a legitimate provider. The developers are not responsible for the content accessed through this application.