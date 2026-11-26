#pragma once

// A video filter as the renderer runs it: the parsed filter.json plus the GLSL ES sources and LUT pixels Kotlin read
// from assets (FilterCatalog). Format: specs/008-shader-filters/contracts/filter-bundle.md.

#include <cstdint>
#include <map>
#include <string>
#include <vector>

struct FilterScale {
    enum class Type { Source, Viewport, Absolute } type{Type::Source};
    float factor{1.0f};
};

struct FilterUniformRef {
    std::string name;   // GL uniform name, e.g. "params.SourceSize"
    std::string type;   // "vec4", "mat4", "uint", "int", "float"
};

struct FilterPassDesc {
    std::string vertexSource;
    std::string fragmentSource;
    std::string alias;
    FilterScale scaleX, scaleY;
    bool filterLinear{true};
    std::string wrap{"clamp_to_edge"};
    bool mipmapInput{false};
    std::string format{"RGBA8"};
    int frameCountMod{0};
    std::map<std::string, FilterUniformRef> uniforms;   // semantic -> uniform
    std::map<std::string, std::string> samplers;        // texture semantic or alias -> sampler name
};

struct FilterLut {
    std::string name;
    int width{0}, height{0};
    bool linear{false};
    std::string wrap{"clamp_to_edge"};
    bool mipmap{false};
    std::vector<uint8_t> rgba;
};

struct FilterParamDesc {
    std::string id;
    float value{0};                                        // current value (Kotlin sends defaults or saved values)
    std::vector<std::pair<int, std::string>> uniforms;     // (pass, uniform name)
};

struct FilterBundle {
    std::string id;
    int historyDepth{0};
    std::vector<FilterPassDesc> passes;
    std::vector<FilterLut> luts;
    std::vector<FilterParamDesc> params;

    // Builds the bundle from filter.json and the per-pass sources (in pass order). False with a reason on bad input.
    static bool fromJson(const std::string& json, const std::vector<std::string>& vertexSources,
                         const std::vector<std::string>& fragmentSources, FilterBundle& out, std::string& error);
};
