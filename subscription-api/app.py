from __future__ import annotations
import subprocess, sys, tempfile, threading, time, urllib.request
from pathlib import Path
from fastapi import FastAPI, HTTPException, Response
from fastapi.middleware.cors import CORSMiddleware
ROOT = Path(__file__).resolve().parent
CONVERTER = ROOT / "tools" / "gkd2skip.py"
SOURCE_URL = "https://gkd667.vv.ax/gkd.json5"
CACHE_SECONDS = 300
MAX_SOURCE_BYTES = 5 * 1024 * 1024
app = FastAPI(title="GKD to AutoSkip", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["GET"], allow_headers=["*"])
_cache_lock = threading.Lock()
_cache_body = None
_cache_time = 0.0

def _fetch_source():
    request = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "autoskip-gkd-converter/1.0", "Accept": "application/json5, text/plain"})
    try:
        with urllib.request.urlopen(request, timeout=30) as result:
            body = result.read(MAX_SOURCE_BYTES + 1)
    except Exception as exc:
        raise RuntimeError(f"Could not fetch configured GKD subscription: {exc}") from exc
    if len(body) > MAX_SOURCE_BYTES:
        raise RuntimeError("GKD subscription exceeded the 5 MiB safety limit")
    return body

def _convert():
    source = _fetch_source()
    with tempfile.TemporaryDirectory(prefix="autoskip-convert-") as temp_dir:
        source_path = Path(temp_dir) / "source.json5"
        output_path = Path(temp_dir) / "autoskip.json"
        source_path.write_bytes(source)
        try:
            result = subprocess.run([sys.executable, str(CONVERTER), str(source_path), str(output_path)], cwd=ROOT, capture_output=True, text=True, timeout=120, check=False)
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError("Rule conversion exceeded the 120 second limit") from exc
        if result.returncode != 0 or not output_path.exists():
            raise RuntimeError((result.stderr or result.stdout or "converter failed")[-2000:])
        return output_path.read_bytes()

@app.get("/health")
def health():
    return {"status": "ok"}

@app.get("/gkd-autoskip.json")
def subscription():
    global _cache_body, _cache_time
    with _cache_lock:
        now = time.monotonic()
        if _cache_body is None or now - _cache_time >= CACHE_SECONDS:
            try:
                _cache_body = _convert()
                _cache_time = now
            except Exception as exc:
                if _cache_body is None:
                    raise HTTPException(status_code=502, detail=str(exc)) from exc
        body = _cache_body
        age = max(0, int(time.monotonic() - _cache_time))
    return Response(content=body, media_type="application/json", headers={"Cache-Control": "public, max-age=60", "X-Subscription-Source": SOURCE_URL, "X-Conversion-Cache-Age": str(age)})
