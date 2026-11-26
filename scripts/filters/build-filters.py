#!/usr/bin/env python3
"""Compiles GOA's video filters (libretro slang presets) into GLSL ES 3.00 bundles for the app.

    python3 scripts/filters/build-filters.py [--check] [--only <id>]

For each filter in filters.toml: parse its .slangp, and for every pass resolve #includes, split the vertex/fragment
stages, collect #pragma parameter/name/format, compile each stage to SPIR-V (glslangValidator, Vulkan semantics) and back
to GLSL ES 3.00 (spirv-cross, uniform buffers and push constants as plain uniform structs, so the app sets everything
by name). Writes app/src/main/assets/filters/<id>/filter.json plus pass sources and LUT images. Format:
specs/008-shader-filters/contracts/filter-bundle.md. Stdlib only.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUT_ROOT = ROOT / "app/src/main/assets/filters"

GLSLANG_VERSION = "16.6.0"                 # glslangValidator --version, "Glslang Version: 11:16.6.0"
SPIRV_CROSS_REVISION = "vulkan-sdk-1.4.363.0"

LICENCES = {"Public domain", "MIT", "BSD-3-Clause", "GPL-2.0-or-later", "GPL-3.0-or-later", "GPL (any version)"}
SPEEDS = {"LIGHT", "MEDIUM", "HEAVY", "UNMEASURED"}

# What the app's runtime provides (research D3). Anything else fails the build, never the phone.
SIZE_SEMANTICS = {"SourceSize", "OriginalSize", "OutputSize", "FinalViewportSize"}
SCALAR_SEMANTICS = {"MVP", "FrameCount", "FrameDirection"}
INDEXED = re.compile(r"^(OriginalHistory|PassOutput|PassFeedback)(\d+)$")
INDEXED_SIZE = re.compile(r"^(OriginalHistory|PassOutput|PassFeedback)Size(\d+)$")

FORMAT_MAP = {  # #pragma format -> GL internal format the runtime supports
    "R8G8B8A8_UNORM": "RGBA8", "R8G8B8A8_SRGB": "SRGB8_ALPHA8",
    "R16G16B16A16_SFLOAT": "RGBA16F", "R32G32B32A32_SFLOAT": "RGBA16F",  # 32-bit float isn't renderable everywhere
    "A2B10G10R10_UNORM_PACK32": "RGBA8",
}


class BuildError(Exception):
    pass


# --- Shader preprocessing ---------------------------------------------------------------------------------------------

@dataclass
class Parameter:
    id: str
    label: str
    default: float
    min: float
    max: float
    step: float


@dataclass
class ShaderSource:
    vertex: str
    fragment: str
    parameters: list[Parameter] = field(default_factory=list)
    name: str | None = None          # #pragma name (an alias the shader gives itself)
    format: str | None = None        # #pragma format


PARAM_RE = re.compile(r'^\s*#pragma\s+parameter\s+(\w+)\s+"([^"]*)"\s+([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)(?:\s+([-\d.eE+]+))?')


def resolve_includes(path: Path, seen: tuple[Path, ...] = ()) -> list[str]:
    """The file's lines with every #include "x" replaced by x's lines (relative to the including file)."""
    path = path.resolve()
    if path in seen:
        raise BuildError(f"include cycle at {path}")
    lines: list[str] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True):
        m = re.match(r'\s*#include\s+"([^"]+)"', line)
        if m:
            lines += resolve_includes(path.parent / m.group(1), seen + (path,))
        else:
            lines.append(line if line.endswith("\n") else line + "\n")
    return lines


def parse_shader(path: Path) -> ShaderSource:
    """Splits a .slang into its two stages, keeping the shared prologue in both, and collects its pragmas."""
    common: list[str] = []
    stages: dict[str, list[str]] = {"vertex": [], "fragment": []}
    stage: str | None = None
    params: dict[str, Parameter] = {}
    name = fmt = None
    for line in resolve_includes(path):
        m = re.match(r"\s*#pragma\s+stage\s+(\w+)", line)
        if m:
            stage = m.group(1)
            if stage not in stages:
                raise BuildError(f"{path}: unknown stage {stage}")
            continue
        pm = PARAM_RE.match(line)
        if pm:
            pid, label, init, lo, hi, step = pm.groups()
            p = Parameter(pid, label.strip(), float(init), float(lo), float(hi), float(step) if step else 0.01)
            if pid in params and params[pid] != p:
                raise BuildError(f"{path}: parameter {pid} declared twice with different values")
            params[pid] = p
            continue
        m = re.match(r"\s*#pragma\s+name\s+(\w+)", line)
        if m:
            name = m.group(1)
            continue
        m = re.match(r"\s*#pragma\s+format\s+(\w+)", line)
        if m:
            fmt = m.group(1)
            continue
        (common if stage is None else stages[stage]).append(line)
    version = [l for l in common if l.lstrip().startswith("#version")]
    if not version:
        raise BuildError(f"{path}: no #version")
    body = [l for l in common if not l.lstrip().startswith("#version")]
    head = version[:1]
    return ShaderSource(
        vertex="".join(head + body + stages["vertex"]),
        fragment="".join(head + body + stages["fragment"]),
        parameters=list(params.values()),
        name=name,
        format=fmt,
    )


# --- Preset parsing -----------------------------------------------------------------------------------------------------

def parse_preset(path: Path) -> dict[str, str]:
    """key = value lines of a .slangp (quotes stripped, # comments ignored)."""
    kv: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip() if not raw.strip().startswith("#") else ""
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        kv[k.strip()] = v.strip().strip('"')
    return kv


def _bool(v: str | None, default: bool = False) -> bool:
    return default if v is None else v.strip().lower() in ("true", "1")


def pass_scale(kv: dict[str, str], i: int, last: bool) -> tuple[dict, dict]:
    def axis(a: str) -> dict:
        t = kv.get(f"scale_type_{a}{i}") or kv.get(f"scale_type{i}")
        f = kv.get(f"scale_{a}{i}") or kv.get(f"scale{i}")
        if t is None:
            return {"type": "viewport" if last else "source", "factor": 1.0}
        if t not in ("source", "viewport", "absolute"):
            raise BuildError(f"pass {i}: scale_type {t}")
        return {"type": t, "factor": float(f) if f is not None else 1.0}
    return axis("x"), axis("y")


# --- Compilation ---------------------------------------------------------------------------------------------------------

def run(cmd: list[str], cwd: str) -> str:
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if p.returncode != 0:
        raise BuildError(f"{' '.join(cmd)} failed:\n{p.stdout}\n{p.stderr}")
    return p.stdout


PRECISION_TYPES = re.compile(r"^(?:u?int|float|[iu]?vec[234]|mat[234](?:x[234])?)$")


def unify_uniform_precision(glsl: str) -> str:
    """Every member of a uniform struct gets an explicit highp, in both stages.

    SPIRV-Cross writes `highp vec4 SourceSize;` in the fragment shader but plain `vec4 SourceSize;` in the vertex
    shader (highp by default there). Some drivers (Adreno) compare the declarations literally and refuse to link with
    "Uniform params precision mismatch with other stage", so the two must read the same.
    """
    uniform_types = {stype for stype, _ in UNIFORM_STRUCT_RE.findall(glsl)}

    def fix_struct(m: re.Match) -> str:
        if m.group(1) not in uniform_types:
            return m.group(0)
        lines = []
        for line in m.group(2).split("\n"):
            mm = re.match(r"^(\s*)(?:(?:highp|mediump|lowp)\s+)?(\w+)(\s+\w+.*;\s*)$", line)
            if mm and PRECISION_TYPES.match(mm.group(2)):
                line = f"{mm.group(1)}highp {mm.group(2)}{mm.group(3)}"
            lines.append(line)
        return f"struct {m.group(1)}\n{{" + "\n".join(lines) + "}"

    return re.sub(r"struct\s+(\w+)\s*\{([^}]*)\}", fix_struct, glsl)


def compile_stage(src: str, stage: str, tmp: str, tag: str) -> str:
    ext = "vert" if stage == "vertex" else "frag"
    src_path = os.path.join(tmp, f"{tag}.{ext}")
    spv = os.path.join(tmp, f"{tag}.{ext}.spv")
    with open(src_path, "w", encoding="utf-8") as fh:
        fh.write(src)
    run(["glslangValidator", "-V", "--target-env", "vulkan1.0", "-S", ext, "-o", spv, src_path], tmp)
    glsl = run(["spirv-cross", spv, "--es", "--version", "300", "--glsl-emit-ubo-as-plain-uniforms"], tmp)
    return unify_uniform_precision(glsl)


STRUCT_RE = re.compile(r"struct\s+(\w+)\s*\{([^}]*)\}\s*;", re.S)
MEMBER_RE = re.compile(r"(?:(?:highp|mediump|lowp)\s+)?(\w+)\s+(\w+)\s*;")
UNIFORM_STRUCT_RE = re.compile(r"^\s*uniform\s+(\w+)\s+(\w+)\s*;", re.M)
SAMPLER_RE = re.compile(r"^\s*uniform\s+(?:(?:highp|mediump|lowp)\s+)?sampler2D\s+(\w+)\s*;", re.M)


def reflect(glsl: str) -> tuple[dict[str, dict], list[str]]:
    """{member: {"name": "inst.member", "type": t}} for every plain uniform struct member, and the sampler names."""
    structs = {m.group(1): MEMBER_RE.findall(m.group(2)) for m in STRUCT_RE.finditer(glsl)}
    members: dict[str, dict] = {}
    for stype, inst in UNIFORM_STRUCT_RE.findall(glsl):
        if stype not in structs:
            continue
        for mtype, mname in structs[stype]:
            members[mname] = {"name": f"{inst}.{mname}", "type": mtype}
    return members, SAMPLER_RE.findall(glsl)


def classify(members: dict[str, dict], samplers: list[str], param_ids: set[str], aliases: set[str],
             luts: set[str], where: str) -> tuple[dict, dict, dict]:
    """Splits reflected names into semantic uniforms, parameter uniforms and samplers; fails on unknown ones."""
    uniforms, params, texs = {}, {}, {}
    alias_sizes = {a + "Size" for a in aliases} | {a + "FeedbackSize" for a in aliases} | {l + "Size" for l in luts}
    for name, info in members.items():
        if name in param_ids:
            params[name] = info
        elif name in SIZE_SEMANTICS or name in SCALAR_SEMANTICS or INDEXED_SIZE.match(name) or name in alias_sizes:
            uniforms[name] = info
        else:
            raise BuildError(f"{where}: uniform {name} isn't a semantic the runtime provides")
    alias_feedback = {a + "Feedback" for a in aliases}
    for s in samplers:
        if s in ("Source", "Original") or INDEXED.match(s) or s in aliases or s in alias_feedback or s in luts:
            texs[s] = s
        else:
            raise BuildError(f"{where}: texture {s} isn't one the runtime provides")
    return uniforms, params, texs


# --- Bundles ---------------------------------------------------------------------------------------------------------------

def build_filter(fid: str, cfg: dict, out_root: Path, write: bool) -> dict:
    for c in cfg.get("credits", []):
        if c.get("licence") not in LICENCES:
            raise BuildError(f"{fid}: licence {c.get('licence')!r} isn't GPL-3.0-compatible (allowed: {sorted(LICENCES)})")
    if not cfg.get("credits"):
        raise BuildError(f"{fid}: no credits")
    if cfg.get("speed") not in SPEEDS:
        raise BuildError(f"{fid}: speed {cfg.get('speed')!r}")

    preset_path = HERE / cfg["preset"]
    if preset_path.suffix == ".slang":   # a bare shader: one viewport pass
        kv = {"shaders": "1", "shader0": preset_path.name, "scale_type0": "viewport", "filter_linear0": "true"}
        base = preset_path.parent
    else:
        kv = parse_preset(preset_path)
        base = preset_path.parent
    n = int(kv["shaders"])

    textures = []
    for name in [t.strip() for t in kv.get("textures", "").split(";") if t.strip()]:
        textures.append({
            "name": name,
            "file": f"lut/{Path(kv[name]).name}",
            "src": str((base / kv[name]).resolve()),
            "linear": _bool(kv.get(f"{name}_linear")),
            "wrap": kv.get(f"{name}_wrap_mode", "clamp_to_edge"),
            "mipmap": _bool(kv.get(f"{name}_mipmap")),
        })
    luts = {t["name"] for t in textures}

    sources = [parse_shader(base / kv[f"shader{i}"]) for i in range(n)]
    aliases = {}
    for i, s in enumerate(sources):
        a = kv.get(f"alias{i}") or s.name
        if a:
            aliases[i] = a
    alias_set = set(aliases.values())

    merged: dict[str, Parameter] = {}
    for s in sources:
        for p in s.parameters:
            if p.id in merged and merged[p.id] != p:
                raise BuildError(f"{fid}: parameter {p.id} differs between passes")
            merged[p.id] = p
    for pid, p in merged.items():          # preset overrides (key = parameter id)
        if pid in kv:
            p.default = float(kv[pid])
    for pid, v in cfg.get("defaults", {}).items():
        if pid not in merged:
            raise BuildError(f"{fid}: default for unknown parameter {pid}")
        merged[pid].default = float(v)
    labels, hidden = cfg.get("labels", {}), set(cfg.get("hidden", []))

    passes, param_uniforms, history = [], {pid: [] for pid in merged}, 0
    out_dir = out_root / fid
    with tempfile.TemporaryDirectory() as tmp:
        for i, s in enumerate(sources):
            last = i == n - 1
            vert = compile_stage(s.vertex, "vertex", tmp, f"p{i}")
            frag = compile_stage(s.fragment, "fragment", tmp, f"p{i}")
            mv, sv = reflect(vert)
            mf, sf = reflect(frag)
            # A struct member the shader never reads (declared, then replaced by a #define upstream) needs no value
            # ("params.X" only appears where the code reads it; the struct definition just says "X")
            used = vert + frag
            members = {k: v for k, v in {**mv, **mf}.items() if re.search(re.escape(v["name"]) + r"\b", used)}
            uniforms, puniforms, samplers = classify(members, sv + sf, set(merged), alias_set, luts, f"{fid} pass {i}")
            for pid, info in puniforms.items():
                param_uniforms[pid].append({"pass": i, "name": info["name"]})
            for name in list(samplers) + list(uniforms):
                m = INDEXED.match(name) or INDEXED_SIZE.match(name)
                if m and m.group(1) == "OriginalHistory":
                    history = max(history, int(m.group(2)))
            fmt = "RGBA8"
            if s.format:
                fmt = FORMAT_MAP.get(s.format) or (_ for _ in ()).throw(BuildError(f"{fid} pass {i}: format {s.format}"))
            if _bool(kv.get(f"float_framebuffer{i}")):
                fmt = "RGBA16F"
            if _bool(kv.get(f"srgb_framebuffer{i}")):
                fmt = "SRGB8_ALPHA8"
            sx, sy = pass_scale(kv, i, last)
            passes.append({
                "vertex": f"pass{i}.vert", "fragment": f"pass{i}.frag",
                "alias": aliases.get(i),
                "scaleX": sx, "scaleY": sy,
                "filterLinear": _bool(kv.get(f"filter_linear{i}"), default=True),
                "wrap": kv.get(f"wrap_mode{i}", "clamp_to_edge"),
                "mipmapInput": _bool(kv.get(f"mipmap_input{i}")),
                "format": fmt,
                "frameCountMod": int(kv.get(f"frame_count_mod{i}", "0")),
                "uniforms": uniforms,
                "samplers": samplers,
            })
            if write:
                out_dir.mkdir(parents=True, exist_ok=True)
                (out_dir / f"pass{i}.vert").write_text(vert, encoding="utf-8")
                (out_dir / f"pass{i}.frag").write_text(frag, encoding="utf-8")

    bundle = {
        "format": 1,
        "id": fid,
        "name": cfg["name"],
        "description": cfg["description"],
        "speed": cfg["speed"],
        "historyDepth": history,
        "textures": [{k: v for k, v in t.items() if k != "src"} for t in textures],
        "passes": passes,
        "parameters": [
            {"id": p.id, "label": labels.get(p.id, p.label), "min": p.min, "max": p.max, "step": p.step,
             "default": p.default, "visible": p.id not in hidden, "uniforms": param_uniforms[p.id]}
            for p in merged.values() if param_uniforms[p.id]
        ],
        "credits": cfg["credits"],
    }
    if write:
        (out_dir / "lut").mkdir(exist_ok=True) if textures else None
        for t in textures:
            shutil.copyfile(t["src"], out_dir / t["file"])
        (out_dir / "filter.json").write_text(json.dumps(bundle, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return bundle


def check_tools() -> None:
    try:
        g = subprocess.run(["glslangValidator", "--version"], capture_output=True, text=True)
        s = subprocess.run(["spirv-cross", "--revision"], capture_output=True, text=True)
        g, s = g.stdout + g.stderr, s.stdout + s.stderr   # spirv-cross prints its revision on stderr
    except FileNotFoundError as e:
        raise BuildError(f"missing tool: {e.filename} (see scripts/filters/README.md)")
    if GLSLANG_VERSION not in g:
        raise BuildError(f"glslangValidator {GLSLANG_VERSION} required, found: {g.splitlines()[0] if g else '?'}")
    if SPIRV_CROSS_REVISION not in s:
        raise BuildError(f"spirv-cross {SPIRV_CROSS_REVISION} required, found: {s.strip()}")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="validate tools, licences and sources; write nothing")
    ap.add_argument("--only", help="build one filter id")
    args = ap.parse_args(argv)
    lineup = tomllib.loads((HERE / "filters.toml").read_text(encoding="utf-8"))
    try:
        check_tools()
        ids = [args.only] if args.only else list(lineup)
        for fid in ids:
            if fid not in lineup:
                raise BuildError(f"unknown filter {fid}")
            if not (HERE / lineup[fid]["preset"]).exists():
                if args.only:
                    raise BuildError(f"{fid}: preset {lineup[fid]['preset']} not found")
                print(f"skip {fid}: preset {lineup[fid]['preset']} not written yet")
                continue
            if not args.check and (OUT_ROOT / fid).exists():
                shutil.rmtree(OUT_ROOT / fid)
            b = build_filter(fid, lineup[fid], OUT_ROOT, write=not args.check)
            print(f"{'ok' if args.check else 'built'} {fid}: {len(b['passes'])} pass(es), {len(b['parameters'])} parameter(s)")
    except BuildError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
