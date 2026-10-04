# ComfyMobile file operations (ComfyUI extension)

ComfyMobile shows each subfolder of ComfyUI's `output` folder as an album. Adding images
to an album, moving them between albums, renaming or deleting an album moves the files on
the server.

ComfyUI's own API can't list subfolders or move files, so install this small extension on
the PC running ComfyUI:

1. Copy the `comfymobile_files` folder into `ComfyUI/custom_nodes/`
   (copying this whole `comfyui-extension` folder there works too).
   **ComfyUI Desktop** loads custom nodes from its own install folder, not from the
   folder you picked for models/output. On Windows that is
   `%LOCALAPPDATA%\Comfy-Desktop\ComfyUI-Installs\ComfyUI\ComfyUI\custom_nodes`
   (the startup log's "Import times for custom nodes" list shows the folder in use).
2. Restart ComfyUI. The console shows
   `[ComfyMobile] file operations enabled: /comfymobile/output/*`.
3. Check: opening `http://<PC address>:8188/comfymobile/output/list` in a browser shows a
   JSON list of files.
4. In the app, pull down to refresh the gallery.

It adds these routes (paths are always kept inside the output folder):

| Route | What it does |
|---|---|
| `GET /comfymobile/output/list` | All media files in the output tree (newest first) and its subfolders |
| `POST /comfymobile/output/mkdir` | Create a subfolder |
| `POST /comfymobile/output/move` | Move files into a subfolder (renamed as `name (1).png` on a clash) |
| `POST /comfymobile/output/rmdir` | Remove a subfolder if it is empty |
| `POST /comfymobile/output/move_folder` | Move a whole folder (album rename / delete), merging into an existing one |
| `GET /comfymobile/output/duplicates` | Groups of files with identical content (for "Clean up duplicates") |
| `POST /comfymobile/output/delete` | Delete files (the root copies of duplicates) |

With the extension the app also keeps the PC in line with the app: deleting an image for good
(from the app's trash) deletes the file, and on each gallery refresh files deleted in the app
are deleted on the PC, album folders made in the app are created, and older app-only albums
become folders with their images moved into them.

Without the extension the app still works, with limits: subfolders are only found through
the history (or the assets API with `--enable-assets`), and "moving" copies the file into the
album folder through ComfyUI's upload API while the original stays on the disk (hidden in the app).
