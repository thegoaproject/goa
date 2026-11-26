#include "core/ScreenText.h"
#include "core/PerfBoost.h"
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <memory>
#include <atomic>
#include <thread>
#include <vector>
#include <mutex>
#include <cmath>
#include <algorithm>
#include <chrono>
#include <climits>
#include <cstdio>
#include <unistd.h>
#include "audio/OboeAudioSink.h"
#include "render/GpuRenderer.h"
#include "core/DosBoxPureCore.h"

#define TAG "DosBoxJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::unique_ptr<GpuRenderer> g_renderer;
static std::unique_ptr<OboeAudioSink> g_audioSink;
static std::atomic<int> g_audioLatencyMode{0};  // ☰ → Audio: 0 Stable, 1 Low latency; set before initEngine
static std::unique_ptr<DosBoxPureCore> g_core;
static std::atomic<bool> g_engineRunning{false};
static std::atomic<bool> g_enginePaused{false};
static std::thread g_emulationThread;
static JavaVM* g_jvm = nullptr;
static jobject g_listenerGlobalRef = nullptr;
static std::mutex g_engineMutex;

jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// ---- Performance telemetry -------------------------------------------------------------------------------------
// One snapshot per 2 s window, logged under tag "GoaPerf" and readable from Kotlin via getPerfStats().
enum PerfField {
    PERF_TARGET_FPS, PERF_EMU_FPS, PERF_PRESENT_FPS, PERF_DROPPED_FPS,
    PERF_RUN_AVG_MS, PERF_RUN_MAX_MS, PERF_PRESENT_AVG_MS, PERF_PRESENT_MAX_MS,
    PERF_AUDIO_BUFFER_MS, PERF_AUDIO_MIN_MS, PERF_AUDIO_IN_HZ, PERF_AUDIO_OUT_HZ,
    PERF_UNDERRUNS, PERF_SILENCE_MS, PERF_OVERFLOW_MS, PERF_LATE_FRAMES,
    PERF_DEVICE_RATE, PERF_XRUNS, PERF_CORE_SPEED_PCT, PERF_GAME_FPS, PERF_CORE_CYCLES,
    PERF_AUDIO_LEVEL_DBFS, PERF_FRAME_CHANGES, PERF_FIELD_COUNT
};
static std::mutex g_perfMutex;
static float g_perfSnapshot[PERF_FIELD_COUNT] = {0};

// Native ports (OpenLara, TyrQuake) report shader errors and the like on stdout/stderr, which Android drops: pipe both
// into logcat under "CoreOutput" (once per process; the emulator process lives for one game)
static void captureCoreOutput() {
    static bool started = false;
    if (started) return;
    int fds[2];
    if (pipe(fds) != 0) return;
    started = true;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    std::thread([fd = fds[0]]() {
        char buf[1024];
        std::string line;
        ssize_t n;
        while ((n = read(fd, buf, sizeof(buf))) > 0) {
            for (ssize_t i = 0; i < n; ++i) {
                if (buf[i] == '\n' || line.size() > 900) {
                    if (!line.empty()) __android_log_print(ANDROID_LOG_INFO, "CoreOutput", "%s", line.c_str());
                    line.clear();
                } else {
                    line += buf[i];
                }
            }
        }
    }).detach();
}

static void emulationLoop() {
    LOGI("Starting DOS emulation main loop with DOSBox Pure core");
    perfboost::registerThread("frame");

    using clock = std::chrono::steady_clock;
    auto nextFrameTime = clock::now();

    // Per-window accumulators
    auto windowStart = clock::now();
    uint64_t frames = 0, lateFrames = 0;
    double runMicrosSum = 0, runMicrosMax = 0;
    uint64_t lastPresented = 0, lastDropped = 0, lastPresentMicros = 0;
    uint64_t lastWritten = 0, lastPlayed = 0, lastUnderruns = 0, lastSilence = 0, lastOverflow = 0;
    uint64_t lastFrameChanges = 0;
    double targetFps = 60.0;
    uint64_t lastFrameAudioWritten = 0;

    while (g_engineRunning.load() && (!g_core || !g_core->isShutdownRequested())) {
        if (g_enginePaused.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            nextFrameTime = clock::now();
            windowStart = clock::now();
            continue;
        }

        double fps = 60.0;
        auto runStart = clock::now();
        {
            std::lock_guard<std::mutex> lock(g_engineMutex);
            if (g_core) {
                g_core->runFrame();
                fps = g_core->getTargetFps();
            }
        }
        double runMicros = (double)std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - runStart).count();
        runMicrosSum += runMicros;
        perfboost::frameDone(static_cast<int64_t>(1e9 / (fps < 10.0 || fps > 120.0 ? 60.0 : fps)), static_cast<int64_t>(runMicros * 1000.0));
        runMicrosMax = std::max(runMicrosMax, runMicros);
        ++frames;

        if (fps < 10.0 || fps > 120.0) fps = 60.0;
        targetFps = fps;
        auto frameDuration = std::chrono::nanoseconds(static_cast<int64_t>(1000000000.0 / fps));
        nextFrameTime += frameDuration;

        // Soft audio sync: the core's clock and the DAC's clock never match exactly, so steer by the audio queue.
        // Running low -> skip the sleep and catch up; running high -> wait a little longer. Only catch up while the
        // core is actually producing audio: ScummVM starts its mixer after launch, and a silent core would otherwise
        // run unthrottled.
        if (g_audioSink) {
            const size_t buffered = g_audioSink->bufferedFrames();
            const size_t target = g_audioSink->targetBufferedFrames();
            const uint64_t audioWritten = g_audioSink->statWrittenFrames.load();
            const bool audioFlowing = audioWritten != lastFrameAudioWritten;
            lastFrameAudioWritten = audioWritten;
            if (buffered < target / 2 && audioFlowing) {
                nextFrameTime = std::min(nextFrameTime, clock::now());
            } else if (buffered > target * 2) {
                nextFrameTime += std::chrono::milliseconds(1);
            }
        }

        auto now = clock::now();
        if (nextFrameTime > now) {
            std::this_thread::sleep_until(nextFrameTime);
        } else if (now - nextFrameTime > std::chrono::milliseconds(50)) {
            // Reset if we fell behind by more than ~3 frames to avoid catch-up bursts
            nextFrameTime = now;
            ++lateFrames;
        }

        double windowSecs = std::chrono::duration<double>(clock::now() - windowStart).count();
        if (windowSecs >= 2.0 && g_renderer && g_audioSink) {
            const uint64_t presented = g_renderer->statPresented.load();
            const uint64_t dropped = g_renderer->statDropped.load(), presentMicros = g_renderer->statPresentMicros.load();
            const uint64_t written = g_audioSink->statWrittenFrames.load(), played = g_audioSink->statPlayedFrames.load();
            const uint64_t underruns = g_audioSink->statUnderruns.load(), silence = g_audioSink->statSilenceFrames.load();
            const uint64_t overflow = g_audioSink->statOverflowDropped.load();
            const double rate = g_audioSink->sampleRate() > 0 ? g_audioSink->sampleRate() : 48000.0;
            const uint64_t presentedDelta = presented - lastPresented;

            float snap[PERF_FIELD_COUNT];
            snap[PERF_TARGET_FPS] = (float)targetFps;
            snap[PERF_EMU_FPS] = (float)(frames / windowSecs);
            snap[PERF_PRESENT_FPS] = (float)(presentedDelta / windowSecs);
            snap[PERF_DROPPED_FPS] = (float)((dropped - lastDropped) / windowSecs);
            snap[PERF_RUN_AVG_MS] = (float)(frames ? runMicrosSum / frames / 1000.0 : 0);
            snap[PERF_RUN_MAX_MS] = (float)(runMicrosMax / 1000.0);
            snap[PERF_PRESENT_AVG_MS] = (float)(presentedDelta ? (presentMicros - lastPresentMicros) / (double)presentedDelta / 1000.0 : 0);
            snap[PERF_PRESENT_MAX_MS] = g_renderer->statMaxPresentMicros.exchange(0) / 1000.0f;
            snap[PERF_AUDIO_BUFFER_MS] = (float)(g_audioSink->bufferedFrames() * 1000.0 / rate);
            uint32_t minBuffered = g_audioSink->statMinBuffered.exchange(UINT32_MAX);
            snap[PERF_AUDIO_MIN_MS] = minBuffered == UINT32_MAX ? 0.0f : (float)(minBuffered * 1000.0 / rate);
            snap[PERF_AUDIO_IN_HZ] = (float)((written - lastWritten) / windowSecs);
            snap[PERF_AUDIO_OUT_HZ] = (float)((played - lastPlayed) / windowSecs);
            snap[PERF_UNDERRUNS] = (float)(underruns - lastUnderruns);
            snap[PERF_SILENCE_MS] = (float)((silence - lastSilence) * 1000.0 / rate);
            snap[PERF_OVERFLOW_MS] = (float)((overflow - lastOverflow) * 1000.0 / rate);
            snap[PERF_LATE_FRAMES] = (float)lateFrames;
            snap[PERF_DEVICE_RATE] = (float)g_audioSink->deviceSampleRate();
            snap[PERF_XRUNS] = (float)g_audioSink->statXRuns.load();
            // Loudest sample the game produced this window (before device volume); -120 dBFS = silence
            const uint32_t peak = g_audioSink->statProducedPeak.exchange(0);
            snap[PERF_AUDIO_LEVEL_DBFS] = peak > 0 ? (float)(20.0 * std::log10(peak / 32768.0)) : -120.0f;
            const uint64_t frameChanges = g_renderer->statSignatureChanges.load();
            snap[PERF_FRAME_CHANGES] = (float)(frameChanges - lastFrameChanges);
            lastFrameChanges = frameChanges;
            {
                std::lock_guard<std::mutex> lock(g_engineMutex);
                snap[PERF_CORE_SPEED_PCT] = g_core ? g_core->getCoreSpeedPercent() : 0.0f;
                snap[PERF_GAME_FPS] = g_core ? g_core->getCoreDrawnFps() : 0.0f;
                snap[PERF_CORE_CYCLES] = g_core ? (float)g_core->getCoreCycles() : 0.0f;
            }
            {
                std::lock_guard<std::mutex> lock(g_perfMutex);
                std::copy(snap, snap + PERF_FIELD_COUNT, g_perfSnapshot);
            }
            __android_log_print(ANDROID_LOG_INFO, "GoaPerf",
                "emu %.1f/%.1f fps (late %d) run avg %.1f max %.1f ms | dos speed %.0f%% game %.0f fps cycles %.0f | level %.0f dBFS changes %.0f | video present %.1f fps (dropped %.1f/s) avg %.1f max %.1f ms | "
                "audio buf %.0f ms (min %.0f) in %.0f Hz out %.0f Hz underruns %d silence %.0f ms overflow %.0f ms dev %d Hz xruns %d",
                snap[PERF_EMU_FPS], snap[PERF_TARGET_FPS], (int)lateFrames, snap[PERF_RUN_AVG_MS], snap[PERF_RUN_MAX_MS],
                snap[PERF_CORE_SPEED_PCT], snap[PERF_GAME_FPS], snap[PERF_CORE_CYCLES],
                snap[PERF_AUDIO_LEVEL_DBFS], snap[PERF_FRAME_CHANGES],
                snap[PERF_PRESENT_FPS], snap[PERF_DROPPED_FPS], snap[PERF_PRESENT_AVG_MS], snap[PERF_PRESENT_MAX_MS],
                snap[PERF_AUDIO_BUFFER_MS], snap[PERF_AUDIO_MIN_MS], snap[PERF_AUDIO_IN_HZ], snap[PERF_AUDIO_OUT_HZ],
                (int)snap[PERF_UNDERRUNS], snap[PERF_SILENCE_MS], snap[PERF_OVERFLOW_MS], (int)snap[PERF_DEVICE_RATE], (int)snap[PERF_XRUNS]);

            windowStart = clock::now();
            frames = 0; lateFrames = 0; runMicrosSum = 0; runMicrosMax = 0;
            lastPresented = presented; lastDropped = dropped; lastPresentMicros = presentMicros;
            lastWritten = written; lastPlayed = played; lastUnderruns = underruns; lastSilence = silence; lastOverflow = overflow;
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_engineMutex);
        if (g_core) {
            g_core->stopGame();
        }
        if (g_renderer) {
            g_renderer->stop();
        }
    }

    g_engineRunning.store(false);
    LOGI("DOS emulation loop terminated");
    perfboost::endLoop();

    // Notify listener that emulation finished
    if (g_jvm && g_listenerGlobalRef) {
        JNIEnv* cbEnv = nullptr;
        bool attached = false;
        int status = g_jvm->GetEnv(reinterpret_cast<void**>(&cbEnv), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&cbEnv, nullptr) == JNI_OK) {
                attached = true;
            }
        }
        if (cbEnv && g_listenerGlobalRef) {
            jclass cls = cbEnv->GetObjectClass(g_listenerGlobalRef);
            if (cls) {
                jmethodID mid = cbEnv->GetMethodID(cls, "onEngineTerminated", "(I)V");
                if (mid) {
                    cbEnv->CallVoidMethod(g_listenerGlobalRef, mid, 0);
                    if (cbEnv->ExceptionCheck()) {
                        cbEnv->ExceptionDescribe();
                        cbEnv->ExceptionClear();
                    }
                }
                cbEnv->DeleteLocalRef(cls);
            }
        }
        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    }
}

// --- Video filters (specs/008-shader-filters) -------------------------------------------------------------------------

// Tells Kotlin that a filter can't run on this GPU (the renderer already fell back to the plain picture). Called on the
// render thread; the call into Java happens on a short-lived attached thread, as for onDosProgramChanged.
static void reportFilterFailed(const std::string& id, const std::string& log) {
    std::thread([id, log]() {
        if (!g_jvm || !g_listenerGlobalRef) return;
        JNIEnv* cbEnv = nullptr;
        bool attached = false;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&cbEnv), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&cbEnv, nullptr) == JNI_OK) attached = true;
        }
        if (cbEnv && g_listenerGlobalRef) {
            jclass cls = cbEnv->GetObjectClass(g_listenerGlobalRef);
            jmethodID mid = cbEnv->GetMethodID(cls, "onFilterFailed", "(Ljava/lang/String;Ljava/lang/String;)V");
            if (mid) {
                jstring jId = cbEnv->NewStringUTF(id.c_str());
                jstring jLog = cbEnv->NewStringUTF(log.substr(0, 2000).c_str());
                cbEnv->CallVoidMethod(g_listenerGlobalRef, mid, jId, jLog);
                if (cbEnv->ExceptionCheck()) { cbEnv->ExceptionDescribe(); cbEnv->ExceptionClear(); }
                cbEnv->DeleteLocalRef(jId);
                cbEnv->DeleteLocalRef(jLog);
            } else if (cbEnv->ExceptionCheck()) {
                cbEnv->ExceptionClear();
            }
            cbEnv->DeleteLocalRef(cls);
        }
        if (attached) g_jvm->DetachCurrentThread();
    }).detach();
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static std::vector<std::string> jstrings(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        auto s = (jstring)env->GetObjectArrayElement(arr, i);
        out.push_back(jstr(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

static GpuRenderer* ensureRenderer() {
    if (!g_renderer) g_renderer = std::make_unique<GpuRenderer>();
    g_renderer->setFilterFailedCallback(reportFilterFailed);
    return g_renderer.get();
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_initEngine(
    JNIEnv* env,
    jobject /* thiz */,
    jstring gameDirectory,
    jstring dosboxConfPath,
    jint soundHardwareId,
    jstring cpuCycles,
    jint memoryMb,
    jobject listener
) {
    const char* nativeDir = gameDirectory ? env->GetStringUTFChars(gameDirectory, nullptr) : "";
    const char* nativeConf = dosboxConfPath ? env->GetStringUTFChars(dosboxConfPath, nullptr) : "";
    const char* nativeCycles = cpuCycles ? env->GetStringUTFChars(cpuCycles, nullptr) : "auto";

    LOGI("Initializing DOSBox Core: dir=%s, conf=%s, soundId=%d, cycles=%s, mem=%dMB",
         nativeDir, nativeConf, soundHardwareId, nativeCycles, memoryMb);

    if (g_engineRunning.load()) {
        LOGI("Stopping existing engine before re-initialization");
        g_engineRunning.store(false);
        if (g_emulationThread.joinable()) {
            g_emulationThread.join();
        }
    }

    std::lock_guard<std::mutex> lock(g_engineMutex);

    if (g_listenerGlobalRef) {
        env->DeleteGlobalRef(g_listenerGlobalRef);
        g_listenerGlobalRef = nullptr;
    }
    if (listener) {
        g_listenerGlobalRef = env->NewGlobalRef(listener);
    }

    if (!g_renderer) {
        g_renderer = std::make_unique<GpuRenderer>();
    }

    if (!g_audioSink) {
        g_audioSink = std::make_unique<OboeAudioSink>();
    }
    g_audioSink->setLatencyMode(g_audioLatencyMode);
    g_audioSink->open(48000, 2);

    if (!g_core) {
        g_core = std::make_unique<DosBoxPureCore>();
    }

    g_core->setOnProgramChangeCallback([](const std::string& progName) {
        std::thread([progName]() {
            if (!g_jvm || !g_listenerGlobalRef) return;
            JNIEnv* cbEnv = nullptr;
            bool attached = false;
            int status = g_jvm->GetEnv(reinterpret_cast<void**>(&cbEnv), JNI_VERSION_1_6);
            if (status == JNI_EDETACHED) {
                if (g_jvm->AttachCurrentThread(&cbEnv, nullptr) == JNI_OK) {
                    attached = true;
                }
            }
            if (cbEnv && g_listenerGlobalRef) {
                jclass cls = cbEnv->GetObjectClass(g_listenerGlobalRef);
                jmethodID mid = cbEnv->GetMethodID(cls, "onDosProgramChanged", "(Ljava/lang/String;)V");
                if (mid) {
                    jstring jProg = cbEnv->NewStringUTF(progName.c_str());
                    cbEnv->CallVoidMethod(g_listenerGlobalRef, mid, jProg);
                    if (cbEnv->ExceptionCheck()) {
                        cbEnv->ExceptionDescribe();
                        cbEnv->ExceptionClear();
                    }
                    cbEnv->DeleteLocalRef(jProg);
                }
                cbEnv->DeleteLocalRef(cls);
            }
            if (attached) {
                g_jvm->DetachCurrentThread();
            }
        }).detach();
    });

    if (g_core->isNativePort()) captureCoreOutput();
    bool started = g_core->startGame(
        nativeDir,
        nativeConf,
        soundHardwareId,
        nativeCycles,
        memoryMb,
        g_renderer.get(),
        g_audioSink.get()
    );

    // Cores with a fixed output rate of their own (OpenLara: 44.1 kHz) get a stream at that rate
    const int coreRate = (int)g_core->getCoreSampleRate();
    if (started && g_core->isNativePort() && coreRate >= 8000 && coreRate != g_audioSink->sampleRate()) {
        LOGI("Reopening audio at the core's %d Hz", coreRate);
        g_audioSink->close();
        g_audioSink->open(coreRate, 2);
    }

    g_engineRunning.store(true);
    g_enginePaused.store(false);

    g_emulationThread = std::thread(emulationLoop);

    // Notify listener
    if (listener) {
        jclass listenerClass = env->GetObjectClass(listener);
        jmethodID onStarted = env->GetMethodID(listenerClass, "onEngineStarted", "()V");
        if (onStarted) {
            env->CallVoidMethod(listener, onStarted);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
    }

    if (gameDirectory && nativeDir) env->ReleaseStringUTFChars(gameDirectory, nativeDir);
    if (dosboxConfPath && nativeConf) env->ReleaseStringUTFChars(dosboxConfPath, nativeConf);
    if (cpuCycles && nativeCycles) env->ReleaseStringUTFChars(cpuCycles, nativeCycles);

    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setSurface(
    JNIEnv* env,
    jobject /* thiz */,
    jobject surface
) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (!g_renderer) {
        g_renderer = std::make_unique<GpuRenderer>();
    }
    g_renderer->setWindow(window);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setFilter(
    JNIEnv* env, jobject /* thiz */, jstring id, jstring json, jobjectArray vertexSources, jobjectArray fragmentSources,
    jobjectArray lutNames, jobjectArray lutPixels, jintArray lutSizes, jfloatArray paramValues
) {
    const std::string filterId = jstr(env, id);
    std::unique_ptr<FilterBundle> bundle;
    if (!filterId.empty() && filterId != "off") {
        bundle = std::make_unique<FilterBundle>();
        std::string error;
        if (!FilterBundle::fromJson(jstr(env, json), jstrings(env, vertexSources), jstrings(env, fragmentSources), *bundle, error)) {
            LOGE("setFilter %s: bad bundle: %s", filterId.c_str(), error.c_str());
            reportFilterFailed(filterId, error);
            bundle.reset();
        } else {
            const auto names = jstrings(env, lutNames);
            std::vector<jint> sizes;
            if (lutSizes) {
                sizes.resize(env->GetArrayLength(lutSizes));
                env->GetIntArrayRegion(lutSizes, 0, (jsize)sizes.size(), sizes.data());
            }
            for (size_t i = 0; i < names.size() && lutPixels; ++i) {
                jobject buf = env->GetObjectArrayElement(lutPixels, (jsize)i);
                auto* data = buf ? static_cast<uint8_t*>(env->GetDirectBufferAddress(buf)) : nullptr;
                const jlong cap = buf ? env->GetDirectBufferCapacity(buf) : 0;
                for (auto& lut : bundle->luts) {
                    if (lut.name != names[i]) continue;
                    lut.width = 2 * i + 1 < sizes.size() ? sizes[2 * i] : 0;
                    lut.height = 2 * i + 1 < sizes.size() ? sizes[2 * i + 1] : 0;
                    if (data && cap > 0) lut.rgba.assign(data, data + cap);
                }
                if (buf) env->DeleteLocalRef(buf);
            }
            if (paramValues) {
                std::vector<jfloat> values(env->GetArrayLength(paramValues));
                env->GetFloatArrayRegion(paramValues, 0, (jsize)values.size(), values.data());
                for (size_t i = 0; i < values.size() && i < bundle->params.size(); ++i) bundle->params[i].value = values[i];
            }
        }
    }
    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureRenderer()->setFilter(std::move(bundle));
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setFilterParam(JNIEnv* /* env */, jobject /* thiz */, jint index, jfloat value) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureRenderer()->setFilterParam(index, value);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setDoubleScan(JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureRenderer()->setDoubleScan(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setFillScreen(JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureRenderer()->setFillScreen(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setAudioLatencyMode(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint mode
) {
    g_audioLatencyMode.store(mode == 1 ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_updateVisualPreset(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint presetId,
    jboolean isSnapdragonGpu,
    jfloat sharpness
) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_renderer) {
        g_renderer->setVisualPreset(presetId, isSnapdragonGpu, sharpness);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendKeyEvent(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint dosScancode,
    jboolean isDown
) {
    if (g_core) {
        g_core->sendKeyEvent(dosScancode, isDown);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendAnalogSticks(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat leftX,
    jfloat leftY,
    jfloat rightX,
    jfloat rightY
) {
    if (g_core) {
        g_core->sendAnalogSticks(leftX, leftY, rightX, rightY);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setStickMode(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint mode
) {
    if (g_core) {
        g_core->setStickMode(mode);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendJoypadButton(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint buttonId,
    jboolean isDown
) {
    if (g_core) {
        g_core->sendJoypadButton(buttonId, isDown);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_pointMouseAt(JNIEnv* /* env */, jobject /* thiz */, jfloat u, jfloat v) {
    if (g_core) g_core->pointMouseAt(u, v);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendRelativeMouse(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat deltaX,
    jfloat deltaY
) {
    if (g_core) {
        g_core->sendRelativeMouse(deltaX, deltaY);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setMouseLook(JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_core) g_core->setMouseLook(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setLookDeadzoneY(JNIEnv* /* env */, jobject /* thiz */, jfloat deadzone) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_core) g_core->setLookDeadzoneY(deadzone);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setMouseLookKey(JNIEnv* /* env */, jobject /* thiz */, jint dosScancode) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_core) g_core->setMouseLookKey(dosScancode);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setStickLookSpeed(JNIEnv* /* env */, jobject /* thiz */, jfloat speed) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_core) g_core->setStickLookSpeed(speed);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setLookRecenter(JNIEnv* /* env */, jobject /* thiz */, jint dosScancode, jint delayMs) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_core) g_core->setLookRecenter(dosScancode, delayMs);
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendMotionPitch(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat pitchY
) {
    if (g_core) {
        g_core->sendMotionPitch(pitchY);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendMouseButton(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint buttonMask,
    jboolean isDown
) {
    if (g_core) {
        g_core->sendMouseButton(buttonMask, isDown);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendMouseWheel(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint deltaLines
) {
    if (g_core) {
        g_core->sendMouseWheel(deltaLines);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_sendPointerAbsolute(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat normalizedX,
    jfloat normalizedY,
    jboolean isPressed
) {
    if (g_core) {
        g_core->sendPointerAbsolute(normalizedX, normalizedY, isPressed);
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_getPerfStats(JNIEnv* env, jobject /* thiz */) {
    float snap[PERF_FIELD_COUNT];
    {
        std::lock_guard<std::mutex> lock(g_perfMutex);
        std::copy(g_perfSnapshot, g_perfSnapshot + PERF_FIELD_COUNT, snap);
    }
    jfloatArray out = env->NewFloatArray(PERF_FIELD_COUNT);
    if (out) env->SetFloatArrayRegion(out, 0, PERF_FIELD_COUNT, snap);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_getScreenText(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF(screenText().screen().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_getRecentLog(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF(screenText().log().c_str());
}

/** Which CPU (and clock) each emulation thread last ran on, for the session journal. */
JNIEXPORT jstring JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_getPerfThreads(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF(perfboost::describe().c_str());
}

JNIEXPORT jlong JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_getFrameChangeCount(JNIEnv* /* env */, jobject /* thiz */) {
    return g_renderer ? (jlong)g_renderer->statSignatureChanges.load(std::memory_order_relaxed) : 0;
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_configureCore(
    JNIEnv* env,
    jobject /* thiz */,
    jstring libraryName,
    jstring systemDir,
    jstring saveDir
) {
    auto toStd = [env](jstring js) {
        if (!js) return std::string();
        const char* c = env->GetStringUTFChars(js, nullptr);
        std::string out(c ? c : "");
        if (c) env->ReleaseStringUTFChars(js, c);
        return out;
    };
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (!g_core) {
        g_core = std::make_unique<DosBoxPureCore>();
    }
    g_core->setCoreConfig(toStd(libraryName), toStd(systemDir), toStd(saveDir));
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setDirectMouse(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jboolean enabled
) {
    if (g_core) {
        g_core->setDirectMouse(enabled);
    }
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_setPaused(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jboolean paused
) {
    g_enginePaused.store(paused);
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_audioSink) {
        if (paused) g_audioSink->pause();
        else g_audioSink->start();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_saveState(
    JNIEnv* env,
    jobject /* thiz */,
    jint slotIndex,
    jstring savePath
) {
    const char* nativePath = savePath ? env->GetStringUTFChars(savePath, nullptr) : nullptr;
    std::lock_guard<std::mutex> lock(g_engineMutex);
    bool ok = false;
    if (g_core && nativePath) {
        ok = g_core->saveState(slotIndex, nativePath);
    }
    if (savePath && nativePath) env->ReleaseStringUTFChars(savePath, nativePath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_getCoreVersion(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    return env->NewStringUTF(g_core ? g_core->coreVersion().c_str() : "");
}

JNIEXPORT jboolean JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_loadState(
    JNIEnv* env,
    jobject /* thiz */,
    jint slotIndex,
    jstring savePath
) {
    const char* nativePath = savePath ? env->GetStringUTFChars(savePath, nullptr) : nullptr;
    std::lock_guard<std::mutex> lock(g_engineMutex);
    bool ok = false;
    if (g_core && nativePath) {
        ok = g_core->loadState(slotIndex, nativePath);
    }
    if (savePath && nativePath) env->ReleaseStringUTFChars(savePath, nativePath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_goa_engine_emulator_DosBoxEngineBridge_terminateEngine(
    JNIEnv* env,
    jobject /* thiz */
) {
    LOGI("Terminating DOSBox engine");
    g_engineRunning.store(false);

    if (g_emulationThread.joinable()) {
        g_emulationThread.join();
    }
    perfboost::reset();

    std::lock_guard<std::mutex> lock(g_engineMutex);

    if (g_core) {
        g_core->stopGame();
        g_core.reset();
    }

    if (g_audioSink) {
        g_audioSink->close();
        g_audioSink.reset();
    }

    if (g_renderer) {
        g_renderer->stop();
        g_renderer.reset();
    }

    if (g_listenerGlobalRef) {
        env->DeleteGlobalRef(g_listenerGlobalRef);
        g_listenerGlobalRef = nullptr;
    }
}

} // extern "C"
