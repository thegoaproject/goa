#include "HwRenderContext.h"
#include <android/log.h>
#include <cstring>

#define TAG "HwRenderContext"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> s_wanted{false};

HwRenderContext& HwRenderContext::instance() {
    static HwRenderContext s_instance;
    return s_instance;
}

void HwRenderContext::setWanted(bool wanted) { s_wanted.store(wanted); }
bool HwRenderContext::wanted() { return s_wanted.load(); }

EGLContext HwRenderContext::shareContext() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_context != EGL_NO_CONTEXT) return m_context;

    m_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_display == EGL_NO_DISPLAY || !eglInitialize(m_display, nullptr, nullptr)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        return EGL_NO_CONTEXT;
    }
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint n = 0;
    if (!eglChooseConfig(m_display, configAttribs, &m_config, 1, &n) || n <= 0) {
        LOGE("No ES3 pbuffer EGL config: 0x%x", eglGetError());
        return EGL_NO_CONTEXT;
    }
    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    m_context = eglCreateContext(m_display, m_config, EGL_NO_CONTEXT, ctxAttribs);
    if (m_context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return EGL_NO_CONTEXT;
    }
    m_inUse.store(true);
    LOGI("Hardware-render share context created");
    return m_context;
}

bool HwRenderContext::bindToThisThread(bool depth, bool stencil) {
    if (shareContext() == EGL_NO_CONTEXT) return false;
    m_depth = depth;
    m_stencil = stencil;
    if (m_pbuffer == EGL_NO_SURFACE) {
        const EGLint pbAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        m_pbuffer = eglCreatePbufferSurface(m_display, m_config, pbAttribs);
        if (m_pbuffer == EGL_NO_SURFACE) {
            LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
            return false;
        }
    }
    if (!eglMakeCurrent(m_display, m_pbuffer, m_pbuffer, m_context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }
    m_bound = true;
    LOGI("Hardware-render context bound to the emulation thread; GL_RENDERER=%s GL_VERSION=%s",
         (const char*)glGetString(GL_RENDERER), (const char*)glGetString(GL_VERSION));
    return true;
}

void HwRenderContext::setMaxSize(int w, int h) {
    if (w > 0 && h > 0) { m_maxW = w; m_maxH = h; }
}

void HwRenderContext::freeTargets() {
    if (m_fbo[0]) glDeleteFramebuffers(kBuffers, m_fbo);
    if (m_tex[0]) glDeleteTextures(kBuffers, m_tex);
    if (m_coreFbo) glDeleteFramebuffers(1, &m_coreFbo);
    if (m_coreTex) glDeleteTextures(1, &m_coreTex);
    if (m_coreDepth) glDeleteRenderbuffers(1, &m_coreDepth);
    if (m_whiteTex) glDeleteTextures(1, &m_whiteTex);
    m_whiteTex = 0;
    memset(m_fbo, 0, sizeof(m_fbo));
    memset(m_tex, 0, sizeof(m_tex));
    m_coreFbo = m_coreTex = m_coreDepth = 0;
    m_fboW = m_fboH = 0;
}

static void makeColorTexture(GLuint tex, int w, int h) {
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

void HwRenderContext::allocTargets() {
    freeTargets();
    m_fboW = m_maxW;
    m_fboH = m_maxH;

    glGenTextures(1, &m_coreTex);
    makeColorTexture(m_coreTex, m_fboW, m_fboH);
    glGenFramebuffers(1, &m_coreFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, m_coreFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, m_coreTex, 0);
    if (m_depth || m_stencil) {
        glGenRenderbuffers(1, &m_coreDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, m_coreDepth);
        glRenderbufferStorage(GL_RENDERBUFFER, m_stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, m_fboW, m_fboH);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, m_stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                                  GL_RENDERBUFFER, m_coreDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Core framebuffer incomplete (%dx%d)", m_fboW, m_fboH);
    }
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

    glGenTextures(kBuffers, m_tex);
    glGenFramebuffers(kBuffers, m_fbo);
    for (int i = 0; i < kBuffers; ++i) {
        makeColorTexture(m_tex[i], m_fboW, m_fboH);
        glBindFramebuffer(GL_FRAMEBUFFER, m_fbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, m_tex[i], 0);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, m_coreFbo);

    // Every texture unit starts on a 1x1 white texture: a sampler the core never binds (OpenLara's shadow map on its
    // title screen) then reads white, where an empty unit reads black on GLES and blacks out what it lights
    if (!m_whiteTex) {
        const uint32_t white = 0xFFFFFFFFu;
        glGenTextures(1, &m_whiteTex);
        glBindTexture(GL_TEXTURE_2D, m_whiteTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, &white);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    GLint units = 0;
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &units);
    for (int i = 0; i < units && i < 32; ++i) {
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(GL_TEXTURE_2D, m_whiteTex);
    }
    glActiveTexture(GL_TEXTURE0);
    LOGI("Core framebuffer and %d presentation textures at %dx%d (depth %d, stencil %d)", kBuffers, m_fboW, m_fboH,
         m_depth, m_stencil);
}

uintptr_t HwRenderContext::currentFramebuffer() {
    if (!m_bound) return 0;
    if (!m_coreFbo) allocTargets();
    return m_coreFbo;
}

bool HwRenderContext::endFrame(int w, int h, Frame* out) {
    if (!m_bound || !m_coreFbo) return false;
    if (w > m_fboW) w = m_fboW;
    if (h > m_fboH) h = m_fboH;

    int index = m_next;
    if (index == presenterBusyIndex.load()) index = (index + 1) % kBuffers;
    m_next = (index + 1) % kBuffers;

    // The core keeps its own idea of what is bound: restore exactly what it had after the copy
    GLint readFbo = 0, drawFbo = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFbo);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
    const GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    if (scissor) glDisable(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_READ_FRAMEBUFFER, m_coreFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, m_fbo[index]);
    glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);

    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)readFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)drawFbo);
    if (scissor) glEnable(GL_SCISSOR_TEST);

    out->texture = m_tex[index];
    out->index = index;
    out->fboWidth = m_fboW;
    out->fboHeight = m_fboH;
    out->width = w;
    out->height = h;
    out->fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    glFlush();
    return true;
}

void HwRenderContext::destroy() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_display == EGL_NO_DISPLAY) return;
    if (m_bound) {
        freeTargets();
        eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        m_bound = false;
    }
    if (m_pbuffer != EGL_NO_SURFACE) { eglDestroySurface(m_display, m_pbuffer); m_pbuffer = EGL_NO_SURFACE; }
    if (m_context != EGL_NO_CONTEXT) { eglDestroyContext(m_display, m_context); m_context = EGL_NO_CONTEXT; }
    m_inUse.store(false);
    m_next = 0;
    LOGI("Hardware-render context destroyed");
}
