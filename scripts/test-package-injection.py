import contextlib
import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from zipfile import ZipFile


flatten = runpy.run_path(str(Path(__file__).with_name("package-injection.py")))["flatten"]


class PackagingChecks(unittest.TestCase):
    def test_embedded_windows_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "mod.jar"
            dependency = io.BytesIO()
            natives = (
                "io/imgui/java/native-bin/imgui-java64.dll",
                "io/imgui/java/native-bin/libimgui-java64.so",
                "io/imgui/java/native-bin/libimgui-java64.dylib",
                "com/sun/jna/win32-x86-64/jnidispatch.dll",
                "com/sun/jna/win32-x86/jnidispatch.dll",
                "com/sun/jna/win32-aarch64/jnidispatch.dll",
                "com/sun/jna/linux-x86-64/libjnidispatch.so",
            )
            with ZipFile(dependency, "w") as archive:
                for name in natives:
                    archive.writestr(name, name.encode())
                for name in ("imgui/ImGui.class", "imgui/gl3/ImGuiImplGl3.class", "com/sun/jna/Native.class"):
                    archive.writestr(name, name.encode())
            with ZipFile(source, "w") as archive:
                archive.writestr("fabric.mod.json", json.dumps({"jars": [{"file": "dependency.jar"}]}))
                archive.writestr("dependency.jar", dependency.getvalue())
                archive.writestr("dev/technix/logic/inject/Bootstrap.class", b"bootstrap")
                archive.writestr("dev/technix/mica/api/InjectedMica.class", b"mica")
                archive.writestr("dev/technix/logic/host/RuntimeHost.class", b"host")
                archive.writestr("assets/mica/font.ttf", b"font")
            original = source.read_bytes()
            for windows_only in (False, True):
                payload = root / str(windows_only) / "payload.jar"
                with contextlib.redirect_stdout(io.StringIO()):
                    flatten(source, payload, thin=True, windows_x64=windows_only)
                with ZipFile(payload) as archive:
                    self.assertIn("assets/mica/font.ttf", archive.namelist())
                    self.assertIn("dev/technix/logic/inject/Bootstrap.class", archive.namelist())
                    self.assertNotIn("imgui/ImGui.class", archive.namelist())
                with ZipFile(payload.with_name("logic-runtime.jar")) as archive:
                    self.assertIn("dev/technix/logic/host/RuntimeHost.class", archive.namelist())
                    self.assertIn("imgui/ImGui.class", archive.namelist())
                    expected = {natives[0], natives[3]} if windows_only else set(natives)
                    self.assertEqual(expected, {name for name in archive.namelist() if name in natives})
            self.assertEqual(original, source.read_bytes())


if __name__ == "__main__":
    unittest.main()
