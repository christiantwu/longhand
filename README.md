<p align="center">
  <img src="design/icon/ink-wave.svg" width="112" alt="Longhand logo">
</p>

<h1 align="center">Longhand</h1>

<p align="center"><em>Get it in writing.</em></p>

<p align="center"><a href="https://github.com/christiantwu/longhand/releases/latest"><b>Download the latest APK</b></a></p>

Longhand turns your recorded phone calls into transcripts you can read, search and share:
who said what, when, and a short summary of what was agreed. It's made for GrapheneOS,
whose Phone app can record calls, and it does all its work on the phone. There's no account
and no server, and nothing is uploaded.

<p align="center">
  <img src="docs/screenshots/calls.png" width="260" alt="The calls list: each call with the caller's name, a topic line, the time and the length">
  <img src="docs/screenshots/transcript.png" width="260" alt="A transcript: a summary and follow-ups, then each turn with its time and speaker">
  <img src="docs/screenshots/transcript-dark.png" width="260" alt="The same transcript in the dark theme">
  <br><sub>The calls in these screenshots are made up.</sub>
</p>

## What it does

- **Transcribes calls automatically,** about a minute after each one ends, or only while
  charging if you prefer.
- **Shows who said what.** Speakers are told apart by their voices, and every turn has a
  timestamp. Mark your own voice once and Longhand labels you "You" in every call.
- **Names the caller** from your call log and contacts, if you allow it.
- **Summarizes each call** with a topic line, a short summary and any follow-ups, written
  by a small language model on the phone. Summaries are optional.
- **Plays any line.** Tap a line to hear that moment of the recording.
- **Search and share.** Search by name, topic or words. Share a transcript as text or
  Markdown, or share the recording itself.
- **Languages:** English, 25 European languages, or Chinese, Japanese and Korean.

## Privacy

- Speech recognition and summaries run on the phone's processor. Once the models are
  downloaded, you can turn off Longhand's Network permission.
- Longhand never records calls. It only reads the folder you choose.
- The Phone, call log and contacts permissions are optional. They let Longhand notice
  when a call ends and name the caller.
- Transcripts stay in the app's private storage. They're included in backups you turn on
  yourself, such as Seedvault.
- To tell speakers apart, Longhand stores a voice fingerprint for each speaker in a call, and
  one of your own voice if you mark it. They're stored and backed up like the transcripts.

## Requirements

- GrapheneOS, with call recording turned on in the Phone app. Longhand can watch any
  folder, so other Android 12+ phones that save call recordings to a folder may work too,
  but that's untested.
- A 64-bit ARM phone.
- Storage for the models: 0.3–0.7 GB for transcription, plus 2.6 GB for summaries.
- For the best accuracy, record calls as WAV: in the Phone app's settings, turn on
  "Use call recording V2 (experimental)", then choose WAV as the recording format.
  Compressed recordings are transcribed noticeably less accurately.

## Install

Download `longhand-<version>.apk` from the
[latest release](https://github.com/christiantwu/longhand/releases/latest). Release APKs are
signed with this certificate (SHA-256):

    10:33:66:AD:3D:78:A9:AC:CC:71:AC:03:41:B4:88:4F:6D:17:31:18:CF:7D:BA:7A:43:44:5C:CC:AF:07:6E:27

Recording calls may need the other person's consent where you live. Transcripts and
summaries can contain mistakes, so check anything important against the recording.

## Models

- **Speech-to-text:** NVIDIA Parakeet TDT 0.6B v2 (int8) for English, or, chosen in Settings,
  Parakeet TDT 0.6B v3 (int8) for 25 European languages, detected per call: Bulgarian, Croatian,
  Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Italian,
  Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, Slovenian, Spanish,
  Swedish and Ukrainian. Or SenseVoice Small (int8) for Chinese (Mandarin and Cantonese),
  Japanese, Korean and English, also detected per call. Only the chosen one is kept; while a new
  choice downloads, the old one keeps transcribing.
- **Who spoke when:** pyannote segmentation 3.0 + NeMo TitaNet speaker embeddings
- **Pause detection:** Silero VAD
- **Speech runtime:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 (ONNX Runtime, CPU)
- **Summaries:** Qwen 3.5 4B (Q4_0 GGUF, Apache 2.0) run by
  [llama.cpp](https://github.com/ggml-org/llama.cpp) b11308, compiled into the app

The models are downloaded from the setup screen: the speech models for the chosen language
(~710 MB, or ~290 MB for Chinese, Japanese and Korean; required) and the summary model (~2.6 GB,
optional). Choosing another language later in Settings downloads its recognizer (240–670 MB) and
then deletes the old one. Downloads wait for Wi-Fi (an unmetered connection) unless you
choose to go ahead on mobile data or a metered network. Summaries follow an English prompt, so a
call in another language may still get its summary in English.
After that you can turn off the app's Network permission in GrapheneOS.

## How it works

1. **Finding new recordings.** `CallEndedReceiver` hears the phone go idle after a connected call.
   This needs the Phone permission (READ_PHONE_STATE), which Android describes as "make and
   manage phone calls"; Longhand only reads the call state. With call log access, Android sends
   each change a second time with the number attached; Longhand ignores that copy.
   `AfterCallWorker` (an expedited job) then waits 75 s and checks the folder; another call
   restarts the wait. A periodic check also runs every 15 minutes and whenever the app opens.
   Files count as finished once they've been unchanged for a minute, so a call in progress
   isn't transcribed half-written. With the Phone permission, heavy work never starts during a call.
   - **Caller lookup:** with call log access, each recording is matched to the call that ended
     just before the file was written (`CallMatcher`). The number is then looked up in
     Contacts. Without call log access it falls back to a phone number in the file name. In a
     transcript, **⋮ → Who was this call with?** picks the contact by hand.
2. `TranscribeWorker` processes the queue, either right after each call (the default) or only
   while charging (a setting). On battery, automatic runs handle the last day's calls; older
   recordings, such as an imported backlog, wait for the charger unless you tap Transcribe now.
   Work that waits for the charger starts at the next folder check after you plug in, within
   about 15 minutes. When an update changes how speakers are found, every earlier transcript is
   redone once on the charger, after new calls. The old transcript stays until its replacement
   is ready, and redos don't send a notification.
   It runs as a foreground job with a progress notification, in two phases so the two model
   sets are never in memory together:
   1. **Transcribe** each pending recording (`TranscriptionEngine`):
      1. Decode to 16 kHz. Telephone audio (8 kHz) is upsampled with a windowed-sinc filter;
         linear interpolation left images above 4 kHz that hid speaker changes.
      2. Diarize (who spoke when), deliberately split too finely (`SpeakerResolver`). Clusters
         with enough clean speech get a voice fingerprint, and those that sound alike merge
         into one person. A short cluster joins the closest voice, or becomes a speaker of its
         own when it sounds unlike everyone. Fragments too short to fingerprint go to the voice
         around them. The number of people isn't fixed, so a transferred call can have three.
         Speakers are numbered in the order they first speak.
      3. Merge each person's consecutive speech into one turn.
      4. Cut turns longer than 25 s in the middle of pauses, keeping all the audio.
      5. Recognise the text.

      Each speaker's voice fingerprint is stored, made from speech nobody talks over. If a
      stereo recording has each person on
      their own channel, the channel is used as the speaker instead of diarization.
   2. **Summarize** each new transcript (`Summarizer`). The model writes a topic, a summary and
      follow-ups. A GBNF grammar forces the exact JSON shape.
3. **Recognising "You":** tap a speaker's name in a transcript and choose **Me**. That voice
   is averaged into `voiceprint-2.bin`, and `VoiceMatchWorker` then labels you in every other
   transcript made by this version, and in older ones once they're redone (cosine similarity
   ≥ 0.5, with a 0.15 margin over the other voices). On a two-person call, the other voice gets
   the contact's name. Speaker separation uses the voiceprint too: the cluster that sounds most
   like you, by the same margin, stays a speaker of its own however little you say, as long as
   one stretch of half a second is clear of other voices. Transcripts made before 0.5.0 may have
   one speaker for two people, so their voices are never learned. Choosing **Me** there labels
   that call only until its redo, which renumbers the speakers.
4. Transcripts are stored in Room (app-private storage). They can be searched by name, topic or
   words, and shared or saved as `.md` or `.txt`. Tap a line to play the audio from that point.
   **Share → Share audio** sends the recording file itself, even before it's transcribed or if
   transcribing failed. Longhand makes no copy of its own: the app you pick can read only that
   one file, and only the file is sent, with no title or summary.

The finished-call notification reads like "Call with Dana Whitfield regarding lake cabin and
car rental".

### Choosing the summary model

The prompt and grammar in `engine/Summarizer.kt` were evaluated with llama.cpp on the desktop
against six sample calls. The samples were supplier, insurance adjuster, new customer, family,
sales and multi-topic calls. The models compared were Qwen 3.5 2B and 4B, Gemma 4 E2B, and the
community Qwen 3.8 2B/4B distills.

Qwen 3.5 4B gave the most specific topic lines with accurate facts. Gemma 4 E2B is the
faster runner-up. Q4_0 is used because llama.cpp repacks it for fast ARM matrix maths; its
quality matched Q4_K_M on the samples.

### Tuning speaker separation

`tools/diarization_eval.py` replays speaker separation on desktop: the same models, resampler
and `SpeakerResolver` rules as the app. It scores the result against reference labels of who
said what. It needs ffmpeg, numpy and sherpa-onnx 1.13.8. Recordings and labels go in
`sample_recordings/`, which git ignores; real calls never belong in the repository.

The thresholds in `SpeakerResolver` were tuned on three real calls (two people, and two
transferred calls with three). Each was also scored cut off before the transfer, to give
two-person cases. The old pipeline put 58% of the labelled speech on the wrong speaker in the
worst call; the current one gets 96–100% right in each, with the right number of people.
`--fixtures` writes the harness's inputs and decisions for `SpeakerResolverParityTest`, which
checks that the Kotlin code decides the same way.

## Design

`design/icon/` holds the icon generator. `make_icon.py` writes the adaptive icon layers,
the themed (monochrome) layer, the notification icon and the in-app logo from the geometry
in `icon_concepts.py`. Edit the Python, not the generated XML. The UI style is "Editorial
Material": Material 3 components and Material You colours from the wallpaper, set in Geist and
Geist Mono (bundled under the SIL Open Font License; the licence text ships in
`app/src/main/assets/licenses/OFL-Geist.txt`), with mono captions and the icon's
coral-violet-sky gradient as a thin rule under titles. `ui/Theme.kt` and `ui/Components.kt` hold
the shared pieces.

## Building

The requirements are:
- the Android SDK, with **NDK 30.0.16248370** and **CMake 4.1.2** for llama.cpp
- JDK 17 or newer (tested with 25 and 27)

The first native build downloads the pinned llama.cpp release (hash-checked).

```sh
# one-time: tell Gradle where the SDK is (Android Studio does this for you)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# one-time: the sherpa-onnx library (not committed, ~50 MB)
curl -Lo app/libs/sherpa-onnx-1.13.8.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar

./gradlew testDebugUnitTest         # unit tests
./gradlew installDebug              # build + install on a connected phone
./gradlew assembleRelease           # release APK (see "Release signing")
./gradlew installDebug -Pemulator   # also include x86_64 native libs, for the emulator
```

Native code is always compiled with release optimizations, even in debug builds, because
llama.cpp is unusably slow without them.

### Release signing

Release builds are signed with the key described in `keystore.properties`. This file is never
committed. Without it, `assembleRelease` still works but signs with the debug key, and that
APK can't update a phone that has the real-key build installed. The file looks like this:

```properties
storeFile=/path/to/release-key.jks
storePassword=...
keyAlias=release
keyPassword=...
```

**Back up the `.jks` file and its password.** Android only accepts an update signed with the
same key. Without it, you would have to uninstall the app, which deletes all transcripts.

### Skipping the in-app model download during development

With a **debug** build installed, copy the models into the app's private storage:

```sh
adb push models /data/local/tmp/ && adb shell chmod -R a+rX /data/local/tmp/models
adb shell run-as io.github.christiantwu.longhand cp -r /data/local/tmp/models files/
adb shell rm -r /data/local/tmp/models
```

The local `models/` directory mirrors the layout in `engine/Models.kt`:
- `parakeet/{encoder,decoder,joiner}.int8.onnx` and `parakeet/tokens.txt` (English), the same
  files in `parakeet-v3/` (then choose "25 European languages" under Settings → Transcription
  language), or `sensevoice/model.int8.onnx` and `sensevoice/tokens.txt` (then choose "Chinese,
  Japanese and Korean")
- `segmentation.onnx`, `embedding.onnx` and `silero_vad.onnx`
- `llm/qwen3.5-4b-q4_0.gguf` for summaries

Release builds can't use `run-as`; they download the models in the app.

## Debugging

```sh
adb logcat -s Longhand           # scans, model load times, real-time factor, summary topics
```

If a native library crashes on GrapheneOS, enable **Exploit protection compatibility mode**
for the app (App info → Exploit protection). GrapheneOS's hardened memory allocator can
expose memory bugs in native code.

## Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`, commit, and tag the commit
   `v<version>`. With `git status` clean on that tag, the tag is the source of the APK.
2. Run `./gradlew assembleRelease` with the release key configured, and copy
   `app/build/outputs/apk/release/app-release.apk` to `dist/longhand-<version>.apk`.
3. Run `scripts/fetch-sources.sh`. It downloads the source archives of the native libraries into
   `dist/sources/` and checks their hashes. Bundle them into one file, so the APK stays easy to
   find on the release page:
   `tar -cf dist/longhand-<version>-native-sources.tar -C dist sources`.
4. Push the tag and create the GitHub release from it, with the APK and the sources bundle. The
   prebuilt sherpa-onnx library contains eSpeak NG (GPL-3.0-or-later), whose licence requires its
   source code to be offered alongside the binary. Put the signing
   certificate's SHA-256 fingerprint in the notes:
   `apksigner verify --print-certs dist/longhand-<version>.apk`.

## License

Copyright © 2026 Christian Wu.

Longhand is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

`SPDX-License-Identifier: GPL-3.0-or-later`

The app is built on open-source libraries under their own licences, mostly Apache-2.0 and MIT. The
prebuilt sherpa-onnx library includes eSpeak NG, which is GPL-3.0-or-later.
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists every component, with its version, licence
and source; the app shows the same list under **Settings → About → Open-source licences**. The
models are not part of the app or this repository. They keep their own licences, which are listed
there too.
