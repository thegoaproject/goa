#pragma once

// Runs a libretro slang filter (compiled to GLSL ES 3.00 by scripts/filters/build-filters.py) as a chain of passes:
// per-pass scale and format, aliases, PassOutput#/PassFeedback#, Original and OriginalHistory#, lookup textures,
// FrameCount and parameters (research D3). Render thread only.

#include <GLES3/gl3.h>
#include <cstdint>
#include <map>
#include <string>
#include <vector>
#include "FilterBundle.h"

class FilterChain {
public:
    // Compiles every pass and uploads the LUTs; false with the driver's log if anything fails
    bool load(const FilterBundle& bundle, std::string& error);
    void destroy();
    bool loaded() const { return !m_passes.empty(); }
    const std::string& id() const { return m_id; }

    void setParam(size_t index, float value);

    // Draws the chain: `original` (from ImportPass, size ow x oh) through every pass; the last one into the bound
    // default framebuffer at the viewport rect. `originalFbo` is read to keep the history ring.
    void render(GLuint original, GLuint originalFbo, int ow, int oh, int vpX, int vpY, int vpW, int vpH, uint32_t frameCount);

private:
    struct Target {
        GLuint tex{0}, fbo{0};
        int w{0}, h{0};
        GLenum format{0};
    };
    // Resolved once at load, so a frame does no name lookups
    enum class Kind { Source, Original, History, PassOutput, PassFeedback, Lut, Output, Viewport };
    struct Binding {
        GLint loc;
        Kind kind;
        int index;   // history depth (0 = Original), pass index or LUT index
    };
    struct Pass {
        FilterPassDesc desc;
        std::vector<Binding> textures, sizes;
        GLint mvpLoc{-1}, frameCountLoc{-1}, frameDirectionLoc{-1};
        std::string frameCountType, frameDirectionType;
        GLuint program{0};
        GLuint inputSampler{0};               // this pass's filter/wrap, for Source/Original/history
        std::map<std::string, GLint> uniformLoc;   // semantic -> location
        std::map<std::string, GLint> samplerLoc;   // texture semantic -> location
        Target out, feedback;
        bool feedbackUsed{false};
        GLenum format{GL_RGBA8};
    };
    struct Lut {
        std::string name;
        GLuint tex{0}, sampler{0};
        int w{0}, h{0};
    };

    void ensureTarget(Target& t, int w, int h, GLenum format, bool mipmapped);
    void deleteTarget(Target& t);
    GLuint samplerFor(bool linear, const std::string& wrap, bool mipmap);
    void resolveBindings();
    void bindTextures(size_t index, int ow, int oh, GLuint original, int vpW, int vpH);

    std::string m_id;
    std::vector<Pass> m_passes;
    std::vector<Lut> m_luts;
    std::vector<FilterParamDesc> m_params;
    std::vector<std::vector<std::pair<int, GLint>>> m_paramLocs;   // per parameter: (pass, location)
    std::vector<Target> m_history;                                   // OriginalHistory1..N, newest first
    std::map<std::string, size_t> m_aliasIndex;
    std::vector<GLuint> m_samplers;                                  // owned sampler objects
    GLuint m_vao{0}, m_vbo{0};
    bool m_halfFloatOk{false};
};
