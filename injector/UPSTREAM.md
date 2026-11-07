# Native loader origin

The native JNI/JVMTI bridge, raw bindings, OS loading backends and CLI were adapted
from [SkidSolutions/OpenPrestige](https://codeberg.org/SkidSolutions/OpenPrestige).

Reference revision: `9b36349e1a87ebd5af9c9f8711bdd78e48847cac`.

Logic changes include product names, payload packaging, paths, startup checks and
all Java live-transform hooks. The Windows native loading method remains the
reference's LoadLibraryW + JNI/JVMTI flow. Unix backends are retained in source but
are not validated by the Windows build. The Minecraft 26.2/Mica runtime integration
is Logic project code, rather than OpenPrestige's Minecraft 1.21.11 payload.
