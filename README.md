# XENOWAVE

<img src="./assets/xenowave.webp" height="88" alt="XENOWAVE app icon">

A Material 3 YouTube Music client & local music player for Android

[![Latest release](https://img.shields.io/github/v/release/paraecologia-arch/XENOWAVE?include_prereleases)](https://github.com/paraecologia-arch/XENOWAVE/releases)
[![License](https://img.shields.io/github/license/paraecologia-arch/XENOWAVE)](https://www.gnu.org/licenses/gpl-3.0)
[![Downloads](https://img.shields.io/github/downloads/paraecologia-arch/XENOWAVE/total)](https://github.com/paraecologia-arch/XENOWAVE/releases)

[<img src="assets/badge_github.png" alt="Baixar o XENOWAVE" height="40">](https://paraecologia-arch.github.io/XENOWAVE/)

## Baixar e instalar gratuitamente

**[Baixar o XENOWAVE para Android](https://paraecologia-arch.github.io/XENOWAVE/)**

A página mostra a versão atual e leva direto ao APK oficial, sem exigir conta no GitHub.
[Ver notas da versão e outros lançamentos](https://github.com/paraecologia-arch/XENOWAVE/releases).

1. Abra a página e toque em **Baixar para Android**.
2. No celular, abra o APK e permita a instalação por esse navegador ou gerenciador de arquivos, se o Android solicitar.
3. Conclua a instalação e abra o **XENOWAVE**.

O download é gratuito e não exige conta no GitHub. O arquivo **Source code** em
Releases é o código-fonte, não o instalador. Android 7.0 ou superior.

Esta primeira versão é **beta**: compilação, verificações automáticas e assinatura são conferidas antes da publicação;
a validação completa em celular ainda está pendente. A versão Core usa os codecs disponíveis no Android.

Se você usa o antigo APK de testes com pacote `com.dd3boh.xenowave.debug`, a versão pública é instalada como outro app
(`com.dd3boh.xenowave`). Exporte um backup pelo aplicativo de testes antes de migrar seus dados.

O XENOWAVE é software livre sob a [GPL v3](./LICENSE), com código-fonte disponível neste repositório.

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
        - Playlist changes are queued on the device and synced to YouTube Music in read/write mode (create, rename, add/remove occurrences, reorder and delete). Pending edits survive restarts and are kept if a request fails. Local audio files stay on the device. See [playlist synchronization](docs/playlist-sync.md) for conflict and retry behavior.
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

## Creation and credits

XENOWAVE is an independent project, created and directed by **Rafael de Souza Silva**. Development uses
**Codex (OpenAI)** as an assistance tool; this does not imply affiliation, sponsorship or endorsement by OpenAI.

O XENOWAVE é um projeto independente, criado e dirigido por **Rafael de Souza Silva**, com assistência ao
desenvolvimento de **Codex (OpenAI)** como ferramenta de apoio. Isso não representa vínculo, patrocínio ou endosso
da OpenAI.

### Base projects

XENOWAVE is derived from [OuterTune](https://github.com/OuterTune/OuterTune) and from
[z-huang/InnerTune](https://github.com/z-huang/InnerTune), which provided an awesome base for this fork; none of this
would have been possible without them. Their authors and maintainers are credited for the original work and are not
presented as creators of XENOWAVE.

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
