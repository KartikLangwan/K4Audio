import yt_dlp
import json


# yt-dlp's default clients change between versions and get bot-checked ("Sign in to confirm you're not a bot").
# Measured 2026-11-05: these serve audio all the way through. android_vr's links are refused past the first
# MiB and tv fails, so neither is listed; the slower pair is the last resort. The PC app uses the same set.
_CLIENTS = (
    {"youtube": {"player_client": ["visionos"]}},
    None,
    {"youtube": {"player_client": ["web_embedded", "mweb"]}},
)
# Songs no client can fix: do not spend another run on them.
_GONE_SIGNS = ("Video unavailable", "Private video", "has been removed", "copyright", "not available in your country")
_first = 0


def _extract(quickjs_bin, video_id, fmt, extractor_args):
    opts = {
        "format": fmt,
        "js_runtimes": {"quickjs": {"path": quickjs_bin}},
        "socket_timeout": 12,
        "retries": 2,
        "extractor_retries": 2,
        "noplaylist": True,
        "quiet": True,
    }
    if extractor_args:
        opts["extractor_args"] = extractor_args

    ydl = yt_dlp.YoutubeDL(opts)
    info = ydl.extract_info("https://www.youtube.com/watch?v=" + video_id, download=False)
    # sanitize_info drops objects json cannot write, whatever the yt-dlp version puts in the result.
    return json.dumps(ydl.sanitize_info(info))


# fmt: the itags of the chosen streaming quality, then any audio (same as the PC app).
def download(quickjs_bin: str, video_id: str, fmt: str = "bestaudio/best") -> str:
    global _first
    # The clients that worked last time go first, so a bot-checked set costs one run, not one per song.
    last_error = None
    for offset in range(len(_CLIENTS)):
        index = (_first + offset) % len(_CLIENTS)
        try:
            result = _extract(quickjs_bin, video_id, fmt, _CLIENTS[index])
            _first = index
            return result
        except Exception as error:
            last_error = error
            if any(sign in str(error) for sign in _GONE_SIGNS):
                break
    raise last_error


def upgrade(package_name):
    try:
        import ensurepip

        ensurepip.bootstrap()
    except Exception as e:
        print(f"Error running ensurepip: {e}")

    try:
        import pip
        from pip._internal import main as pip_main

        # Only yt-dlp, wheels only (an sdist runs setup code at install time), no dependency swaps.
        if package_name != "yt-dlp":
            raise ValueError(f"Refusing to upgrade {package_name}")
        result = pip_main(
            ["install", "--upgrade", "--only-binary=:all:", "--no-deps", "yt-dlp>=2026.08.19"]
        )
        if result != 0:
            raise RuntimeError(f"Package update failed with exit code {result}")
        print(f"Successfully upgraded {package_name}")
    except Exception as e:
        print(f"Error upgrading package {package_name}: {e}")
        # Chaquopy must receive the failure so the UI cannot report false success.
        raise
