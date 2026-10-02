#!/usr/bin/env python3
"""Rebuilds the Parakeet TDT 0.6B v3 encoder that Longhand downloads for the 25 European languages.

sherpa-onnx's int8 export of Parakeet v3 quantizes every MatMul and Conv in the encoder, including the
pre-encode (subsampling) stage and the depthwise convolutions. Quantizing those costs a
lot of accuracy, most of all on phone audio. This script quantizes sherpa-onnx's fp32 export the same way
(ONNX Runtime dynamic quantization, QUInt8 weights) but leaves everything under /pre_encode/ (its convolutions and output projection) and the depthwise
convolutions in full precision. On
FLEURS, across all 25 languages, the word error rate fell from 18.1% to 11.9% on wideband audio and from
34.0% to 14.9% on audio coded like the Phone app's default recordings (8 kHz AAC), within about 2 points of
the fp32 encoder.

With onnxruntime 1.30.0 and onnx 1.23.1 the output is bit-identical to the file Longhand downloads (the
size and SHA-256 in engine/Models.kt). It downloads 2.5 GB, and quantizing peaks at about 7.5 GB of RAM.

  pip install onnxruntime==1.30.0 onnx==1.23.1
  tools/requantize_parakeet_v3.py               # works in build/requantize-parakeet-v3
  tools/requantize_parakeet_v3.py --work DIR    # keeps the fp32 download and the output in DIR

The output, parakeet-tdt-0.6b-v3-encoder.int8.onnx, is published as an asset of the GitHub release
"models-1". Parakeet TDT 0.6B v3 is NVIDIA's, under CC-BY-4.0: the release notes must credit NVIDIA, link
the licence and the original model, and say the encoder was re-quantized by Longhand.
"""
import argparse
import hashlib
import logging
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# sherpa-onnx's fp32 export, pinned to the commit the hosted file was made from. encoder.onnx keeps its
# weights in encoder.weights, which must sit beside it under that name.
SOURCE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3/resolve/1a468a35cbba69418f126de829e75261dea4a4e4"
FP32 = [
    ("encoder.onnx", 41_766_257, "3eed7ce424bf8339ad09233533c687e2dbd07e74ccf5027b5e7344019ea373b0"),
    ("encoder.weights", 2_435_420_160, "3af3f51af5f2d01dbbf5af47d42c7962a2c205f11004254bb4f2b979862f39a8"),
]

OUTPUT = "parakeet-tdt-0.6b-v3-encoder.int8.onnx"
# engine/Models.kt, Set.MULTILINGUAL.
EXPECTED_SIZE = 665_796_726
EXPECTED_SHA256 = "013290f8001e0434a33bfc1f4ea2a9039107a878d894a51a212c48802b58efac"
EXPECTED_EXCLUDED = 457
VERSIONS = {"onnxruntime": "1.30.0", "onnx": "1.23.1"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, dest, size, digest):
    """Downloads url to dest, resuming a partial download, and checks its size and SHA-256."""
    if os.path.exists(dest) and os.path.getsize(dest) == size:
        if sha256(dest) == digest:
            print(f"  {os.path.basename(dest)}: already downloaded")
            return
        os.remove(dest)
    part = dest + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    if have < size:
        req = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
        with urllib.request.urlopen(req) as r:
            if have and r.status != 206:
                have = 0  # the server ignored the range; start over
            with open(part, "ab" if have else "wb") as out:
                done, shown = have, -1
                while chunk := r.read(1 << 20):
                    out.write(chunk)
                    done += len(chunk)
                    pct = done * 100 // size
                    if pct != shown and pct % 5 == 0:
                        shown = pct
                        print(f"  {os.path.basename(dest)}: {done / 1e6:,.0f} / {size / 1e6:,.0f} MB", flush=True)
    if os.path.getsize(part) != size or sha256(part) != digest:
        os.remove(part)
        sys.exit(f"{os.path.basename(dest)}: size or SHA-256 does not match; run again to download it afresh")
    os.replace(part, dest)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=os.path.join(ROOT, "build", "requantize-parakeet-v3"),
                    help="where the fp32 encoder is downloaded and the output written")
    args = ap.parse_args()

    import onnx
    import onnxruntime
    from onnxruntime.quantization import QuantType, quantize_dynamic

    found = {"onnxruntime": onnxruntime.__version__, "onnx": onnx.__version__}
    for name, want in VERSIONS.items():
        if found[name] != want:
            print(f"warning: {name} {found[name]} is installed; the hosted file was made with {want}, "
                  "and another version may give a different file", file=sys.stderr)

    os.makedirs(args.work, exist_ok=True)
    print("fp32 encoder from", SOURCE)
    for name, size, digest in FP32:
        fetch(f"{SOURCE}/{name}", os.path.join(args.work, name), size, digest)

    fp32 = os.path.join(args.work, "encoder.onnx")
    out = os.path.join(args.work, OUTPUT)
    # The pre-encode convolutions (and everything else under /pre_encode/) and the depthwise convolutions
    # stay in full precision; the other MatMuls and the pointwise convolutions become int8.
    graph = onnx.load(fp32, load_external_data=False).graph
    exclude = [n.name for n in graph.node if n.name.startswith("/pre_encode/") or "depthwise_conv" in n.name]
    print(f"onnxruntime {found['onnxruntime']}, onnx {found['onnx']}: "
          f"quantizing, {len(exclude)} nodes kept in full precision (expected {EXPECTED_EXCLUDED})", flush=True)
    # ONNX Runtime warns about every tensor it can't quantize, such as integer shapes; they're meant to stay.
    logging.getLogger().setLevel(logging.ERROR)
    quantize_dynamic(fp32, out, weight_type=QuantType.QUInt8, nodes_to_exclude=exclude)

    size, digest = os.path.getsize(out), sha256(out)
    print(out)
    print(f"  size    {size:,} bytes")
    print(f"  sha256  {digest}")
    if (size, digest) == (EXPECTED_SIZE, EXPECTED_SHA256):
        print("Matches the encoder in engine/Models.kt.")
    else:
        sys.exit(f"Differs from engine/Models.kt ({EXPECTED_SIZE:,} bytes, sha256 {EXPECTED_SHA256}).")


if __name__ == "__main__":
    main()
