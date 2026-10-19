# XENOWAVE

<img src="./assets/xenowave.webp" height="88" alt="XENOWAVE app icon">

A Material 3 YouTube Music client & local music player for Android

[![Latest release](https://img.shields.io/github/v/release/paraecologia-arch/XENOWAVE?include_prereleases)](https://github.com/paraecologia-arch/XENOWAVE/releases)
[![License](https://img.shields.io/github/license/paraecologia-arch/XENOWAVE)](https://www.gnu.org/licenses/gpl-3.0)
[![Downloads](https://img.shields.io/github/downloads/paraecologia-arch/XENOWAVE/total)](https://github.com/paraecologia-arch/XENOWAVE/releases)

[<img src="assets/badge_github.png" alt="Get it on GitHub" height="40">](https://github.com/paraecologia-arch/XENOWAVE/releases/latest)

> [!WARNING]
> XENOWAVE is only available through the GitHub repository linked above. This app is not listed on the Play Store or any
> other website claiming to be us. We recommend you to immediately delete any fake versions or clones for your safety.

## Features

XENOWAVE is a fork of [InnerTune](https://github.com/z-huang/InnerTune), built on the [OuterTune](https://github.com/OuterTune/OuterTune)
project. This app is both a local media player and a YouTube Music client.

- YouTube Music client features
    - Song downloading (offline playback)
    - Seamless playback: no ADs & background playback
    - Account synchronization
        - Full playlist sync from the app to the remote account is temporarily unavailable
- Local audio file playback (ex. MP3, OGG, FLAC, etc.)
    - Play local and YouTube Music songs at the same time
    - Uses a custom tag extractor instead of MediaStore's broken metadata extractor! (e.g. tags delimited with \\ now show up properly)
- Sleek Material 3 design
- Multiple queues
- Synchronized lyrics, and support for word by word/Karaoke lyrics formats (e.g. LRC, TTML)
- Audio normalization, tempo/pitch adjustment, and various other audio effects
- Android Auto support
- Support for Android 8 (Oreo) and higher

> [!NOTE]
> Android 8 (Oreo) and higher is supported. While the app may work on Android 7.x (Nougat), we do not officially support this version

> [!NOTE]
> The inherited documentation of the upstream OuterTune project is available in the
> [upstream wiki](https://github.com/OuterTune/OuterTune/wiki/Frequently-Asked-Questions-(FAQ)).

## Screenshots

> [!NOTE]
> Screenshots of the current XENOWAVE interface will be published after validation on a physical
> device. Captures of the inherited interface were removed from the active documentation.

> [!WARNING]
>
> If you're in a region where YouTube Music is not supported, you won't be able to use this app
> ***unless*** you have a proxy or VPN to connect to a YTM supported region.

## Building & Contributing

We are looking for contributors, translators, and maintainers! If you would like to help out, or just wish to build the
app yourself, please see the [building and contribution notes](./CONTRIBUTING.md).

### Translations

XENOWAVE inherits the translations maintained by the upstream OuterTune project on
[Weblate](https://hosted.weblate.org/projects/outertune/). Thank you very much to everyone who made this app accessible
to many people worldwide.

## Help & Support

Contact us through GitHub
- For bug reports and/or feature requests, please create a [GitHub issue](https://github.com/paraecologia-arch/XENOWAVE/issues).
- If you need help with the app, check out the [Discussion Forum](https://github.com/paraecologia-arch/XENOWAVE/discussions).

## Attribution

XENOWAVE is derived from [OuterTune](https://github.com/OuterTune/OuterTune) and from
[z-huang/InnerTune](https://github.com/z-huang/InnerTune), which provided an awesome base for this fork; none of this
would have been possible without them.

Thanks to all previous contributors! Check them out [here](https://github.com/OuterTune/OuterTune/graphs/contributors)

[Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musicolet) for inspiration of a local music player
experience done right.

[Gramophone](https://github.com/FoedusProgramme/Gramophone) for emotional support, and a legendary lyrics parser

This project is licensed under the GNU General Public License v3.0. See [LICENSE](./LICENSE).

## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any
way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project
are owned by the respective owners.
