#!/usr/bin/env python3
"""Replays Longhand's speaker separation on sample calls and scores it against reference labels.

The steps mirror the app:
  1. decode, and resample to 16 kHz the way engine/Resampler.kt does (Kaiser-windowed sinc)
  2. diarize like engine/SpeakerSeparation.kt (pyannote segmentation 3.0 + TitaNet,
     over-clustered with FastClustering threshold 0.8)
  3. fingerprint and resolve the clusters into people like engine/SpeakerResolver.kt
  4. merge each person's consecutive speech like SegmentLogic.merge

Reference labels live in <samples>/labels.json:
  {"<file>": {"people": ["P1", "P2"], "owner": "P1", "turns": [{"start": 0.3, "end": 8.3, "person": "P1"}, ...]}}
"owner" (optional) is the phone owner. Their voiceprint for a call is built from the owner's
turns in the *other* calls, as the app learns it from calls you've already labelled "Me".

Each call is scored by labelled time: every reference person is matched to the output speaker
that best covers their turns (one speaker per person), and the share of the labelled speech the
output covers that lands on the matched speaker is the accuracy (how much of the labelled speech
the output covers at all is printed too). Calls with three or more people are also scored
cut off just before the last person joins (a transferred call becomes an ordinary two-person
one), to catch spurious extra speakers.

Steps 1-2 are cached per call in <samples>/.cache, keyed by the recording, the models, the
sherpa-onnx version and the code of resample() and diarizer(), so --tune only redoes the cheap parts.
Needs ffmpeg, numpy and sherpa-onnx (the version the app uses, 1.13.8).

  tools/diarization_eval.py                 # score the app's current settings
  tools/diarization_eval.py --tune          # search the thresholds around them
  tools/diarization_eval.py --fixtures      # write inputs/outputs for SpeakerResolverParityTest
"""
import argparse
import dataclasses
import hashlib
import inspect
import itertools
import json
import math
import os
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass

import numpy as np
import sherpa_onnx

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SR = 16000


# ---- 1. decoding and resampling (engine/Resampler.kt) ----

def decode(path):
    info = json.loads(subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate,channels",
         "-of", "json", path], capture_output=True, check=True).stdout)["streams"][0]
    rate, channels = int(info["sample_rate"]), int(info["channels"])
    if channels != 1:
        print(f"  note: {channels} channels; the app separates speakers by channel when they differ, this mixes them")
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "f32le", "-ac", "1", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(pcm, np.float32), rate


def resample(x, in_rate, out_rate=SR, max_phases=4096, beta=8.0):
    """StreamResampler: rational polyphase windowed sinc with zero lag, output length ceil(n * P / Q)."""
    if in_rate == out_rate:
        return x.astype(np.float32)
    g = math.gcd(in_rate, out_rate)
    up, down = out_rate // g, in_rate // g
    ratio = out_rate / in_rate
    cutoff = 0.475 * min(1.0, ratio)
    half = math.ceil(16 * max(1.0, 1 / ratio))
    taps = 2 * half
    phases = min(up, max_phases)
    rows = np.empty((phases, taps), np.float32)
    for p in range(phases):
        u = p / phases + half - 1 - np.arange(taps)
        r = np.clip(u / half, -1, 1)
        row = 2 * cutoff * np.sinc(2 * cutoff * u) * np.i0(beta * np.sqrt(1 - r * r)) / np.i0(beta)
        row[np.abs(u / half) > 1] = 0
        rows[p] = row / row.sum()
    n_out = (len(x) * up + down - 1) // down
    padded = np.concatenate([np.zeros(half - 1), x.astype(np.float64), np.zeros(taps + 1)])
    out = np.empty(n_out, np.float32)
    k = np.arange(taps)
    for start in range(0, n_out, 1 << 18):
        n = np.arange(start, min(n_out, start + (1 << 18)), dtype=np.int64)
        base, phase = (n * down) // up, (n * down) % up
        row = phase if up <= max_phases else (phase * max_phases) // up
        out[start:start + len(n)] = np.einsum("ij,ij->i", padded[base[:, None] + k[None, :]], rows[row])
    return out


# ---- 2. diarization (engine/SpeakerSeparation.kt) ----

def diarizer(models):
    return sherpa_onnx.OfflineSpeakerDiarization(sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=f"{models}/segmentation.onnx"),
            num_threads=8),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=f"{models}/embedding.onnx", num_threads=8),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=0.8),
        min_duration_on=0.3, min_duration_off=0.5))


class Fingerprints:
    """VoiceAnalyzer.fingerprint: the given stretches joined into one clip, embedded and normalised."""

    def __init__(self, models):
        self.extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=f"{models}/embedding.onnx", num_threads=8))
        self.memo = {}

    def __call__(self, key, audio, parts):
        memo_key = (key, tuple(parts))
        if memo_key not in self.memo:
            pieces = []
            for a, b in parts:
                lo = min(max(int(a * SR), 0), len(audio))
                hi = min(max(int(b * SR), lo), len(audio))
                if hi > lo:
                    pieces.append(audio[lo:hi])
            v = None
            if pieces:
                stream = self.extractor.create_stream()
                stream.accept_waveform(sample_rate=SR, waveform=np.concatenate(pieces))
                stream.input_finished()
                if self.extractor.is_ready(stream):
                    v = normalize(np.array(self.extractor.compute(stream), np.float64))
            self.memo[memo_key] = v
        return self.memo[memo_key]


# ---- 3. resolving clusters into people (engine/SpeakerResolver.kt) ----

@dataclass(frozen=True)
class Config:
    anchor_seconds: float = 8.0
    probe_seconds: float = 0.5
    merge_at: float = 0.62
    distinct_below: float = 0.5
    distinct_seconds: float = 3.0
    owner_at: float = 0.5
    owner_margin: float = 0.15
    fingerprint_seconds: float = 15.0
    max_fingerprints: int = 20


MIN_PART_SECONDS = 0.5
MIN_WEIGHT = 0.01


def normalize(v):
    n = math.sqrt(float(np.dot(v, v)))
    return v.copy() if n == 0 else v / n


def cosine(a, b):
    na, nb = float(np.dot(a, a)), float(np.dot(b, b))
    return 0.0 if na == 0 or nb == 0 else float(np.dot(a, b)) / math.sqrt(na * nb)


def clean_parts(spans):
    by = defaultdict(list)
    for s in spans:
        by[s[2]].append(s)
    out = {}
    for cluster, own in by.items():
        others = sorted((o for o in spans if o[2] != cluster), key=lambda o: o[0])
        parts_all = []
        for s in sorted(own, key=lambda o: o[0]):
            parts = [(s[0], s[1])]
            for o in others:
                if o[1] <= s[0] or o[0] >= s[1]:
                    continue
                parts = [p for a, b in parts for p in ((a, min(b, o[0])), (max(a, o[1]), b)) if p[1] > p[0]]
            parts_all += parts
        out[cluster] = parts_all
    return out


def longest_first(parts, limit):
    out, total = [], 0.0
    for a, b in sorted(parts, key=lambda p: (-(p[1] - p[0]), p[0])):
        if total >= limit:
            break
        if b - a < MIN_PART_SECONDS:
            continue
        out.append((a, min(b, a + (limit - total))))
        total += min(b - a, limit - total)
    return out


def usable_parts(spans):
    """Clean pieces long enough to say something about a voice."""
    return {c: [(a, b) for a, b in parts if b - a >= MIN_PART_SECONDS] for c, parts in clean_parts(spans).items()}


def fingerprint_plan(spans, cfg):
    items = [(c, parts, sum(b - a for a, b in parts)) for c, parts in usable_parts(spans).items()]
    items = sorted((t for t in items if t[1] and t[2] >= cfg.probe_seconds), key=lambda t: (-t[2], t[0]))[:cfg.max_fingerprints]
    return {c: longest_first(parts, cfg.fingerprint_seconds) for c, parts, _ in items}


class Group:
    """mean: the members' unit fingerprints averaged by clean seconds, not normalised."""

    def __init__(self, members, seconds, mean):
        self.members, self.seconds, self.mean = members, seconds, mean


def weighted_mean(pairs):
    total = sum(w for _, w in pairs)
    return sum(v * w for v, w in pairs) / total


def merge_while_similar(groups, at):
    """Average linkage: the dot product of two means is the average score between their members."""
    while len(groups) > 1:
        best = None
        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                sim = float(np.dot(groups[i].mean, groups[j].mean))
                if best is None or sim > best[0]:
                    best = (sim, i, j)
        if best[0] < at:
            return
        a, b = groups[best[1]], groups.pop(best[2])
        a.mean = weighted_mean([(a.mean, a.seconds), (b.mean, b.seconds)])
        a.members = a.members + b.members
        a.seconds += b.seconds


def gap(a, b):
    return max(0.0, max(a[0], b[0]) - min(a[1], b[1]))


def fragment_speaker(s, placed, speakers):
    if not placed:
        return 0
    over = [p for p in placed if p[0] < s[1] and s[0] < p[1]]
    if over and speakers > 1:
        talking = max(over, key=lambda p: (min(p[1], s[1]) - max(p[0], s[0]), p[0]))[2]
        others = [p for p in placed if p[2] != talking]
        if others:
            return min(others, key=lambda p: (gap(p, s), p[0]))[2]
    return min(placed, key=lambda p: (gap(p, s), p[0]))[2]


def resolve(spans, fingerprints, owner=None, cfg=Config()):
    if not spans:
        return spans
    clean = {c: sum(b - a for a, b in parts) for c, parts in usable_parts(spans).items()}
    unit = {c: normalize(v) for c, v in fingerprints.items()}

    def weight(c):
        return max(clean.get(c, 0.0), MIN_WEIGHT)

    groups = [Group([c], weight(c), unit[c]) for c in sorted(c for c in unit if clean.get(c, 0.0) >= cfg.anchor_seconds)]
    if owner is not None and unit:
        profile = normalize(owner)
        score = {c: cosine(v, profile) for c, v in unit.items()}
        best = max(score, key=lambda c: (score[c], -c))

        def like(c):
            return c == best or cosine(unit[c], unit[best]) >= cfg.merge_at

        runner_up = max((s for c, s in score.items() if not like(c) and clean.get(c, 0.0) >= cfg.anchor_seconds), default=None)
        top = score[best]
        if top >= cfg.owner_at and (runner_up is None or top - runner_up >= cfg.owner_margin):
            owner_ids = sorted(c for c in score if like(c) and score[c] >= cfg.owner_at)
            groups = [g for g in groups if not any(m in owner_ids for m in g.members)]
            groups.append(Group(list(owner_ids), sum(weight(c) for c in owner_ids),
                                weighted_mean([(unit[c], weight(c)) for c in owner_ids])))

    merge_while_similar(groups, cfg.merge_at)

    placed_ids = {m for g in groups for m in g.members}
    for c in sorted(unit, key=lambda c: (-clean.get(c, 0.0), c)):
        too_short = clean.get(c, 0.0) < min(cfg.distinct_seconds, cfg.anchor_seconds)
        if c in placed_ids or (too_short and groups):
            continue
        if all(cosine(unit[c], g.mean) < cfg.distinct_below for g in groups):
            groups.append(Group([c], weight(c), unit[c]))
            placed_ids.add(c)

    groups.sort(key=lambda g: (-g.seconds, min(g.members)))
    speaker_of = {m: i for i, g in enumerate(groups) for m in g.members}
    for c in sorted(unit):
        if c in speaker_of or not groups:
            continue
        speaker_of[c] = max(range(len(groups)), key=lambda i: (cosine(unit[c], groups[i].mean), -i))
    if not groups:
        return [(s, e, 0) for s, e, _ in spans]

    placed = [(s, e, speaker_of[c]) for s, e, c in spans if c in speaker_of]
    return [(s, e, speaker_of[c]) if c in speaker_of else (s, e, fragment_speaker((s, e), placed, len(groups)))
            for s, e, c in spans]


def merge_turns(spans, max_gap=1.0):
    """SegmentLogic.merge."""
    out = []
    for s in sorted(spans, key=lambda s: s[0]):
        if out and out[-1][2] == s[2] and s[0] - out[-1][1] <= max_gap:
            out[-1] = (out[-1][0], max(out[-1][1], s[1]), s[2])
        else:
            out.append(s)
    return out


# ---- scoring ----

def overlap(a0, a1, b0, b1):
    return max(0.0, min(a1, b1) - max(a0, b0))


def union(spans):
    out = []
    for s, e in sorted(spans):
        if out and s <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], e))
        else:
            out.append((s, e))
    return out


def score(turns, ref):
    """
    Accuracy by labelled time under the best person-to-speaker matching (of the labelled speech
    the output covers), the matching, seconds per (person, speaker), and coverage: the share of
    labelled speech that some output turn covers.
    """
    speakers = sorted({t[2] for t in turns})
    people = sorted({r["person"] for r in ref})
    own = {k: union([(s, e) for s, e, kk in turns if kk == k]) for k in speakers}
    anyone = union([(s, e) for s, e, _ in turns])
    cover = defaultdict(float)  # (person, speaker) -> seconds
    covered = 0.0
    for r in ref:
        for k in speakers:
            cover[r["person"], k] += sum(overlap(r["start"], r["end"], s, e) for s, e in own[k])
        covered += sum(overlap(r["start"], r["end"], s, e) for s, e in anyone)
    best, best_map = -1.0, {}
    slots = speakers + [None] * len(people)
    for perm in itertools.permutations(slots, len(people)):
        if any(k is not None and perm.count(k) > 1 for k in perm):
            continue
        total = sum(cover[p, k] for p, k in zip(people, perm) if k is not None)
        if total > best:
            best, best_map = total, dict(zip(people, perm))
    labelled = sum(r["end"] - r["start"] for r in ref)
    return (best / covered if covered else 0.0), best_map, cover, (covered / labelled if labelled else 0.0)


# ---- running ----

def steps_digest(models):
    """What steps 1-2 depend on besides the recording, so a change there can't reuse stale spans."""
    model_files = [(m, os.path.getsize(f"{models}/{m}"), os.path.getmtime(f"{models}/{m}"))
                   for m in ("segmentation.onnx", "embedding.onnx")]
    text = inspect.getsource(resample) + inspect.getsource(diarizer) + sherpa_onnx.__version__ + repr(model_files)
    return hashlib.sha1(text.encode()).hexdigest()[:8]


def load_call(name, samples, cache, models, cut=None):
    path = os.path.join(samples, name)
    with open(path, "rb") as f:
        digest = hashlib.sha1(f.read()).hexdigest()[:12]
    key = f"{name}.{digest}.{steps_digest(models)}.{'full' if cut is None else f'cut{cut:.2f}'}"
    audio_file = os.path.join(cache, key + ".f32")
    spans_file = os.path.join(cache, key + ".spans.json")
    if os.path.exists(audio_file) and os.path.exists(spans_file):
        audio = np.fromfile(audio_file, np.float32)
        spans = [tuple(s) for s in json.load(open(spans_file))]
        return key, audio, spans
    x, rate = decode(path)
    audio = resample(x, rate)
    if cut is not None:
        audio = audio[:int(cut * SR)]
    segs = diarizer(models).process(audio).sort_by_start_time()
    spans = [(round(s.start, 3), round(s.end, 3), s.speaker) for s in segs]
    os.makedirs(cache, exist_ok=True)
    audio.tofile(audio_file)
    json.dump(spans, open(spans_file, "w"))
    return key, audio, spans


def owner_voiceprint(name, labels, calls, fps, max_seconds=30.0):
    """
    VoiceProfile built from the owner's turns in the other calls, each the way VoiceAnalyzer.voices
    makes a speaker's voice: their longest stretches that nobody talks over, up to 30 s.
    """
    prints = []
    for other, lab in labels.items():
        if other == name or not lab.get("owner") or other not in calls:
            continue
        key, audio, _ = calls[other]
        turns = [(r["start"], r["end"], r["person"]) for r in lab["turns"]]
        parts = longest_first(clean_parts(turns).get(lab["owner"], []), max_seconds)
        if sum(b - a for a, b in parts) >= 1.5:
            v = fps(key, audio, parts)
            if v is not None:
                prints.append(v)
    return normalize(np.mean(prints, axis=0)) if prints else None


def cases(labels, samples, cache, models):
    """(case name, cache key, audio, spans, reference turns, owner person, base call) for each call and cut."""
    calls = {}
    out = []
    for name, lab in labels.items():
        if not os.path.exists(os.path.join(samples, name)):
            print(f"missing {name}, skipped", file=sys.stderr)
            continue
        calls[name] = load_call(name, samples, cache, models)
        key, audio, spans = calls[name]
        out.append((name, key, audio, spans, lab["turns"], lab.get("owner"), name))
        if len(lab["people"]) >= 3:
            first = {p: min(r["start"] for r in lab["turns"] if r["person"] == p) for p in lab["people"]}
            last = max(first, key=first.get)
            cut = first[last] - 1.0
            ckey, caudio, cspans = load_call(name, samples, cache, models, cut=cut)
            ref = [r for r in lab["turns"] if r["end"] <= cut]
            out.append((f"{name} (before {last} joins)", ckey, caudio, cspans, ref, lab.get("owner"), name))
    return out, calls


# A speaker with less of its time in labelled speech than this is some other sound (hold music,
# say). Real speakers are 50-80% labelled on the samples (unclear turns aren't); music about 10%.
OTHER_SOUND_SHARE = 0.25


def evaluate(case_list, labels, calls, fps, cfg, use_owner):
    results = []
    for title, key, audio, spans, ref, owner_person, base in case_list:
        plan = fingerprint_plan(spans, cfg)
        prints = {c: v for c, parts in plan.items() if (v := fps(key, audio, parts)) is not None}
        owner = owner_voiceprint(base, labels, calls, fps) if use_owner and owner_person else None
        turns = merge_turns(resolve(spans, prints, owner, cfg))
        acc, mapping, cover, coverage = score(turns, ref)
        people = len({r["person"] for r in ref})
        seconds = {k: sum(e - s for s, e, kk in turns if kk == k) for k in sorted({t[2] for t in turns})}
        # Speakers heard in labelled speech; the others are hold music, recorded announcements and the like.
        heard = {k for k in seconds if sum(v for (p, kk), v in cover.items() if kk == k) >= OTHER_SOUND_SHARE * seconds[k]}
        found = len(heard)
        results.append(dict(title=title, acc=acc, coverage=coverage, people=people, found=found, mapping=mapping,
                            cover=cover, other=len(seconds) - found, clusters=len({s[2] for s in spans}),
                            fingerprinted=len(prints), owner=owner is not None, seconds=seconds))
    return results


def report(results):
    for r in results:
        secs = ", ".join(f"{s:.0f}s" for s in r["seconds"].values())
        flag = "" if r["found"] == r["people"] else "   <-- wrong number of people"
        other = f" + {r['other']} other sound" if r["other"] else ""
        print(f"  {r['title'][:44]:44s} {'owner' if r['owner'] else '     '}  {r['clusters']:2d} clusters, "
              f"{r['fingerprinted']:2d} fp -> {r['found']} speakers{other} ({secs}); {r['people']} people; "
              f"{100 * r['coverage']:.1f}% of labelled speech covered, {100 * r['acc']:5.1f}% of that right{flag}")


def summary(results):
    right = sum(r["found"] == r["people"] for r in results)
    return right, min(r["acc"] for r in results), sum(r["acc"] for r in results) / len(results)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--samples", default=os.path.join(ROOT, "sample_recordings"))
    ap.add_argument("--models", default=os.path.join(ROOT, "models"))
    ap.add_argument("--tune", action="store_true", help="search the resolver thresholds")
    ap.add_argument("--fixtures", action="store_true", help="write parity-test fixtures into <samples>/.cache/fixtures")
    args = ap.parse_args()
    cache = os.path.join(args.samples, ".cache")
    labels = json.load(open(os.path.join(args.samples, "labels.json")))
    case_list, calls = cases(labels, args.samples, cache, args.models)
    fps = Fingerprints(args.models)

    base = Config()
    for use_owner in (False, True):
        print(f"\n{'with' if use_owner else 'without'} the owner's voiceprint, app settings:")
        report(evaluate(case_list, labels, calls, fps, base, use_owner))

    if args.fixtures:
        out_dir = os.path.join(cache, "fixtures")
        os.makedirs(out_dir, exist_ok=True)
        for n, (title, key, audio, spans, ref, owner_person, call) in enumerate(case_list):
            plan = fingerprint_plan(spans, base)
            prints = {c: v for c, parts in plan.items() if (v := fps(key, audio, parts)) is not None}
            for use_owner in (False, True):
                owner = owner_voiceprint(call, labels, calls, fps) if use_owner and owner_person else None
                if use_owner and owner is None:
                    continue
                expected = resolve(spans, prints, owner, base)
                with open(os.path.join(out_dir, f"case{n}{'-owner' if use_owner else ''}.txt"), "w") as f:
                    f.write("".join(f"span {s!r} {e!r} {c}\n" for s, e, c in spans))
                    f.write("".join(f"plan {c} " + " ".join(f"{a!r} {b!r}" for a, b in parts) + "\n" for c, parts in plan.items()))
                    f.write("".join(f"fp {c} " + " ".join(repr(float(x)) for x in v) + "\n" for c, v in prints.items()))
                    if owner is not None:
                        f.write("owner " + " ".join(repr(float(x)) for x in owner) + "\n")
                    f.write("expect " + " ".join(str(k) for _, _, k in expected) + "\n")
        print(f"\nfixtures written to {out_dir}")

    if args.tune:
        grid = dict(anchor_seconds=[5.0, 8.0, 12.0], merge_at=[0.5, 0.52, 0.55, 0.6, 0.62, 0.65, 0.7, 0.72, 0.75],
                    distinct_below=[0.4, 0.45, 0.5, 0.55], distinct_seconds=[2.0, 3.0, 5.0], probe_seconds=[0.5, 1.0, 1.5])
        rows = []
        for values in itertools.product(*grid.values()):
            cfg = dataclasses.replace(base, **dict(zip(grid, values)))
            if cfg.distinct_below > cfg.merge_at:
                continue
            res = evaluate(case_list, labels, calls, fps, cfg, False) + evaluate(case_list, labels, calls, fps, cfg, True)
            rows.append((summary(res), dict(zip(grid, values))))
        rows.sort(key=lambda r: (-r[0][0], -r[0][1], -r[0][2]))
        total = 2 * len(case_list)
        print(f"\nbest of {len(rows)} settings (right speaker count of {total}, worst and mean accuracy):")
        for (right, worst, mean), values in rows[:15]:
            print(f"  {right}/{total}  worst {100 * worst:5.1f}%  mean {100 * mean:5.1f}%  {values}")
        print("\none setting at a time from the app's:")
        for name, options in grid.items():
            for v in options:
                cfg = dataclasses.replace(base, **{name: v})
                res = evaluate(case_list, labels, calls, fps, cfg, False) + evaluate(case_list, labels, calls, fps, cfg, True)
                right, worst, mean = summary(res)
                mark = " (app)" if getattr(base, name) == v else ""
                print(f"  {name:17s} {v:5}: {right}/{total}  worst {100 * worst:5.1f}%  mean {100 * mean:5.1f}%{mark}")


if __name__ == "__main__":
    main()
