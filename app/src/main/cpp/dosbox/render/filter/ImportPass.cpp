#include "ImportPass.h"
#include "GlUtil.h"

namespace {
const char* kVertex = R"(#version 300 es
layout(location = 0) in vec4 Position;
layout(location = 1) in vec2 TexCoord;
uniform highp vec2 uUvScale;
uniform float uFlipY;
out highp vec2 vUv;
void main() {
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
    float v = uFlipY > 0.5 ? 1.0 - TexCoord.y : TexCoord.y;
    vUv = vec2(TexCoord.x, v) * uUvScale;
}
)";

const char* kFragment = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
in highp vec2 vUv;
out vec4 fragColor;
void main() {
    // Core-drawn frames can carry alpha 0; filters expect an opaque picture
    fragColor = vec4(texture(uTex, vUv).rgb, 1.0);
}
)";
}  // namespace

bool ImportPass::init() {
    std::string log;
    m_program = glutil::buildProgram(kVertex, kFragment, log);
    if (!m_program) {
        LOGE_GLU("ImportPass: %s", log.c_str());
        return false;
    }
    m_locUvScale = glGetUniformLocation(m_program, "uUvScale");
    m_locFlipY = glGetUniformLocation(m_program, "uFlipY");
    m_locTex = glGetUniformLocation(m_program, "uTex");
    glutil::makeQuad(m_vao, m_vbo);
    glGenSamplers(1, &m_sampler);
    // Nearest: the Original is pixel-exact (and a double-scanned row must not blend with its neighbour)
    glSamplerParameteri(m_sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glSamplerParameteri(m_sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glSamplerParameteri(m_sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glSamplerParameteri(m_sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return true;
}

void ImportPass::destroy() {
    if (m_program) glDeleteProgram(m_program);
    if (m_vao) glDeleteVertexArrays(1, &m_vao);
    if (m_vbo) glDeleteBuffers(1, &m_vbo);
    if (m_sampler) glDeleteSamplers(1, &m_sampler);
    if (m_fbo) glDeleteFramebuffers(1, &m_fbo);
    if (m_texture) glDeleteTextures(1, &m_texture);
    *this = ImportPass{};
}

GLuint ImportPass::run(GLuint source, int width, int height, float uScale, float vScale, bool flipY, bool doubleScan,
                       int& outWidth, int& outHeight) {
    if (!m_program || width <= 0 || height <= 0) return 0;
    const int h = (doubleScan && height < 400) ? height * 2 : height;
    if (width != m_w || h != m_h || !m_texture) {
        if (!glutil::allocTarget(m_texture, m_fbo, width, h, GL_RGBA8)) return 0;
        m_w = width;
        m_h = h;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, m_fbo);
    glViewport(0, 0, m_w, m_h);
    glUseProgram(m_program);
    glUniform2f(m_locUvScale, uScale, vScale);
    glUniform1f(m_locFlipY, flipY ? 1.0f : 0.0f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, source);
    glBindSampler(0, m_sampler);
    glUniform1i(m_locTex, 0);
    glBindVertexArray(m_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glBindSampler(0, 0);
    outWidth = m_w;
    outHeight = m_h;
    return m_texture;
}
