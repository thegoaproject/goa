#pragma once

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <atomic>
#include <cstdint>
#include <mutex>

/**
 * A GPU context for libretro cores that draw with OpenGL ES (OpenLara for Tomb Raider) instead of handing over pixels.
 *
 * One GLES3 context is created here and bound to the emulation thread, where retro_run (and so every core GL call)
 * runs. GpuRenderer's own context joins its share group, so frames move between them as textures, with no copy back
 * to the CPU.
 *
 * The core draws into one framebuffer for the whole session (OpenLara asks for it once, at context_reset), with the
 * depth and stencil buffers it requested. After each frame it is blitted into one of three presentation textures,
 * fenced, and handed to the presenter; the presenter's texture is never the one being written.
 */
class HwRenderContext {
public:
    static HwRenderContext& instance();

    // The next game's core draws with the GPU: GpuRenderer creates its context in this share group (set before the
    // game starts, cleared when it ends)
    static void setWanted(bool wanted);
    static bool wanted();

    // Any thread: creates the display and the (unbound) context on first use. EGL_NO_CONTEXT on failure.
    EGLContext shareContext();
    EGLDisplay display() const { return m_display; }
    // True from the first shareContext() until destroy(); GpuRenderer must not eglTerminate the display meanwhile
    bool inUse() const { return m_inUse.load(); }

    // Emulation thread only
    bool bindToThisThread(bool depth, bool stencil);
    void destroy();
    bool bound() const { return m_bound; }

    // The size of the core's framebuffer: its max geometry
    void setMaxSize(int w, int h);
    // The framebuffer the core draws into (the same one every frame)
    uintptr_t currentFramebuffer();

    struct Frame {
        GLuint texture = 0;
        int index = 0;
        int fboWidth = 0, fboHeight = 0;
        int width = 0, height = 0;
        GLsync fence = nullptr;
    };
    // After the core's video_refresh(RETRO_HW_FRAME_BUFFER_VALID, w, h): copy the frame out to a presentation texture,
    // fence it and describe it in [out]
    bool endFrame(int w, int h, Frame* out);

    // Set by the presenter while it samples presentation texture `index`, -1 otherwise
    std::atomic<int> presenterBusyIndex{-1};

private:
    static constexpr int kBuffers = 3;
    void allocTargets();
    void freeTargets();

    std::mutex m_mutex;
    EGLDisplay m_display{EGL_NO_DISPLAY};
    EGLConfig m_config{nullptr};
    EGLContext m_context{EGL_NO_CONTEXT};
    EGLSurface m_pbuffer{EGL_NO_SURFACE};
    std::atomic<bool> m_inUse{false};
    bool m_bound{false};
    bool m_depth{true}, m_stencil{true};

    int m_maxW{640}, m_maxH{480};
    int m_fboW{0}, m_fboH{0};
    // The core's framebuffer
    GLuint m_coreFbo{0}, m_coreTex{0}, m_coreDepth{0};
    GLuint m_whiteTex{0};   // bound on every texture unit before the core starts
    // Presentation textures
    GLuint m_tex[kBuffers]{};
    GLuint m_fbo[kBuffers]{};
    int m_next{0};
};
