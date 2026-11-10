import os
import re
import base64
import shutil
import urllib.parse
import tempfile
import subprocess
from typing import Optional, Dict, Any
from io import BytesIO

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
        "endpoints": ["/health", "/api/download"],
    }


@app.get("/health")
def health_check():
    has_cookies = bool(os.environ.get("YOUTUBE_COOKIES", "").strip())
    return {
        "status": "ok",
        "version": "1.0.0",
        "engine": "spotdl-cloud-server",
        "has_ffmpeg": has_binary("ffmpeg"),
        "has_nodejs": has_binary("node"),
        "has_youtube_cookies": has_cookies,
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


def get_cookie_file(target_dir: str) -> Optional[str]:
    raw_cookies = os.environ.get("YOUTUBE_COOKIES", "").strip()
    if not raw_cookies:
        return None
    path = os.path.join(target_dir, "youtube_cookies.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(raw_cookies)
    return path


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

    # 1. Download cover art if available
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

    # 3. Resolve & download raw audio stream with yt-dlp
    query = f"ytsearch1:{clean_art} - {clean_tit} Topic"
    raw_template = os.path.join(temp_dir, "raw.%(ext)s")

    ydl_opts = {
        "format": "141/251/bestaudio[ext=webm]/140/bestaudio/best",
        "outtmpl": raw_template,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": True,
        "socket_timeout": 30,
        "retries": 10,
        "fragment_retries": 10,
    }

    cookie_path = get_cookie_file(temp_dir)
    if cookie_path:
        ydl_opts["cookiefile"] = cookie_path

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([query])
    except Exception:
        fallback_query = f"ytsearch1:{clean_art} {clean_tit} Audio"
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([fallback_query])

    # Locate raw downloaded file
    raw_file = None
    for f in os.listdir(temp_dir):
        if f.startswith("raw."):
            raw_file = os.path.join(temp_dir, f)
            break

    if not raw_file or not os.path.isfile(raw_file):
        raise HTTPException(status_code=500, detail="Failed to download audio stream from source.")

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
