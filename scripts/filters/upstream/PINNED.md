# Pinned upstream filter sources

Vendored on 2026-11-13. Never edit these files; adapt in `scripts/filters/build-filters.py` or in GOA presets under
`scripts/filters/goa/`. Only the files GOA's lineup uses are copied.

| Directory | Upstream | Commit |
|---|---|---|
| `slang-shaders/` | https://github.com/libretro/slang-shaders | `2345ee91602a7404d3a1a6a5df6108d7305fdad4` |
| `snapdragon-gsr/` | https://github.com/SnapdragonStudios/snapdragon-gsr | `d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e` |

Licences are stated in each shader's header (slang-shaders has no repository-wide licence) and in
`snapdragon-gsr/LICENSE` (BSD-3-Clause). `filters.toml` records the licence GOA relies on for each filter, and the build
script refuses a filter whose licence isn't GPL-3.0-compatible.
