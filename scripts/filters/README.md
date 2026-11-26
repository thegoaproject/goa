# GOA video filters

GOA's video filters are libretro **slang** shaders, compiled here (not on the phone) into GLSL ES 3.00 bundles under
`app/src/main/assets/filters/<id>/`. The generated files are committed, so building the app needs none of these tools.

## Toolchain (only to regenerate filters)

Exactly these versions (the script refuses others, so output stays reproducible):

- `glslangValidator` 16.6.0 (Khronos glslang, package 1.4.363 / vulkan-sdk-1.4.363.0)
- `spirv-cross` 1.4.363 (vulkan-sdk-1.4.363.0)

On Arch/CachyOS: `sudo pacman -S glslang spirv-cross`.

## Use

```sh
python3 scripts/filters/build-filters.py --check      # tools, licences, pinned sources; writes nothing
python3 scripts/filters/build-filters.py               # regenerates every bundle
python3 scripts/filters/build-filters.py --only crt-light
python3 -m pytest scripts/filters                      # the script's own tests
```

- `filters.toml`: GOA's lineup (names, descriptions, upstream preset, licences, GOA labels and defaults).
- `upstream/`: pinned upstream sources (see `upstream/PINNED.md`). Never edit; adapt in the script or in `goa/`.
- `goa/`: GOA's own presets and wrappers (DOS CRT, Sharp Pixels, Clean Upscale).

Bundle format: specs/008-shader-filters/contracts/filter-bundle.md.
