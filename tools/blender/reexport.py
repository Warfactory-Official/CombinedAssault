"""Import a pack model and export it back in place through the add-on, headless.

    blender -b --factory-startup --python "$PWD/tools/blender/reexport.py" -- <pack model.gltf>
"""
import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import combinedassault_tools as T  # noqa: E402

T.register()
path = sys.argv[sys.argv.index("--") + 1]
bpy.ops.wm.read_factory_settings(use_empty=True)
print("import", bpy.ops.combinedassault.import_model(filepath=path))
print("export", bpy.ops.combinedassault.export(), bpy.context.scene.combinedassault_export.note)
