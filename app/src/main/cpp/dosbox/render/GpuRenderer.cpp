#include "GpuRenderer.h"
#include "../core/PerfBoost.h"
#include <android/log.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <chrono>
#include <cstring>

#define TAG "GpuRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
uniform vec2 uUvScale;  // (1,1) for CPU frames; w/fboW, h/fboH for a core-rendered texture
uniform float uFlipY;   // 1 for core-rendered textures (GL origin is bottom-left)

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    float v = uFlipY > 0.5 ? 1.0 - aTexCoord.y : aTexCoord.y;
    vTexCoord = vec2(aTexCoord.x, v) * uUvScale;
}
)";

// Fragment shader with edge-adaptive sharpening (SGSR / FSR approximation) & CRT scanlines
static const char* FRAGMENT_SHADER = R"(#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform int uPreset;
uniform float uSharpness;

void main() {
    if (uPreset == 1) {
        // Sharp Retro: Nearest neighbor sample
        fragColor = texture(uTexture, vTexCoord);
    } else if (uPreset == 2) {
        // Classic CRT: Add subtle scanlines
        vec4 color = texture(uTexture, vTexCoord);
        float scanline = sin(gl_FragCoord.y * 3.14159265) * 0.15;
        fragColor = vec4(color.rgb - scanline, color.a);
    } else {
        // Clean Modern (Preset 0: Edge-adaptive sharpening)
        vec2 texel = 1.0 / vec2(textureSize(uTexture, 0));
        vec4 c = texture(uTexture, vTexCoord);
        vec4 up = texture(uTexture, vTexCoord - vec2(0.0, texel.y));
        vec4 down = texture(uTexture, vTexCoord + vec2(0.0, texel.y));
        vec4 left = texture(uTexture, vTexCoord - vec2(texel.x, 0.0));
        vec4 right = texture(uTexture, vTexCoord + vec2(texel.x, 0.0));

        vec4 laplacian = (up + down + left + right) * 0.25 - c;
        vec4 sharpened = c - laplacian * uSharpness;
        fragColor = clamp(sharpened, 0.0, 1.0);
    }
    // Core-drawn (hardware) frames can carry alpha 0; the window surface must stay opaque
    fragColor.a = 1.0;
}
)";

GpuRenderer::GpuRenderer() {}

GpuRenderer::~GpuRenderer() {
    stop();
    std::lock_guard<std::mutex> lock(m_windowMutex);
    if (m_pendingWindow) {
        ANativeWindow_release(m_pendingWindow);
        m_pendingWindow = nullptr;
    }
}

void GpuRenderer::setWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(m_windowMutex);
    if (m_pendingWindow && m_pendingWindow != window) {
        ANativeWindow_release(m_pendingWindow);
    }
    m_pendingWindow = window;
    m_windowChanged = true;
    LOGI("GpuRenderer setWindow queued: %p", window);
    ensureThread();
    m_frameCv.notify_one();
}

void GpuRenderer::ensureThread() {
    std::lock_guard<std::mutex> lock(m_frameMutex);
    if (m_thread.joinable()) return;
    m_stopRequested = false;
    m_thread = std::thread(&GpuRenderer::renderLoop, this);
}

void GpuRenderer::stop() {
    {
        std::lock_guard<std::mutex> lock(m_frameMutex);
        if (!m_thread.joinable()) return;
        m_stopRequested = true;
    }
    m_frameCv.notify_one();
    m_thread.join();
}

void GpuRenderer::submitFrame(const void* pixels, int width, int height, size_t pitchBytes, FramePixelFormat format) {
    if (!pixels || width <= 0 || height <= 0) return;
    ensureThread();
    const size_t bpp = format == FramePixelFormat::RGB565 ? 2 : 4;
    const size_t rowBytes = (size_t)width * bpp;
    {
        std::lock_guard<std::mutex> lock(m_frameMutex);
        m_pending.resize(rowBytes * height);
        const auto* src = static_cast<const uint8_t*>(pixels);
        if (pitchBytes == rowBytes) {
            std::memcpy(m_pending.data(), src, rowBytes * height);
        } else {
            for (int y = 0; y < height; ++y) {
                std::memcpy(m_pending.data() + y * rowBytes, src + y * pitchBytes, rowBytes);
            }
        }
        if (m_hasPending) statDropped.fetch_add(1, std::memory_order_relaxed);

        // 8x8 grid of sampled pixels, O(1) per frame
        uint64_t signature = 1469598103934665603ULL;
        for (int gy = 0; gy < 8; ++gy) {
            const uint8_t* row = m_pending.data() + (size_t)((height - 1) * gy / 7) * rowBytes;
            for (int gx = 0; gx < 8; ++gx) {
                const uint8_t* px = row + (size_t)((width - 1) * gx / 7) * bpp;
                uint32_t value = 0;
                std::memcpy(&value, px, bpp);
                signature = (signature ^ value) * 1099511628211ULL;
            }
        }
        if (statLastFrameSignature.exchange(signature, std::memory_order_relaxed) != signature) {
            statSignatureChanges.fetch_add(1, std::memory_order_relaxed);
        }
        m_pendingW = width;
        m_pendingH = height;
        m_pendingFormat = format;
        m_hasPending = true;
    }
    statSubmitted.fetch_add(1, std::memory_order_relaxed);
    m_frameCv.notify_one();
}

void GpuRenderer::submitHwFrame(const HwRenderContext::Frame& frame) {
    ensureThread();
    {
        std::lock_guard<std::mutex> lock(m_frameMutex);
        if (m_hasPendingHw) {
            statDropped.fetch_add(1, std::memory_order_relaxed);
            if (m_pendingHw.fence) glDeleteSync(m_pendingHw.fence);  // emulation thread: its context is current
        }
        m_pendingHw = frame;
        m_hasPendingHw = true;
        m_hasPending = false;
    }
    statSubmitted.fetch_add(1, std::memory_order_relaxed);
    m_frameCv.notify_one();
}

void GpuRenderer::renderLoop() {
    LOGI("GpuRenderer render thread started");
    perfboost::registerThread("render");
    int width = 0, height = 0;
    FramePixelFormat format = FramePixelFormat::XRGB8888;
    HwRenderContext::Frame hwFrame;
    bool haveHw = false;
    while (true) {
        {
            std::unique_lock<std::mutex> lock(m_frameMutex);
            // Wake for a new frame, a stop request, or (every 100 ms) to pick up window changes while paused
            m_frameCv.wait_for(lock, std::chrono::milliseconds(100),
                               [this] { return m_hasPending || m_hasPendingHw || m_stopRequested || m_filterDirty.load(); });
            if (m_stopRequested) break;
            // A filter change wakes the loop at once; the last frame is presented again below, so it shows even
            // while the game is paused in its menu
            m_filterDirty.store(false);
            if (m_hasPendingHw) {
                hwFrame = m_pendingHw;
                haveHw = true;
                m_hasPendingHw = false;
            } else if (m_hasPending) {
                std::swap(m_pending, m_current);
                width = m_pendingW;
                height = m_pendingH;
                format = m_pendingFormat;
                m_hasPending = false;
                haveHw = false;
            }
        }
        if (haveHw) {
            auto t0 = std::chrono::steady_clock::now();
            presentFrame(nullptr, hwFrame.width, hwFrame.height, format, &hwFrame);
            hwFrame.fence = nullptr;  // consumed (waited on and deleted) by the first present
            auto us = (uint32_t)std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
            statPresentMicros.fetch_add(us, std::memory_order_relaxed);
            uint32_t prevMax = statMaxPresentMicros.load(std::memory_order_relaxed);
            while (us > prevMax && !statMaxPresentMicros.compare_exchange_weak(prevMax, us)) {}
            statPresented.fetch_add(1, std::memory_order_relaxed);
            continue;
        }
        if (width <= 0 || height <= 0 || m_current.empty()) {
            presentFrame(nullptr, 0, 0, format);  // still service window changes
            continue;
        }
        auto t0 = std::chrono::steady_clock::now();
        presentFrame(m_current.data(), width, height, format);
        auto us = (uint32_t)std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
        statPresentMicros.fetch_add(us, std::memory_order_relaxed);
        uint32_t prevMax = statMaxPresentMicros.load(std::memory_order_relaxed);
        while (us > prevMax && !statMaxPresentMicros.compare_exchange_weak(prevMax, us)) {}
        statPresented.fetch_add(1, std::memory_order_relaxed);
    }
    destroyEgl();
    LOGI("GpuRenderer render thread stopped");
}

void GpuRenderer::setFilter(std::unique_ptr<FilterBundle> bundle) {
    std::lock_guard<std::mutex> lock(m_filterMutex);
    m_pendingFilter = std::move(bundle);
    m_hasPendingFilter = true;
    m_pendingParams.clear();
    m_filterMode.store(true);
    m_filterDirty.store(true);
    m_frameCv.notify_one();
}

void GpuRenderer::setFilterParam(int index, float value) {
    {
        std::lock_guard<std::mutex> lock(m_filterMutex);
        m_pendingParams.emplace_back(index, value);
    }
    m_filterDirty.store(true);
    m_frameCv.notify_one();
}

// Render thread, EGL current
void GpuRenderer::applyFilterRequests() {
    std::unique_ptr<FilterBundle> bundle;
    bool hasBundle = false;
    std::vector<std::pair<int, float>> params;
    std::function<void(const std::string&, const std::string&)> onFailed;
    {
        std::lock_guard<std::mutex> lock(m_filterMutex);
        if (m_hasPendingFilter) {
            bundle = std::move(m_pendingFilter);
            hasBundle = true;
            m_hasPendingFilter = false;
        }
        params.swap(m_pendingParams);
        onFailed = m_onFilterFailed;
    }
    if (hasBundle) {
        m_activeBundle.reset();
        if (!bundle || bundle->id.empty() || bundle->id == "off") {
            m_chain.destroy();
            LOGI("Filter: off (plain picture)");
        } else {
            std::string error;
            const std::string id = bundle->id;
            if (m_chain.load(*bundle, error)) {
                m_activeBundle = std::move(bundle);
            } else {
                LOGE("Filter %s can't run on this GPU: %s", id.c_str(), error.c_str());
                m_chain.destroy();
                if (onFailed) onFailed(id, error);
            }
        }
    }
    for (const auto& [index, value] : params) {
        m_chain.setParam((size_t)index, value);
        if (m_activeBundle && index >= 0 && index < (int)m_activeBundle->params.size()) m_activeBundle->params[index].value = value;
    }
}

void GpuRenderer::setVisualPreset(int presetId, bool isSnapdragon, float sharpness) {
    m_presetId.store(presetId);
    m_isSnapdragon.store(isSnapdragon);
    m_sharpness.store(sharpness);
    m_presetChanged.store(true);
}

bool GpuRenderer::initEgl(ANativeWindow* window) {
    if (!window) return false;

    m_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_display == EGL_NO_DISPLAY) {
        LOGE("Failed to get EGL display");
        return false;
    }

    if (!eglInitialize(m_display, nullptr, nullptr)) {
        LOGE("Failed to initialize EGL");
        return false;
    }

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(m_display, attribs, &config, 1, &numConfigs) || numConfigs <= 0) {
        LOGE("Failed to choose EGL config");
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    // A core that draws with the GPU (OpenLara): join the emulation thread context's share group to sample its frames
    EGLContext shareWith = EGL_NO_CONTEXT;
    if (HwRenderContext::wanted()) {
        shareWith = HwRenderContext::instance().shareContext();
        m_display = HwRenderContext::instance().display();  // one display for both contexts
    }
    m_context = eglCreateContext(m_display, config, shareWith, contextAttribs);
    if (m_context == EGL_NO_CONTEXT) {
        LOGE("Failed to create EGL context");
        return false;
    }

    m_surface = eglCreateWindowSurface(m_display, config, window, nullptr);
    if (m_surface == EGL_NO_SURFACE) {
        LOGE("Failed to create EGL surface: 0x%x", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(m_display, m_surface, m_surface, m_context)) {
        LOGE("Failed to make EGL current on render thread: 0x%x", eglGetError());
        return false;
    }

    eglQuerySurface(m_display, m_surface, EGL_WIDTH, &m_surfaceWidth);
    eglQuerySurface(m_display, m_surface, EGL_HEIGHT, &m_surfaceHeight);

    initShaders();
    m_importReady = m_import.init();
    if (m_activeBundle) {   // the surface (and context) was recreated: compile the active filter again
        std::string error;
        if (!m_chain.load(*m_activeBundle, error)) {
            LOGE("Filter %s failed to reload: %s", m_activeBundle->id.c_str(), error.c_str());
            m_activeBundle.reset();
        }
    }

    m_texWidth = 0;
    m_texHeight = 0;

    LOGI("GpuRenderer initialized successfully on render thread. Canvas: %dx%d", m_surfaceWidth, m_surfaceHeight);
    return true;
}

void GpuRenderer::initShaders() {
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &VERTEX_SHADER, nullptr);
    glCompileShader(vs);

    GLint compiled = 0;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(vs, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(vs, infoLen, nullptr, infoLog.data());
            LOGE("Error compiling vertex shader: %s", infoLog.data());
        }
        glDeleteShader(vs);
        return;
    }

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &FRAGMENT_SHADER, nullptr);
    glCompileShader(fs);

    compiled = 0;
    glGetShaderiv(fs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(fs, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(fs, infoLen, nullptr, infoLog.data());
            LOGE("Error compiling fragment shader: %s", infoLog.data());
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return;
    }

    m_program = glCreateProgram();
    glAttachShader(m_program, vs);
    glAttachShader(m_program, fs);
    glLinkProgram(m_program);

    GLint linked = 0;
    glGetProgramiv(m_program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(m_program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetProgramInfoLog(m_program, infoLen, nullptr, infoLog.data());
            LOGE("Error linking program: %s", infoLog.data());
        }
        glDeleteProgram(m_program);
        m_program = 0;
        glDeleteShader(vs);
        glDeleteShader(fs);
        return;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);

    glGenTextures(1, &m_textureId);
    glBindTexture(GL_TEXTURE_2D, m_textureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    float quadVertices[] = {
        // x, y, u, v
        -1.0f,  1.0f, 0.0f, 0.0f,
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
    };

    glGenVertexArrays(1, &m_vao);
    glBindVertexArray(m_vao);

    glGenBuffers(1, &m_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVertices), quadVertices, GL_STATIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GpuRenderer::calculateViewport(int screenWidth, int screenHeight, int dosWidth, int dosHeight) {
    if (m_presetId.load() == 3 || m_fillScreen.load()) {
        // Fullscreen stretch ("Fill the screen")
        m_viewportX = 0;
        m_viewportY = 0;
        m_viewportW = screenWidth;
        m_viewportH = screenHeight;
        publishViewport(screenWidth, screenHeight);
        return;
    }

    // 4:3 Aspect Ratio with Pure Black Pillarbox borders
    float targetAspect = 4.0f / 3.0f;
    float screenAspect = (float)screenWidth / (float)screenHeight;

    if (screenAspect > targetAspect) {
        // Screen is wider than 4:3 (typical modern phone / tablet)
        m_viewportH = screenHeight;
        m_viewportW = (int)(screenHeight * targetAspect);
        m_viewportX = (screenWidth - m_viewportW) / 2;
        m_viewportY = 0;
    } else {
        m_viewportW = screenWidth;
        m_viewportH = (int)(screenWidth / targetAspect);
        m_viewportX = 0;
        m_viewportY = (screenHeight - m_viewportH) / 2;
    }
    publishViewport(screenWidth, screenHeight);
}

void GpuRenderer::publishViewport(int screenWidth, int screenHeight) {
    m_pubX.store(m_viewportX); m_pubY.store(m_viewportY); m_pubW.store(m_viewportW); m_pubH.store(m_viewportH);
    m_pubSurfaceW.store(screenWidth); m_pubSurfaceH.store(screenHeight);
}

bool GpuRenderer::pictureFraction(float viewU, float viewV, float& u, float& v) const {
    const int sw = m_pubSurfaceW.load(), sh = m_pubSurfaceH.load(), w = m_pubW.load(), h = m_pubH.load();
    if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return false;
    // The bars are the same size on both sides, so GL's bottom-up Y gives the same offset from the top
    u = (viewU * (float)sw - (float)m_pubX.load()) / (float)w;
    v = (viewV * (float)sh - (float)m_pubY.load()) / (float)h;
    return u >= 0.0f && u <= 1.0f && v >= 0.0f && v <= 1.0f;
}

void GpuRenderer::presentFrame(const uint8_t* pixels, int dosWidth, int dosHeight, FramePixelFormat format,
                               const HwRenderContext::Frame* hw) {
    bool windowChanged = false;
    ANativeWindow* targetWindow = nullptr;
    {
        std::lock_guard<std::mutex> lock(m_windowMutex);
        if (m_windowChanged) {
            windowChanged = true;
            m_windowChanged = false;
            targetWindow = m_pendingWindow;
            m_pendingWindow = nullptr;
        }
    }

    if (windowChanged) {
        destroyEgl();
        if (targetWindow) {
            m_activeWindow = targetWindow;
            if (!initEgl(m_activeWindow)) {
                LOGE("Failed to initialize EGL for active window");
                destroyEgl();
            }
        }
    }

    if (m_display == EGL_NO_DISPLAY || m_surface == EGL_NO_SURFACE || m_context == EGL_NO_CONTEXT) {
        return;
    }

    eglQuerySurface(m_display, m_surface, EGL_WIDTH, &m_surfaceWidth);
    eglQuerySurface(m_display, m_surface, EGL_HEIGHT, &m_surfaceHeight);

    if (m_surfaceWidth <= 0 || m_surfaceHeight <= 0 || (!pixels && !hw)) {
        // Window not ready: a pending hardware frame's fence still has to be released
        if (hw && hw->fence) glDeleteSync(hw->fence);
        return;
    }

    calculateViewport(m_surfaceWidth, m_surfaceHeight, dosWidth, dosHeight);

    // Pure black OLED background
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, m_surfaceWidth, m_surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // The frame to show: a texture the core drew (wait for its GPU work), or the CPU pixels uploaded to m_textureId
    GLuint source = m_textureId;
    float uScale = 1.0f, vScale = 1.0f;
    bool flipY = false;
    if (hw) {
        if (hw->fence) {
            glWaitSync(hw->fence, 0, GL_TIMEOUT_IGNORED);
            glDeleteSync(hw->fence);
        }
        HwRenderContext::instance().presenterBusyIndex.store(hw->index);
        source = hw->texture;
        uScale = (float)hw->width / (float)hw->fboWidth;
        vScale = (float)hw->height / (float)hw->fboHeight;
        flipY = true;
    } else if (dosWidth > 0 && dosHeight > 0) {
        // Upload the core's native layout; no per-pixel CPU conversion. XRGB8888 is B,G,R,X in memory on little-endian,
        // so it is uploaded as RGBA and the texture swizzle swaps red/blue and forces alpha to 1.
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, m_textureId);
        const bool is565 = format == FramePixelFormat::RGB565;
        const GLenum glFormat = is565 ? GL_RGB : GL_RGBA;
        const GLenum glType = is565 ? GL_UNSIGNED_SHORT_5_6_5 : GL_UNSIGNED_BYTE;
        glPixelStorei(GL_UNPACK_ALIGNMENT, is565 ? 2 : 4);
        if (m_texWidth != dosWidth || m_texHeight != dosHeight || m_texFormat != format) {
            glTexImage2D(GL_TEXTURE_2D, 0, is565 ? GL_RGB565 : GL_RGBA8, dosWidth, dosHeight, 0, glFormat, glType, pixels);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, is565 ? GL_RED : GL_BLUE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, is565 ? GL_BLUE : GL_RED);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE);
            m_texWidth = dosWidth;
            m_texHeight = dosHeight;
            m_texFormat = format;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, dosWidth, dosHeight, glFormat, glType, pixels);
        }
    }

    // Video filters (specs/008-shader-filters): game frame -> Original (ImportPass) -> filter passes -> screen
    if (m_filterMode.load()) {
        applyFilterRequests();
        if (m_chain.loaded() && m_importReady) {
            int ow = 0, oh = 0;
            GLuint original = m_import.run(source, dosWidth, dosHeight, uScale, vScale, flipY, m_doubleScan.load(), ow, oh);
            if (original) {
                m_chain.render(original, m_import.framebuffer(), ow, oh, m_viewportX, m_viewportY, m_viewportW, m_viewportH,
                               m_frameCount++);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                eglSwapBuffers(m_display, m_surface);
                if (hw) HwRenderContext::instance().presenterBusyIndex.store(-1);
                return;
            }
        }
        // "Off", or a filter that couldn't run: the plain picture (the legacy shader's straight sample)
    }

    const int currentPreset = m_filterMode.load() ? 1 : m_presetId.load();
    const float currentSharpness = m_sharpness.load();
    if (m_presetChanged.exchange(false) && !m_filterMode.load()) {
        glBindTexture(GL_TEXTURE_2D, m_textureId);
        if (currentPreset == 1) {
            // Nearest neighbor for sharp retro
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
    }

    // Set 4:3 pillarbox viewport
    glViewport(m_viewportX, m_viewportY, m_viewportW, m_viewportH);

    glUseProgram(m_program);

    glUniform1i(glGetUniformLocation(m_program, "uPreset"), currentPreset);
    glUniform1f(glGetUniformLocation(m_program, "uSharpness"), currentSharpness);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, source);
    glUniform2f(glGetUniformLocation(m_program, "uUvScale"), uScale, vScale);
    glUniform1f(glGetUniformLocation(m_program, "uFlipY"), flipY ? 1.0f : 0.0f);
    glUniform1i(glGetUniformLocation(m_program, "uTexture"), 0);

    glBindVertexArray(m_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    eglSwapBuffers(m_display, m_surface);
    if (hw) HwRenderContext::instance().presenterBusyIndex.store(-1);
}

void GpuRenderer::destroyEgl() {
    if (m_display != EGL_NO_DISPLAY) {
        if (m_context != EGL_NO_CONTEXT) {
            eglMakeCurrent(m_display, m_surface, m_surface, m_context);
        }

        // A new context needs the filter compiled again: re-queue it
        if (m_chain.loaded() || m_importReady) {
            m_chain.destroy();
            m_import.destroy();
            m_importReady = false;
        }
        if (m_program) {
            glDeleteProgram(m_program);
            m_program = 0;
        }
        if (m_textureId) {
            glDeleteTextures(1, &m_textureId);
            m_textureId = 0;
        }
        if (m_vbo) {
            glDeleteBuffers(1, &m_vbo);
            m_vbo = 0;
        }
        if (m_vao) {
            glDeleteVertexArrays(1, &m_vao);
            m_vao = 0;
        }

        eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (m_surface != EGL_NO_SURFACE) {
            eglDestroySurface(m_display, m_surface);
            m_surface = EGL_NO_SURFACE;
        }
        if (m_context != EGL_NO_CONTEXT) {
            eglDestroyContext(m_display, m_context);
            m_context = EGL_NO_CONTEXT;
        }
        // The emulation thread's hardware-render context lives on this same display (eglTerminate is process-wide)
        if (!HwRenderContext::instance().inUse()) eglTerminate(m_display);
        m_display = EGL_NO_DISPLAY;
    }

    if (m_activeWindow) {
        ANativeWindow_release(m_activeWindow);
        m_activeWindow = nullptr;
    }
}
