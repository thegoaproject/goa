#pragma once

#include <string>
#include <cstdint>
#include <vector>
#include <atomic>
#include <chrono>
#include <mutex>
#include <functional>
#include "render/GpuRenderer.h"
#include "audio/OboeAudioSink.h"

class DosBoxPureCore {
public:
    DosBoxPureCore();
    ~DosBoxPureCore();

    bool loadLibrary();
    bool startGame(
        const std::string& gameDir,
        const std::string& confPath,
        int soundHardwareId,
        const std::string& cpuCycles,
        int memoryMb,
        GpuRenderer* renderer,
        OboeAudioSink* audioSink
    );
    void runFrame();
    void stopGame();

    void sendKeyEvent(int dosScancode, bool isDown);
    void sendAnalogSticks(float leftX, float leftY, float rightX, float rightY);
    void setStickMode(int mode);
    void applyControllerDevice();
    void sendJoypadButton(int buttonId, bool isDown);
    void sendRelativeMouse(float deltaX, float deltaY);
    void sendMotionPitch(float pitchY);
    // Build-engine games (Duke3D, Shadow Warrior, ...) patched to start with mouse aiming on: feed right-stick Y / gyro
    // pitch as proportional vertical mouse motion instead of PageUp/PageDown (which is the snap-back Look_Up/Down)
    void setMouseLook(bool enabled);
    // Right-stick vertical look rate for mouse-aim games (1.0 = default)
    void setStickLookSpeed(float speed);
    // Look-spring for key-look games (Heretic, Hexen: PageUp/PageDown) and mouse-aim Build games (Duke, Shadow Warrior):
    // once the right stick has been back at rest for delayMs after looking up or down, tap the game's centre-view key (a
    // DOS scancode, as sendKeyEvent takes). "At rest" is the same zone that decides the stick is looking (0.18 for keys,
    // 0.12 for mouse aim), so a drifting stick never holds it off. Aiming by tilt cancels it. 0 = off.
    void setLookRecenter(int dosScancode, int delayMs);
    // Mouse look through a held key (DOS Quake's +mlook): with setMouseLook on, the key goes down while the right stick
    // or gyro moves the view up or down and up again 250 ms after it stops, so vertical mouse never walks the player
    // and the game's view centring (cancelled every frame while mlook is held) works. 0 = aiming without a key.
    void setMouseLookKey(int dosScancode);
    // The vertical look deadzone (0-0.6 of the stick's travel): right-stick Y inside it does not look up or down in
    // FPS mode or native Quake, and the rest of the travel is stretched back to the full range. Turning is unaffected.
    void setLookDeadzoneY(float deadzone);
    float lookY(float y) const;
    void sendMouseButton(int buttonMask, bool isDown);
    void sendMouseWheel(int lines);
    void sendPointerAbsolute(float normalizedX, float normalizedY, bool isPressed);
    void setDirectMouse(bool enabled);
    // Tap-to-point that also works in games which read mouse motion (mickeys) and draw their own cursor, where DOSBox
    // Pure's direct mode only moves the driver's position and the game's cursor stays put (The Oregon Trail Deluxe).
    // [u], [v] (0-1) is where on the surface view the finger touched (mapped onto the letterboxed picture): the cursor is pushed into the top-left corner,
    // which games clamp to, then moved by that many frame pixels, a few frames apart so the game reads each step. A
    // left click that arrives meanwhile is held until the cursor is there. Direct mode's absolute position is still
    // sent too, for the games that read the driver's position.
    void pointMouseAt(float u, float v);

    // Selects which libretro core to dlopen (default libdosbox_pure.so) and where it finds support data / writes saves.
    // Must be called before startGame().
    void setCoreConfig(const std::string& libraryName, const std::string& systemDir, const std::string& saveDir);

    bool saveState(int slotIndex, const std::string& savePath);
    bool loadState(int slotIndex, const std::string& savePath);
    // The loaded core's retro_get_system_info().library_version (e.g. "1.0-preview7"), recorded with each save state
    // so a save state's engine version is known on another phone. Empty before the core library loads.
    const std::string& coreVersion() const { return m_coreVersion; }

    double getTargetFps() const { return m_targetFps.load(); }
    // The core's own audio rate from its AV info (0 before a game loads)
    double getCoreSampleRate() const { return m_coreSampleRate.load(); }
    bool isNativePort() const { return m_isNativePort; }

    // DOSBox Pure runs the emulated CPU on its own thread, so retro_run timing says nothing about CPU load. Its
    // perfstats message (~1 Hz) does: speed % of real time, frames the game actually drew, and current cycles.
    float getCoreSpeedPercent() const { return m_coreSpeedPct.load(); }
    float getCoreDrawnFps() const { return m_coreDrawnFps.load(); }
    unsigned getCoreCycles() const { return m_coreCycles.load(); }
    void handleCoreMessage(const char* msg);

    void setOnProgramChangeCallback(std::function<void(const std::string&)> cb) {
        m_onProgramChange = std::move(cb);
    }

    std::atomic<bool> m_shutdownRequested{false};
    bool isShutdownRequested() const { return m_shutdownRequested.load(); }
    void requestShutdown() { m_shutdownRequested.store(true); }

private:
    // Key presses and controller-device changes arrive on Android threads (UI, typer, debug receiver) but must reach
    // the core on the emulation thread: DOSBox Pure's keyboard callback writes the same unlocked event queue and key
    // counters that retro_run writes, and retro_set_controller_port_device rebuilds the bindings retro_run reads.
    // Calling them from another thread could drop key presses or leave a key ignored for the rest of the session.
    struct PendingKey { int scancode; bool isDown; };
    static constexpr size_t kMaxPendingKeys = 256;
    std::mutex m_pendingKeysMutex;
    std::vector<PendingKey> m_pendingKeys;
    std::vector<PendingKey> m_drainedKeys; // emulation thread only
    std::atomic<bool> m_controllerDeviceDirty{false};
    void drainPendingInput();
    void applyKeyEvent(int dosScancode, bool isDown);

    void* m_libHandle{nullptr};
    bool m_gameLoaded{false};
    std::atomic<double> m_targetFps{60.0};
    std::atomic<double> m_coreSampleRate{0.0};
    std::atomic<float> m_coreSpeedPct{0.0f};
    std::atomic<float> m_coreDrawnFps{0.0f};
    std::atomic<unsigned> m_coreCycles{0};
    std::function<void(const std::string&)> m_onProgramChange;

    std::string m_gameDir;
    std::string m_coreLibrary{"libdosbox_pure.so"};
    std::string m_systemDir;   // empty = use the game directory (DOSBox Pure)
    std::string m_saveDir;
    bool m_isDosBoxCore{true};
    // TyrQuake (native Quake): the pad and both sticks go to the core as a pad; it binds buttons to Quake commands itself
    bool m_isTyrQuake{false};
    // OpenLara (Tomb Raider's engine rebuilt): draws with the GPU and reads the pad as a pad
    bool m_isOpenLara{false};
    // A native port (TyrQuake, OpenLara) rather than an emulator: pad and sticks pass straight through
    bool m_isNativePort{false};
    std::string m_confPath;
    std::string m_cpuCycles;
    std::string m_memoryMbStr;
    int m_soundHardwareId{1};

    // Input states
    std::atomic<bool> m_keyUp{false};
    std::atomic<bool> m_keyDown{false};
    std::atomic<bool> m_keyLeft{false};
    std::atomic<bool> m_keyRight{false};
    std::atomic<bool> m_btnA{false};
    std::atomic<bool> m_btnB{false};
    std::atomic<bool> m_btnX{false};
    std::atomic<bool> m_btnY{false};
    std::atomic<bool> m_btnStart{false};
    std::atomic<bool> m_btnSelect{false};

    std::atomic<int16_t> m_mouseDeltaX{0};
    std::atomic<int16_t> m_mouseDeltaY{0};
    std::atomic<float> m_motionPitchY{0.0f};
    std::atomic<float> m_leftStickX{0.0f};
    std::atomic<float> m_leftStickY{0.0f};
    std::atomic<float> m_rightStickX{0.0f};
    std::atomic<float> m_rightStickY{0.0f};
    std::atomic<int> m_stickMode{0}; // 0 = FPS Dual-Stick, 1 = Classic Keyboard Arrow Keys, 2 = Native Gameport Joystick (Port 201h), 3 = Virtual Mouse Pointer
    std::atomic<bool> m_joypadButtons[16]{};
    float m_mouseSubpixelX{0.0f};
    float m_mouseSubpixelY{0.0f};
    uint32_t m_pwmCounter{0};
    std::atomic<bool> m_mouseLeft{false};
    std::atomic<bool> m_mouseRight{false};
    std::atomic<bool> m_mouseMiddle{false};
    std::atomic<int16_t> m_mouseWheel{0};
    std::atomic<int16_t> m_pointerX{0};
    std::atomic<int16_t> m_pointerY{0};
    std::atomic<bool> m_pointerPressed{false};
    std::atomic<int> m_frameWidth{0};
    std::atomic<int> m_frameHeight{0};
    // pointMouseAt: frames left in the corner-then-target move (emulation thread steps it), the target, and a left
    // click held until the cursor has arrived
    std::atomic<int> m_warpFrames{0};
    std::atomic<float> m_warpU{0.0f};
    std::atomic<float> m_warpV{0.0f};
    std::atomic<bool> m_clickAfterWarp{false};
    int m_heldClickFrames{0};
    void stepMouseWarp();
    std::atomic<bool> m_directMouse{false};
    std::atomic<bool> m_mouseLook{false};       // see setMouseLook
    std::atomic<float> m_stickLookSpeed{1.0f};
    std::atomic<int> m_recenterKey{0};          // see setLookRecenter
    std::atomic<int> m_recenterDelayMs{0};
    bool m_recenterArmed{false};                // the stick looked up/down since the last recentre (emulation thread)
    std::chrono::steady_clock::time_point m_recenterRestSince{};
    std::atomic<float> m_gyroPitchSinceRest{0.0f}; // mouse-aim games: gyro pitch since the stick last looked
    void lookSpring(bool stickLooking, bool otherAiming); // see setLookRecenter
    void tapRecenter(int recenterKey);
    // Held-key mouse look (setMouseLookKey); the bools and counters belong to the emulation thread
    std::atomic<int> m_mouseLookKey{0};
    std::atomic<float> m_lookDeadzoneY{0.15f};
    std::atomic<float> m_pendingGyroY{0.0f};    // gyro pitch waiting for the key to be down
    bool m_lookKeyHeld{false};
    int m_lookKeyFrames{0};                     // frames since the key went down
    int m_framesSinceLookKeyUp{1000};
    int m_deferredRecenterFrames{0};            // a centre-view tap waiting for the key to be up
    int m_lookKeyBlockedFrames{0};              // after centring: the gyro alone does not press the key
    bool m_tappingRecenter{false};
    float m_lookYCarry{0.0f};
    std::chrono::steady_clock::time_point m_lastLookYAt{};
    void heldKeyMouseLook(int lookKey, float stickDy);
    void releaseLookKey();
    std::atomic<bool> m_verticalLookPaused{false}; // game menu likely open (Esc pressed): no vertical mouse look
    float m_relSubpixelX{0.0f};                 // fractional sendRelativeMouse deltas (gyro) carried between calls
    float m_relSubpixelY{0.0f};
    std::atomic<bool> m_variablesDirty{false};  // reported via RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE
    std::atomic<bool> m_keys[512]{};
    std::atomic<uint8_t> m_keyReleaseFrames[512]{};
    // Keys a press is holding (the D-pad, keyboard, buttons): the sticks' arrow and look keys leave them alone, or a
    // centred stick would let go of a D-pad arrow on the very next frame. Emulation thread only.
    bool m_keyHeldByPress[512]{};

    // Function pointers from libdosbox_pure.so
    void (*m_retro_init)(void){nullptr};
    void (*m_retro_deinit)(void){nullptr};
    unsigned (*m_retro_api_version)(void){nullptr};
    void (*m_retro_set_environment)(bool (*)(unsigned, void *)){nullptr};
    void (*m_retro_set_video_refresh)(void (*)(const void *, unsigned, unsigned, size_t)){nullptr};
    void (*m_retro_set_audio_sample)(void (*)(int16_t, int16_t)){nullptr};
    void (*m_retro_set_audio_sample_batch)(size_t (*)(const int16_t *, size_t)){nullptr};
    void (*m_retro_set_input_poll)(void (*)(void)){nullptr};
    void (*m_retro_set_input_state)(int16_t (*)(unsigned, unsigned, unsigned, unsigned)){nullptr};
    void (*m_retro_set_controller_port_device)(unsigned, unsigned){nullptr};
    void (*m_retro_reset)(void){nullptr};
    void (*m_retro_run)(void){nullptr};
    size_t (*m_retro_serialize_size)(void){nullptr};
    bool (*m_retro_serialize)(void *, size_t){nullptr};
    bool (*m_retro_unserialize)(const void *, size_t){nullptr};
    bool (*m_retro_load_game)(const void *){nullptr};
    void (*m_retro_unload_game)(void){nullptr};
    void (*m_retro_get_system_av_info)(void *){nullptr};
    std::string m_coreVersion;

    friend bool retro_environment_callback(unsigned cmd, void* data);
    friend void retro_log_printf(int level, const char* fmt, ...);
    friend void retro_video_refresh_callback(const void* data, unsigned width, unsigned height, size_t pitch);
    friend size_t retro_audio_sample_batch_callback(const int16_t* data, size_t frames);
    friend void retro_audio_sample_callback(int16_t left, int16_t right);
    friend void retro_input_poll_callback(void);
    friend int16_t retro_input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id);
};
