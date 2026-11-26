#include "FilterChain.h"
#include "GlUtil.h"
#include <cmath>
#include <regex>

namespace {
// MVPs for slang's 0..1 quad. Into a pass texture: y 0 -> bottom row (t = 0), so t = 0 holds the image's top, as in
// every texture of the chain. Into the window: flipped, so the image's top is at the top of the screen.
const float kMvpOffscreen[16] = {2, 0, 0, 0,  0, 2, 0, 0,  0, 0, 1, 0,  -1, -1, 0, 1};
const float kMvpScreen[16] = {2, 0, 0, 0,  0, -2, 0, 0,  0, 0, 1, 0,  -1, 1, 0, 1};

GLenum glFormat(const std::string& f, bool halfFloatOk) {
    if (f == "RGBA16F") return halfFloatOk ? GL_RGBA16F : GL_RGBA8;
    if (f == "SRGB8_ALPHA8") return GL_SRGB8_ALPHA8;
    return GL_RGBA8;
}

int scaled(const FilterScale& s, int source, int viewport) {
    float v = s.type == FilterScale::Type::Viewport ? viewport * s.factor
            : s.type == FilterScale::Type::Absolute ? s.factor
            : source * s.factor;
    return std::max(1, (int)std::lround(v));
}

const std::regex kIndexed("^(OriginalHistory|PassOutput|PassFeedback)(Size)?(\\d+)$");
}  // namespace

GLuint FilterChain::samplerFor(bool linear, const std::string& wrap, bool mipmap) {
    GLuint s = 0;
    glGenSamplers(1, &s);
    glSamplerParameteri(s, GL_TEXTURE_MIN_FILTER, mipmap ? (linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST)
                                                         : (linear ? GL_LINEAR : GL_NEAREST));
    glSamplerParameteri(s, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    const GLenum w = glutil::wrapMode(wrap);
    glSamplerParameteri(s, GL_TEXTURE_WRAP_S, w);
    glSamplerParameteri(s, GL_TEXTURE_WRAP_T, w);
    m_samplers.push_back(s);
    return s;
}

bool FilterChain::load(const FilterBundle& bundle, std::string& error) {
    destroy();
    m_id = bundle.id;
    m_halfFloatOk = glutil::hasExtension("GL_EXT_color_buffer_half_float") || glutil::hasExtension("GL_EXT_color_buffer_float");
    if (!m_halfFloatOk) LOGI_GLU("no half-float render targets: RGBA16F passes fall back to RGBA8");

    for (size_t i = 0; i < bundle.passes.size(); ++i) {
        Pass p;
        p.desc = bundle.passes[i];
        std::string log;
        p.program = glutil::buildProgram(p.desc.vertexSource.c_str(), p.desc.fragmentSource.c_str(), log);
        if (!p.program) {
            error = "pass " + std::to_string(i) + ": " + log;
            m_passes.push_back(std::move(p));
            destroy();
            return false;
        }
        for (const auto& [semantic, u] : p.desc.uniforms) p.uniformLoc[semantic] = glGetUniformLocation(p.program, u.name.c_str());
        for (const auto& [semantic, name] : p.desc.samplers) p.samplerLoc[semantic] = glGetUniformLocation(p.program, name.c_str());
        p.inputSampler = samplerFor(p.desc.filterLinear, p.desc.wrap, p.desc.mipmapInput);
        p.format = glFormat(p.desc.format, m_halfFloatOk);
        if (!p.desc.alias.empty()) m_aliasIndex[p.desc.alias] = i;
        m_passes.push_back(std::move(p));
    }
    // Which passes' outputs are read back next frame
    for (const auto& p : m_passes) {
        for (const auto& [semantic, _] : p.desc.samplers) {
            std::smatch m;
            if (std::regex_match(semantic, m, kIndexed) && m[1] == "PassFeedback") {
                size_t idx = std::stoul(m[3]);
                if (idx < m_passes.size()) m_passes[idx].feedbackUsed = true;
            }
            if (semantic.size() > 8 && semantic.compare(semantic.size() - 8, 8, "Feedback") == 0) {
                auto it = m_aliasIndex.find(semantic.substr(0, semantic.size() - 8));
                if (it != m_aliasIndex.end()) m_passes[it->second].feedbackUsed = true;
            }
        }
    }
    for (const auto& l : bundle.luts) {
        if (l.rgba.size() < (size_t)l.width * l.height * 4 || l.width <= 0) {
            error = "LUT " + l.name + " has no pixels";
            destroy();
            return false;
        }
        Lut t;
        t.name = l.name;
        t.w = l.width;
        t.h = l.height;
        glGenTextures(1, &t.tex);
        glBindTexture(GL_TEXTURE_2D, t.tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, l.width, l.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, l.rgba.data());
        if (l.mipmap) glGenerateMipmap(GL_TEXTURE_2D);
        t.sampler = samplerFor(l.linear, l.wrap, l.mipmap);
        m_luts.push_back(t);
    }
    m_params = bundle.params;
    for (const auto& prm : m_params) {
        std::vector<std::pair<int, GLint>> locs;
        for (const auto& [pass, name] : prm.uniforms) {
            if (pass >= 0 && pass < (int)m_passes.size()) {
                locs.emplace_back(pass, glGetUniformLocation(m_passes[pass].program, name.c_str()));
            }
        }
        m_paramLocs.push_back(std::move(locs));
    }
    m_history.resize(bundle.historyDepth);
    resolveBindings();
    glutil::makeQuad(m_vao, m_vbo);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) LOGI_GLU("filter %s: GL error 0x%x while loading (continuing)", m_id.c_str(), err);
    LOGI_GLU("filter %s ready: %zu pass(es), %zu LUT(s), %zu parameter(s), history %d", m_id.c_str(), m_passes.size(),
             m_luts.size(), m_params.size(), bundle.historyDepth);
    return true;
}

void FilterChain::deleteTarget(Target& t) {
    if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
    if (t.tex) glDeleteTextures(1, &t.tex);
    t = Target{};
}

void FilterChain::destroy() {
    for (auto& p : m_passes) {
        if (p.program) glDeleteProgram(p.program);
        deleteTarget(p.out);
        deleteTarget(p.feedback);
    }
    for (auto& l : m_luts) if (l.tex) glDeleteTextures(1, &l.tex);
    for (auto& h : m_history) deleteTarget(h);
    if (!m_samplers.empty()) glDeleteSamplers((GLsizei)m_samplers.size(), m_samplers.data());
    if (m_vao) glDeleteVertexArrays(1, &m_vao);
    if (m_vbo) glDeleteBuffers(1, &m_vbo);
    m_passes.clear();
    m_luts.clear();
    m_params.clear();
    m_paramLocs.clear();
    m_history.clear();
    m_aliasIndex.clear();
    m_samplers.clear();
    m_vao = m_vbo = 0;
    m_id.clear();
}

void FilterChain::setParam(size_t index, float value) {
    if (index < m_params.size()) m_params[index].value = value;
}

void FilterChain::ensureTarget(Target& t, int w, int h, GLenum format, bool mipmapped) {
    if (t.tex && t.w == w && t.h == h && t.format == format) return;
    glutil::allocTarget(t.tex, t.fbo, w, h, format, mipmapped);
    t.w = w;
    t.h = h;
    t.format = format;
}

void FilterChain::resolveBindings() {
    auto lutIndex = [&](const std::string& name) {
        for (size_t k = 0; k < m_luts.size(); ++k) if (m_luts[k].name == name) return (int)k;
        return -1;
    };
    auto endsWith = [](const std::string& s, const char* suffix) {
        size_t n = std::char_traits<char>::length(suffix);
        return s.size() > n && s.compare(s.size() - n, n, suffix) == 0;
    };
    for (auto& p : m_passes) {
        for (const auto& [semantic, loc] : p.samplerLoc) {
            if (loc < 0) continue;
            std::smatch m;
            if (semantic == "Source") p.textures.push_back({loc, Kind::Source, 0});
            else if (semantic == "Original") p.textures.push_back({loc, Kind::Original, 0});
            else if (std::regex_match(semantic, m, kIndexed)) {
                int n = std::stoi(m[3]);
                Kind k = m[1] == "OriginalHistory" ? Kind::History : m[1] == "PassOutput" ? Kind::PassOutput : Kind::PassFeedback;
                p.textures.push_back({loc, k, n});
            } else if (auto it = m_aliasIndex.find(semantic); it != m_aliasIndex.end()) {
                p.textures.push_back({loc, Kind::PassOutput, (int)it->second});
            } else if (endsWith(semantic, "Feedback") && m_aliasIndex.count(semantic.substr(0, semantic.size() - 8))) {
                p.textures.push_back({loc, Kind::PassFeedback, (int)m_aliasIndex[semantic.substr(0, semantic.size() - 8)]});
            } else if (int l = lutIndex(semantic); l >= 0) {
                p.textures.push_back({loc, Kind::Lut, l});
            }
        }
        for (const auto& [semantic, loc] : p.uniformLoc) {
            if (loc < 0) continue;
            std::smatch m;
            if (semantic == "MVP") p.mvpLoc = loc;
            else if (semantic == "FrameCount") { p.frameCountLoc = loc; p.frameCountType = p.desc.uniforms[semantic].type; }
            else if (semantic == "FrameDirection") { p.frameDirectionLoc = loc; p.frameDirectionType = p.desc.uniforms[semantic].type; }
            else if (semantic == "SourceSize") p.sizes.push_back({loc, Kind::Source, 0});
            else if (semantic == "OriginalSize") p.sizes.push_back({loc, Kind::Original, 0});
            else if (semantic == "OutputSize") p.sizes.push_back({loc, Kind::Output, 0});
            else if (semantic == "FinalViewportSize") p.sizes.push_back({loc, Kind::Viewport, 0});
            else if (std::regex_match(semantic, m, kIndexed) && m[2] == "Size") {
                int n = std::stoi(m[3]);
                p.sizes.push_back({loc, m[1] == "OriginalHistory" ? Kind::History : Kind::PassOutput, n});
            } else if (endsWith(semantic, "Size")) {
                std::string base = semantic.substr(0, semantic.size() - 4);
                if (endsWith(base, "Feedback")) base.resize(base.size() - 8);
                if (auto it = m_aliasIndex.find(base); it != m_aliasIndex.end()) p.sizes.push_back({loc, Kind::PassOutput, (int)it->second});
                else if (int l = lutIndex(base); l >= 0) p.sizes.push_back({loc, Kind::Lut, l});
            }
        }
    }
}

void FilterChain::bindTextures(size_t index, int ow, int oh, GLuint original, int vpW, int vpH) {
    Pass& p = m_passes[index];
    const Target* prev = index > 0 ? &m_passes[index - 1].out : nullptr;
    const GLuint source = prev ? prev->tex : original;
    if (p.desc.mipmapInput && prev) {
        glBindTexture(GL_TEXTURE_2D, source);
        glGenerateMipmap(GL_TEXTURE_2D);
    }
    // The pass that would normally read pass N's output decides how it is sampled (as RetroArch does)
    auto passSampler = [&](int n) { return n + 1 < (int)m_passes.size() ? m_passes[n + 1].inputSampler : p.inputSampler; };
    GLint unit = 0;
    for (const auto& b : p.textures) {
        GLuint tex = 0, sampler = p.inputSampler;
        switch (b.kind) {
            case Kind::Source: tex = source; break;
            case Kind::Original: tex = original; break;
            case Kind::History:
                tex = b.index == 0 ? original : (b.index - 1 < (int)m_history.size() ? m_history[b.index - 1].tex : 0);
                break;
            case Kind::PassOutput:
                if (b.index < (int)m_passes.size()) { tex = m_passes[b.index].out.tex; sampler = passSampler(b.index); }
                break;
            case Kind::PassFeedback:
                if (b.index < (int)m_passes.size()) { tex = m_passes[b.index].feedback.tex; sampler = passSampler(b.index); }
                break;
            case Kind::Lut: tex = m_luts[b.index].tex; sampler = m_luts[b.index].sampler; break;
            default: break;
        }
        if (!tex) continue;
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindSampler(unit, sampler);
        glUniform1i(b.loc, unit);
        ++unit;
    }
    const int sw = prev ? prev->w : ow, sh = prev ? prev->h : oh;
    for (const auto& b : p.sizes) {
        int w = 0, h = 0;
        switch (b.kind) {
            case Kind::Source: w = sw; h = sh; break;
            case Kind::Original: w = ow; h = oh; break;
            case Kind::Output: w = p.out.w ? p.out.w : vpW; h = p.out.h ? p.out.h : vpH; break;
            case Kind::Viewport: w = vpW; h = vpH; break;
            case Kind::History:
                if (b.index == 0) { w = ow; h = oh; }
                else if (b.index - 1 < (int)m_history.size()) { w = m_history[b.index - 1].w; h = m_history[b.index - 1].h; }
                break;
            case Kind::PassOutput:
                if (b.index < (int)m_passes.size()) { w = m_passes[b.index].out.w; h = m_passes[b.index].out.h; }
                break;
            case Kind::Lut: w = m_luts[b.index].w; h = m_luts[b.index].h; break;
            default: break;
        }
        if (w > 0 && h > 0) glUniform4f(b.loc, (float)w, (float)h, 1.0f / w, 1.0f / h);
    }
}

void FilterChain::render(GLuint original, GLuint originalFbo, int ow, int oh, int vpX, int vpY, int vpW, int vpH,
                         uint32_t frameCount) {
    if (m_passes.empty() || !original) return;
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);

    // Last frame's outputs become this frame's feedback
    for (auto& p : m_passes) if (p.feedbackUsed) std::swap(p.out, p.feedback);

    int sw = ow, sh = oh;
    for (size_t i = 0; i < m_passes.size(); ++i) {
        Pass& p = m_passes[i];
        const bool last = i + 1 == m_passes.size();
        if (last) {
            p.out.w = vpW;
            p.out.h = vpH;
        } else {
            const int w = scaled(p.desc.scaleX, sw, vpW), h = scaled(p.desc.scaleY, sh, vpH);
            const bool nextWantsMips = i + 1 < m_passes.size() && m_passes[i + 1].desc.mipmapInput;
            ensureTarget(p.out, w, h, p.format, nextWantsMips);
            if (p.feedbackUsed) ensureTarget(p.feedback, w, h, p.format, false);
        }

        glUseProgram(p.program);
        bindTextures(i, ow, oh, original, vpW, vpH);

        if (p.mvpLoc >= 0) glUniformMatrix4fv(p.mvpLoc, 1, GL_FALSE, last ? kMvpScreen : kMvpOffscreen);
        const uint32_t fc = p.desc.frameCountMod > 0 ? frameCount % (uint32_t)p.desc.frameCountMod : frameCount;
        auto setCounter = [](GLint loc, const std::string& type, uint32_t v) {
            if (loc < 0) return;
            if (type == "uint") glUniform1ui(loc, v);
            else if (type == "int") glUniform1i(loc, (GLint)v);
            else glUniform1f(loc, (float)v);
        };
        setCounter(p.frameCountLoc, p.frameCountType, fc);
        setCounter(p.frameDirectionLoc, p.frameDirectionType, 1u);
        for (size_t k = 0; k < m_params.size(); ++k) {
            for (const auto& [pass, loc] : m_paramLocs[k]) {
                if (pass == (int)i && loc >= 0) glUniform1f(loc, m_params[k].value);
            }
        }

        if (last) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(vpX, vpY, vpW, vpH);
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, p.out.fbo);
            glViewport(0, 0, p.out.w, p.out.h);
        }
        glBindVertexArray(m_vao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        sw = p.out.w;
        sh = p.out.h;
    }
    glBindVertexArray(0);
    for (GLint u = 0; u < 16; ++u) glBindSampler(u, 0);

    // History ring: OriginalHistory1 = the Original just used, older ones shift down
    if (!m_history.empty() && originalFbo) {
        Target oldest = m_history.back();
        for (size_t k = m_history.size() - 1; k > 0; --k) m_history[k] = m_history[k - 1];
        m_history[0] = oldest;
        ensureTarget(m_history[0], ow, oh, GL_RGBA8, false);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, originalFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, m_history[0].fbo);
        glBlitFramebuffer(0, 0, ow, oh, 0, 0, ow, oh, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
}
