#!/bin/sh
# Downloads the source code of the native libraries in the APK, for attaching to a GitHub release.
#
# libsherpa-onnx-jni.so contains eSpeak NG (GPL-3.0-or-later), so every release has to offer its
# source code next to the APK. These are the exact archives the prebuilt sherpa-onnx 1.13.8 was
# built from (the hashes are the ones its build files pin), the ONNX Runtime 1.28.2 it loads, the
# libraries ONNX Runtime's build downloads (its cmake/deps.txt) and the recipe that built it, and
# the llama.cpp release compiled into libllm.so.
#
# Usage: scripts/fetch-sources.sh [output directory, default dist/sources]
set -eu

out="${1:-dist/sources}"
mkdir -p "$out"

if command -v sha256sum >/dev/null 2>&1; then sha() { sha256sum "$1" | cut -d' ' -f1; }
else sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
fi

# fetch <file name> <url> <sha256>: downloads beside the final name and moves the file into place
# only once its hash matches, so a failed or interrupted run never leaves a bad archive behind.
fetch() {
    if [ -f "$out/$1" ] && [ "$(sha "$out/$1")" = "$3" ]; then return; fi
    echo "downloading $1"
    rm -f "$out/$1" "$out/$1.part"
    curl -fsSL --retry 3 -o "$out/$1.part" "$2"
    if [ "$(sha "$out/$1.part")" != "$3" ]; then
        rm -f "$out/$1.part"
        echo "$1: checksum mismatch" >&2
        exit 1
    fi
    mv "$out/$1.part" "$out/$1"
}

# sherpa-onnx 1.13.8 and the archives its CMake build pins.
fetch sherpa-onnx-1.13.8.tar.gz https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v1.13.8.tar.gz \
    b0374cc56dbc186d442ae73d5de743bb092470b640c4c50ce7b029044c0c4fa8
fetch espeak-ng-ed530aa1.zip https://github.com/csukuangfj/espeak-ng/archive/ed530aa113046142eb5115cf2fc9157854d0ffe1.zip \
    e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd
fetch piper-phonemize-f3ff95af.zip https://github.com/csukuangfj/piper-phonemize/archive/f3ff95afc03640bc1399e113e83361192a2fafb4.zip \
    d9cca4e2bdc7d6dd8dffb96a4668283dbd3f77a9c194a3e530c1e8eba9406a5d
fetch kaldi-native-fbank-1.22.3.tar.gz https://github.com/csukuangfj/kaldi-native-fbank/archive/refs/tags/v1.22.3.tar.gz \
    9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61
fetch kissfft-febd4cae.zip https://github.com/mborgerding/kissfft/archive/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec.zip \
    497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd
fetch kaldi-decoder-0.3.0.tar.gz https://github.com/k2-fsa/kaldi-decoder/archive/refs/tags/v0.3.0.tar.gz \
    b9f34cfb4fd3b1344100eead79ef4d37aa15962274b9e3056de345021f76a1b0
fetch kaldifst-1.8.0.tar.gz https://github.com/k2-fsa/kaldifst/archive/refs/tags/v1.8.0.tar.gz \
    3f247b7e5a2409071202f5e2bc6200060f66728c0a3443c03923ad2723e040b3
fetch openfst-1.8.5-2026-07-09.tar.gz https://github.com/csukuangfj/openfst/archive/refs/tags/v1.8.5-2026-07-09.tar.gz \
    2ff712a32952fcb01d351121a6bc8ccf4fdc6b2aa06ce8df2b3095dedd518c0e
fetch eigen-5.0.1.tar.gz https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz \
    e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec
fetch simple-sentencepiece-0.7.tar.gz https://github.com/pkufool/simple-sentencepiece/archive/refs/tags/v0.7.tar.gz \
    1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af
fetch json-3.12.0.tar.gz https://github.com/nlohmann/json/archive/refs/tags/v3.12.0.tar.gz \
    4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187
fetch hclust-cpp-2026-02-25.tar.gz https://github.com/csukuangfj/hclust-cpp/archive/refs/tags/2026-02-25.tar.gz \
    8f14e024c709d73afb40ae69cb22de4b73dba67cbce40f2e518813da8139ab56

# ONNX Runtime 1.28.2 (libonnxruntime.so) and the workflow that built the copy in the AAR.
fetch onnxruntime-1.28.2.tar.gz https://github.com/microsoft/onnxruntime/archive/refs/tags/v1.28.2.tar.gz \
    a5003dfd85d66a4d45bd2757c7f813f9e835142e9a936ac04877c788d6afe938
fetch onnxruntime-libs-bb069649.tar.gz https://github.com/csukuangfj/onnxruntime-libs/archive/bb069649de9edc4689b2c5f9609e5499ae0bee9d.tar.gz \
    abb7156213a226bed377cb640bb3ee592ed6a46a103e7b12825c7d907f6780cc

# The libraries ONNX Runtime 1.28.2's build downloads and compiles in (cmake/deps.txt, whose SHA-1
# pins these files match).
fetch ort-abseil-cpp-20250814.0.zip https://github.com/abseil/abseil-cpp/archive/refs/tags/20250814.0.zip \
    b2bdcf6682d8cb53df365bcc5d6c318a22e55821d9978a10fdb61404c026daff
fetch ort-date-3.0.1.zip https://github.com/HowardHinnant/date/archive/refs/tags/v3.0.1.zip \
    f4300b96f7a304d4ef9bf6e0fa3ded72159f7f2d0f605bdde3e030a0dba7cf9f
fetch ort-eigen-1d8b82b0.zip https://github.com/eigen-mirror/eigen/archive/1d8b82b0740839c0de7f1242a3585e3390ff5f33/eigen-1d8b82b0740839c0de7f1242a3585e3390ff5f33.zip \
    6a60d76351f97132669daeeb721d6bf14b008101883ad2d687a3201c5c461eb0
fetch ort-flatbuffers-23.5.26.zip https://github.com/google/flatbuffers/archive/refs/tags/v23.5.26.zip \
    57bd580c0772fd1a726c34ab8bf05325293bc5f9c165060a898afa1feeeb95e1
fetch ort-json-3.11.3.zip https://github.com/nlohmann/json/archive/refs/tags/v3.11.3.zip \
    04022b05d806eb5ff73023c280b68697d12b93e1b7267a0b22a1a39ec7578069
fetch ort-gsl-4.2.1.zip https://github.com/microsoft/GSL/archive/refs/tags/v4.2.1.zip \
    c9291d95f5f6e5c561990d06a589b01d89e553d0f366f0ce723dd1788e7a6076
fetch ort-mp11-boost-1.82.0.zip https://github.com/boostorg/mp11/archive/refs/tags/boost-1.82.0.zip \
    81431bdc44c439a324e02c07ed067f8f556419fd86f2d8b486ff568df6aac899
fetch ort-onnx-1.22.0.zip https://github.com/onnx/onnx/archive/refs/tags/v1.22.0.zip \
    8dc1181d33529a1249e031226126d0699ac9bdfc571ee530ee3a12f4656f2be3
fetch ort-protobuf-21.12.zip https://github.com/protocolbuffers/protobuf/archive/refs/tags/v21.12.zip \
    6a31b662deaeb0ac35e6287bda2f3369b19836e6c9f8828d4da444346f420298
fetch ort-cpuinfo-4628dc06.zip https://github.com/pytorch/cpuinfo/archive/4628dc060ce4e82345dc166bbac875609db4ff69.zip \
    2ed3ebc6c2656cc0aafc7af319e5cb0f97cc9b415eae180f566def84f1ca6a29
fetch ort-re2-2024-07-02.zip https://github.com/google/re2/archive/refs/tags/2024-07-02.zip \
    a835fe55fbdcd8e80f38584ab22d0840662c67f2feb36bd679402da9641dc71e
fetch ort-safeint-3.0.28.zip https://github.com/dcleblanc/SafeInt/archive/refs/tags/3.0.28.zip \
    3ffbd9a2fdff45da77da3e7269e9aa512ea43bed5c38ce8fd8f3d1068a032c3f
fetch ort-kleidiai-1.20.0.tar.gz https://github.com/ARM-software/kleidiai/archive/refs/tags/v1.20.0.tar.gz \
    e4b84c369f0f39af1660ac71c30f74038d4b89e1f20dde070bb96398382a111f

# llama.cpp, compiled into libllm.so (the same pin as app/src/main/cpp/CMakeLists.txt).
fetch llama.cpp-b11308.tar.gz https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11308.tar.gz \
    3af8fa61fe0c2dea31f2819e6d7fa4eab7a58cb7dc580f3fe4fb025e2d4d3da6

echo "all source archives are in $out"
