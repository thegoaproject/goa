#pragma once

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cstdint>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <thread>
#include <vector>
#include "HwRenderContext.h"
#include "filter/FilterBundle.h"
#include "filter/FilterChain.h"
#include "filter/ImportPass.h"
#include <functional>
#include <memory>
#include <string>

// Pixel layouts accepted from libretro cores
enum class FramePixelFormat { XRGB8888, RGB565 };

class GpuRenderer {
public:
    GpuRenderer();
    ~GpuRenderer();

    // UI-thread callable methods (thread-safe)
    void setWindow(ANativeWindow* window);
    void setVisualPreset(int presetId, bool isSnapdragon, float sharpness);

    // Video filters (specs/008-shader-filters). Thread-safe: the request is applied by the render thread at its next
    // frame, and the previous picture keeps drawing until then. An empty bundle id or "off" means the plain picture.
    void setFilter(std::unique_ptr<FilterBundle> bundle);
    void setFilterParam(int index, float value);
    void setDoubleScan(bool enabled) { m_doubleScan.store(enabled); }
    void setFillScreen(bool enabled) { m_fillScreen.store(enabled); }
    // Called on the render thread when a filter can't be compiled on this GPU; the plain picture is shown instead
    void setFilterFailedCallback(std::function<void(const std::string& id, const std::string& log)> cb) {
        std::lock_guard<std::mutex> lock(m_filterMutex);
        m_onFilterFailed = std::move(cb);
    }

    // Emulation-thread callable: copies the frame into a mailbox and returns immediately. Presentation (texture upload
    // and the vsync-blocking eglSwapBuffers) happens on the renderer's own thread, so a 60 Hz display can never slow a
    // 70 Hz DOS core, and audio keeps being produced at full rate.
    void submitFrame(const void* pixels, int width, int height, size_t pitchBytes, FramePixelFormat format);

    // Emulation-thread callable: a core that draws with the GPU drew into a texture of a context that shares with ours.
    // Takes ownership of `frame.fence`; the render thread waits on it before sampling.
    void submitHwFrame(const HwRenderContext::Frame& frame);

    // Stops the render thread and releases EGL (EGL objects are only touched on the render thread).
    void stop();

    // Telemetry, read by the perf monitor
    std::atomic<uint64_t> statSubmitted{0};
    std::atomic<uint64_t> statPresented{0};
    std::atomic<uint64_t> statDropped{0};      // frames replaced in the mailbox before the render thread presented them
    std::atomic<uint64_t> statPresentMicros{0}; // summed time spent uploading + swapping
    std::atomic<uint32_t> statMaxPresentMicros{0};
    // Cheap content signature (64 sampled pixels) of the last submitted frame, and how many submitted frames
    // differed from their predecessor; telemetry uses it to tell whether the game reacted to input.
    std::atomic<uint64_t> statLastFrameSignature{0};
    std::atomic<uint64_t> statSignatureChanges{0};

    // Where a touch at ([viewU], [viewV]) of the surface (0-1) lands on the game picture, which is letterboxed to 4:3
    // unless the player stretched it. False until the first frame, or when the touch is on the black bars.
    bool pictureFraction(float viewU, float viewV, float& u, float& v) const;

private:
    void ensureThread();
    void renderLoop();
    void presentFrame(const uint8_t* pixels, int width, int height, FramePixelFormat format,
                      const HwRenderContext::Frame* hw = nullptr);
    void destroyEgl();
    bool initEgl(ANativeWindow* window);
    void initShaders();
    void calculateViewport(int screenWidth, int screenHeight, int dosWidth, int dosHeight);
    void publishViewport(int screenWidth, int screenHeight);
    // The picture's place on the surface, copied for other threads (pictureFraction)
    std::atomic<int> m_pubSurfaceW{0}, m_pubSurfaceH{0}, m_pubX{0}, m_pubY{0}, m_pubW{0}, m_pubH{0};

    std::mutex m_windowMutex;
    ANativeWindow* m_pendingWindow{nullptr};
    bool m_windowChanged{false};

    ANativeWindow* m_activeWindow{nullptr};
    EGLDisplay m_display{EGL_NO_DISPLAY};
    EGLSurface m_surface{EGL_NO_SURFACE};
    EGLContext m_context{EGL_NO_CONTEXT};

    int m_surfaceWidth{0};
    int m_surfaceHeight{0};
    int m_viewportX{0};
    int m_viewportY{0};
    int m_viewportW{0};
    int m_viewportH{0};

    std::atomic<int> m_presetId{0};
    std::atomic<bool> m_isSnapdragon{false};
    std::atomic<float> m_sharpness{0.6f};
    std::atomic<bool> m_presetChanged{false};

    // Filters: requests from any thread, applied on the render thread
    std::mutex m_filterMutex;
    std::unique_ptr<FilterBundle> m_pendingFilter;
    bool m_hasPendingFilter{false};
    std::vector<std::pair<int, float>> m_pendingParams;
    std::function<void(const std::string&, const std::string&)> m_onFilterFailed;
    std::atomic<bool> m_filterDirty{false};  // a filter request arrived: redraw the last frame even while paused
    std::atomic<bool> m_filterMode{false};   // setFilter was called: the legacy presets no longer apply
    std::atomic<bool> m_doubleScan{false};
    std::atomic<bool> m_fillScreen{false};
    FilterChain m_chain;
    std::unique_ptr<FilterBundle> m_activeBundle;   // kept (with current values) to rebuild after a context change
    ImportPass m_import;
    bool m_importReady{false};
    uint32_t m_frameCount{0};
    void applyFilterRequests();

    GLuint m_program{0};
    GLuint m_textureId{0};
    GLuint m_vbo{0};
    GLuint m_vao{0};

    int m_texWidth{0};
    int m_texHeight{0};
    FramePixelFormat m_texFormat{FramePixelFormat::XRGB8888};

    // Mailbox: emulation thread writes m_pending, render thread swaps it into m_current
    std::mutex m_frameMutex;
    std::condition_variable m_frameCv;
    std::vector<uint8_t> m_pending;
    std::vector<uint8_t> m_current;
    int m_pendingW{0}, m_pendingH{0};
    FramePixelFormat m_pendingFormat{FramePixelFormat::XRGB8888};
    bool m_hasPending{false};
    HwRenderContext::Frame m_pendingHw;
    bool m_hasPendingHw{false};
    bool m_stopRequested{false};
    std::thread m_thread;
};
