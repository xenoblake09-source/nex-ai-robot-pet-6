#!/usr/bin/env python3
"""Download, trim to active vocal burst, normalize, and verify all 20 animal sound assets."""

from __future__ import annotations

import hashlib
import subprocess
import tempfile
import time
import urllib.request
import wave
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np

RAW_DIR = Path("/home/user/nex-ai-robot-pet-6/android/app/src/main/res/raw")
CREDITS_PATH = Path("/home/user/nex-ai-robot-pet-6/docs/AUDIO_CREDITS_AND_LICENSES.md")

# Exact 20 animals required in Part F:
# (slug, display_name, source_url, source_label, license_label)
ANIMAL_SOURCES: List[Tuple[str, str, str, str, str]] = [
    (
        "dog",
        "Dog",
        "https://actions.google.com/sounds/v1/animals/dog_barking.ogg",
        "Google Sound Library (Dog Barking)",
        "Google Developer Sound Library",
    ),
    (
        "cat",
        "Cat",
        "https://assets.scratch.mit.edu/internalapi/asset/83c36d806dc92327b9e7049a565c6bff.wav/get/",
        "MIT Scratch Sound Library (Meow)",
        "CC0 1.0 Public Domain",
    ),
    (
        "cow",
        "Cow",
        "https://assets.scratch.mit.edu/internalapi/asset/7206280bd4444a06d25f19a84dcb56b1.wav/get/",
        "MIT Scratch Sound Library (Moo)",
        "CC0 1.0 Public Domain",
    ),
    (
        "lion",
        "Lion",
        "https://upload.wikimedia.org/wikipedia/commons/7/7d/Lion_raring-sound1TamilNadu178.ogg",
        "Wikimedia Commons - Lion raring-sound1TamilNadu178.ogg",
        "Public Domain",
    ),
    (
        "tiger",
        "Tiger",
        "https://upload.wikimedia.org/wikipedia/commons/c/c3/Tiger_sound_effect.wav",
        "Wikimedia Commons - Tiger sound effect.wav",
        "CC0 1.0 Public Domain",
    ),
    (
        "elephant",
        "Elephant",
        "https://upload.wikimedia.org/wikipedia/commons/4/40/Elephant_voice_-_trumpeting.ogg",
        "Wikimedia Commons - Elephant voice - trumpeting.ogg",
        "CC0 1.0 Public Domain",
    ),
    (
        "horse",
        "Horse",
        "https://assets.scratch.mit.edu/internalapi/asset/f9513bacf2fc665de05a8dd9bcb88117.wav/get/",
        "MIT Scratch Sound Library (Whinny)",
        "CC0 1.0 Public Domain",
    ),
    (
        "sheep",
        "Sheep",
        "https://assets.scratch.mit.edu/internalapi/asset/ca694053020e42704bcf1fc01a70f1c3.wav/get/",
        "MIT Scratch Sound Library (Baa)",
        "CC0 1.0 Public Domain",
    ),
    (
        "goat",
        "Goat",
        "https://upload.wikimedia.org/wikipedia/commons/b/bc/Herd_of_goats_bleating.ogg",
        "Wikimedia Commons - Herd of goats bleating.ogg",
        "Public Domain",
    ),
    (
        "monkey",
        "Monkey",
        "https://assets.scratch.mit.edu/internalapi/asset/fd8543abeeba255072da239223d2d342.wav/get/",
        "MIT Scratch Sound Library (Monkey Chatter)",
        "CC0 1.0 Public Domain",
    ),
    (
        "duck",
        "Duck",
        "https://assets.scratch.mit.edu/internalapi/asset/af5b039e1b05e0ccb12944f648a8884e.wav/get/",
        "MIT Scratch Sound Library (Duck)",
        "CC0 1.0 Public Domain",
    ),
    (
        "chicken",
        "Chicken",
        "https://upload.wikimedia.org/wikipedia/commons/d/d6/Chickens_demanding_food.ogg",
        "Wikimedia Commons - Chickens demanding food.ogg",
        "Public Domain",
    ),
    (
        "rooster",
        "Rooster",
        "https://assets.scratch.mit.edu/internalapi/asset/2e375acae2c7c0d655935a9de14b12f6.wav/get/",
        "MIT Scratch Sound Library (Rooster)",
        "CC0 1.0 Public Domain",
    ),
    (
        "wolf",
        "Wolf",
        "https://assets.scratch.mit.edu/internalapi/asset/5e36d74bb16aa5085b901362788b0fbf.wav/get/",
        "MIT Scratch Sound Library (Wolf Howl)",
        "CC0 1.0 Public Domain",
    ),
    (
        "fox",
        "Fox",
        "https://upload.wikimedia.org/wikipedia/commons/0/0b/Vulpes_vulpes%2C_at_Henclov%C3%A1%2C_Slovakia_XC108315.mp3",
        "Wikimedia Commons / Xeno-canto XC108315 (Vulpes vulpes)",
        "CC BY-SA 4.0",
    ),
    (
        "bear",
        "Bear",
        "https://upload.wikimedia.org/wikipedia/commons/4/41/Yellowstone_sound_library_-_Grizzly_Bear_vocalizations_-_001.mp3",
        "US National Park Service Yellowstone Sound Library (Grizzly Bear)",
        "Public Domain (US Gov)",
    ),
    (
        "snake",
        "Snake",
        "https://actions.google.com/sounds/v1/animals/animal_hiss_and_rattle.ogg",
        "Google Sound Library (Animal Hiss And Rattle)",
        "Google Developer Sound Library",
    ),
    (
        "frog",
        "Frog",
        "https://assets.scratch.mit.edu/internalapi/asset/c6ce0aadb89903a43f76fc20ea57633e.wav/get/",
        "MIT Scratch Sound Library (Croak)",
        "CC0 1.0 Public Domain",
    ),
    (
        "bird",
        "Bird",
        "https://assets.scratch.mit.edu/internalapi/asset/18bd4b634a3f992a16b30344c7d810e0.wav/get/",
        "MIT Scratch Sound Library (Bird)",
        "CC0 1.0 Public Domain",
    ),
    (
        "dolphin",
        "Dolphin",
        "https://upload.wikimedia.org/wikipedia/commons/5/5a/161691_felixblume_dolphin-screaming-underwater-in-caribbean-sea-mexico.wav",
        "Wikimedia Commons - Felix Blume Caribbean Dolphin Recording",
        "CC0 1.0 Public Domain",
    ),
]

TARGET_SR = 22050
MAX_CLIP_SEC = 2.8


def download_source(url: str, dest: Path) -> None:
    headers = {
        "User-Agent": "NEX6EducationalExhibition/1.0 (https://montfortbagaha.org/) Python-urllib/3",
    }
    # For very large WAVs on Wikimedia (like Tiger/Dolphin), cap Range to 4 MB aligned to 16 bytes
    if "wikimedia.org" in url and url.endswith(".wav"):
        headers["Range"] = "bytes=0-4194303"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=25) as resp:
        data = resp.read()
        if url.endswith(".wav") and len(data) > 44:
            trim_len = 44 + ((len(data) - 44) // 8) * 8
            data = data[:trim_len]
        dest.write_bytes(data)


def extract_best_normalized_wav(src_path: Path, out_wav: Path) -> Dict[str, float]:
    """Decode audio via ffmpeg, select highest-energy vocal window up to MAX_CLIP_SEC, normalize & write WAV."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_decoded = Path(tmp.name)

    try:
        subprocess.run(
            [
                "ffmpeg", "-y", "-v", "quiet",
                "-err_detect", "ignore_err",
                "-i", str(src_path),
                "-t", "60",
                "-ac", "1",
                "-ar", str(TARGET_SR),
                "-sample_fmt", "s16",
                str(tmp_decoded),
            ],
            check=True,
        )

        with wave.open(str(tmp_decoded), "rb") as wf:
            raw_frames = wf.readframes(wf.getnframes())
        samples = np.frombuffer(raw_frames, dtype=np.int16).astype(np.float32) / 32768.0
        if len(samples) < int(TARGET_SR * 0.12):
            raise RuntimeError(f"Decoded clip too short ({len(samples)} samples)")

        # Remove DC offset
        samples = samples - float(np.mean(samples))

        max_samples = int(TARGET_SR * MAX_CLIP_SEC)
        if len(samples) > max_samples:
            # Slide a window of max_samples in 0.1s steps to pick the loudest vocal burst
            step = int(TARGET_SR * 0.1)
            best_idx = 0
            best_energy = -1.0
            for start in range(0, len(samples) - max_samples + 1, step):
                chunk = samples[start : start + max_samples]
                energy = float(np.mean(chunk * chunk))
                if energy > best_energy:
                    best_energy = energy
                    best_idx = start
            samples = samples[best_idx : best_idx + max_samples]
        elif len(samples) < int(TARGET_SR * 1.0):
            # If clip is a crisp single call (e.g. chirp / croak / meow), repeat naturally with 0.2s pause
            single_call = samples.copy()
            gap = np.zeros(int(TARGET_SR * 0.20), dtype=np.float32)
            while len(samples) < int(TARGET_SR * 1.05):
                samples = np.concatenate([samples, gap, single_call * 0.93])
            samples = samples[:max_samples]

        # Apply smooth 25ms fade-in and 45ms fade-out
        fade_in = min(int(TARGET_SR * 0.025), len(samples) // 6)
        fade_out = min(int(TARGET_SR * 0.045), len(samples) // 6)
        if fade_in > 1:
            samples[:fade_in] *= np.linspace(0.0, 1.0, fade_in, dtype=np.float32)
        if fade_out > 1:
            samples[-fade_out:] *= np.linspace(1.0, 0.0, fade_out, dtype=np.float32)

        peak = float(np.max(np.abs(samples)))
        if peak < 1e-4:
            raise RuntimeError("Decoded audio appears silent!")

        # Normalize peak to -1.2 dBFS (0.87 full scale); apply gentle soft-knee boost if crest factor is high
        samples = (samples / peak) * 0.87
        if float(np.sqrt(np.mean(samples * samples))) < 0.055:
            samples = np.tanh(samples * 3.0)
            peak2 = float(np.max(np.abs(samples)))
            if peak2 > 1e-4:
                samples = (samples / peak2) * 0.87
        samples = np.clip(samples, -0.98, 0.98)
        pcm16 = (samples * 32767.0).astype(np.int16)

        RAW_DIR.mkdir(parents=True, exist_ok=True)
        with wave.open(str(out_wav), "wb") as out_wf:
            out_wf.setnchannels(1)
            out_wf.setsampwidth(2)
            out_wf.setframerate(TARGET_SR)
            out_wf.writeframes(pcm16.tobytes())

        duration = len(pcm16) / TARGET_SR
        rms = float(np.sqrt(np.mean(samples * samples)))
        final_peak = float(np.max(np.abs(samples)))
        return {"duration_sec": round(duration, 3), "rms": round(rms, 4), "peak": round(final_peak, 4)}
    finally:
        tmp_decoded.unlink(missing_ok=True)


def verify_all_20_assets() -> List[Dict[str, object]]:
    seen_hashes = set()
    records = []
    for idx, (slug, name, url, src_label, lic_label) in enumerate(ANIMAL_SOURCES, start=1):
        wav_path = RAW_DIR / f"animal_{slug}.wav"
        assert wav_path.exists(), f"Missing animal audio file: {wav_path}"
        size_bytes = wav_path.stat().st_size
        assert size_bytes > 15_000, f"Audio file suspiciously small ({size_bytes}B): {wav_path}"

        digest = hashlib.sha256(wav_path.read_bytes()).hexdigest()
        assert digest not in seen_hashes, f"Duplicate audio hash detected for {slug}!"
        seen_hashes.add(digest)

        with wave.open(str(wav_path), "rb") as wf:
            assert wf.getnchannels() == 1
            assert wf.getsampwidth() == 2
            assert wf.getframerate() == TARGET_SR
            nframes = wf.getnframes()
            duration = nframes / TARGET_SR
            assert 0.7 <= duration <= 3.5, f"Unexpected duration {duration:.2f}s for {slug}"
            arr = np.frombuffer(wf.readframes(nframes), dtype=np.int16).astype(np.float32) / 32768.0
            peak = float(np.max(np.abs(arr)))
            rms = float(np.sqrt(np.mean(arr * arr)))
            assert peak > 0.5, f"Peak too quiet ({peak}) for {slug}"
            assert rms > 0.025, f"RMS too low ({rms}) for {slug}"

        records.append(
            {
                "index": idx,
                "slug": slug,
                "name": name,
                "res_name": f"animal_{slug}",
                "size_kb": round(size_bytes / 1024, 1),
                "duration_sec": round(duration, 2),
                "rms": round(rms, 3),
                "peak": round(peak, 3),
                "source": src_label,
                "license": lic_label,
                "url": url,
            }
        )
    assert len(records) == 20
    return records


def main() -> None:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    CREDITS_PATH.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_root = Path(tmpdir)
        for idx, (slug, name, url, src_label, _lic) in enumerate(ANIMAL_SOURCES, start=1):
            ext = ".wav" if ".wav" in url else (".mp3" if ".mp3" in url else ".ogg")
            dl_path = tmp_root / f"raw_{slug}{ext}"
            out_wav = RAW_DIR / f"animal_{slug}.wav"
            if "wikimedia.org" in url:
                time.sleep(0.8)
            download_source(url, dl_path)
            stats = extract_best_normalized_wav(dl_path, out_wav)
            print(
                f"[{idx:02d}/20] {name:10s} -> {out_wav.name:20s} | "
                f"dur={stats['duration_sec']:.2f}s rms={stats['rms']:.3f} peak={stats['peak']:.2f} | {src_label}"
            )

    verified = verify_all_20_assets()

    lines = [
        "# NEX 6.0 — 20 Animal Sound Assets Verification & Credits",
        "",
        "All 20 animal sound files in `android/app/src/main/res/raw/` are real recorded animal vocalizations,",
        "normalized to 22,050 Hz 16-bit mono PCM WAV and verified for header integrity, duration, and non-silent RMS energy.",
        "No copyrighted commercial music tracks are bundled in NEX 6.0.",
        "",
        "| # | Animal | Resource ID | Duration | Size | Source | License |",
        "|---|--------|-------------|----------|------|--------|---------|",
    ]
    for r in verified:
        lines.append(
            f"| {r['index']} | {r['name']} | `R.raw.{r['res_name']}` | "
            f"{r['duration_sec']}s | {r['size_kb']} KB | {r['source']} | {r['license']} |"
        )
    CREDITS_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nALL 20 ANIMAL SOUND ASSETS VERIFIED SUCCESSFULLY! Credits written to {CREDITS_PATH}")


if __name__ == "__main__":
    main()
