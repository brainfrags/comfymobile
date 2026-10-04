# Lets ComfyUI load this whole folder as a custom node
# (custom_nodes/comfyui-extension/), so the comfymobile_files subfolder works
# whether it is copied directly into custom_nodes or left inside this folder.
from .comfymobile_files import NODE_CLASS_MAPPINGS, NODE_DISPLAY_NAME_MAPPINGS

__all__ = ["NODE_CLASS_MAPPINGS", "NODE_DISPLAY_NAME_MAPPINGS"]
