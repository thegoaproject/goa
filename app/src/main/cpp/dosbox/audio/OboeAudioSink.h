#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>
#include <climits>
#include <vector>

class OboeAudioSink {
public:
    OboeAudioSink();
    ~OboeAudioSink();

    bool open(int32_t sampleRate = 44100, int32_t channelCount = 2);
    void close();
    void start();
    void pause();
    void writeAudio(const int16_t* samples, int32_t numFrames);

    aaudio_data_callback_result_t onAudioData(void* audioData, int32_t numFrames);

    // Frames queued in the ring buffer; the emulation loop paces itself on this
    size_t bufferedFrames() const {
        size_t w = m_writeFrame.load(std::memory_order_acquire);
        size_t r = m_readFrame.load(std::memory_order_acquire);
        return w >= r ? w - r : 0;
    }
    int32_t sampleRate() const { return m_sampleRate; }
    int32_t deviceSampleRate() const { return m_deviceRate; }  // what AAudio actually opened (no resampling if it differs)

    // Cushion the loop aims to keep queued (~50 ms at 48 kHz)
    static constexpr size_t TARGET_BUFFERED_FRAMES = 2400;
    // Low latency (☰ → Audio): half the cushion (~25 ms) and a smaller device buffer
    static constexpr size_t LOW_LATENCY_TARGET_FRAMES = 1200;

    // 0 = Stable (what GOA always did), 1 = Low latency. Applies the next time the stream opens.
    void setLatencyMode(int mode) { m_latencyMode.store(mode == 1 ? 1 : 0); }
    bool lowLatency() const { return m_latencyMode.load() == 1; }
    size_t targetBufferedFrames() const { return lowLatency() ? LOW_LATENCY_TARGET_FRAMES : TARGET_BUFFERED_FRAMES; }

    // Telemetry (monotonic counters; the perf monitor diffs them per window)
    std::atomic<uint64_t> statWrittenFrames{0};
    std::atomic<uint64_t> statPlayedFrames{0};
    std::atomic<uint64_t> statUnderruns{0};          // callbacks that ran dry
    std::atomic<uint64_t> statSilenceFrames{0};      // frames zero-filled because nothing was queued
    std::atomic<uint64_t> statOverflowDropped{0};    // frames discarded because the ring was too full
    std::atomic<uint32_t> statMinBuffered{UINT32_MAX};
    std::atomic<int32_t> statXRuns{0};               // AAudio's own xrun counter
    // Loudest sample the core produced since the last read, before the ring buffer and device volume, so a muted
    // phone still reports the game's own level (telemetry "no audio" detection). Read with exchange(0).
    std::atomic<uint32_t> statProducedPeak{0};

private:
    AAudioStream* m_stream{nullptr};
    std::atomic<bool> m_isPlaying{false};
    int32_t m_sampleRate{44100};
    int32_t m_channelCount{2};
    int32_t m_deviceRate{0};
    std::atomic<int> m_latencyMode{0};

    // Lock-free Single-Producer Single-Consumer (SPSC) Ring Buffer
    static constexpr size_t RING_BUFFER_FRAMES = 16384; // ~340ms at 48 kHz
    std::vector<int16_t> m_ringBuffer;
    std::atomic<size_t> m_writeFrame{0};
    std::atomic<size_t> m_readFrame{0};
    std::atomic<bool> m_prebuffered{false};
    bool m_hasStarted{false};  // touched only on the AAudio callback thread (and in open())
};

