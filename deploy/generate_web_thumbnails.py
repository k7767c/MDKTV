"""Generate the six fnOS home PNG previews without changing original images.

Development-only dependency: Pillow==12.3.0, already available in this workspace.
Do not add Pillow to bridge runtime requirements. No network or installer is used.
Encoding: LANCZOS resize, PNG optimize=True/compress_level=9, no metadata.
Use the default destination to generate bridge/web-assets/images/thumbs.
Original images remain the full-resolution fallback; the stage preview is for
the home-sized media panel, not fullscreen singing.

    python deploy/generate_web_thumbnails.py
    python deploy/generate_web_thumbnails.py --output-dir artifacts/fnos/startup-optimization/reproduced

The pinned input and output hashes deliberately fail closed if artwork or the
encoder changes. Review and benchmark such changes before updating these pins.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import zlib
from pathlib import Path

try:
    from PIL import Image, __version__ as pillow_version
except ImportError as exc:
    raise SystemExit("Development Pillow==12.3.0 is required; no package is installed automatically.") from exc


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "bridge/web-assets/images"
DEFAULT_OUTPUT = SOURCE / "thumbs"
PILLOW_VERSION = "12.3.0"
INPUT_SHA256 = {
    "pc_home_crown.png": "910cedc97ea016f646c9a21a9216778f6cb2c265a008966628fff69cf0b75d5f",
    "pc_home_headphones.png": "10d36af4537a138d75dff8cc06a211276d86eae20ec6fe86ee91b7e28db2cb85",
    "pc_home_heart.png": "6937abcf5c524a22167e09f5c2ec7b3701ec82d335493e9ef0dd56ad526cf16b",
    "pc_home_music.png": "f5c2016f5b01ce788066dccc497b976a0a29ce45931ae31026d010001d76961a",
    "pc_home_queue.png": "68db520fc8d44c198c2b67569d948b2881261391081fe3f4e441012ccea8c1ab",
    "pc_home_stage.png": "d54814d00e67c0e6992ea1b5ca920af609d787e7b162c213313258d25366cae8",
    "pc_home_star.png": "8d5c22a6d5e6668f51e4c46aa592dfb77365b4cb6a60a2c6dc6f30ed09b8cc56",
    "pc_icon_accounts.png": "dca5565da27710b14a13de4e871acf38217be3b9811076d5b3b159945fbee082",
    "pc_icon_albums.png": "62d59c8dbe39b882ac85a850ea8176f19f4098dc664157b81966c6be93cd4fbf",
    "pc_icon_artists.png": "ab11d5eae3e999d08a9b0d7a13c352b1cdaf7704e7ba75af827c02225212bf61",
    "pc_icon_back.png": "959c1d7612192f4df5b691f6428bab7eb84d48dd5f76642edccd1c617713c553",
    "pc_icon_charts.png": "3241c74155c5cbbf419268716071bc3078ea10df4fa1f7d6ef5abd3cd2be4f23",
    "pc_icon_close.png": "b0903e6fb5f32210071f4634142b3294a5c3a0efb49925caf0fdad5853d2ec33",
    "pc_icon_cloud.png": "ed9e2f8c4b549e2c00d22846c8e46bd404f2098be40283e656fb1232731d2066",
    "pc_icon_down.png": "d6253b58f54b588e66e8c4166d3c8b9adec33098cf46e8bd0869971432ea1b3f",
    "pc_icon_eye.png": "ea058fc2bddee2b978727feaff71a5ecb2c60309a07f657d224c2637aa2df8b6",
    "pc_icon_favorites.png": "f07f4174797cb965d3972c36c28929231f2712ae2b77e93e2730d362bd0f91f5",
    "pc_icon_folder.png": "e9ad4ce46ceb424302f2814cd1ea9ea2c8cdd8ccc7c30a0101e0bf3fe4bace06",
    "pc_icon_forward.png": "8299a3250b0348307c09811398dafccc3f5b056c1be6891b90ece0a10503025b",
    "pc_icon_genres.png": "f72a9898f4c493a4145347ee18ae5d0997875d8b2eb52bb3301b99c41d198db4",
    "pc_icon_heart.png": "f07f4174797cb965d3972c36c28929231f2712ae2b77e93e2730d362bd0f91f5",
    "pc_icon_history.png": "4507734eef2ed23ea671e3e73927d5af87de819abe7afa09dd1add1109bef65e",
    "pc_icon_home.png": "f9bcca465156eacb47a3c5afa731c785b66039ebd4105e63c927f17ff60520b2",
    "pc_icon_maximize.png": "328f74a3dd1faf3e6fd243a6d575e9ab9772488d7c0acf8ba462364ac4d16a5a",
    "pc_icon_mic.png": "3628f58fbbc75439fbda1b8e0df6e1197b01acbfbfbbb4462b43ed6fe53cd003",
    "pc_icon_minimize.png": "16ce195ed3a89e31f29302f1d58d37b26720d1c1dc31e6a7d478ed6ff592a63b",
    "pc_icon_more.png": "ccbfa5887bbad5873697ad8233b1c653f4259bcead73d08b20bf8066a44dfa07",
    "pc_icon_next.png": "f24e31c598ec674cac01bd3a678247457f075412d9a93b4f5e1ddd751d04ddcd",
    "pc_icon_pause.png": "7d6da339d5590eabd04361c1b9c09871c34cd2d77bfb400da56628ffa1cc910c",
    "pc_icon_play.png": "f901ceaca09c12888b45c365d0ac8962133edb1a39ae1ce8d87d8ac11ec7ee5d",
    "pc_icon_playlists.png": "ec9f1994393feb2e6dfbdc8699683cbef608b7760e1cc9928de8aa03c6eed27a",
    "pc_icon_queue.png": "ec9f1994393feb2e6dfbdc8699683cbef608b7760e1cc9928de8aa03c6eed27a",
    "pc_icon_repeat.png": "fbbcc4208a1c0c9c1d96959bb554b9befe774abf4110bcc559830ef2665ccf61",
    "pc_icon_search.png": "d7aa116cc86abf87aa02dfa6d9e66d43bb959ccac0139d9501310928817dd92c",
    "pc_icon_settings.png": "9cb02e6c6e79ab74a00bc97c30e8e3f5e4e150fe905291b288adb4bb90862571",
    "pc_icon_songs.png": "e6215f4cfb3eb0785bcd6e512f0401e1af9a677578384a761158495c3360bd18",
    "pc_icon_sound.png": "7de73c1d5be133482caf77882200c61afd540160f780b0ca23fe7aba33ff651f",
    "pc_icon_tone.png": "7de73c1d5be133482caf77882200c61afd540160f780b0ca23fe7aba33ff651f",
    "pc_icon_up.png": "e84f7b3df5cc5eeff41cc3085b9ca4f299c78c17f9e815b7cfb80525d30a284b",
    "pc_icon_videos.png": "acad0a8a2aaaa3ae8246d4a549ba9be8f889f139148d44e95679bffc0e771157",
    "pc_icon_voice.png": "8f3bc62147956506a51b8f4c944a46a98e009ea3e49a3205edeaca87f3b488d5",
    "pc_icon_volume.png": "813157462410bf6b4ca5e2dfce090cafdcfffe6aea2028a5a9b949ac95d7a3fd",
    "pc_logo.png": "4dbd6a9be5f7a7403e2bc7472a590031a9b0a8be9fa877b203626718b5cbe32b",
}
# filename, dimensions, mode, encoded SHA-256
OUTPUTS = (
    ("pc_logo.png", (192, 192), "RGBA", "633da7a3deed50edf0e63610360762c7a2b8398364e82db4c45b2d092cc3b85f"),
    ("pc_home_music.png", (256, 256), "RGBA", "092e8e5951791623d24d5fa6aa94f44faa881a54afc74782a766bae633f0c085"),
    ("pc_home_crown.png", (256, 256), "RGBA", "26948c669243007c0e51176e8653d7e62bcf42eba7b4242895a15833ac168057"),
    ("pc_home_headphones.png", (256, 256), "RGBA", "6b47ae73044b38bd93d840c67e9ef981f5deb2408f12932922020708632ff307"),
    ("pc_home_queue.png", (256, 256), "RGBA", "f8c768ef6cf873a6cd2b5bfa15d32adf294a179378ebc87291ad44f60f503c08"),
    ("pc_home_stage.png", (800, 500), "RGB", "3753ed615615e94e3025e62a562fc0999c9a66e6a77f6384d646affc1488c896"),
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_originals() -> None:
    names = {path.name for path in SOURCE.glob("*.png")}
    if names != set(INPUT_SHA256):
        raise ValueError("The original 43 PNG asset names changed; review the input manifest.")
    for name, expected in INPUT_SHA256.items():
        path = SOURCE / name
        if path.is_symlink() or not path.is_file() or digest(path.read_bytes()) != expected:
            raise ValueError(f"Original asset changed: {name}; no output was generated.")


def generate(output: Path) -> dict:
    if pillow_version != PILLOW_VERSION:
        raise ValueError(f"Encoder version must be Pillow {PILLOW_VERSION}, found {pillow_version}.")
    output = output.resolve()
    if output == SOURCE.resolve() or SOURCE.resolve().is_relative_to(output):
        raise ValueError("The output must be a separate thumbnail or benchmark directory.")
    verify_originals()
    products = []
    for name, dimensions, mode, expected in OUTPUTS:
        with Image.open(SOURCE / name) as original:
            original.load()
            if original.mode != mode or any(key in original.info for key in ("icc_profile", "gamma", "srgb")):
                raise ValueError(f"Unexpected source mode/profile: {name}; review image encoding.")
            if name == "pc_home_stage.png":
                expected_dimensions = (800, round(original.height * 800 / original.width))
                if dimensions != expected_dimensions:
                    raise ValueError("The stage preview must preserve the source aspect ratio.")
            elif original.width != original.height:
                raise ValueError(f"The square icon aspect ratio changed: {name}")
            resized = original.resize(dimensions, Image.Resampling.LANCZOS)
            encoded = io.BytesIO()
            resized.save(encoded, format="PNG", optimize=True, compress_level=9)
            payload = encoded.getvalue()
            actual = digest(payload)
            if actual != expected:
                raise ValueError(f"Encoding changed for {name}; review Pillow/zlib before updating output hashes.")
            with Image.open(io.BytesIO(payload)) as decoded:
                decoded.load()
                if decoded.mode != mode or decoded.size != dimensions or decoded.tobytes() != resized.tobytes():
                    raise ValueError(f"PNG round-trip failed: {name}")
            destination = output / name
            if destination.is_symlink() or destination.exists() and (not destination.is_file() or destination.read_bytes() != payload):
                raise ValueError(f"Output already exists with different contents: {name}")
            products.append((destination, payload, {
                "name": name, "dimensions": list(dimensions), "mode": mode,
                "bytes": len(payload), "sha256": actual, "inputSha256": INPUT_SHA256[name],
            }))
    # All source/output preflight checks succeed before any destination is written.
    verify_originals()
    output.mkdir(parents=True, exist_ok=True)
    for destination, payload, _ in products:
        if not destination.exists():
            destination.write_bytes(payload)
    verify_originals()
    for destination, _, row in products:
        if digest(destination.read_bytes()) != row["sha256"]:
            raise ValueError(f"Written thumbnail verification failed: {destination.name}")
    return {
        "originalCount": len(INPUT_SHA256), "originalsUnchanged": True,
        "pillowVersion": pillow_version, "zlibVersion": zlib.ZLIB_RUNTIME_VERSION,
        "assets": [row for _, _, row in products],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    print(json.dumps(generate(args.output_dir), indent=2))


if __name__ == "__main__":
    main()
