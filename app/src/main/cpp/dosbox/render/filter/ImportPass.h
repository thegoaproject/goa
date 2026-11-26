#pragma once

#include <GLES3/gl3.h>

// Draws each game frame, whatever its source, into one top-left-origin RGBA8 "Original" texture at the game's own
// resolution (research D4): CPU frames (already uploaded, swizzled) and textures a core drew with the GPU (UV scale and
// Y flip). With doubleScan, a frame under 400 lines is written with each row twice, as a VGA card drove a 31 kHz
// monitor (research D5); filters then draw thin VGA scanlines without knowing about it.
class ImportPass {
public:
    bool init();
    void destroy();

    // Returns the Original texture (and its size), or 0 if the pass isn't ready
    GLuint run(GLuint source, int width, int height, float uScale, float vScale, bool flipY, bool doubleScan,
               int& outWidth, int& outHeight);

    GLuint framebuffer() const { return m_fbo; }

private:
    GLuint m_program{0};
    GLuint m_vao{0}, m_vbo{0};
    GLuint m_sampler{0};
    GLuint m_texture{0}, m_fbo{0};
    int m_w{0}, m_h{0};
    GLint m_locUvScale{-1}, m_locFlipY{-1}, m_locTex{-1};
};
