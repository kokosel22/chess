# Third-party components

## Stockfish 17.1

Source: https://github.com/official-stockfish/Stockfish/tree/sf_17.1

Copyright Stockfish contributors. GPL-3.0-or-later. Built without source modifications, using the Android NDK. The official source's network downloader verifies the SHA-256 prefix embedded in each NNUE filename. NNUE is embedded in the executable, and no device-time download is used. Full corresponding source including the network files, exact build script, upstream AUTHORS and license accompany each APK artifact. Actual source commit is included in the APK asset `stockfish/SOURCE_COMMIT.txt`.

## Screen recognition

No Chess.com image assets are bundled or downloaded. Piece templates are learned from the standard initial board in the user's consented screen capture and remain in memory for that capture session. The test fixture draws system-font chess glyphs. Chess.com and its trademarks belong to their respective owners. This application is independent and is not endorsed by Chess.com.

## Build and test dependencies

Kotlin (Apache-2.0), AndroidX Test (Apache-2.0), JUnit (EPL-1.0). Runtime uses Android platform APIs and the bundled Stockfish executable. GitHub Actions fetches the development dependencies on the cloud runner.
