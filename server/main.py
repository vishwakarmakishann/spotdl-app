import os
import re
import base64
import shutil
import urllib.parse
import tempfile
import subprocess
from typing import Optional, Dict, Any, List
from io import BytesIO
from http.cookiejar import MozillaCookieJar

from fastapi import FastAPI, Query, HTTPException, BackgroundTasks
from fastapi.responses import JSONResponse, FileResponse
from pydantic import BaseModel
import requests
from PIL import Image
import yt_dlp
from mutagen.oggopus import OggOpus
from mutagen.flac import Picture
from mutagen.mp4 import MP4, MP4Cover
from mutagen.id3 import ID3, TIT2, TPE1, TALB, TDRC, TRCK, APIC, USLT

app = FastAPI(title="spotdl-cloud-server", version="1.0.0")

USER_AGENT_BROWSER = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)


def has_binary(name: str) -> bool:
    return shutil.which(name) is not None


@app.get("/")
def read_root():
    return {
        "service": "spotdl-cloud-server",
        "status": "healthy",
        "endpoints": ["/health", "/api/download", "/api/inspect"],
    }


@app.get("/health")
def health_check():
    raw_cookies = os.environ.get("YOUTUBE_COOKIES", "").strip()
    has_auth_cookies = any(k in raw_cookies for k in ["LOGIN_INFO", "SID", "SSID", "__Secure-1PSID", "__Secure-3PSID"])
    return {
        "status": "ok",
        "version": "1.0.0",
        "engine": "spotdl-cloud-server",
        "has_ffmpeg": has_binary("ffmpeg"),
        "has_nodejs": has_binary("node"),
        "has_youtube_cookies": bool(raw_cookies),
        "has_valid_auth_cookies": has_auth_cookies,
    }


class TrackDownloadRequest(BaseModel):
    title: str
    artist: str
    album: Optional[str] = None
    year: Optional[str] = None
    track_number: Optional[int] = 1
    total_tracks: Optional[int] = 1
    cover_url: Optional[str] = None
    format: Optional[str] = "opus"


def clean_query_term(term: str) -> str:
    cleaned = re.sub(r"\s*\(.*?(feat|ft|official|remaster|video|audio).*?\)", "", term, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*\[.*?(feat|ft|official|remaster|video|audio).*?\]", "", cleaned, flags=re.IGNORECASE)
    return cleaned.strip()


def fetch_lyrics(title: str, artist: str, album: Optional[str] = None, duration: Optional[int] = None) -> Optional[str]:
    try:
        url = "https://lrclib.net/api/get"
        params = {
            "track_name": clean_query_term(title),
            "artist_name": clean_query_term(artist),
        }
        if album:
            params["album_name"] = clean_query_term(album)
        if duration and duration > 0:
            params["duration"] = duration

        resp = requests.get(url, params=params, headers={"User-Agent": USER_AGENT_BROWSER}, timeout=6)
        if resp.status_code == 200:
            data = resp.json()
            return data.get("syncedLyrics")
    except Exception:
        pass
    return None


def ensure_square_jpeg(img_bytes: bytes) -> bytes:
    try:
        img = Image.open(BytesIO(img_bytes)).convert("RGB")
        w, h = img.size
        if w != h:
            min_dim = min(w, h)
            left = (w - min_dim) // 2
            top = (h - min_dim) // 2
            img = img.crop((left, top, left + min_dim, top + min_dim))
        out = BytesIO()
        img.save(out, format="JPEG", quality=95)
        return out.getvalue()
    except Exception:
        return img_bytes


def sanitize_netscape_cookies(raw_text: str) -> Optional[str]:
    """
    Normalizes cookies string into standard Netscape HTTP cookie format with strict tabs.
    Returns validated cookie file text or None if empty/invalid.
    """
    if not raw_text or not raw_text.strip():
        return None

    lines = []
    has_header = False
    valid_count = 0

    normalized_text = raw_text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n")

    for line in normalized_text.splitlines():
        line = line.strip()
        if not line:
            continue
        if "Netscape HTTP Cookie File" in line:
            has_header = True
            lines.append("# Netscape HTTP Cookie File")
            continue
        if line.startswith("#"):
            lines.append(line)
            continue

        parts = line.split()
        if len(parts) >= 7:
            domain, flag, path, secure, expiration, name = parts[:6]
            value = " ".join(parts[6:])
            lines.append(f"{domain}\t{flag}\t{path}\t{secure}\t{expiration}\t{name}\t{value}")
            valid_count += 1

    if valid_count == 0:
        return None

    if not has_header:
        lines.insert(0, "# Netscape HTTP Cookie File")

    formatted = "\n".join(lines) + "\n"

    temp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as tf:
            tf.write(formatted)
            temp_path = tf.name
        cj = MozillaCookieJar(temp_path)
        cj.load(ignore_discard=True, ignore_expires=True)
        os.remove(temp_path)
        return formatted
    except Exception:
        if temp_path and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception:
                pass
        return None


def get_cookie_file(target_dir: str) -> Optional[str]:
    raw_cookies = os.environ.get("YOUTUBE_COOKIES", "").strip()
    if not raw_cookies:
        return None

    # CRITICAL: Only attach cookiefile if real authenticated session cookies exist.
    # Passing single/visitor cookies like __Secure-YNTD triggers yt-dlp's is_authenticated=True,
    # which causes yt-dlp to strip out visionos/android_vr clients (which don't support cookies),
    # dropping all playable formats on datacenter IPs.
    has_auth_cookies = any(k in raw_cookies for k in ["LOGIN_INFO", "SID", "SSID", "__Secure-1PSID", "__Secure-3PSID"])
    if not has_auth_cookies:
        return None

    clean_cookies = sanitize_netscape_cookies(raw_cookies)
    if not clean_cookies:
        return None

    path = os.path.join(target_dir, "youtube_cookies.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(clean_cookies)
    return path


def download_best_audio_stream(
    clean_artist: str,
    clean_title: str,
    temp_dir: str,
    cookie_path: Optional[str] = None,
) -> str:
    """
    Searches using flat extraction to discover candidates without format-check crashes.
    Iterates through candidate videos using resilient multi-tier client strategies.
    """
    queries = [
        f"ytsearch5:{clean_artist} - {clean_title} Topic",
        f"ytsearch5:{clean_artist} {clean_title} Audio",
        f"ytsearch5:{clean_artist} {clean_title}",
    ]

    # Search options MUST use extract_flat to avoid format evaluation during search
    search_opts: Dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "noplaylist": True,
        "socket_timeout": 20,
    }

    node_bin = shutil.which("node")

    # Multi-tier download strategies tailored for authenticated vs unauthenticated extraction
    download_tiers: List[Dict[str, Any]] = []

    if cookie_path and os.path.isfile(cookie_path):
        # Authenticated tiers (cookies present)
        download_tiers.extend([
            {
                "format": "141/251/bestaudio[ext=webm]/140/bestaudio/ba/b",
                "cookiefile": cookie_path,
                "extractor_args": {
                    "youtube": {
                        "player_client": ["web_music", "web"],
                    }
                },
            },
            {
                "format": "bestaudio/best",
                "cookiefile": cookie_path,
            },
            # Fallback: try unauthenticated visionos (in case cookie is rotated/expired)
            {
                "format": "141/251/bestaudio[ext=webm]/140/bestaudio/ba/b",
                "extractor_args": {
                    "youtube": {
                        "player_client": ["visionos", "android_vr"],
                    }
                },
            },
            {
                "format": "bestaudio/best",
            },
        ])
    else:
        # Unauthenticated tiers (no cookies)
        download_tiers.extend([
            {
                "format": "141/251/bestaudio[ext=webm]/140/bestaudio/ba/b",
                "extractor_args": {
                    "youtube": {
                        "player_client": ["visionos", "android_vr"],
                    }
                },
            },
            {
                "format": "bestaudio/best",
                "extractor_args": {
                    "youtube": {
                        "player_client": ["tv_samsung", "web"],
                    }
                },
            },
            {
                "format": "bestaudio/best",
            },
        ])

    last_error: Optional[Exception] = None
    seen_ids = set()

    for query in queries:
        try:
            with yt_dlp.YoutubeDL(search_opts) as ydl_search:
                search_res = ydl_search.extract_info(query, download=False)
                entries = search_res.get("entries", []) if search_res else []
        except Exception as e:
            last_error = e
            continue

        for entry in entries:
            if not entry:
                continue
            vid_id = entry.get("id")
            if not vid_id or vid_id in seen_ids:
                continue
            seen_ids.add(vid_id)

            vid_url = f"https://www.youtube.com/watch?v={vid_id}"

            # Try each download tier for this candidate
            for tier in download_tiers:
                try:
                    down_opts: Dict[str, Any] = {
                        "quiet": True,
                        "no_warnings": True,
                        "noprogress": True,
                        "noplaylist": True,
                        "socket_timeout": 30,
                        "retries": 5,
                        "fragment_retries": 5,
                        "outtmpl": os.path.join(temp_dir, "raw.%(ext)s"),
                        **tier,
                    }

                    if node_bin:
                        down_opts["js_runtimes"] = {"node": {"path": node_bin}}

                    with yt_dlp.YoutubeDL(down_opts) as dl:
                        dl.download([vid_url])

                    # Check if audio was downloaded successfully
                    for f in os.listdir(temp_dir):
                        if f.startswith("raw."):
                            return os.path.join(temp_dir, f)

                except Exception as e:
                    last_error = e
                    continue

    err_msg = str(last_error) if last_error else "Audio stream extraction failed across all candidates."
    raise HTTPException(status_code=500, detail=f"Failed to download audio stream: {err_msg}")


def process_and_package_track(
    title: str,
    artist: str,
    album: Optional[str] = None,
    year: Optional[str] = None,
    track_number: Optional[int] = 1,
    cover_url: Optional[str] = None,
    target_format: str = "opus",
) -> tuple[str, str, Optional[str], str]:
    """
    Downloads raw stream from YouTube Music, remuxes losslessly with canonical tags,
    and returns (output_file_path, filename, synced_lyrics, temp_dir).
    """
    temp_dir = tempfile.mkdtemp(prefix="spotdl_srv_")
    clean_art = clean_query_term(artist)
    clean_tit = clean_query_term(title)
    final_album = album or title
    final_track_no = track_number or 1

    # 1. Download full-resolution cover art (preserving square aspect ratio, zero downscaling)
    cover_bytes = None
    if cover_url:
        try:
            r = requests.get(cover_url, headers={"User-Agent": USER_AGENT_BROWSER}, timeout=8)
            if r.status_code == 200 and len(r.content) > 500:
                cover_bytes = ensure_square_jpeg(r.content)
        except Exception:
            pass

    # 2. Fetch synced lyrics from LRCLIB
    lyrics = fetch_lyrics(title, artist, album)

    # 3. Resolve & download raw audio stream with resilient multi-client yt-dlp
    cookie_path = get_cookie_file(temp_dir)
    raw_file = download_best_audio_stream(
        clean_artist=clean_art,
        clean_title=clean_tit,
        temp_dir=temp_dir,
        cookie_path=cookie_path,
    )

    raw_ext = os.path.splitext(raw_file)[1].lower().replace(".", "")
    out_ext = "opus" if target_format == "opus" else ("m4a" if target_format == "m4a" else "mp3")

    track_str = f"{final_track_no:02d}"
    sanitized_title = re.sub(r'[\\/*?:"<>|]', "", title)
    output_filename = f"{track_str} - {sanitized_title}.{out_ext}"
    final_output_path = os.path.join(temp_dir, output_filename)

    # 4. Lossless FFmpeg remux & authoritative Mutagen tagging
    if out_ext == "opus":
        if raw_ext in ["webm", "opus"]:
            ffmpeg_cmd = ["ffmpeg", "-y", "-i", raw_file, "-vn", "-c:a", "copy", final_output_path]
        else:
            ffmpeg_cmd = ["ffmpeg", "-y", "-i", raw_file, "-vn", "-c:a", "libopus", "-b:a", "160k", final_output_path]
        subprocess.run(ffmpeg_cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        # Mutagen VorbisComment tagging
        audio = OggOpus(final_output_path)
        audio["TITLE"] = [title]
        audio["ARTIST"] = [artist]
        audio["ALBUM"] = [final_album]
        audio["ALBUMARTIST"] = [artist]
        if year:
            audio["DATE"] = [str(year)]
            audio["YEAR"] = [str(year)]
        audio["TRACKNUMBER"] = [str(final_track_no)]
        if lyrics:
            audio["LYRICS"] = [lyrics]
        if cover_bytes:
            pic = Picture()
            pic.type = 3
            pic.mime = "image/jpeg"
            pic.desc = "Front Cover"
            pic.data = cover_bytes
            audio["metadata_block_picture"] = [base64.b64encode(pic.write()).decode("ascii")]
        audio.save()

    elif out_ext == "m4a":
        if raw_ext == "m4a":
            ffmpeg_cmd = ["ffmpeg", "-y", "-i", raw_file, "-vn", "-c:a", "copy", final_output_path]
        else:
            ffmpeg_cmd = ["ffmpeg", "-y", "-i", raw_file, "-vn", "-c:a", "aac", "-b:a", "256k", final_output_path]
        subprocess.run(ffmpeg_cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        audio = MP4(final_output_path)
        audio["\xa9nam"] = [title]
        audio["\xa9ART"] = [artist]
        audio["\xa9alb"] = [final_album]
        if year:
            audio["\xa9day"] = [str(year)]
        audio["trkn"] = [(final_track_no, 0)]
        if lyrics:
            audio["\xa9lyr"] = [lyrics]
        if cover_bytes:
            audio["covr"] = [MP4Cover(cover_bytes, imageformat=MP4Cover.FORMAT_JPEG)]
        audio.save()

    else:  # mp3
        ffmpeg_cmd = ["ffmpeg", "-y", "-i", raw_file, "-vn", "-c:a", "libmp3lame", "-b:a", "320k", final_output_path]
        subprocess.run(ffmpeg_cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        audio = ID3(final_output_path)
        audio.add(TIT2(encoding=3, text=title))
        audio.add(TPE1(encoding=3, text=artist))
        audio.add(TALB(encoding=3, text=final_album))
        if year:
            audio.add(TDRC(encoding=3, text=str(year)))
        audio.add(TRCK(encoding=3, text=str(final_track_no)))
        if lyrics:
            audio.add(USLT(encoding=3, lang="eng", desc="lyrics", text=lyrics))
        if cover_bytes:
            audio.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Front Cover", data=cover_bytes))
        audio.save()

    return final_output_path, output_filename, lyrics, temp_dir


@app.get("/api/download")
def download_get(
    title: str = Query(..., description="Track title"),
    artist: str = Query(..., description="Artist name"),
    album: Optional[str] = Query(None, description="Album name"),
    year: Optional[str] = Query(None, description="Release year"),
    track_number: Optional[int] = Query(1, description="Track number"),
    cover_url: Optional[str] = Query(None, description="Artwork URL"),
    format: Optional[str] = Query("opus", description="Target audio format (opus, m4a, mp3)"),
    background_tasks: BackgroundTasks = BackgroundTasks(),
):
    output_path, filename, lyrics, temp_dir = process_and_package_track(
        title=title,
        artist=artist,
        album=album,
        year=year,
        track_number=track_number,
        cover_url=cover_url,
        target_format=format,
    )

    background_tasks.add_task(shutil.rmtree, temp_dir, ignore_errors=True)

    headers = {
        "Content-Disposition": f'attachment; filename="{urllib.parse.quote(filename)}"',
        "X-Track-Title": urllib.parse.quote(title),
        "X-Track-Artist": urllib.parse.quote(artist),
        "X-Track-Album": urllib.parse.quote(album or title),
        "X-Has-Lyrics": "true" if lyrics else "false",
    }
    if lyrics:
        headers["X-Synced-Lyrics"] = base64.b64encode(lyrics.encode("utf-8")).decode("ascii")

    return FileResponse(
        path=output_path,
        media_type="audio/opus" if format == "opus" else "audio/mp4",
        filename=filename,
        headers=headers,
    )


@app.post("/api/download")
def download_post(
    req: TrackDownloadRequest,
    background_tasks: BackgroundTasks = BackgroundTasks(),
):
    output_path, filename, lyrics, temp_dir = process_and_package_track(
        title=req.title,
        artist=req.artist,
        album=req.album,
        year=req.year,
        track_number=req.track_number,
        cover_url=req.cover_url,
        target_format=req.format or "opus",
    )

    background_tasks.add_task(shutil.rmtree, temp_dir, ignore_errors=True)

    headers = {
        "Content-Disposition": f'attachment; filename="{urllib.parse.quote(filename)}"',
        "X-Track-Title": urllib.parse.quote(req.title),
        "X-Track-Artist": urllib.parse.quote(req.artist),
        "X-Track-Album": urllib.parse.quote(req.album or req.title),
        "X-Has-Lyrics": "true" if lyrics else "false",
    }
    if lyrics:
        headers["X-Synced-Lyrics"] = base64.b64encode(lyrics.encode("utf-8")).decode("ascii")

    return FileResponse(
        path=output_path,
        media_type="audio/opus" if req.format == "opus" else "audio/mp4",
        filename=filename,
        headers=headers,
    )


@app.get("/api/inspect")
def inspect_track(v: Optional[str] = "pyGU-UudvrM", q: Optional[str] = None):
    """
    Diagnostic endpoint to inspect environment cookies and format extraction on Render.
    """
    raw_cookies = os.environ.get("YOUTUBE_COOKIES", "").strip()
    target = f"https://www.youtube.com/watch?v={v}" if v else f"ytsearch1:{q}"

    diag: Dict[str, Any] = {
        "raw_cookie_length": len(raw_cookies),
        "raw_cookie_lines": len(raw_cookies.splitlines()),
        "auth_keywords_present": [k for k in ["LOGIN_INFO", "SID", "SSID", "__Secure-1PSID", "__Secure-3PSID", "__Secure-YNTD"] if k in raw_cookies],
    }

    # Test cookie parsing
    temp_dir = tempfile.mkdtemp(prefix="spotdl_diag_")
    cpath = os.path.join(temp_dir, "test_cookies.txt")
    
    # Write normalized cookies
    normalized = raw_cookies.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n")
    if not normalized.startswith("# Netscape HTTP Cookie File"):
        normalized = "# Netscape HTTP Cookie File\n" + normalized
    
    with open(cpath, "w", encoding="utf-8") as f:
        f.write(normalized)

    parsed_cookies = []
    try:
        cj = MozillaCookieJar(cpath)
        cj.load(ignore_discard=True, ignore_expires=True)
        parsed_cookies = [c.name for c in cj]
        diag["cookiejar_parsed_count"] = len(parsed_cookies)
        diag["parsed_cookie_names"] = parsed_cookies[:15]
    except Exception as e:
        diag["cookiejar_error"] = str(e)

    # Test 1: Extract with cookies using default clients
    try:
        opts_with_cookie = {
            "quiet": True,
            "cookiefile": cpath,
        }
        with yt_dlp.YoutubeDL(opts_with_cookie) as ydl:
            info = ydl.extract_info(target, download=False)
            fmts = [f.get("format_id") for f in info.get("formats", []) if f.get("acodec") != "none"]
            diag["extract_with_cookies_status"] = "success"
            diag["extract_with_cookies_audio_formats"] = fmts
    except Exception as e:
        diag["extract_with_cookies_status"] = "error"
        diag["extract_with_cookies_error"] = str(e)

    # Test 2: Extract without cookies using visionos
    try:
        opts_no_cookie = {
            "quiet": True,
            "extractor_args": {
                "youtube": {
                    "player_client": ["visionos", "android_vr"],
                }
            },
        }
        with yt_dlp.YoutubeDL(opts_no_cookie) as ydl:
            info = ydl.extract_info(target, download=False)
            fmts = [f.get("format_id") for f in info.get("formats", []) if f.get("acodec") != "none"]
            diag["extract_no_cookies_visionos_status"] = "success"
            diag["extract_no_cookies_audio_formats"] = fmts
    except Exception as e:
        diag["extract_no_cookies_visionos_status"] = "error"
        diag["extract_no_cookies_error"] = str(e)

    shutil.rmtree(temp_dir, ignore_errors=True)
    return diag
