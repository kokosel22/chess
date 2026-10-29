#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cloud="$root/.cloud"
mkdir -p "$cloud" "$root/app/src/main/assets/stockfish"
if [[ ! -d "$cloud/Stockfish/.git" ]]; then
  git clone --depth 1 --branch sf_17.1 https://github.com/official-stockfish/Stockfish.git "$cloud/Stockfish"
fi
cd "$cloud/Stockfish/src"
export PATH="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
test -x "$(command -v aarch64-linux-android33-clang++)"
make net
for spec in 'arm64-v8a armv8 aarch64-linux-android33-clang++' 'x86_64 x86-64 x86_64-linux-android33-clang++'; do
  read -r abi arch compiler <<< "$spec"
  destination="$root/app/src/main/jniLibs/$abi"
  mkdir -p "$destination"
  make objclean
  make -j2 build ARCH="$arch" COMP=ndk COMPCXX="$compiler" EXE=stockfish
  llvm-strip stockfish
  cp stockfish "$destination/libstockfish.so"
  llvm-readelf -h "$destination/libstockfish.so"
done
cp ../Copying.txt "$root/app/src/main/assets/stockfish/LICENSE.txt"
cp ../AUTHORS "$root/app/src/main/assets/stockfish/AUTHORS.txt"
git -C .. rev-parse HEAD > "$root/app/src/main/assets/stockfish/SOURCE_COMMIT.txt"
# Full corresponding engine source, bundled NNUE and exact build script travel with the APK.
tar --exclude=.git --exclude='*.o' --exclude=stockfish --exclude=.depend -czf "$cloud/stockfish-source.tar.gz" -C "$cloud" Stockfish
cd "$root"
cp scripts/prepare-stockfish.sh "$cloud/BUILD-STOCKFISH.sh"
