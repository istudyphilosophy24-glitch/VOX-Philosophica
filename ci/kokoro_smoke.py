#!/usr/bin/env python3
"""Real x86-64 Kokoro model inference smoke test for the exact archives packed in the APK.

This tests model integrity and desktop Sherpa-ONNX initialization/generation.
It does NOT certify performance on any specific Android ARM64 device.
"""
from pathlib import Path
import sys
import time
import sherpa_onnx

def check(directory: Path, sid: int):
    required = ["model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data",
                "lexicon-us-en.txt", "lexicon-zh.txt"]
    for filename in required:
        p = directory / filename
        if not p.exists():
            raise RuntimeError(f"{directory.name}: missing {filename}")
    lexicon = ",".join(str(directory / filename)
        for filename in ("lexicon-us-en.txt", "lexicon-zh.txt"))
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=str(directory / "model.int8.onnx"),
                voices=str(directory / "voices.bin"),
                tokens=str(directory / "tokens.txt"),
                data_dir=str(directory / "espeak-ng-data"),
                lexicon=lexicon,
            ),
            num_threads=2,
            provider="cpu",
            debug=False,
        ),
        max_num_sentences=1,
    )
    start = time.monotonic()
    engine = sherpa_onnx.OfflineTts(cfg)
    print(f"{directory.name}: initialized in {time.monotonic()-start:.1f}s", flush=True)
    phrase = "Vox Philosophica reads books offline. This is a voice test."
    audio = engine.generate(phrase, sid=sid, speed=1.0)
    assert audio.sample_rate >= 16000, f"sample rate {audio.sample_rate}"
    assert len(audio.samples) >= audio.sample_rate, "No meaningful audio generated"
    assert max(abs(float(x)) for x in audio.samples) > 0.001, "Silent model output"
    print(
        f"{directory.name}: PASS, sid={sid}, {audio.sample_rate} Hz, "
        f"{len(audio.samples)} samples, {time.monotonic()-start:.1f}s",
        flush=True,
    )

if __name__ == "__main__":
    root = Path(sys.argv[1]).resolve()
    check(root / "kokoro-int8-multi-lang-v1_0", 26)
    check(root / "kokoro-int8-multi-lang-v1_1", 0)
    print("Both Kokoro INT8 models produced non-silent audio.", flush=True)
