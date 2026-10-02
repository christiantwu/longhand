# Third-party notices

Longhand is licensed under the GNU General Public License v3.0 or later (see [LICENSE](LICENSE)).
The APK also contains the components below, each under its own licence. The full licence texts are
in [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/), and the app shows the same list
under **Settings → About → Open-source licences**.

## Native libraries

### `libsherpa-onnx-jni.so`: sherpa-onnx 1.13.8

This is the prebuilt Android library from the sherpa-onnx v1.13.8 release, built by the k2-fsa
project from tag [v1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) (commit
`11afbd009a7f`) with its standard Android build script. It statically includes:

| Component | Version | Licence | Source |
|---|---|---|---|
| sherpa-onnx | 1.13.8 | Apache-2.0 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) |
| **eSpeak NG**, with its ucd-tools | 1.52-dev, commit `ed530aa` | **GPL-3.0-or-later** | [csukuangfj/espeak-ng](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1) |
| piper-phonemize | 1.2.0, commit `f3ff95a` | MIT | [csukuangfj/piper-phonemize](https://github.com/csukuangfj/piper-phonemize/tree/f3ff95afc03640bc1399e113e83361192a2fafb4) |
| uni-algo (copy in piper-phonemize) | 0.8.1 | Unlicense OR MIT | [uni-algo/uni-algo](https://github.com/uni-algo/uni-algo/tree/v0.8.1) |
| kaldi-native-fbank | 1.22.3 | Apache-2.0 | [csukuangfj/kaldi-native-fbank](https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.22.3) |
| KISS FFT | commit `febd4ca` | BSD-3-Clause | [mborgerding/kissfft](https://github.com/mborgerding/kissfft/tree/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec) |
| whisper.cpp FFT routines (copied into kaldi-native-fbank) | 2023 | MIT | [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp) |
| kaldi-decoder | 0.3.0 | Apache-2.0 | [k2-fsa/kaldi-decoder](https://github.com/k2-fsa/kaldi-decoder/tree/v0.3.0) |
| kaldifst | 1.8.0 | Apache-2.0 | [k2-fsa/kaldifst](https://github.com/k2-fsa/kaldifst/tree/v1.8.0) |
| LLVM libc++ `basic_filebuf` (copy in kaldifst) | 2014 | MIT OR NCSA | [kaldifst basic-filebuf.h](https://github.com/k2-fsa/kaldifst/blob/v1.8.0/kaldifst/csrc/basic-filebuf.h) |
| OpenFst | 1.8.5 (fork tag v1.8.5-2026-07-09) | Apache-2.0 | [csukuangfj/openfst](https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-07-09) |
| Eigen | 5.0.1 | MPL-2.0 | [libeigen/eigen](https://gitlab.com/libeigen/eigen/-/tree/5.0.1) |
| simple-sentencepiece (patched for Android by the sherpa-onnx build) | 0.7 | Apache-2.0 | [pkufool/simple-sentencepiece](https://github.com/pkufool/simple-sentencepiece/tree/v0.7) |
| darts-clone (copy in simple-sentencepiece) | 0.32 | BSD-2-Clause | [s-yata/darts-clone](https://github.com/s-yata/darts-clone) |
| JSON for Modern C++ | 3.12.0 | MIT | [nlohmann/json](https://github.com/nlohmann/json/tree/v3.12.0) |
| hclust-cpp / fastcluster | fork tag 2026-02-25 | BSD-2-Clause | [csukuangfj/hclust-cpp](https://github.com/csukuangfj/hclust-cpp/tree/2026-02-25) |
| Supertonic (code adapted into sherpa-onnx) | 2026 | MIT | [supertone-oss-archive/supertonic](https://github.com/supertone-oss-archive/supertonic) |
| CATT tashkeel tokenizer (ported into sherpa-onnx; its transliteration table follows [KentonMurray/Buckwalter](https://github.com/KentonMurray/Buckwalter), which has no licence statement) | 2026 | Apache-2.0 | [abjadai/catt](https://github.com/abjadai/catt) |
| OpenAI Whisper word-timestamp logic (re-implemented in sherpa-onnx) | 2022 | MIT | [openai/whisper](https://github.com/openai/whisper) |
| Kaldi (code copied into the projects above) | various | Apache-2.0 | [kaldi-asr/kaldi](https://github.com/kaldi-asr/kaldi) |
| Unicode Character Database tables | UCD 11.0 and 15.0 | Unicode-DFS-2016 | [unicode.org](https://www.unicode.org/Public/) |
| ONNX Runtime C/C++ API headers | 1.28.2 | MIT | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime/tree/v1.28.2) |
| LLVM C++ runtime (Android NDK r29) | LLVM 21 | Apache-2.0 WITH LLVM-exception | [llvm/llvm-project](https://github.com/llvm/llvm-project) |

The notices for the libraries bundled in sherpa-onnx are in
[`sherpa-onnx-NOTICES.txt`](app/src/main/assets/licenses/sherpa-onnx-NOTICES.txt). The rest have
their own files:

- sherpa-onnx: [`Apache-2.0.txt`](app/src/main/assets/licenses/Apache-2.0.txt).
- eSpeak NG: [`GPL-3.0.txt`](app/src/main/assets/licenses/GPL-3.0.txt).
- Eigen: [`MPL-2.0.txt`](app/src/main/assets/licenses/MPL-2.0.txt).
- The ONNX Runtime headers: [`onnxruntime-LICENSE.txt`](app/src/main/assets/licenses/onnxruntime-LICENSE.txt).
- The LLVM runtime: [`LLVM-LICENSE.txt`](app/src/main/assets/licenses/LLVM-LICENSE.txt).

The sherpa-onnx
AAR also contains its C and C++ API libraries, but the build leaves them out of the APK because
nothing uses them.

### `libonnxruntime.so`: ONNX Runtime 1.28.2

MIT, Copyright (c) Microsoft Corporation, with portions copyright Amazon.com, Arm Limited, Intel
Corporation, NVIDIA Corporation, FUJITSU LIMITED and Oracle. The sherpa-onnx project built this library with the
[`android-shared.yaml` workflow](https://github.com/csukuangfj/onnxruntime-libs/blob/bb069649de9edc4689b2c5f9609e5499ae0bee9d/.github/workflows/android-shared.yaml)
from Microsoft's tag [v1.28.2](https://github.com/microsoft/onnxruntime/tree/v1.28.2) (commit
`33ca962`), using NDK r27. The third-party code it contains is listed in Microsoft's
[ThirdPartyNotices.txt](app/src/main/assets/licenses/onnxruntime-ThirdPartyNotices.txt) for that
commit. That code includes Abseil, Protocol Buffers, ONNX, RE2, FlatBuffers, KleidiAI, cpuinfo, and
a development snapshot of Eigen (MPL-2.0); the snapshot's source is at
[eigen-mirror/eigen@1d8b82b](https://github.com/eigen-mirror/eigen/tree/1d8b82b0740839c0de7f1242a3585e3390ff5f33).

Microsoft's list leaves out some code that is compiled in. Its notices are in
[`onnxruntime-ADDITIONAL-NOTICES.txt`](app/src/main/assets/licenses/onnxruntime-ADDITIONAL-NOTICES.txt):

- RE2's Plan 9 UTF-8 routines (Lucent Technologies licence).
- LIBSVM's `multiclass_probability` (BSD-3-Clause).
- Fabian Giesen's half-precision conversions (BSD-style).
- Eigen's `BFloat16.h` (Apache-2.0, The TensorFlow Authors).
- Eigen's non-blocking thread pool as modified by Microsoft (MPL-2.0). Its source is
  [in the ONNX Runtime repository](https://github.com/microsoft/onnxruntime/blob/33ca9628233dc8f002435e868d4c2e9f82766ca1/include/onnxruntime/core/platform/EigenNonBlockingThreadPool.h).
- nlohmann/json 3.11.3's embedded code.
- The Semantic Versioning regular expression (CC-BY-3.0).
- RE2's Unicode 15.1 tables (Unicode-3.0).

### `libllm.so`: Longhand's summary engine

Built by this repository from `app/src/main/cpp`. Longhand's JNI bridge (`llm.cpp`) is
GPL-3.0-or-later. llama.cpp is compiled in, unmodified, from release
[b11308](https://github.com/ggml-org/llama.cpp/tree/b11308) (MIT, Copyright (c) 2023-2026 The
ggml authors). llama.cpp carries its own notices for llamafile tinyBLAS (MIT, Mozilla Foundation),
YaRN (MIT), Arm Optimized Routines (MIT), FP16 (MIT), ggllm.cpp (MIT) and its Unicode tables
(Unicode-3.0). Its DRY sampler was ported from KoboldCpp with its author's permission, and its
Z-algorithm is under The Unlicense. All of these are in
[`llama.cpp-NOTICES.txt`](app/src/main/assets/licenses/llama.cpp-NOTICES.txt). The library also
contains the LLVM C++ runtime (Apache-2.0 WITH LLVM-exception) and the C runtime startup objects
(BSD-2-Clause) from Android NDK r30.

### `libonline-batch.so`: batched decoding for the Hindi model

Built by this repository from `app/src/main/cpp/online_batch.c` (GPL-3.0-or-later). It calls the batched
decoding in `libsherpa-onnx-jni.so` that sherpa-onnx's Kotlin API leaves out, and contains no third-party code
apart from the toolchain code below.

### Toolchain code in every native library

Each native library carries its own static copy of the Android NDK's C runtime startup objects
(`crtbegin_so.o`, `crtend_so.o`; BSD-2-Clause, [`bionic-NOTICE.txt`](app/src/main/assets/licenses/bionic-NOTICE.txt)).
The three large libraries also carry the LLVM C++ runtime and compiler-rt builtins (Apache-2.0
WITH LLVM-exception, [`LLVM-LICENSE.txt`](app/src/main/assets/licenses/LLVM-LICENSE.txt)).

### AndroidX native helpers

`libandroidx.graphics.path.so` (graphics-path 1.0.1) and `libdatastore_shared_counter.so`
(DataStore 1.2.1) come from AndroidX libraries and are Apache-2.0.

## Java and Kotlin libraries

| Component | Version | Licence | Source |
|---|---|---|---|
| AndroidX: Activity 1.13.0, Annotation 1.10.0, Collection 1.5.0, Compose 1.12.1 (BOM 2026.09.00), Compose Material 3 1.4.0, Material Icons Core 1.7.8, Core 1.19.1, DataStore 1.2.1, Emoji2 1.4.0, Graphics Path 1.0.1, Lifecycle 2.11.0, Media3 1.11.1, Navigation 2.10.2, NavigationEvent 1.1.2, Room 2.8.5, SavedState 1.5.0, SQLite 2.6.2, Window 1.5.0, WorkManager 2.12.0, and support libraries (Arch Core 2.2.0, Concurrent Futures 1.1.0, CustomView PoolingContainer 1.0.0, ExifInterface 1.3.6, ProfileInstaller 1.4.0, Startup 1.1.1, Tracing 1.2.0, VersionedParcelable 1.1.1) | as listed | Apache-2.0 | [androidx/androidx](https://github.com/androidx/androidx), [androidx/media](https://github.com/androidx/media/tree/1.11.1) |
| Kotlin standard library | 2.4.20 | Apache-2.0 | [JetBrains/kotlin](https://github.com/JetBrains/kotlin/tree/v2.4.20) |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.11.0) |
| kotlinx.collections.immutable (copy inside Compose runtime) | 0.3.4 | Apache-2.0 | [Kotlin/kotlinx.collections.immutable](https://github.com/Kotlin/kotlinx.collections.immutable/tree/v0.3.4) |
| Guava (mostly removed by R8) | 33.3.1-android | Apache-2.0 | [google/guava](https://github.com/google/guava/tree/v33.3.1) |
| FlatBuffers for Java (copy inside Emoji2) | 1.12.0 | Apache-2.0 | [google/flatbuffers](https://github.com/google/flatbuffers/tree/v1.12.0) |
| Protocol Buffers Java Lite (copy inside DataStore) | 4.28.2 | BSD-3-Clause | [protocolbuffers/protobuf](https://github.com/protocolbuffers/protobuf/tree/v28.2) |
| sherpa-onnx Kotlin API | 1.13.8 | Apache-2.0 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) |

Media3 includes the Sonic library, Copyright (C) 2010 Bill Cox. The Kotlin standard library
includes code derived from GWT, (C) 2007-08 Google Inc., and from Guava, (C) 2011 The Guava
Authors. kotlinx.coroutines' NOTICE file reads: *"kotlinx.coroutines library. Copyright 2016-2025 JetBrains
s.r.o and contributors."* Okio, kotlinx.serialization, JetBrains annotations and JSpecify are on the
build classpath, but R8 removes all of their code from the APK.

## Fonts

Geist 1.800 and Geist Mono 1.701, Copyright 2024 The Geist Project Authors, under the SIL Open Font
License 1.1 ([`OFL-Geist.txt`](app/src/main/assets/licenses/OFL-Geist.txt)). Source:
[vercel/geist-font](https://github.com/vercel/geist-font).

## Source code for the GPL parts

`libsherpa-onnx-jni.so` contains eSpeak NG, which is GPL-3.0-or-later. Its source code, and the
source of everything built into the native libraries, is at the links above.
[`scripts/fetch-sources.sh`](scripts/fetch-sources.sh) downloads the exact archives and checks them
against pinned hashes:

- sherpa-onnx 1.13.8 and every archive its Android build compiles in (with the hashes its build
  files pin).
- ONNX Runtime 1.28.2, the libraries its build downloads, and the recipe that built it.
- llama.cpp b11308.

Each Longhand release on GitHub has these archives attached. Longhand's own source for a release is
the repository at that release's tag.

## Models (not part of the APK)

The app downloads these during setup, from the addresses in
[`engine/Models.kt`](app/src/main/java/io/github/christiantwu/longhand/engine/Models.kt). They keep
their own licences:

| Model | Licence | Source |
|---|---|---|
| NVIDIA Parakeet TDT 0.6B v2 (int8 ONNX conversion by sherpa-onnx) | CC-BY-4.0 | [nvidia/parakeet-tdt-0.6b-v2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2) |
| NVIDIA Parakeet TDT 0.6B v3 (ONNX conversion by sherpa-onnx, encoder re-quantized by Longhand, see below; only with "25 European languages") | CC-BY-4.0 | [nvidia/parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) |
| Alibaba FunAudioLLM SenseVoice Small (int8 ONNX conversion by sherpa-onnx; only with "Chinese, Japanese and Korean") | [FunASR Model Open Source License Agreement 1.1](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE) | [FunAudioLLM/SenseVoiceSmall](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) |
| NVIDIA Nemotron 3.5 ASR Streaming 0.6B (int8 ONNX conversion by sherpa-onnx with 1120 ms chunks, unmodified, hosted by Longhand, see below; only with "Hindi") | [OpenMDW-1.1](app/src/main/assets/licenses/OpenMDW-1.1.txt) | [nvidia/nemotron-3.5-asr-streaming-0.6b](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) |
| pyannote segmentation 3.0 | MIT | [pyannote/segmentation-3.0](https://huggingface.co/pyannote/segmentation-3.0) |
| NVIDIA NeMo TitaNet-S | Apache-2.0 (NeMo toolkit licence) | [NGC titanet_small](https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small) |
| Silero VAD | MIT | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) |
| Qwen3.5 4B, Q4_0 GGUF by Unsloth | Apache-2.0 | [Qwen/Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B) |

**Parakeet TDT 0.6B v3 is modified.** Its encoder was re-quantized to int8 by Longhand from sherpa-onnx's
full-precision ONNX conversion of NVIDIA's model
([csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3),
commit `1a468a35`): the whole pre-encode (subsampling) stage, its convolutions and output projection, and the
depthwise convolutions are kept in full precision, where
sherpa-onnx's own int8 encoder quantizes them too. The recipe is
[`tools/requantize_parakeet_v3.py`](tools/requantize_parakeet_v3.py). The modified encoder is downloaded
from Longhand's GitHub release
[models-1](https://github.com/christiantwu/longhand/releases/tag/models-1); the decoder, joiner and
tokens are sherpa-onnx's int8 conversion, unmodified, downloaded from
[csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8).
The model is licensed by NVIDIA under the
[Creative Commons Attribution 4.0 International licence](https://creativecommons.org/licenses/by/4.0/),
and is provided as-is, without warranties.

**Nemotron 3.5 ASR Streaming 0.6B is hosted by Longhand, unmodified.** sherpa-onnx publishes its int8 ONNX
conversion of NVIDIA's model only inside one archive,
`sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11.tar.bz2` in its
[asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) release (SHA-256
`adbdd5e9fef87300c37cebfcfc4f1ebe56845c860c8a760af0a1dd65ce9beed3`). Longhand downloads the archive's encoder,
decoder, joiner and tokens, byte for byte, from its own GitHub release
[models-1](https://github.com/christiantwu/longhand/releases/tag/models-1). The model is licensed by NVIDIA under
the [OpenMDW License Agreement 1.1](app/src/main/assets/licenses/OpenMDW-1.1.txt), which asks that a copy of the
licence and the model's notices of origin go with any copy of it, and is provided as-is, without warranties. The app
shows the licence under **Settings → About → Open-source licences**.
