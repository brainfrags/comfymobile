"""
ComfyMobile file operations for ComfyUI.

ComfyUI's own API can't list subfolders of the output folder or move files.
This extension adds a few small routes the ComfyMobile app uses so that albums
are real subfolders of the output folder:

  GET  /comfymobile/output/list    -> {"version", "files": [...], "times": [...], "folders": [...]}
                                      ("times": each file's modification time, ms since epoch)
  POST /comfymobile/output/mkdir   {"path": "album"}
  POST /comfymobile/output/move    {"items": [{"type": "output", "path": "a.png"}], "to": "album"}
  POST /comfymobile/output/rmdir   {"path": "album"}   (only removes an empty folder)
  POST /comfymobile/output/move_folder {"from": "album", "to": "renamed"}  ("to": "" = output root)
  GET  /comfymobile/output/duplicates -> {"groups": [["a.png", "album/a.png"], ...]} (same content)
  POST /comfymobile/output/delete  {"paths": ["a.png"]}

All paths are relative to the output folder (or the temp folder for "type": "temp")
and can't point outside it.

Install: copy this folder into ComfyUI/custom_nodes/ and restart ComfyUI.
"""

import asyncio
import hashlib
import os
import shutil

from aiohttp import web

import folder_paths
from server import PromptServer

VERSION = 2  # 2: move_folder
MEDIA_EXTENSIONS = {
    ".png", ".jpg", ".jpeg", ".jfif", ".webp", ".gif", ".bmp", ".tif", ".tiff", ".avif", ".heic",
    ".mp4", ".m4v", ".webm", ".mov", ".avi", ".mkv",
}

NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}

routes = PromptServer.instance.routes

# Register the routes only once, even if this folder is installed twice
# (e.g. custom_nodes/comfymobile_files and custom_nodes/comfyui-extension/comfymobile_files)
_ALREADY_LOADED = getattr(PromptServer.instance, "_comfymobile_files_loaded", False)
PromptServer.instance._comfymobile_files_loaded = True
if _ALREADY_LOADED:
    class _Ignore:
        def get(self, *_):
            return lambda f: f

        post = get

    routes = _Ignore()
else:
    print("[ComfyMobile] file operations enabled: /comfymobile/output/*")


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


def _walk(base):
    """os.walk that also enters linked folders (symlinks, Windows junctions), once each."""
    seen = set()
    for root, dirs, names in os.walk(base, followlinks=True):
        real = os.path.realpath(root)
        if real in seen:
            dirs[:] = []
            continue
        seen.add(real)
        # Skip hidden folders and links back to a folder already listed
        dirs[:] = [
            d for d in dirs
            if not d.startswith(".") and os.path.realpath(os.path.join(root, d)) not in seen
        ]
        yield root, dirs, names


def _scan():
    base = _base()
    files, folders = [], []
    for root, dirs, names in _walk(base):
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
    return {
        "version": VERSION,
        "files": [f for _, f in files],
        "times": [int(t * 1000) for t, _ in files],
        "folders": sorted(folders),
    }


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


# (path, size, mtime) -> hash, so checking again only reads new or changed files
_hash_cache = {}


def _file_hash(path, size, mtime):
    key = (path, size, mtime)
    cached = _hash_cache.get(key)
    if cached is None:
        h = hashlib.blake2b(digest_size=20)
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        cached = _hash_cache[key] = h.hexdigest()
    return cached


def _find_duplicates():
    """
    Groups (2+ paths) of media files with identical content, where at least one copy is in
    the output folder itself and one in a subfolder. Only files of the same size are read.
    """
    base = _base()
    by_size = {}
    for root, dirs, names in _walk(base):
        for name in names:
            if name.startswith(".") or os.path.splitext(name)[1].lower() not in MEDIA_EXTENSIONS:
                continue
            path = os.path.join(root, name)
            try:
                st = os.stat(path)
            except OSError:
                continue
            by_size.setdefault(st.st_size, []).append((path, st.st_mtime))
    groups = []
    for size, entries in by_size.items():
        in_root = [e for e in entries if os.path.dirname(e[0]) == base]
        if not in_root or len(in_root) == len(entries):
            continue
        by_hash = {}
        for path, mtime in entries:
            try:
                by_hash.setdefault(_file_hash(path, size, mtime), []).append(_rel(base, path))
            except OSError:
                continue
        groups.extend(sorted(g) for g in by_hash.values() if len(g) > 1)
    return {"groups": groups}


@routes.get("/comfymobile/output/duplicates")
async def find_duplicates(request):
    result = await asyncio.get_running_loop().run_in_executor(None, _find_duplicates)
    return web.json_response(result)


@routes.post("/comfymobile/output/delete")
async def delete_files(request):
    """Delete files from the output folder (used to remove duplicates)."""
    body = await request.json()
    base = _base()
    deleted, failed = [], []
    for rel in body.get("paths", []):
        try:
            path = _resolve(base, rel)
            if path == base or not os.path.isfile(path):
                raise FileNotFoundError(rel)
            os.remove(path)
            deleted.append(rel)
        except Exception as e:
            failed.append({"path": rel, "error": str(e)})
    return web.json_response({"deleted": deleted, "failed": failed})


def _move_tree(src_dir, dst_dir, base, moved):
    """Move everything in src_dir into dst_dir (merging folders, renaming files on a clash),
    then remove src_dir. Appends {"from", "path"} (paths relative to base) for every file."""
    os.makedirs(dst_dir, exist_ok=True)
    for name in os.listdir(src_dir):
        src = os.path.join(src_dir, name)
        if os.path.isdir(src) and not os.path.islink(src):
            target = os.path.join(dst_dir, name)
            if os.path.exists(target) and not os.path.isdir(target):
                target = os.path.join(dst_dir, _free_name(dst_dir, name))
            _move_tree(src, target, base, moved)
        else:
            dst = os.path.join(dst_dir, _free_name(dst_dir, name))
            shutil.move(src, dst)
            moved.append({"from": _rel(base, src), "path": _rel(base, dst)})
    try:
        os.rmdir(src_dir)
    except OSError:
        pass


@routes.post("/comfymobile/output/move_folder")
async def move_folder(request):
    """Move a whole folder: {"from": "a", "to": "b"} renames a -> b (merging if b exists);
    "to": "" moves its contents into the output folder itself."""
    body = await request.json()
    base = _base()
    try:
        src = _resolve(base, body.get("from"))
        dst = _resolve(base, body.get("to"))
    except ValueError as e:
        return web.json_response({"error": str(e)}, status=400)
    if src == base or not os.path.isdir(src):
        return web.json_response({"error": "no such folder"}, status=400)
    if os.path.commonpath((src, dst)) == src:
        return web.json_response({"error": "cannot move a folder into itself"}, status=400)
    moved = []
    try:
        await asyncio.get_running_loop().run_in_executor(None, _move_tree, src, dst, base, moved)
    except Exception as e:
        return web.json_response({"moved": moved, "error": str(e)}, status=500)
    return web.json_response({"moved": moved})


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
