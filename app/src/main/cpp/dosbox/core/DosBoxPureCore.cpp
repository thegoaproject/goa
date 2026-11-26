#include "DosBoxPureCore.h"
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <EGL/egl.h>
#include "HwRenderContext.h"
#include <android/log.h>
#include "ScreenText.h"
#include "PerfBoost.h"
#include <cstring>
#include <cstdarg>
#include <cstdio>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <filesystem>
#include <zlib.h>
#include <cerrno>
#include <unistd.h>

#define TAG "DosBoxPureCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define RETRO_DEVICE_JOYPAD             1
#define RETRO_DEVICE_MOUSE              2
#define RETRO_DEVICE_KEYBOARD           3
#define RETRO_DEVICE_ANALOG             5

#define RETRO_DEVICE_INDEX_ANALOG_LEFT  0
#define RETRO_DEVICE_INDEX_ANALOG_RIGHT 1
#define RETRO_DEVICE_ID_ANALOG_X        0
#define RETRO_DEVICE_ID_ANALOG_Y        1

#define RETRO_DEVICE_ID_JOYPAD_B        0
#define RETRO_DEVICE_ID_JOYPAD_Y        1
#define RETRO_DEVICE_ID_JOYPAD_SELECT   2
#define RETRO_DEVICE_ID_JOYPAD_START    3
#define RETRO_DEVICE_ID_JOYPAD_UP       4
#define RETRO_DEVICE_ID_JOYPAD_DOWN     5
#define RETRO_DEVICE_ID_JOYPAD_LEFT     6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT    7
#define RETRO_DEVICE_ID_JOYPAD_A        8
#define RETRO_DEVICE_ID_JOYPAD_X        9

#define RETRO_DEVICE_ID_MOUSE_X         0
#define RETRO_DEVICE_ID_MOUSE_Y         1
#define RETRO_DEVICE_ID_MOUSE_LEFT      2
#define RETRO_DEVICE_ID_MOUSE_RIGHT     3
#define RETRO_DEVICE_ID_MOUSE_MIDDLE    4
#define RETRO_DEVICE_ID_MOUSE_WHEELUP   7
#define RETRO_DEVICE_ID_MOUSE_WHEELDOWN 8

#define RETRO_DEVICE_POINTER            6
#define RETRO_DEVICE_ID_POINTER_X       0
#define RETRO_DEVICE_ID_POINTER_Y       1
#define RETRO_DEVICE_ID_POINTER_PRESSED 2
#define RETRO_DEVICE_ID_POINTER_COUNT   3

#define RETRO_ENVIRONMENT_SHUTDOWN 7
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 10
#define RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK 12
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31
#define RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO 32
#define RETRO_ENVIRONMENT_GET_VARIABLE 15
#define RETRO_ENVIRONMENT_SET_VARIABLES 16
#define RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE 17
#define RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME 18
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27
#define RETRO_ENVIRONMENT_SET_GEOMETRY 37
#define RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION 52
#define RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE (0x10000 | 47)
#define RETRO_ENVIRONMENT_GET_THROTTLE_STATE (0x10000 | 71)
#define RETRO_ENVIRONMENT_GET_FASTFORWARDING (0x10000 | 31)

struct retro_throttle_state {
    unsigned mode;
    float rate;
};

typedef void (*retro_keyboard_event_t)(bool down, unsigned keycode, uint32_t character, uint16_t key_modifiers);

struct retro_keyboard_callback {
    retro_keyboard_event_t callback;
};

static retro_keyboard_event_t s_keyboardCallback = nullptr;

enum retro_log_level {
    RETRO_LOG_DEBUG = 0,
    RETRO_LOG_INFO,
    RETRO_LOG_WARN,
    RETRO_LOG_ERROR,
    RETRO_LOG_DUMMY = 0x7FFFFFFF
};

struct retro_log_callback {
    void (*log)(int level, const char* fmt, ...);
};

#define RETRO_ENVIRONMENT_SET_MESSAGE 6
#define RETRO_ENVIRONMENT_SET_MESSAGE_EXT 60

struct retro_message {
    const char* msg;
    unsigned frames;
};

// Only the leading field is read; the rest (duration, priority, level, target, type, progress) is ignored
struct retro_message_ext {
    const char* msg;
};

static DosBoxPureCore* s_activeCore = nullptr;
static GpuRenderer* s_activeRenderer = nullptr;
static OboeAudioSink* s_activeAudioSink = nullptr;

void retro_log_printf(int level, const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    int android_level = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_DEBUG: android_level = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO:  android_level = ANDROID_LOG_INFO;  break;
        case RETRO_LOG_WARN:  android_level = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_ERROR: android_level = ANDROID_LOG_ERROR; break;
        default: break;
    }
    __android_log_print(android_level, "DosBoxPureCore", "%s", buffer);
    screenText().onLog(level, buffer);   // kept for the troubleshooter (Honor and others keep no app logs)

    // Detect DOS program transitions (e.g., SETUP.EXE -> DOSBOX -> DOOM2.EXE)
    if (strncmp(buffer, "[DOSBOX STATUS] Program: ", 25) == 0) {
        perfboost::registerThread("dos"); // status lines come from DOSBox Pure's own DOS thread
        const char* progStart = buffer + 25;
        const char* dash = strstr(progStart, " - ");
        std::string progName = dash ? std::string(progStart, dash - progStart) : std::string(progStart);
        if (s_activeCore && s_activeCore->m_onProgramChange) {
            s_activeCore->m_onProgramChange(progName);
        }
    }
}

enum retro_pixel_format {
   RETRO_PIXEL_FORMAT_0RGB1555 = 0,
   RETRO_PIXEL_FORMAT_XRGB8888 = 1,
   RETRO_PIXEL_FORMAT_RGB565   = 2
};

struct retro_game_info {
   const char *path;
   const void *data;
   size_t      size;
   const char *meta;
};

struct retro_variable {
   const char *key;
   const char *value;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float    aspect_ratio;
};

struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};

static retro_pixel_format s_pixelFormat = RETRO_PIXEL_FORMAT_XRGB8888;

// --- Hardware rendering: cores that draw with OpenGL ES (OpenLara) get a GLES3 context of their own -------------------
#define RETRO_ENVIRONMENT_SET_HW_RENDER 14
#define RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT (0x10000 | 44)
#define RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER 56
#define RETRO_HW_FRAME_BUFFER_VALID ((const void*)(intptr_t)-1)
enum { RETRO_HW_CONTEXT_NONE = 0, RETRO_HW_CONTEXT_OPENGLES2 = 2, RETRO_HW_CONTEXT_OPENGLES3 = 4, RETRO_HW_CONTEXT_OPENGLES_VERSION = 5 };
typedef void (*retro_proc_address_t)(void);
struct retro_hw_render_callback {
    unsigned context_type;
    void (*context_reset)(void);
    uintptr_t (*get_current_framebuffer)(void);
    retro_proc_address_t (*get_proc_address)(const char* sym);
    bool depth;
    bool stencil;
    bool bottom_left_origin;
    unsigned version_major;
    unsigned version_minor;
    bool cache_context;
    void (*context_destroy)(void);
    bool debug_context;
};

static bool s_hwAllowed = false;      // this game's core draws with the GPU (OpenLara); other cores are refused
static bool s_hwRequested = false;    // the core asked for a context and we agreed
static bool s_hwInited = false;       // context bound to the emulation thread and context_reset called
static retro_hw_render_callback s_hwCb = {};

static uintptr_t hw_get_current_framebuffer() { return HwRenderContext::instance().currentFramebuffer(); }

static retro_proc_address_t hw_get_proc_address(const char* name) {
    void* p = dlsym(RTLD_DEFAULT, name);
    if (!p) p = (void*)eglGetProcAddress(name);
    return (retro_proc_address_t)p;
}

// Emulation thread, first runFrame: the context must be current where retro_run (every core GL call) runs
static void hwInitOnEmulationThread() {
    if (!s_hwRequested || s_hwInited) return;
    if (!HwRenderContext::instance().bindToThisThread(s_hwCb.depth, s_hwCb.stencil)) {
        LOGE("Hardware render: no GLES context for the core");
        s_hwRequested = false;
        return;
    }
    HwRenderContext::instance().currentFramebuffer();  // allocates the core's framebuffer before it asks for it
    if (s_hwCb.context_reset) s_hwCb.context_reset();
    s_hwInited = true;
    LOGI("Hardware render: context_reset delivered");
}

static void hwShutdownOnEmulationThread() {
    if (s_hwInited && s_hwCb.context_destroy) s_hwCb.context_destroy();
    if (s_hwInited || HwRenderContext::instance().inUse()) HwRenderContext::instance().destroy();
    s_hwRequested = s_hwInited = false;
    s_hwCb = {};
}

bool retro_environment_callback(unsigned cmd, void* data) {
    if (!s_activeCore) return false;

    switch (cmd) {
        case RETRO_ENVIRONMENT_SHUTDOWN: {
            LOGI("DOSBox core requested shutdown (RETRO_ENVIRONMENT_SHUTDOWN)");
            if (s_activeCore) {
                s_activeCore->requestShutdown();
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const enum retro_pixel_format* fmt = (const enum retro_pixel_format*)data;
            if (*fmt == RETRO_PIXEL_FORMAT_XRGB8888 || *fmt == RETRO_PIXEL_FORMAT_RGB565) {
                s_pixelFormat = *fmt;
                LOGI("DOSBox Pure set pixel format: %d", *fmt);
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            const char** dir = (const char**)data;
            *dir = s_activeCore->m_systemDir.empty() ? s_activeCore->m_gameDir.c_str() : s_activeCore->m_systemDir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            const char** dir = (const char**)data;
            *dir = s_activeCore->m_saveDir.empty() ? s_activeCore->m_gameDir.c_str() : s_activeCore->m_saveDir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable* var = (struct retro_variable*)data;
            if (var && var->key && s_activeCore) {
                if (strcmp(var->key, "dosbox_pure_menu_time") == 0) {
                    var->value = "0";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_conf") == 0) {
                    var->value = "inside";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_cycles") == 0) {
                    var->value = s_activeCore->m_cpuCycles.c_str();
                    return true;
                }
                // With auto/max the speed is only capped by the time budget below, never by a fixed ceiling
                if (strcmp(var->key, "dosbox_pure_cycles_max") == 0) {
                    var->value = "none";
                    return true;
                }
                // With cycles=max/auto DOSBox Pure would spend 100% of every frame emulating the CPU, so one slow frame
                // (Quake at high resolution) misses the audio deadline. Keep 10% headroom for audio and frame hand-off.
                // The core reads a fraction with atof ("0.9"): "90%" read as 90, a budget of 90 frames per frame.
                if (strcmp(var->key, "dosbox_pure_cycle_limit") == 0) {
                    var->value = "0.9";
                    return true;
                }
                // Detailed perfstats arrive as SET_MESSAGE once a second; parsed into GoaPerf telemetry, never shown
                if (strcmp(var->key, "dosbox_pure_perfstats") == 0) {
                    var->value = "detailed";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_memory_size") == 0) {
                    var->value = s_activeCore->m_memoryMbStr.c_str();
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_auto_mapping") == 0) {
                    var->value = "false";
                    return true;
                }
                // ScummVM: this frontend has no GL context to hand the core, so ask for software rendering
                // Finger/stylus taps are absolute ("pointer"); trackpad and hardware mouse are relative ("mouse")
                if (strcmp(var->key, "scummvm_pointer_device") == 0) {
                    var->value = s_activeCore->m_directMouse.load() ? "pointer" : "mouse";
                    return true;
                }
                if (strcmp(var->key, "scummvm_video_hw_acceleration") == 0) {
                    var->value = "disabled";
                    return true;
                }
                // TyrQuake: twice the original resolution each way (it renders in software, natively on the CPU), GOA's
                // 48 kHz output, and a steady 60 fps
                if (strcmp(var->key, "tyrquake_resolution") == 0) {
                    // adb shell setprop debug.goa.tyrquake.res 960x720 tries another size (for measuring)
                    static char res[PROP_VALUE_MAX] = {0};
                    var->value = (__system_property_get("debug.goa.tyrquake.res", res) > 0) ? res : "640x480";
                    return true;
                }
                // OpenLara draws with the GPU: a sharp 4:3 picture for a phone screen, at a steady 60 fps
                if (strcmp(var->key, "openlara_resolution") == 0) {
                    // adb shell setprop debug.goa.openlara.res 1920x1440 tries another size (for measuring)
                    static char res[PROP_VALUE_MAX] = {0};
                    var->value = (__system_property_get("debug.goa.openlara.res", res) > 0) ? res : "1280x960";
                    return true;
                }
                if (strcmp(var->key, "openlara_framerate") == 0) {
                    var->value = "60fps";
                    return true;
                }
                if (strcmp(var->key, "tyrquake_sound_samplerate") == 0) {
                    var->value = "48000";
                    return true;
                }
                if (strcmp(var->key, "tyrquake_framerate") == 0) {
                    var->value = "60";
                    return true;
                }
                // TyrQuake pitches the view against the stick unless this is on (libretro.c IN_Move: viewangles[PITCH]
                // -= invert_y_axis * stick Y, and "disabled" means 1), so pushing up looked down
                if (strcmp(var->key, "tyrquake_invert_y_axis") == 0) {
                    var->value = "enabled";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_bind_unused") == 0) {
                    var->value = "false";
                    return true;
                }
                // Touch/stylus point-and-click places the cursor where the user taps ("direct");
                // hardware mice and the virtual-mouse stick use relative motion ("true")
                if (strcmp(var->key, "dosbox_pure_mouse_input") == 0) {
                    var->value = s_activeCore->m_directMouse.load() ? "direct" : "true";
                    return true;
                }
                // Sound card hardware emulation:
                // PAS16 (0), SB16 (1), SBPro (2), MT-32 (3), GM (4), GUS (5), AdLib (6)
                if (strcmp(var->key, "dosbox_pure_sblaster_type") == 0) {
                    if (s_activeCore->m_soundHardwareId == 2) {
                        var->value = "sbpro2";
                    } else {
                        // Pro Audio Spectrum 16 (PAS16) and SB16 use SoundBlaster 16 with Nuked OPL3
                        var->value = "sb16";
                    }
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_sblaster_adlib_emu") == 0) {
                    var->value = "default";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_sblaster_adlib_mode") == 0) {
                    var->value = "auto";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_gus") == 0) {
                    // Enable Gravis UltraSound (GUS) only when explicitly selected and ULTRASND exists
                    std::error_code ec; // the throwing overload aborts the process on a folder it can't read
                    bool hasUltrasnd = std::filesystem::exists(s_activeCore->m_gameDir + "/ULTRASND", ec);
                    var->value = (s_activeCore->m_soundHardwareId == 5 && hasUltrasnd) ? "true" : "false";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_midi") == 0) {
                    if (s_activeCore->m_soundHardwareId == 3) {
                        var->value = "Roland MT-32";
                    } else {
                        var->value = "SoundFont";
                    }
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_tandysound") == 0) {
                    // Tandy 3-Voice Sound System
                    var->value = "auto";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_audiorate") == 0) {
                    var->value = "48000";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_sblaster_conf") == 0) {
                    var->value = "A220 I7 D1 H5";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_volume_sb") == 0 ||
                    strcmp(var->key, "dosbox_pure_volume_midi") == 0 ||
                    strcmp(var->key, "dosbox_pure_volume_other") == 0 ||
                    strcmp(var->key, "dosbox_pure_volume_speaker") == 0 ||
                    strcmp(var->key, "dosbox_pure_volume_adlib") == 0 ||
                    strcmp(var->key, "dosbox_pure_volume_cdrom") == 0) {
                    var->value = "1.0";
                    return true;
                }
                // 3dfx Glide Hardware 3D Acceleration:
                if (strcmp(var->key, "dosbox_pure_voodoo") == 0) {
                    var->value = "8mb";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_voodoo_perf") == 0) {
                    var->value = "auto";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_voodoo_scale") == 0) {
                    var->value = "1";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_voodoo_gamma") == 0) {
                    var->value = "0";
                    return true;
                }
                // SVGA Video Modules:
                if (strcmp(var->key, "dosbox_pure_machine") == 0) {
                    var->value = "svga";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_svga") == 0) {
                    var->value = "svga_s3";
                    return true;
                }
                if (strcmp(var->key, "dosbox_pure_svgamem") == 0) {
                    var->value = "4";
                    return true;
                }
            }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK: {
            const struct retro_keyboard_callback* cb = (const struct retro_keyboard_callback*)data;
            if (cb) {
                s_keyboardCallback = cb->callback;
                LOGI("DOSBox Pure registered keyboard callback");
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE:
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            // Both structs start with the message text
            const struct retro_message_ext* message = (const struct retro_message_ext*)data;
            if (message && message->msg && s_activeCore) {
                s_activeCore->handleCoreMessage(message->msg);
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback* cb = (struct retro_log_callback*)data;
            if (cb) {
                cb->log = retro_log_printf;
                LOGI("DOSBox Pure connected to log interface");
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: {
            int* mask = (int*)data;
            if (mask) {
                *mask = 1 | 2; // Bit 0: Video enabled, Bit 1: Audio enabled
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
            struct retro_throttle_state* state = (struct retro_throttle_state*)data;
            if (state) {
                // Normal play is RETRO_THROTTLE_NONE at the core's own rate. FRAME_STEPPING (1) means paused or stepping
                // single frames: DOSBox Pure then skips its speed adjustment, so auto/max games never got more cycles
                // than they started with (Tomb Raider held at 30,000 on any device)
                state->mode = 0;
                state->rate = s_activeCore ? (float)s_activeCore->getTargetFps() : 60.0f;
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING: {
            bool* ff = (bool*)data;
            if (ff) {
                *ff = false;
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            bool* updated = (bool*)data;
            if (updated) {
                *updated = s_activeCore->m_variablesDirty.exchange(false);
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            if (!s_hwAllowed) return false;
            *(unsigned*)data = RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            return s_hwAllowed;
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto* cb = (retro_hw_render_callback*)data;
            if (!s_hwAllowed || !cb) return false;  // ScummVM and DOSBox Pure probe for it: they keep drawing pixels
            if (cb->context_type != RETRO_HW_CONTEXT_OPENGLES2 && cb->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                cb->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) {
                return false;  // desktop GL: the core tries its next context type
            }
            cb->get_current_framebuffer = hw_get_current_framebuffer;
            cb->get_proc_address = hw_get_proc_address;
            s_hwCb = *cb;
            s_hwRequested = true;
            LOGI("Hardware render accepted: context_type=%u version=%u.%u depth=%d stencil=%d bottom_left=%d",
                 cb->context_type, cb->version_major, cb->version_minor, cb->depth, cb->stencil, cb->bottom_left_origin);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const struct retro_system_av_info* av = (const struct retro_system_av_info*)data;
            if (av) HwRenderContext::instance().setMaxSize((int)av->geometry.max_width, (int)av->geometry.max_height);
            if (av && av->timing.fps > 10.0) {
                s_activeCore->m_targetFps.store(av->timing.fps);
                LOGI("DOSBox Pure updated AV info: fps=%.2f, sample_rate=%.1f", av->timing.fps, av->timing.sample_rate);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;
        default: {
            static uint64_t s_seenEnvMask = 0;  // log each distinct unhandled command once (cmd & 0xFFFF < 64 covers the common ones)
            unsigned base = cmd & 0xFFFF;
            if (base < 64 && !(s_seenEnvMask & (1ull << base))) {
                s_seenEnvMask |= (1ull << base);
                LOGI("Unhandled libretro environment cmd %u (0x%x) -> false", base, cmd);
            }
            return false;
        }
    }
}

void retro_video_refresh_callback(const void* data, unsigned width, unsigned height, size_t pitch) {
    // RETRO_HW_FRAME_BUFFER_VALID ((void*)-1) means "the frame is in the GL context": only cores granted one (OpenLara)
    // draw there; for the rest (ScummVM probes for it) the marker must never be read as pixels
    if (data == RETRO_HW_FRAME_BUFFER_VALID && s_hwInited && width > 0 && height > 0 && s_activeRenderer) {
        if (s_activeCore) { s_activeCore->m_frameWidth.store((int)width); s_activeCore->m_frameHeight.store((int)height); }
        HwRenderContext::Frame frame;
        if (HwRenderContext::instance().endFrame((int)width, (int)height, &frame)) {
            s_activeRenderer->submitHwFrame(frame);
        }
        return;
    }
    if (!data || data == (const void*)(intptr_t)-1 || width == 0 || height == 0 || !s_activeRenderer) return;
    if (s_activeCore) { s_activeCore->m_frameWidth.store((int)width); s_activeCore->m_frameHeight.store((int)height); }

    // Hand the core's own pixel layout to the renderer; it copies into its mailbox and converts on the GPU
    const FramePixelFormat format = s_pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? FramePixelFormat::RGB565 : FramePixelFormat::XRGB8888;
    s_activeRenderer->submitFrame(data, (int)width, (int)height, pitch, format);
    screenText().onFrame(data, width, height, pitch, s_pixelFormat == RETRO_PIXEL_FORMAT_RGB565);
}

static uint64_t s_audioSampleCallCount = 0;

size_t retro_audio_sample_batch_callback(const int16_t* data, size_t frames) {
    if (s_activeAudioSink && data && frames > 0) {
        s_activeAudioSink->writeAudio(data, (int32_t)frames);
    }
    return frames;
}

void retro_audio_sample_callback(int16_t left, int16_t right) {
    if (++s_audioSampleCallCount <= 5 || s_audioSampleCallCount % 300 == 0) {
        LOGI("Audio sample: call #%llu, left=%d, right=%d",
             (unsigned long long)s_audioSampleCallCount, (int)left, (int)right);
    }
    if (s_activeAudioSink) {
        int16_t frame[2] = {left, right};
        s_activeAudioSink->writeAudio(frame, 1);
    }
}

void retro_input_poll_callback(void) {
    // Input polling handled via state queries
}

int16_t retro_input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (!s_activeCore || port != 0) return 0;

    if (device == RETRO_DEVICE_JOYPAD) {
        if ((s_activeCore->m_stickMode.load() == 2 /* NATIVE_GAMEPORT */ || s_activeCore->m_isNativePort) && id < 16) {
            return s_activeCore->m_joypadButtons[id].load() ? 1 : 0;
        }
        // In Keyboard/FPS modes, return 0 so DOSBox Pure's internal pad-to-key mapper
        // never conflicts with our direct scancode dispatch.
        return 0;
    } else if (device == RETRO_DEVICE_ANALOG) {
        if (s_activeCore->m_stickMode.load() == 2 /* NATIVE_GAMEPORT */ || s_activeCore->m_isNativePort) {
            // Libretro Analog: index 0 = Left Stick, index 1 = Right Stick; id 0 = X, id 1 = Y
            // Directly maps to IBM PC 15-pin Gameport I/O Port 0x201 axes
            if (index == 0 /* LEFT STICK */) {
                if (id == 0 /* X */) {
                    return static_cast<int16_t>(s_activeCore->m_leftStickX.load() * 32767.0f);
                } else if (id == 1 /* Y */) {
                    return static_cast<int16_t>(s_activeCore->m_leftStickY.load() * 32767.0f);
                }
            } else if (index == 1 /* RIGHT STICK */) {
                if (id == 0 /* X */) {
                    return static_cast<int16_t>(s_activeCore->m_rightStickX.load() * 32767.0f);
                } else if (id == 1 /* Y */) {
                    // Native Quake looks up and down with it: the vertical look deadzone applies
                    const float y = s_activeCore->m_rightStickY.load();
                    return static_cast<int16_t>((s_activeCore->m_isTyrQuake ? s_activeCore->lookY(y) : y) * 32767.0f);
                }
            }
        }
        return 0;
    } else if (device == RETRO_DEVICE_MOUSE) {
        switch (id) {
            case RETRO_DEVICE_ID_MOUSE_X:      return s_activeCore->m_mouseDeltaX.exchange(0);
            case RETRO_DEVICE_ID_MOUSE_Y:      return s_activeCore->m_mouseDeltaY.exchange(0);
            case RETRO_DEVICE_ID_MOUSE_LEFT:   return s_activeCore->m_mouseLeft.load() ? 1 : 0;
            case RETRO_DEVICE_ID_MOUSE_RIGHT:  return s_activeCore->m_mouseRight.load() ? 1 : 0;
            case RETRO_DEVICE_ID_MOUSE_MIDDLE: return s_activeCore->m_mouseMiddle.load() ? 1 : 0;
            case RETRO_DEVICE_ID_MOUSE_WHEELUP: {
                int16_t w = s_activeCore->m_mouseWheel.load();
                if (w > 0) {
                    s_activeCore->m_mouseWheel.fetch_sub(1);
                    return 1;
                }
                return 0;
            }
            case RETRO_DEVICE_ID_MOUSE_WHEELDOWN: {
                int16_t w = s_activeCore->m_mouseWheel.load();
                if (w < 0) {
                    s_activeCore->m_mouseWheel.fetch_add(1);
                    return 1;
                }
                return 0;
            }
            default: return 0;
        }
    } else if (device == RETRO_DEVICE_POINTER) {
        switch (id) {
            case RETRO_DEVICE_ID_POINTER_X:       return s_activeCore->m_pointerX.load();
            case RETRO_DEVICE_ID_POINTER_Y:       return s_activeCore->m_pointerY.load();
            case RETRO_DEVICE_ID_POINTER_PRESSED: return s_activeCore->m_pointerPressed.load() ? 1 : 0;
            case RETRO_DEVICE_ID_POINTER_COUNT:   return 1;
            default: return 0;
        }
    } else if (device == RETRO_DEVICE_KEYBOARD) {
        if (id < 512) {
            return s_activeCore->m_keys[id].load() ? 1 : 0;
        }
        return 0;
    }
    return 0;
}

DosBoxPureCore::DosBoxPureCore() {
    loadLibrary();
}

DosBoxPureCore::~DosBoxPureCore() {
    stopGame();
    if (m_libHandle) {
        dlclose(m_libHandle);
        m_libHandle = nullptr;
    }
}

bool DosBoxPureCore::loadLibrary() {
    if (m_libHandle) return true;

    // Load the selected libretro core from the app's native library directory
    m_libHandle = dlopen(m_coreLibrary.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!m_libHandle) {
        LOGE("Failed to dlopen %s: %s", m_coreLibrary.c_str(), dlerror());
        return false;
    }

    m_retro_init = (void (*)(void))dlsym(m_libHandle, "retro_init");
    m_retro_deinit = (void (*)(void))dlsym(m_libHandle, "retro_deinit");
    m_retro_api_version = (unsigned (*)(void))dlsym(m_libHandle, "retro_api_version");
    m_retro_set_environment = (void (*)(bool (*)(unsigned, void *)))dlsym(m_libHandle, "retro_set_environment");
    m_retro_set_video_refresh = (void (*)(void (*)(const void *, unsigned, unsigned, size_t)))dlsym(m_libHandle, "retro_set_video_refresh");
    m_retro_set_audio_sample = (void (*)(void (*)(int16_t, int16_t)))dlsym(m_libHandle, "retro_set_audio_sample");
    m_retro_set_audio_sample_batch = (void (*)(size_t (*)(const int16_t *, size_t)))dlsym(m_libHandle, "retro_set_audio_sample_batch");
    m_retro_set_input_poll = (void (*)(void (*)(void)))dlsym(m_libHandle, "retro_set_input_poll");
    m_retro_set_input_state = (void (*)(int16_t (*)(unsigned, unsigned, unsigned, unsigned)))dlsym(m_libHandle, "retro_set_input_state");
    m_retro_set_controller_port_device = (void (*)(unsigned, unsigned))dlsym(m_libHandle, "retro_set_controller_port_device");
    m_retro_reset = (void (*)(void))dlsym(m_libHandle, "retro_reset");
    m_retro_run = (void (*)(void))dlsym(m_libHandle, "retro_run");
    m_retro_serialize_size = (size_t (*)(void))dlsym(m_libHandle, "retro_serialize_size");
    m_retro_serialize = (bool (*)(void *, size_t))dlsym(m_libHandle, "retro_serialize");
    m_retro_unserialize = (bool (*)(const void *, size_t))dlsym(m_libHandle, "retro_unserialize");
    m_retro_load_game = (bool (*)(const void *))dlsym(m_libHandle, "retro_load_game");
    m_retro_unload_game = (void (*)(void))dlsym(m_libHandle, "retro_unload_game");
    m_retro_get_system_av_info = (void (*)(void *))dlsym(m_libHandle, "retro_get_system_av_info");

    if (!m_retro_init || !m_retro_run || !m_retro_load_game) {
        LOGE("Failed to locate required libretro symbols in %s", m_coreLibrary.c_str());
        dlclose(m_libHandle);
        m_libHandle = nullptr;
        return false;
    }

    // libretro's struct retro_system_info, read once for the engine version recorded with save states
    struct SystemInfo {
        const char* library_name;
        const char* library_version;
        const char* valid_extensions;
        bool need_fullpath;
        bool block_extract;
    };
    auto getSystemInfo = (void (*)(SystemInfo*))dlsym(m_libHandle, "retro_get_system_info");
    m_coreVersion.clear();
    if (getSystemInfo) {
        SystemInfo info{};
        getSystemInfo(&info);
        if (info.library_version) m_coreVersion = info.library_version;
    }

    LOGI("%s %s loaded successfully! Libretro API v%u", m_coreLibrary.c_str(), m_coreVersion.c_str(),
         m_retro_api_version ? m_retro_api_version() : 1);
    return true;
}

bool DosBoxPureCore::startGame(
    const std::string& gameDir,
    const std::string& confPath,
    int soundHardwareId,
    const std::string& cpuCycles,
    int memoryMb,
    GpuRenderer* renderer,
    OboeAudioSink* audioSink
) {
    if (!loadLibrary()) {
        return false;
    }

    stopGame();

    s_activeCore = this;
    s_activeRenderer = renderer;
    s_activeAudioSink = audioSink;
    s_hwAllowed = m_isOpenLara;

    m_gameDir = gameDir;
    m_confPath = confPath;
    // DOSBox Pure's option takes auto, max or a number: "fixed 25000" -> "25000", "max 80%" -> "max"
    m_cpuCycles = cpuCycles.empty() ? "auto" : cpuCycles;
    if (m_cpuCycles.rfind("fixed", 0) == 0) {
        const size_t digits = m_cpuCycles.find_first_of("0123456789");
        m_cpuCycles = digits == std::string::npos ? "auto" : m_cpuCycles.substr(digits);
    } else if (m_cpuCycles.rfind("max", 0) == 0) {
        m_cpuCycles = "max";
    }
    // Games that take all the CPU they get (cycles auto/max) ask Android for the fastest cores (PerfBoost)
    perfboost::setBoost((m_isDosBoxCore && (m_cpuCycles == "auto" || m_cpuCycles == "max")) || m_isNativePort);
    m_memoryMbStr = std::to_string(memoryMb > 0 ? memoryMb : 16);
    m_soundHardwareId = soundHardwareId;

    m_retro_set_environment(retro_environment_callback);
    m_retro_set_video_refresh(retro_video_refresh_callback);
    if (m_retro_set_audio_sample) {
        m_retro_set_audio_sample(retro_audio_sample_callback);
    }
    if (m_retro_set_audio_sample_batch) {
        m_retro_set_audio_sample_batch(retro_audio_sample_batch_callback);
    }
    m_retro_set_input_poll(retro_input_poll_callback);
    m_retro_set_input_state(retro_input_state_callback);

    m_retro_init();

    {
        std::lock_guard<std::mutex> lock(m_pendingKeysMutex);
        m_pendingKeys.clear(); // presses meant for a previous session
    }
    std::fill(std::begin(m_keyHeldByPress), std::end(m_keyHeldByPress), false);
    m_controllerDeviceDirty.store(false);
    applyControllerDevice();

    struct retro_game_info gameInfo = {};
    std::string targetPath = confPath;
    // Non-throwing: an unreadable path must fall back to the game folder, not abort the game process (SIGABRT)
    std::error_code pathError;
    if (targetPath.empty() || !std::filesystem::exists(targetPath, pathError)) {
        targetPath = gameDir;
    }

    gameInfo.path = targetPath.c_str();
    gameInfo.data = nullptr;
    gameInfo.size = 0;
    gameInfo.meta = nullptr;

    LOGI("Loading %s content from path: %s", m_coreLibrary.c_str(), gameInfo.path);
    screenText().clear();
    bool loaded = m_retro_load_game(&gameInfo);
    if (!loaded) {
        // Fallback to game directory directly
        LOGI("Fallback to game directory: %s", gameDir.c_str());
        gameInfo.path = gameDir.c_str();
        loaded = m_retro_load_game(&gameInfo);
    }

    m_gameLoaded = loaded;
    LOGI("DOSBox Pure retro_load_game result: %s", loaded ? "SUCCESS" : "FAILED");

    if (loaded && m_retro_get_system_av_info) {
        struct retro_system_av_info avInfo = {};
        m_retro_get_system_av_info(&avInfo);
        if (avInfo.timing.fps > 10.0) {
            m_targetFps.store(avInfo.timing.fps);
        }
        HwRenderContext::instance().setMaxSize((int)avInfo.geometry.max_width, (int)avInfo.geometry.max_height);
        m_coreSampleRate.store(avInfo.timing.sample_rate);
        LOGI("DOSBox Pure AV Info: base=%ux%u, max=%ux%u, aspect=%.2f, fps=%.2f, sample_rate=%.1f Hz",
             avInfo.geometry.base_width, avInfo.geometry.base_height,
             avInfo.geometry.max_width, avInfo.geometry.max_height,
             avInfo.geometry.aspect_ratio,
             avInfo.timing.fps, avInfo.timing.sample_rate);
    }

    return loaded;
}

void DosBoxPureCore::runFrame() {
    if (m_gameLoaded && m_retro_run) {
        drainPendingInput();
        for (int i = 0; i < 512; ++i) {
            uint8_t frames = m_keyReleaseFrames[i].load();
            if (frames > 0) {
                if (m_keyReleaseFrames[i].fetch_sub(1) == 1) {
                    m_keyHeldByPress[i] = false;
                    m_keys[i].store(false);
                    if (s_keyboardCallback) {
                        s_keyboardCallback(false, i, 0, 0);
                    }
                }
            }
        }

        // Apply Dual-Stick FPS Wrapper per emulation frame:
        // 1. Left Stick: Forward/Backward (Up=273 / Down=274) + Strafe Left/Right (','=44 / '.'=46)
        // 2. Right Stick: Proportional Mouse Yaw + Turn Keys (Left=276 / Right=275) + Pitch Look (PgUp=280 / PgDn=281)
        const float lx = m_leftStickX.load();
        const float ly = m_leftStickY.load();
        const float rx = m_rightStickX.load();
        const float ry = m_rightStickY.load();
        ++m_pwmCounter;

        auto applyPwmKey = [this](unsigned retroKey, uint32_t ch, float magnitude) {
            if (m_keyHeldByPress[retroKey]) return; // a D-pad / keyboard press owns this key until it is let go
            bool active = false;
            if (magnitude > 0.72f) {
                active = true;
            } else if (magnitude > 0.42f) {
                active = (m_pwmCounter % 2 == 0);
            } else if (magnitude > 0.18f) {
                active = (m_pwmCounter % 4 == 0);
            }
            bool prev = m_keys[retroKey].exchange(active);
            if (prev != active && s_keyboardCallback) {
                s_keyboardCallback(active, retroKey, ch, 0);
            }
        };

        // A stick as the DOS arrow keys: Y -> Up (RETROK_UP = 273) / Down (274), X -> Left (276) / Right (275),
        // with no strafe injection and no mouse yaw
        auto applyArrowStick = [&](float x, float y) {
            if (y < -0.18f || y > 0.18f || m_keyReleaseFrames[273].load() == 0) {
                applyPwmKey(273, 0, y < 0.0f ? -y : 0.0f);
            }
            if (y > 0.18f || y < -0.18f || m_keyReleaseFrames[274].load() == 0) {
                applyPwmKey(274, 0, y > 0.0f ? y : 0.0f);
            }
            if (x < -0.18f || x > 0.18f || m_keyReleaseFrames[276].load() == 0) {
                applyPwmKey(276, 0, x < 0.0f ? -x : 0.0f);
            }
            if (x > 0.18f || x < -0.18f || m_keyReleaseFrames[275].load() == 0) {
                applyPwmKey(275, 0, x > 0.0f ? x : 0.0f);
            }
        };

        // A stick as the mouse pointer: curved, accelerated relative motion (precise at a slight tilt, fast at full)
        auto applyPointerStick = [&](float x, float y) {
            const float deadzone = 0.12f;
            if (std::abs(x) > deadzone || std::abs(y) > deadzone) {
                float effectiveX = (std::abs(x) > deadzone) ? (x > 0.0f ? (x - deadzone) / (1.0f - deadzone) : (x + deadzone) / (1.0f - deadzone)) : 0.0f;
                float effectiveY = (std::abs(y) > deadzone) ? (y > 0.0f ? (y - deadzone) / (1.0f - deadzone) : (y + deadzone) / (1.0f - deadzone)) : 0.0f;
                float signX = effectiveX >= 0.0f ? 1.0f : -1.0f;
                float signY = effectiveY >= 0.0f ? 1.0f : -1.0f;
                m_mouseSubpixelX += signX * (effectiveX * effectiveX) * 16.0f;
                m_mouseSubpixelY += signY * (effectiveY * effectiveY) * 16.0f;
                int16_t intDx = static_cast<int16_t>(m_mouseSubpixelX);
                int16_t intDy = static_cast<int16_t>(m_mouseSubpixelY);
                if (intDx != 0) {
                    m_mouseSubpixelX -= intDx;
                    m_mouseDeltaX.fetch_add(intDx);
                }
                if (intDy != 0) {
                    m_mouseSubpixelY -= intDy;
                    m_mouseDeltaY.fetch_add(intDy);
                }
            } else {
                m_mouseSubpixelX = 0.0f;
                m_mouseSubpixelY = 0.0f;
            }
        };

        if (m_stickMode.load() == 1 /* CLASSIC_KEYBOARD */) {
            // Classic Keyboard Mode (Commander Keen, Rayman, Prince of Persia, RPGs): Left Stick -> arrow keys
            applyArrowStick(lx, ly);

            // Right Stick: Can also mirror arrow directions for flexible one-handed navigation
            if (std::abs(rx) > 0.25f) {
                applyPwmKey(rx < 0.0f ? 276 : 275, 0, std::abs(rx));
            }
            if (std::abs(ry) > 0.25f) {
                applyPwmKey(ry < 0.0f ? 273 : 274, 0, std::abs(ry));
            }
        } else if (m_stickMode.load() == 0 /* FPS_DUAL_STICK */) {
            // Modern FPS Dual-Stick Mode (Doom, Duke3D, Quake, Blood, Hexen, Heretic):
            // Left Stick Y -> Forward (RETROK_UP = 273) / Backward (RETROK_DOWN = 274)
            if (ly < -0.18f || ly > 0.18f || m_keyReleaseFrames[273].load() == 0) {
                applyPwmKey(273, 0, ly < 0.0f ? -ly : 0.0f);
            }
            if (ly > 0.18f || ly < -0.18f || m_keyReleaseFrames[274].load() == 0) {
                applyPwmKey(274, 0, ly > 0.0f ? ly : 0.0f);
            }

            // Left Stick X -> Strafe Left (',' = 44) / Strafe Right ('.' = 46)
            applyPwmKey(44, ',', lx < 0.0f ? -lx : 0.0f);
            applyPwmKey(46, '.', lx > 0.0f ? lx : 0.0f);

            // Right Stick X -> Smooth proportional mouse yaw + keyboard turn (RETROK_LEFT=276 / RETROK_RIGHT=275)
            if (std::abs(rx) > 0.12f) {
                float signedCurveX = (rx > 0.0f ? 1.0f : -1.0f) * (rx * rx);
                m_mouseSubpixelX += signedCurveX * 14.0f;
                int16_t intDx = static_cast<int16_t>(m_mouseSubpixelX);
                if (intDx != 0) {
                    m_mouseSubpixelX -= intDx;
                    m_mouseDeltaX.fetch_add(intDx);
                }
            } else {
                m_mouseSubpixelX = 0.0f;
            }
            applyPwmKey(276, 0, rx < 0.0f ? -rx : 0.0f);
            applyPwmKey(275, 0, rx > 0.0f ? rx : 0.0f);

            // Vertical look reads the stick through the vertical look deadzone (setLookDeadzoneY)
            const float lookRy = lookY(ry);
            if (m_mouseLook.load() && (std::abs(rx) > 0.5f || std::abs(ry) > 0.5f)) {
                m_verticalLookPaused.store(false); // a deliberate right-stick push means the player is back in play
            }
            if (m_mouseLook.load()) {
                // Mouse-aiming games: right stick Y -> proportional vertical mouse motion, the same curve as yaw.
                // Gyro pitch arrives as mouse Y via sendRelativeMouse, so the tilt-based motion pitch is ignored here.
                // DOS SW / Duke take aim from the forward axis (SW: aimvel = dz >> 10), so this is a pitch *rate*:
                // full stick sweeps the whole range in about a second, a light touch creeps. DOS Quake (held key)
                // pitches about 0.26 degrees a unit, so it gets a third of that: about 150 degrees a second at full.
                const int lookKey = m_mouseLookKey.load();
                float stickDy = 0.0f;
                if (std::abs(lookRy) > 0.12f) {
                    stickDy = (lookRy > 0.0f ? 1.0f : -1.0f) * (lookRy * lookRy) * (lookKey != 0 ? 10.0f : 30.0f) * m_stickLookSpeed.load();
                }
                if (lookKey != 0) {
                    heldKeyMouseLook(lookKey, stickDy);
                } else if (stickDy != 0.0f) {
                    m_mouseSubpixelY += stickDy;
                    int16_t intDy = static_cast<int16_t>(m_mouseSubpixelY);
                    if (intDy != 0) {
                        m_mouseSubpixelY -= intDy;
                        m_mouseDeltaY.fetch_add(intDy);
                    }
                } else {
                    m_mouseSubpixelY = 0.0f;
                }
                for (unsigned key : {280u, 281u}) {
                    if (!m_keyHeldByPress[key] && m_keys[key].exchange(false) && s_keyboardCallback) s_keyboardCallback(false, key, 0, 0);
                }
                // Mouse aim holds the pitch where it is put; the game's centre-view key levels it once the stick rests.
                // A deliberate tilt (more than hand tremor) since the stick last looked leaves the view where it aims.
                const bool stickLooking = std::abs(lookRy) > 0.12f && !m_verticalLookPaused.load();
                if (stickLooking) m_gyroPitchSinceRest.store(0.0f);
                lookSpring(stickLooking, m_gyroPitchSinceRest.load() >= 24.0f);
            } else {
            // Right Stick Y + Motion Pitch -> Continuous vertical look (PageUp=280 / PageDown=281 for Build/Heretic/Hexen/Strife)
            // Digital continuous hold without PWM chopping to prevent Build Engine / INT 9h auto-recenter stutter
            float effectiveRy = lookRy;
            float motionY = m_motionPitchY.load();
            if (std::abs(motionY) > std::abs(effectiveRy)) {
                effectiveRy = motionY;
            }
            bool lookUp = effectiveRy < -0.18f;
            bool lookDown = effectiveRy > 0.18f;

            if (!m_keyHeldByPress[280]) {
                bool prevUp = m_keys[280].exchange(lookUp);
                if (prevUp != lookUp && s_keyboardCallback) {
                    s_keyboardCallback(lookUp, 280, 0, 0);
                }
            }

            if (!m_keyHeldByPress[281]) {
                bool prevDown = m_keys[281].exchange(lookDown);
                if (prevDown != lookDown && s_keyboardCallback) {
                    s_keyboardCallback(lookDown, 281, 0, 0);
                }
            }

            lookSpring(std::abs(lookRy) > 0.18f, std::abs(motionY) > 0.18f);
            }
        } else if (m_stickMode.load() == 3 /* VIRTUAL_MOUSE */) {
            // Mode 3: Virtual Mouse (Point & Click Adventures - Beneath a Steel Sky, Flight of the Amazon Queen):
            // Left Stick: Curved accelerated mouse pointer movement
            const float deadzone = 0.12f;
            applyPointerStick(lx, ly);

            // Right Stick: Micro-precision fine pointer movement (1/4th speed)
            if (std::abs(rx) > deadzone || std::abs(ry) > deadzone) {
                float fineDx = rx * 3.5f;
                float fineDy = ry * 3.5f;
                int16_t intFineX = static_cast<int16_t>(fineDx);
                int16_t intFineY = static_cast<int16_t>(fineDy);
                if (intFineX != 0) m_mouseDeltaX.fetch_add(intFineX);
                if (intFineY != 0) m_mouseDeltaY.fetch_add(intFineY);
            }
        } else if (m_stickMode.load() == 4 /* KEYS_POINTER */) {
            // Mode 4: Keys + Pointer (System Shock, Ultima Underworld): walk and turn on the arrow keys, aim, use and
            // pick up with the mouse pointer
            applyArrowStick(lx, ly);
            applyPointerStick(rx, ry);
        } else {
            // Mode 2: NATIVE_GAMEPORT (I/O Port 0x201)
            // Left & Right sticks feed directly into Libretro RETRO_DEVICE_ANALOG in retro_input_state_callback.
            // No keyboard PWM or mouse yaw is injected so DOS gameport hardware emulation is 100% clean.
        }

        if (m_isDosBoxCore) stepMouseWarp();
        hwInitOnEmulationThread();
        m_retro_run();
    }
}

void DosBoxPureCore::handleCoreMessage(const char* msg) {
    // "Speed: %4.1f%%, DOS: %dx%d@%4.2fhz, Actual: %4.2ffps, Drawn: %dfps, Cycles: %u (%s)" (DOSBox Pure, detailed)
    const char* speed = strstr(msg, "Speed:");
    if (!speed) {
        LOGI("Core message: %s", msg);
        return;
    }
    float speedPct = 0, dosHz = 0, actualFps = 0;
    int dosW = 0, dosH = 0, drawnFps = 0;
    unsigned cycles = 0;
    int fields = sscanf(speed, "Speed: %f%%, DOS: %dx%d@%fhz, Actual: %ffps, Drawn: %dfps, Cycles: %u",
                        &speedPct, &dosW, &dosH, &dosHz, &actualFps, &drawnFps, &cycles);
    if (fields >= 1) m_coreSpeedPct.store(speedPct);
    if (fields >= 6) m_coreDrawnFps.store((float)drawnFps);
    if (fields >= 7) m_coreCycles.store(cycles);
}

void DosBoxPureCore::stopGame() {
    m_motionPitchY.store(0.0f);
    if (m_gameLoaded) {
        if (m_retro_unload_game) m_retro_unload_game();
        // Runs on the emulation thread at loop end, where the core's GL context is current
        hwShutdownOnEmulationThread();
        if (m_retro_deinit) m_retro_deinit();
        m_gameLoaded = false;
    }
    m_shutdownRequested.store(false);
    s_activeCore = nullptr;
    s_activeRenderer = nullptr;
    s_activeAudioSink = nullptr;
}

void DosBoxPureCore::sendKeyEvent(int dosScancode, bool isDown) {
    // Any thread: queue only, the emulation thread hands it to the core at the start of its next frame
    std::lock_guard<std::mutex> lock(m_pendingKeysMutex);
    // A full queue (game paused or stalled) drops new presses but never a release, so no key is left held down
    if (isDown && m_pendingKeys.size() >= kMaxPendingKeys) return;
    m_pendingKeys.push_back({dosScancode, isDown});
}

void DosBoxPureCore::drainPendingInput() {
    if (m_controllerDeviceDirty.exchange(false)) applyControllerDevice();
    {
        std::lock_guard<std::mutex> lock(m_pendingKeysMutex);
        m_drainedKeys.swap(m_pendingKeys);
    }
    for (const PendingKey& key : m_drainedKeys) applyKeyEvent(key.scancode, key.isDown);
    m_drainedKeys.clear();
}

void DosBoxPureCore::applyKeyEvent(int dosScancode, bool isDown) {
    unsigned retroKey = 0;
    uint32_t character = 0;

    if (isDown && !m_tappingRecenter && dosScancode != 0 && dosScancode == m_recenterKey.load() &&
        m_mouseLookKey.load() != 0 && m_mouseLook.load()) {
        // A centre-view button in a held-key mouse-look game: as the look-spring's tap, after the look key is up
        tapRecenter(dosScancode);
        return;
    }

    if (isDown && m_mouseLook.load()) {
        // Esc opens the game's menu, which DOS Build games navigate with vertical mouse too: hold vertical look
        // until a key that only means something in play (fire, jump, run, strafe, weapons). Not Z: GOA's B button
        // sends Esc + Z together, so crouch would undo the pause on the same press.
        if (dosScancode == 0x01) {
            m_verticalLookPaused.store(true);
        } else if (dosScancode == 0x1D || dosScancode == 0x1E || dosScancode == 0x2A ||
                   dosScancode == 0x36 || dosScancode == 0x38 || (dosScancode >= 0x02 && dosScancode <= 0x0B)) {
            m_verticalLookPaused.store(false);
        }
    }

    switch (dosScancode) {
        case 0x48: // Up arrow
            m_keyUp.store(isDown);
            retroKey = 273; // RETROK_UP
            break;
        case 0x50: // Down arrow
            m_keyDown.store(isDown);
            retroKey = 274; // RETROK_DOWN
            break;
        case 0x4B: // Left arrow
            m_keyLeft.store(isDown);
            retroKey = 276; // RETROK_LEFT
            break;
        case 0x4D: // Right arrow
            m_keyRight.store(isDown);
            retroKey = 275; // RETROK_RIGHT
            break;
        case 0x1D: // Ctrl / Button A
            m_btnA.store(isDown);
            retroKey = 306; // RETROK_LCTRL
            break;
        case 0x39: // Space / Button B
            m_btnB.store(isDown);
            retroKey = 32; // RETROK_SPACE
            character = ' ';
            break;
        case 0x2A: // Left Shift / Button X
            m_btnX.store(isDown);
            retroKey = 304; // RETROK_LSHIFT
            break;
        case 0x36: // Right Shift
            retroKey = 303; // RETROK_RSHIFT
            break;
        case 0x38: // Left Alt / Button Y
            m_btnY.store(isDown);
            retroKey = 308; // RETROK_LALT
            break;
        case 0x01: // Esc
            m_btnSelect.store(isDown);
            retroKey = 27; // RETROK_ESCAPE
            break;
        case 0x1C: // Enter
            m_btnStart.store(isDown);
            retroKey = 13; // RETROK_RETURN
            character = '\r';
            break;
        case 0x0E: // Backspace
            retroKey = 8; // RETROK_BACKSPACE
            character = '\b';
            break;
        case 0x0F: // Tab
            retroKey = 9; // RETROK_TAB
            character = '\t';
            break;
        case 0x3A: // CapsLock
            retroKey = 301; // RETROK_CAPSLOCK
            break;
        case 0x0C: retroKey = 45; character = '-'; break;
        case 0x0D: retroKey = 61; character = '='; break;
        case 0x1A: retroKey = 91; character = '['; break;
        case 0x1B: retroKey = 93; character = ']'; break;
        case 0x27: retroKey = 59; character = ';'; break;
        case 0x28: retroKey = 39; character = '\''; break;
        case 0x29: retroKey = 96; character = '`'; break;
        case 0x2B: retroKey = 92; character = '\\'; break;
        case 0x33: retroKey = 44; character = ','; break;
        case 0x34: retroKey = 46; character = '.'; break;
        case 0x35: retroKey = 47; character = '/'; break;
        // Function keys F1-F10 (0x3B - 0x44 -> RETROK_F1(282) - RETROK_F10(291))
        case 0x3B: retroKey = 282; break; // F1
        case 0x3C: retroKey = 283; break; // F2
        case 0x3D: retroKey = 284; break; // F3
        case 0x3E: retroKey = 285; break; // F4
        case 0x3F: retroKey = 286; break; // F5
        case 0x40: retroKey = 287; break; // F6
        case 0x41: retroKey = 288; break; // F7
        case 0x42: retroKey = 289; break; // F8
        case 0x43: retroKey = 290; break; // F9
        case 0x44: retroKey = 291; break; // F10
        case 0x57: retroKey = 292; break; // F11
        case 0x58: retroKey = 293; break; // F12
        // Navigation keys
        case 0x47: retroKey = 278; break; // Home
        case 0x4F: retroKey = 279; break; // End
        case 0x49: retroKey = 280; break; // PageUp
        case 0x51: retroKey = 281; break; // PageDown
        case 0x52: retroKey = 277; break; // Insert
        case 0x53: retroKey = 127; break; // Delete
        case 0x02: retroKey = 49; character = '1'; break;
        case 0x03: retroKey = 50; character = '2'; break;
        case 0x04: retroKey = 51; character = '3'; break;
        case 0x05: retroKey = 52; character = '4'; break;
        case 0x06: retroKey = 53; character = '5'; break;
        case 0x07: retroKey = 54; character = '6'; break;
        case 0x08: retroKey = 55; character = '7'; break;
        case 0x09: retroKey = 56; character = '8'; break;
        case 0x0A: retroKey = 57; character = '9'; break;
        case 0x0B: retroKey = 48; character = '0'; break;
        case 0x10: retroKey = 113; character = 'q'; break;
        case 0x11: retroKey = 119; character = 'w'; break;
        case 0x12: retroKey = 101; character = 'e'; break;
        case 0x13: retroKey = 114; character = 'r'; break;
        case 0x14: retroKey = 116; character = 't'; break;
        case 0x15: m_btnY.store(isDown); retroKey = 121; character = 'y'; break;
        case 0x16: retroKey = 117; character = 'u'; break;
        case 0x17: retroKey = 105; character = 'i'; break;
        case 0x18: retroKey = 111; character = 'o'; break;
        case 0x19: retroKey = 112; character = 'p'; break;
        case 0x1E: retroKey = 97;  character = 'a'; break;
        case 0x1F: retroKey = 115; character = 's'; break;
        case 0x20: retroKey = 100; character = 'd'; break;
        case 0x21: retroKey = 102; character = 'f'; break;
        case 0x22: retroKey = 103; character = 'g'; break;
        case 0x23: retroKey = 104; character = 'h'; break;
        case 0x24: retroKey = 106; character = 'j'; break;
        case 0x25: retroKey = 107; character = 'k'; break;
        case 0x26: retroKey = 108; character = 'l'; break;
        case 0x2C: retroKey = 122; character = 'z'; break;
        case 0x2D: retroKey = 120; character = 'x'; break;
        case 0x2E: retroKey = 99;  character = 'c'; break;
        case 0x2F: retroKey = 118; character = 'v'; break;
        case 0x30: retroKey = 98;  character = 'b'; break;
        case 0x31: retroKey = 110; character = 'n'; break;
        case 0x32: retroKey = 109; character = 'm'; break;
        default:
            if (dosScancode > 0 && dosScancode < 512) {
                retroKey = dosScancode;
                character = (dosScancode >= 32 && dosScancode < 127) ? (uint32_t)dosScancode : 0;
            }
            break;
    }

    if (retroKey > 0 && retroKey < 512) {
        if (isDown) {
            m_keyHeldByPress[retroKey] = true;
            m_keys[retroKey].store(true);
            m_keyReleaseFrames[retroKey].store(0);
            if (s_keyboardCallback) {
                s_keyboardCallback(true, retroKey, character, 0);
            }
        } else {
            // Keep pressed for at least 6 emulation frames (~85ms) so DOS BIOS INT 9h registers
            m_keyReleaseFrames[retroKey].store(6);
        }
    }
}

void DosBoxPureCore::sendAnalogSticks(float leftX, float leftY, float rightX, float rightY) {
    m_leftStickX.store(std::clamp(leftX, -1.0f, 1.0f));
    m_leftStickY.store(std::clamp(leftY, -1.0f, 1.0f));
    m_rightStickX.store(std::clamp(rightX, -1.0f, 1.0f));
    m_rightStickY.store(std::clamp(rightY, -1.0f, 1.0f));
}

void DosBoxPureCore::setStickMode(int mode) {
    // TyrQuake reads the sticks itself (left moves, right looks): never turn them into arrow keys or mouse motion
    m_stickMode.store(m_isNativePort ? 2 /* pad + analog pass-through */ : mode);
    // The core switches port device on the emulation thread (see drainPendingInput); startGame applies the first one
    m_controllerDeviceDirty.store(true);
}

void DosBoxPureCore::applyControllerDevice() {
    if (!m_retro_set_controller_port_device) return;
    // TyrQuake keeps the pad it starts with: choosing a device makes it rewrite Quake's pad binds with its own layouts,
    // and GOA's layout is in Quake's config.cfg (QuakeSupport)
    if (m_isNativePort) return;
    const int mode = m_stickMode.load();
    unsigned device;
    if (m_isDosBoxCore) {
        // Mode 2: RETRO_DEVICE_ANALOG (Native Gameport), Mode 3: RETRO_DEVICE_MOUSE (Virtual Mouse), Others: RETRO_DEVICE_KEYBOARD
        device = (mode == 2) ? 5 /* RETRO_DEVICE_ANALOG */ : (mode == 3 ? 2 /* RETRO_DEVICE_MOUSE */ : 3 /* RETRO_DEVICE_KEYBOARD */);
    } else {
        // ScummVM and other point-and-click cores only read the mouse once told it is the active device
        device = (mode == 3) ? 2 /* RETRO_DEVICE_MOUSE */ : 1 /* RETRO_DEVICE_JOYPAD */;
    }
    m_retro_set_controller_port_device(0, device);
}

void DosBoxPureCore::sendJoypadButton(int buttonId, bool isDown) {
    if (buttonId >= 0 && buttonId < 16) {
        m_joypadButtons[buttonId].store(isDown);
    }
}

void DosBoxPureCore::sendRelativeMouse(float deltaX, float deltaY) {
    if (m_isDosBoxCore) {
        // Carry the fraction: gyro deltas are often below a pixel, and dropping them made slow tilts do nothing
        m_relSubpixelX += deltaX;
        const int ix = (int)m_relSubpixelX;
        m_relSubpixelX -= (float)ix;
        m_mouseDeltaX.fetch_add((int16_t)ix);
        // FPS mode drops vertical mouse motion (most DOS shooters walk with it), unless the game aims with it
        const bool aimY = m_mouseLook.load() && !m_verticalLookPaused.load();
        if (m_stickMode.load() != 0 /* FPS_DUAL_STICK */ || aimY) {
            if (aimY) {
                float seen = m_gyroPitchSinceRest.load();
                while (!m_gyroPitchSinceRest.compare_exchange_weak(seen, seen + std::abs(deltaY))) {}
                if (m_mouseLookKey.load() != 0) {
                    // Held-key mouse look: the emulation thread sends it once the key is down (heldKeyMouseLook)
                    float pending = m_pendingGyroY.load();
                    while (!m_pendingGyroY.compare_exchange_weak(pending, pending + deltaY)) {}
                    return;
                }
            }
            m_relSubpixelY += deltaY;
            const int iy = (int)m_relSubpixelY;
            m_relSubpixelY -= (float)iy;
            m_mouseDeltaY.fetch_add((int16_t)iy);
        } else {
            m_relSubpixelY = 0.0f;
        }
        return;
    }
    if (m_isTyrQuake) {
        // The gyro as Quake's mouse (GOA's TyrQuake build reads it beside the pad). Doubled across and quadrupled
        // down, so it turns and pitches as in DOS Quake: DOSBox makes 1 mickey per unit across and 2 down, and GOA's
        // DOS Quake config has sensitivity 6 where TyrQuake's default is 3.
        m_relSubpixelX += deltaX * 2.0f;
        m_relSubpixelY += deltaY * 4.0f;
        const int ix = (int)m_relSubpixelX;
        const int iy = (int)m_relSubpixelY;
        m_relSubpixelX -= (float)ix;
        m_relSubpixelY -= (float)iy;
        m_mouseDeltaX.fetch_add((int16_t)ix);
        m_mouseDeltaY.fetch_add((int16_t)iy);
        return;
    }
    // Deltas arrive in screen pixels but point-and-click cores run in a small frame (320x200 for most adventures),
    // so scale to frame units and carry the fractional part so slow drags still move the cursor
    const int width = m_frameWidth.load();
    const float scale = width > 0 ? (float)width / 1000.0f : 0.32f;
    m_mouseSubpixelX += deltaX * scale;
    m_mouseSubpixelY += deltaY * scale;
    const int ix = (int)m_mouseSubpixelX;
    const int iy = (int)m_mouseSubpixelY;
    m_mouseSubpixelX -= (float)ix;
    m_mouseSubpixelY -= (float)iy;
    m_mouseDeltaX.fetch_add((int16_t)ix);
    m_mouseDeltaY.fetch_add((int16_t)iy);
}

void DosBoxPureCore::lookSpring(bool stickLooking, bool otherAiming) {
    // Look-spring (setLookRecenter): the stick looked up or down, came back to rest and stayed there -> tap the game's
    // centre-view key. Aiming by tilt (gyro) disarms it, so the view stays where the tilt put it.
    const int recenterKey = m_recenterKey.load();
    if (recenterKey == 0) return;
    const auto now = std::chrono::steady_clock::now();
    if (stickLooking) {
        m_recenterArmed = true;
        m_recenterRestSince = now;
    } else if (otherAiming) {
        m_recenterArmed = false;
    } else if (m_recenterArmed && now - m_recenterRestSince >= std::chrono::milliseconds(m_recenterDelayMs.load())) {
        m_recenterArmed = false;
        tapRecenter(recenterKey);
    }
}

void DosBoxPureCore::tapRecenter(int recenterKey) {
    if (m_mouseLookKey.load() != 0 && (m_lookKeyHeld || m_framesSinceLookKeyUp < 10)) {
        // DOS Quake cancels centring every frame while mlook is held: let the key go and tap once it is up
        if (m_lookKeyHeld) releaseLookKey();
        m_deferredRecenterFrames = 10;
        return;
    }
    LOGI("Look recentre: tap key %d", recenterKey);
    m_tappingRecenter = true;
    applyKeyEvent(recenterKey, true);
    applyKeyEvent(recenterKey, false); // held for the minimum frames, then let go
    m_tappingRecenter = false;
    m_lookKeyBlockedFrames = 30;        // the gyro's tremor must not press the key again while the view levels
}

void DosBoxPureCore::heldKeyMouseLook(int lookKey, float stickDy) {
    float gyroDy = m_pendingGyroY.exchange(0.0f);
    if (m_lookKeyBlockedFrames > 0) {
        --m_lookKeyBlockedFrames;
        if (stickDy == 0.0f) gyroDy = 0.0f;
    }
    if (m_deferredRecenterFrames > 0) {
        if (!m_lookKeyHeld && --m_deferredRecenterFrames == 0) {
            const int recenterKey = m_recenterKey.load();
            if (recenterKey != 0) tapRecenter(recenterKey);
        }
        stickDy = gyroDy = 0.0f; // centring: the key stays up until the tap
    }
    const bool paused = m_verticalLookPaused.load();
    const float dy = paused ? 0.0f : stickDy + gyroDy;
    const auto now = std::chrono::steady_clock::now();
    if (dy != 0.0f) {
        if (!m_lookKeyHeld) {
            applyKeyEvent(lookKey, true);
            m_lookKeyHeld = true;
            m_lookKeyFrames = 0;
            m_lookYCarry = 0.0f;
        }
        m_lookYCarry += dy;
        m_lastLookYAt = now;
    }
    if (m_lookKeyHeld) {
        // The motion waits a few frames for the key: the game walks with vertical mouse while it is up
        if (++m_lookKeyFrames >= 3) {
            const int iy = (int)m_lookYCarry;
            if (iy != 0) {
                m_lookYCarry -= (float)iy;
                m_mouseDeltaY.fetch_add((int16_t)iy);
            }
        }
        if (paused || now - m_lastLookYAt >= std::chrono::milliseconds(250)) releaseLookKey();
    } else if (m_framesSinceLookKeyUp < 1000) {
        ++m_framesSinceLookKeyUp;
    }
}

void DosBoxPureCore::releaseLookKey() {
    const int lookKey = m_mouseLookKey.load();
    if (lookKey != 0) applyKeyEvent(lookKey, false);
    m_lookKeyHeld = false;
    m_lookYCarry = 0.0f;
    m_framesSinceLookKeyUp = 0;
}

void DosBoxPureCore::setLookDeadzoneY(float deadzone) {
    m_lookDeadzoneY.store(std::isnan(deadzone) ? 0.15f : std::clamp(deadzone, 0.0f, 0.6f));
    LOGI("Vertical look deadzone %.2f", m_lookDeadzoneY.load());
}

float DosBoxPureCore::lookY(float y) const {
    const float d = m_lookDeadzoneY.load();
    const float a = std::abs(y);
    if (a <= d) return 0.0f;
    return (y > 0.0f ? 1.0f : -1.0f) * std::min(1.0f, (a - d) / (1.0f - d));
}

void DosBoxPureCore::setMouseLookKey(int dosScancode) {
    m_mouseLookKey.store(dosScancode > 0 && dosScancode < 512 ? dosScancode : 0);
    LOGI("Mouse-look key %d", m_mouseLookKey.load());
}

void DosBoxPureCore::setStickLookSpeed(float speed) {
    m_stickLookSpeed.store(std::clamp(speed, 0.1f, 10.0f));
}

void DosBoxPureCore::setLookRecenter(int dosScancode, int delayMs) {
    m_recenterDelayMs.store(std::clamp(delayMs, 100, 5000));
    m_recenterKey.store(dosScancode > 0 && dosScancode < 512 ? dosScancode : 0);
    LOGI("Look recentre: key %d after %d ms", m_recenterKey.load(), m_recenterDelayMs.load());
}

void DosBoxPureCore::setMouseLook(bool enabled) {
    // The game's config starts it with mouse aiming switched on, so this only routes vertical input to mouse Y
    m_mouseLook.store(enabled);
    LOGI("Mouse-look %s", enabled ? "enabled" : "disabled");
}

void DosBoxPureCore::sendMotionPitch(float pitchY) {
    m_motionPitchY.store(std::clamp(pitchY, -1.0f, 1.0f));
}

void DosBoxPureCore::sendMouseButton(int buttonMask, bool isDown) {
    if (isDown && (buttonMask & 1)) m_verticalLookPaused.store(false); // firing means the player is in play
    if ((buttonMask & 1) && m_warpFrames.load() > 0) {
        // The cursor is still on its way to where the finger touched (pointMouseAt): click once it is there
        if (isDown) m_clickAfterWarp.store(true);
        buttonMask &= ~1;
    }
    if (buttonMask & 1) m_mouseLeft.store(isDown);
    if (buttonMask & 2) m_mouseRight.store(isDown);
    if (buttonMask & 4) m_mouseMiddle.store(isDown);
}

void DosBoxPureCore::sendMouseWheel(int lines) {
    m_mouseWheel.fetch_add((int16_t)lines);
}

void DosBoxPureCore::sendPointerAbsolute(float normalizedX, float normalizedY, bool isPressed) {
    // Libretro pointer coordinate range is -32767 to 32767
    int16_t px = (int16_t)(normalizedX * 32767.0f);
    int16_t py = (int16_t)(normalizedY * 32767.0f);
    m_pointerX.store(px);
    m_pointerY.store(py);
    m_pointerPressed.store(isPressed);
}

void DosBoxPureCore::setCoreConfig(const std::string& libraryName, const std::string& systemDir, const std::string& saveDir) {
    if (!libraryName.empty() && libraryName != m_coreLibrary && m_libHandle) {
        // A different core is already mapped; the emulator process is restarted per launch, so just drop it
        dlclose(m_libHandle);
        m_libHandle = nullptr;
    }
    if (!libraryName.empty()) m_coreLibrary = libraryName;
    m_isDosBoxCore = m_coreLibrary.find("dosbox") != std::string::npos;
    m_isTyrQuake = m_coreLibrary.find("tyrquake") != std::string::npos;
    m_isOpenLara = m_coreLibrary.find("openlara") != std::string::npos;
    m_isNativePort = m_isTyrQuake || m_isOpenLara;
    if (m_isNativePort) m_stickMode.store(2);
    // The presenter joins the core's GL share group before the window's context is made
    HwRenderContext::setWanted(m_isOpenLara);
    m_systemDir = systemDir;
    m_saveDir = saveDir;
}

// Frames of pointMouseAt: the push into the corner, frames for the game to read it, the move to the target, and
// frames for the game to read that before a held click goes through
static constexpr int WARP_CORNER_AT = 8, WARP_TARGET_AT = 4, WARP_CLICK_FRAMES = 6;

void DosBoxPureCore::pointMouseAt(float viewU, float viewV) {
    // Only DOSBox Pure needs it (and only its frames step it: elsewhere a pending move would swallow every click)
    if (!m_isDosBoxCore) return;
    // Pointer games only (Virtual Mouse, Keys + Pointer): in a shooter the push into the corner would spin the view
    const int mode = m_stickMode.load();
    if (mode != 3 && mode != 4) return;
    // The touch is on the surface view; the picture may sit between black bars
    float u = viewU, v = viewV;
    if (s_activeRenderer && !s_activeRenderer->pictureFraction(viewU, viewV, u, v)) return;
    m_warpU.store(std::clamp(u, 0.0f, 1.0f));
    m_warpV.store(std::clamp(v, 0.0f, 1.0f));
    m_warpFrames.store(WARP_CORNER_AT);
}

void DosBoxPureCore::stepMouseWarp() {
    // A click held for a few frames after the move, so games that poll the button state see it
    if (m_heldClickFrames > 0 && --m_heldClickFrames == 0) m_mouseLeft.store(false);
    const int frames = m_warpFrames.load();
    if (frames <= 0) return;
    const int width = std::max(m_frameWidth.load(), 320);
    const int height = std::max(m_frameHeight.load(), 200);
    if (frames == WARP_CORNER_AT) {
        // Far enough past the edge for any mouse ratio, small enough that DOS's 16-bit motion counters don't wrap
        m_mouseDeltaX.store((int16_t)-std::min(width * 3, 12000));
        m_mouseDeltaY.store((int16_t)-std::min(height * 3, 12000));
    } else if (frames == WARP_TARGET_AT) {
        // DOSBox Pure moves one pixel per unit at its default mouse speed, which GOA keeps
        m_mouseDeltaX.store((int16_t)std::lround(m_warpU.load() * (float)(width - 1)));
        m_mouseDeltaY.store((int16_t)std::lround(m_warpV.load() * (float)(height - 1)));
    }
    if (m_warpFrames.fetch_sub(1) == 1 && m_clickAfterWarp.exchange(false)) {
        m_mouseLeft.store(true);
        m_heldClickFrames = WARP_CLICK_FRAMES;
    }
}

void DosBoxPureCore::setDirectMouse(bool enabled) {
    // Only flag a variable update on an actual change so the core doesn't re-read options every frame
    if (m_directMouse.exchange(enabled) != enabled) {
        m_variablesDirty.store(true);
    }
}

bool DosBoxPureCore::saveState(int slotIndex, const std::string& savePath) {
    if (!m_gameLoaded) {
        LOGE("saveState failed: m_gameLoaded is false");
        return false;
    }
    if (!m_retro_serialize || !m_retro_serialize_size) {
        LOGE("saveState failed: serialize function pointers null");
        return false;
    }
    size_t size = m_retro_serialize_size();
    if (size == 0) {
        LOGE("saveState failed: retro_serialize_size is 0");
        return false;
    }

    std::vector<uint8_t> buffer(size);
    if (!m_retro_serialize(buffer.data(), size)) {
        LOGE("saveState failed: retro_serialize returned false");
        return false;
    }

    // Written next to the save state and renamed over it only once it is complete and on disk: a crash, a full disk
    // or GOA being killed mid-write leaves the previous save state intact (SaveStateStore.recover() deletes the part)
    const std::string partPath = savePath + ".part";
    FILE* out = fopen(partPath.c_str(), "wb");
    if (!out) {
        LOGE("saveState failed: cannot open file %s for writing", partPath.c_str());
        return false;
    }
    bool written = fwrite(buffer.data(), 1, size, out) == size;
    written = (fflush(out) == 0) && written;
    written = (fsync(fileno(out)) == 0) && written;
    written = (fclose(out) == 0) && written;
    if (!written || rename(partPath.c_str(), savePath.c_str()) != 0) {
        LOGE("saveState failed: writing %s (errno %d); the previous save state is kept", savePath.c_str(), errno);
        remove(partPath.c_str());
        return false;
    }
    LOGI("Saved state slot %d to %s (%zu bytes)", slotIndex, savePath.c_str(), size);
    return true;
}

// RetroArch saves DOSBox Pure's state wrapped: RZIP-compressed ("#RZIPv" ver "#", u32 chunk size, u64 total, then
// (u32 length, zlib data) chunks) around a RASTATE container ("RASTATE" ver, 8-byte aligned (tag, u32 size, data) chunks,
// the state in "MEM "). Leaves [data] as DOSBox Pure's own state; false when a wrapper is damaged.
static bool unwrapRetroArchState(std::vector<uint8_t>& data) {
    auto le32 = [](const uint8_t* p) { uint32_t v; memcpy(&v, p, 4); return v; };
    if (data.size() >= 20 && memcmp(data.data(), "#RZIPv", 6) == 0 && data[7] == '#') {
        uint64_t total; memcpy(&total, data.data() + 12, 8);
        if (total == 0 || total > 256ull * 1024 * 1024) return false;
        std::vector<uint8_t> out(total);
        size_t p = 20, written = 0;
        while (p + 4 <= data.size() && written < total) {
            uint32_t n = le32(data.data() + p); p += 4;
            if (n == 0 || p + n > data.size()) return false;
            uLongf room = (uLongf)(total - written);
            if (uncompress(out.data() + written, &room, data.data() + p, n) != Z_OK) return false;
            written += room; p += n;
        }
        if (written != total) return false;
        data.swap(out);
    }
    if (data.size() >= 8 && memcmp(data.data(), "RASTATE", 7) == 0) {
        size_t p = 8;
        while (p + 8 <= data.size()) {
            uint32_t size = le32(data.data() + p + 4);
            if (p + 8 + size > data.size()) return false;
            if (memcmp(data.data() + p, "MEM ", 4) == 0) {
                data = std::vector<uint8_t>(data.begin() + p + 8, data.begin() + p + 8 + size);
                return true;
            }
            if (memcmp(data.data() + p, "END ", 4) == 0) return false;
            p = (p + 8 + size + 7) & ~(size_t)7;
        }
        return false;
    }
    return true;
}

// Whether [state] is a complete DOSBox Pure state (dbp_serialize.cpp): its magic number, and as its last 8 bytes before
// any zero padding, the offset at which they start (each part of the machine is followed by its end offset). The core
// applies a state as it reads it, so one cut short crashes the game a few seconds after "Invalid file format".
static bool isCompleteDosBoxState(const std::vector<uint8_t>& state) {
    static const uint8_t magic[4] = {0x47, 0x57, 0x5B, 0xD0};
    if (state.size() < 16 || memcmp(state.data(), magic, 4) != 0) return false;
    size_t end = state.size();
    while (end >= 8) {
        uint64_t offset; memcpy(&offset, state.data() + end - 8, 8);
        if (offset == end - 8) return true;
        if (state[end - 1] != 0) return false;
        end--;
    }
    return false;
}

bool DosBoxPureCore::loadState(int slotIndex, const std::string& savePath) {
    if (!m_gameLoaded || !m_retro_unserialize) return false;
    std::ifstream in(savePath, std::ios::binary | std::ios::ate);
    if (!in.is_open()) return false;

    std::streamoff end = in.tellg();
    if (end < 16 || end > 512ll * 1024 * 1024) {
        LOGE("loadState: %s isn't a save state (%lld bytes)", savePath.c_str(), (long long)end);
        return false;
    }
    std::vector<uint8_t> buffer((size_t)end);
    in.seekg(0, std::ios::beg);
    if (!in.read((char*)buffer.data(), end)) {
        LOGE("loadState: could not read %s", savePath.c_str());
        return false;
    }
    // Only DOSBox Pure reads these (TyrQuake, OpenLara and ScummVM have their own saves)
    if (m_coreLibrary.find("dosbox") != std::string::npos) {
        if (!unwrapRetroArchState(buffer)) {
            LOGE("loadState: %s: damaged RetroArch save state", savePath.c_str());
            return false;
        }
        if (!isCompleteDosBoxState(buffer)) {
            LOGE("loadState: %s isn't a complete DOSBox Pure save state; not loaded", savePath.c_str());
            return false;
        }
    }

    // A state the core turns away before changing anything (another machine type or memory size) needs nothing more.
    // One that fails part way leaves the machine half loaded: put the game back as it was before.
    std::vector<uint8_t> before;
    if (m_retro_serialize_size && m_retro_serialize) {
        size_t now = m_retro_serialize_size();
        if (now > 0) {
            before.resize(now);
            if (!m_retro_serialize(before.data(), now)) before.clear();
        }
    }

    bool ok = m_retro_unserialize(buffer.data(), buffer.size());
    LOGI("Loaded state slot %d from %s (%zu bytes): %s", slotIndex, savePath.c_str(), buffer.size(), ok ? "SUCCESS" : "FAILED");
    if (!ok && !before.empty()) {
        bool restored = m_retro_unserialize(before.data(), before.size());
        LOGI("loadState: put the game back as it was: %s", restored ? "yes" : "no");
    }
    return ok;
}
