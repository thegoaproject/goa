#include "FilterBundle.h"
#include "MiniJson.h"

namespace {
FilterScale scaleFrom(const minijson::Value& v) {
    FilterScale s;
    const std::string type = v["type"].str("source");
    s.type = type == "viewport" ? FilterScale::Type::Viewport
           : type == "absolute" ? FilterScale::Type::Absolute
           : FilterScale::Type::Source;
    s.factor = (float)v["factor"].num(1.0);
    return s;
}
}  // namespace

bool FilterBundle::fromJson(const std::string& json, const std::vector<std::string>& vertexSources,
                            const std::vector<std::string>& fragmentSources, FilterBundle& out, std::string& error) {
    minijson::Value root;
    if (!minijson::Parser(json).parse(root, error)) return false;
    if ((int)root["format"].num(0) != 1) { error = "unsupported bundle format"; return false; }

    out = FilterBundle{};
    out.id = root["id"].str();
    out.historyDepth = (int)root["historyDepth"].num(0);

    const auto& passes = root["passes"].array;
    if (passes.empty()) { error = "no passes"; return false; }
    if (passes.size() != vertexSources.size() || passes.size() != fragmentSources.size()) {
        error = "pass sources don't match filter.json";
        return false;
    }
    for (size_t i = 0; i < passes.size(); ++i) {
        const auto& p = passes[i];
        FilterPassDesc d;
        d.vertexSource = vertexSources[i];
        d.fragmentSource = fragmentSources[i];
        d.alias = p["alias"].str();
        d.scaleX = scaleFrom(p["scaleX"]);
        d.scaleY = scaleFrom(p["scaleY"]);
        d.filterLinear = p["filterLinear"].flag(true);
        d.wrap = p["wrap"].str("clamp_to_edge");
        d.mipmapInput = p["mipmapInput"].flag(false);
        d.format = p["format"].str("RGBA8");
        d.frameCountMod = (int)p["frameCountMod"].num(0);
        for (const auto& [semantic, u] : p["uniforms"].object) {
            d.uniforms[semantic] = FilterUniformRef{u["name"].str(), u["type"].str("float")};
        }
        for (const auto& [semantic, name] : p["samplers"].object) d.samplers[semantic] = name.str();
        out.passes.push_back(std::move(d));
    }
    for (const auto& t : root["textures"].array) {
        FilterLut l;
        l.name = t["name"].str();
        l.linear = t["linear"].flag(false);
        l.wrap = t["wrap"].str("clamp_to_edge");
        l.mipmap = t["mipmap"].flag(false);
        out.luts.push_back(std::move(l));   // pixels are attached by the caller
    }
    for (const auto& p : root["parameters"].array) {
        FilterParamDesc d;
        d.id = p["id"].str();
        d.value = (float)p["default"].num(0);
        for (const auto& u : p["uniforms"].array) d.uniforms.emplace_back((int)u["pass"].num(0), u["name"].str());
        out.params.push_back(std::move(d));
    }
    return true;
}
