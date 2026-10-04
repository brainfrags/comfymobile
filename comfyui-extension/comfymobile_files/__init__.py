"""
ComfyMobile file operations for ComfyUI.

ComfyUI's own API can't list subfolders of the output folder or move files.
This extension adds a few small routes the ComfyMobile app uses so that albums
are real subfolders of the output folder:

  GET  /comfymobile/output/list    -> {"version", "files": [...], "folders": [...]}
  POST /comfymobile/output/mkdir   {"path": "album"}
  POST /comfymobile/output/move    {"items": [{"type": "output", "path": "a.png"}], "to": "album"}
  POST /comfymobile/output/rmdir   {"path": "album"}   (only removes an empty folder)

All paths are relative to the output folder (or the temp folder for "type": "temp")
and can't point outside it.

Install: copy this folder into ComfyUI/custom_nodes/ and restart ComfyUI.
"""

import asyncio
import os
import shutil

from aiohttp import web

import folder_paths
from server import PromptServer

VERSION = 1
MEDIA_EXTENSIONS = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp",
    ".mp4", ".webm", ".mov", ".avi", ".mkv",
}

NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}

routes = PromptServer.instance.routes


def _base(dir_type="output"):
    if dir_type == "temp":
        return os.path.abspath(folder_paths.get_temp_directory())
    return os.path.abspath(folder_paths.get_output_directory())


def _resolve(base, rel):
    """Absolute path of [rel] inside [base]; raises ValueError if it would leave [base]."""
    rel = (rel or "").replace("\\", "/").strip("/")
    path = os.path.abspath(os.path.join(base, os.path.normpath(rel))) if rel else base
    if os.path.commonpath((base, path)) != base:
        raise ValueError("path outside folder")
    return path


def _rel(base, path):
    return os.path.relpath(path, base).replace(os.sep, "/")


def _free_name(folder, name):
    """[name], or "stem (n).ext" if a file with that name already exists in [folder]."""
    stem, ext = os.path.splitext(name)
    candidate, n = name, 1
    while os.path.exists(os.path.join(folder, candidate)):
        candidate = f"{stem} ({n}){ext}"
        n += 1
    return candidate


def _scan():
    base = _base()
    files, folders = [], []
    for root, dirs, names in os.walk(base):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        folders.extend(_rel(base, os.path.join(root, d)) for d in dirs)
        for name in names:
            if name.startswith(".") or os.path.splitext(name)[1].lower() not in MEDIA_EXTENSIONS:
                continue
            path = os.path.join(root, name)
            try:
                mtime = os.path.getmtime(path)
            except OSError:
                continue
            files.append((mtime, _rel(base, path)))
    files.sort(key=lambda t: -t[0])
    return {"version": VERSION, "files": [f for _, f in files], "folders": sorted(folders)}


@routes.get("/comfymobile/output/list")
async def list_output(request):
    result = await asyncio.get_running_loop().run_in_executor(None, _scan)
    return web.json_response(result)


@routes.post("/comfymobile/output/mkdir")
async def make_folder(request):
    body = await request.json()
    try:
        path = _resolve(_base(), body.get("path"))
    except ValueError as e:
        return web.json_response({"error": str(e)}, status=400)
    if path == _base():
        return web.json_response({"error": "empty path"}, status=400)
    os.makedirs(path, exist_ok=True)
    return web.json_response({"path": _rel(_base(), path)})


@routes.post("/comfymobile/output/move")
async def move_files(request):
    body = await request.json()
    base = _base()
    try:
        target = _resolve(base, body.get("to"))
    except ValueError as e:
        return web.json_response({"error": str(e)}, status=400)
    os.makedirs(target, exist_ok=True)

    moved, failed = [], []
    for item in body.get("items", []):
        dir_type = item.get("type", "output")
        rel = item.get("path", "")
        try:
            src = _resolve(_base(dir_type), rel)
            if not os.path.isfile(src):
                raise FileNotFoundError(rel)
            if os.path.dirname(src) == target:
                # Already there
                moved.append({"type": dir_type, "from": rel, "path": _rel(base, src)})
                continue
            dst = os.path.join(target, _free_name(target, os.path.basename(src)))
            shutil.move(src, dst)
            moved.append({"type": dir_type, "from": rel, "path": _rel(base, dst)})
        except Exception as e:
            failed.append({"type": dir_type, "from": rel, "error": str(e)})
    return web.json_response({"moved": moved, "failed": failed})


@routes.post("/comfymobile/output/rmdir")
async def remove_folder(request):
    body = await request.json()
    try:
        path = _resolve(_base(), body.get("path"))
    except ValueError as e:
        return web.json_response({"error": str(e)}, status=400)
    if path == _base():
        return web.json_response({"error": "empty path"}, status=400)
    try:
        os.rmdir(path)  # only succeeds when empty
        return web.json_response({"removed": True})
    except OSError:
        return web.json_response({"removed": False})
