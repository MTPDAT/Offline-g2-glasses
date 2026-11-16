#!/usr/bin/env python3
"""Synthesize Portuguese test clips for the app's "Run test WAVs" mode.

Reads test_audio/references.csv and writes one 16 kHz, mono, 16-bit PCM WAV per row
into G2glasses/app/src/main/assets/test/ (gitignored). Uses Microsoft's neural pt-BR
voices through edge-tts (needs internet) and ffmpeg for conversion.

Synthetic speech is clean and evenly paced, so it flatters the recognizer. Use it to
check the pipeline end to end; use real recordings (pt_01.wav, ...) for reported numbers.

    pip install edge-tts      # plus ffmpeg on PATH
    python scripts/make_test_wavs.py [--voice pt-BR-AntonioNeural] [--force]
"""
import argparse
import asyncio
import csv
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
REFS = REPO / "test_audio" / "references.csv"
OUT = REPO / "G2glasses" / "app" / "src" / "main" / "assets" / "test"


async def synth(text: str, voice: str, mp3: Path) -> None:
    import edge_tts
    await edge_tts.Communicate(text, voice).save(str(mp3))


def to_wav(mp3: Path, wav: Path) -> None:
    # 16 kHz mono s16le, plus 0.6 s of trailing silence so Vosk sees an end of utterance.
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-i", str(mp3),
         "-af", "apad=pad_dur=0.6", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
         "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", str(wav)],
        check=True,
    )


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--voice", default="pt-BR-FranciscaNeural")
    ap.add_argument("--force", action="store_true", help="re-create files that already exist")
    a = ap.parse_args()
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg not found on PATH")

    OUT.mkdir(parents=True, exist_ok=True)
    with REFS.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    with tempfile.TemporaryDirectory() as tmp:
        for row in rows:
            wav = OUT / row["file"]
            if wav.exists() and not a.force:
                print(f"skip {wav.name} (exists)")
                continue
            mp3 = Path(tmp) / (wav.stem + ".mp3")
            asyncio.run(synth(row["reference_source"], a.voice, mp3))
            to_wav(mp3, wav)
            print(f"wrote {wav.name}: {row['reference_source']}")


if __name__ == "__main__":
    main()
