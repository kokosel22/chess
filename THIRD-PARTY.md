# Third-party components

## Stockfish 17.1

Source: https://github.com/official-stockfish/Stockfish/tree/sf_17.1

Copyright Stockfish contributors. GPL-3.0-or-later. Built without source modifications, using the Android NDK. The official source's network downloader verifies the SHA-256 prefix embedded in each NNUE filename. NNUE is embedded in the executable, and no device-time download is used. Full corresponding source including the network files, exact build script, upstream AUTHORS and license accompany each APK artifact. Actual source commit is included in the APK asset `stockfish/SOURCE_COMMIT.txt`.

## Chess.com Neo recognition templates

The build runner retrieves twelve reference PNG files from the public Chess.com theme CDN:
`https://images.chesscomfiles.com/chess-themes/pieces/neo/150/{w,b}{p,n,b,r,q,k}.png`

These files are used only as recognition templates. They are owned by their respective copyright holders; the GPL license for this repository does not relicense these assets. Chess.com and its trademarks belong to their respective owners. This application is independent and is not endorsed by Chess.com. Redistribution of the reference assets is subject to their owners' rights.

## Build and test dependencies

Kotlin (Apache-2.0), AndroidX Test (Apache-2.0), JUnit (EPL-1.0). Runtime uses Android platform APIs and the bundled Stockfish executable. GitHub Actions fetches the development dependencies on the cloud runner.
