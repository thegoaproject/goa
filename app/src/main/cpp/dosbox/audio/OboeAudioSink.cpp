#include "OboeAudioSink.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <cstdlib>
#include <cmath>

#define TAG "OboeAudioSink"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static aaudio_data_callback_result_t aaudioDataCallback(
    AAudioStream* /* stream */,
    void* userData,
    void* audioData,
    int32_t numFrames
) {
    auto* sink = static_cast<OboeAudioSink*>(userData);
    if (sink) {
        return sink->onAudioData(audioData, numFrames);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

OboeAudioSink::OboeAudioSink() {}

OboeAudioSink::~OboeAudioSink() {
    close();
}

bool OboeAudioSink::open(int32_t sampleRate, int32_t channelCount) {
    close();

    m_sampleRate = sampleRate;
    m_channelCount = channelCount;

    m_ringBuffer.assign(RING_BUFFER_FRAMES * m_channelCount, 0);
    m_writeFrame.store(0);
    m_readFrame.store(0);
    m_prebuffered.store(false);
    m_hasStarted = false;

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("Failed to create AAudio stream builder: %d", result);
        return false;
    }

    AAudioStreamBuilder_setSampleRate(builder, m_sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, m_channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, lowLatency() ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(builder, aaudioDataCallback, this);

    result = AAudioStreamBuilder_openStream(builder, &m_stream);
    if (result != AAUDIO_OK) {
        LOGE("Failed to open AAudio stream at %d Hz (result: %d). Retrying with native rate...", m_sampleRate, result);
        AAudioStreamBuilder_delete(builder);
        builder = nullptr;
        result = AAudio_createStreamBuilder(&builder);
        if (result == AAUDIO_OK) {
            AAudioStreamBuilder_setSampleRate(builder, AAUDIO_UNSPECIFIED);
            AAudioStreamBuilder_setChannelCount(builder, m_channelCount);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setPerformanceMode(builder, lowLatency() ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY : AAUDIO_PERFORMANCE_MODE_NONE);
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
            AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
            AAudioStreamBuilder_setDataCallback(builder, aaudioDataCallback, this);
            result = AAudioStreamBuilder_openStream(builder, &m_stream);
        }
    }
    if (builder) {
        AAudioStreamBuilder_delete(builder);
    }

    if (result != AAUDIO_OK || !m_stream) {
        LOGE("Failed to open AAudio stream: %d", result);
        return false;
    }

    int32_t actualRate = AAudioStream_getSampleRate(m_stream);
    m_deviceRate = actualRate;
    int32_t actualChannels = AAudioStream_getChannelCount(m_stream);
    int32_t burst = AAudioStream_getFramesPerBurst(m_stream);
    int32_t bufferCapacity = AAudioStream_getBufferCapacityInFrames(m_stream);
    int32_t targetSize = lowLatency() ? std::max(burst * 2, 960) : std::max(burst * 4, 2048);
    if (targetSize > bufferCapacity) targetSize = bufferCapacity;
    AAudioStream_setBufferSizeInFrames(m_stream, targetSize);
    int32_t actualSize = AAudioStream_getBufferSizeInFrames(m_stream);

    LOGI("Audio latency mode: %s", lowLatency() ? "low latency" : "stable");
    LOGI("Opened AAudio callback stream: actual %d Hz, %d channels, burst=%d, bufferSize=%d/%d, ringBuffer=%zu frames",
         actualRate, actualChannels, burst, actualSize, bufferCapacity, RING_BUFFER_FRAMES);
    start();
    return true;
}

void OboeAudioSink::close() {
    if (m_stream) {
        pause();
        AAudioStream_close(m_stream);
        m_stream = nullptr;
    }
}

void OboeAudioSink::start() {
    if (m_stream && !m_isPlaying.load()) {
        aaudio_result_t result = AAudioStream_requestStart(m_stream);
        if (result == AAUDIO_OK) {
            m_isPlaying.store(true);
            LOGI("AAudio stream started");
        } else {
            LOGE("Failed to start AAudio stream: %d", result);
        }
    }
}

void OboeAudioSink::pause() {
    if (m_stream && m_isPlaying.load()) {
        AAudioStream_requestPause(m_stream);
        m_isPlaying.store(false);
    }
}

void OboeAudioSink::writeAudio(const int16_t* samples, int32_t numFrames) {
    if (!samples || numFrames <= 0) return;

    uint32_t peak = 0;
    for (int32_t i = 0; i < numFrames * 2; ++i) {
        const uint32_t magnitude = (uint32_t)std::abs((int32_t)samples[i]);
        if (magnitude > peak) peak = magnitude;
    }
    uint32_t seen = statProducedPeak.load(std::memory_order_relaxed);
    while (peak > seen && !statProducedPeak.compare_exchange_weak(seen, peak, std::memory_order_relaxed)) {}

    if (!m_isPlaying.load()) return;

    size_t writePos = m_writeFrame.load(std::memory_order_relaxed);
    size_t readPos = m_readFrame.load(std::memory_order_acquire);

    size_t used = (writePos >= readPos) ? (writePos - readPos) : 0;
    size_t space = used >= RING_BUFFER_FRAMES ? 0 : RING_BUFFER_FRAMES - used;
    size_t toWrite = std::min((size_t)numFrames, space);
    if (toWrite < (size_t)numFrames) {
        statOverflowDropped.fetch_add(numFrames - toWrite, std::memory_order_relaxed);
    }
    if (toWrite == 0) return;

    size_t offset = (writePos % RING_BUFFER_FRAMES) * m_channelCount;
    size_t endFrames = RING_BUFFER_FRAMES - (writePos % RING_BUFFER_FRAMES);
    size_t firstSlice = std::min(toWrite, endFrames);
    size_t secondSlice = toWrite - firstSlice;

    std::memcpy(&m_ringBuffer[offset], samples, firstSlice * m_channelCount * sizeof(int16_t));
    if (secondSlice > 0) {
        std::memcpy(&m_ringBuffer[0], samples + firstSlice * m_channelCount, secondSlice * m_channelCount * sizeof(int16_t));
    }

    m_writeFrame.store(writePos + toWrite, std::memory_order_release);
    statWrittenFrames.fetch_add(toWrite, std::memory_order_relaxed);
}

aaudio_data_callback_result_t OboeAudioSink::onAudioData(void* audioData, int32_t numFrames) {
    if (!audioData || numFrames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;

    int16_t* out = static_cast<int16_t*>(audioData);
    size_t readPos = m_readFrame.load(std::memory_order_relaxed);
    size_t writePos = m_writeFrame.load(std::memory_order_acquire);

    size_t available = (writePos >= readPos) ? (writePos - readPos) : 0;

    uint32_t prevMin = statMinBuffered.load(std::memory_order_relaxed);
    if (available < prevMin) statMinBuffered.store((uint32_t)available, std::memory_order_relaxed);

    // Start (and restart after running dry) only once a cushion has built up, so playback resumes smoothly instead
    // of stuttering frame-by-frame. 50 ms on first start, a shorter 20 ms after an underrun to keep the gap brief.
    if (!m_prebuffered.load(std::memory_order_relaxed)) {
        const size_t needed = m_hasStarted ? 960 : targetBufferedFrames();
        if (available >= needed) {
            m_prebuffered.store(true, std::memory_order_release);
            m_hasStarted = true;
        } else {
            std::memset(out, 0, numFrames * m_channelCount * sizeof(int16_t));
            if (m_hasStarted) statSilenceFrames.fetch_add(numFrames, std::memory_order_relaxed);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
    }

    // Latency guard: only if the producer has run far ahead (>250 ms) trim back to ~100 ms. The emulation loop paces
    // on bufferedFrames(), so this should be rare; trimming more eagerly causes audible skips.
    if (available > 12000) {
        size_t excess = available - 4800;
        readPos += excess;
        m_readFrame.store(readPos, std::memory_order_relaxed);
        statOverflowDropped.fetch_add(excess, std::memory_order_relaxed);
        available = 4800;
    }

    size_t toRead = std::min((size_t)numFrames, available);

    if (toRead > 0) {
        size_t offset = (readPos % RING_BUFFER_FRAMES) * m_channelCount;
        size_t endFrames = RING_BUFFER_FRAMES - (readPos % RING_BUFFER_FRAMES);
        size_t firstSlice = std::min(toRead, endFrames);
        size_t secondSlice = toRead - firstSlice;

        std::memcpy(out, &m_ringBuffer[offset], firstSlice * m_channelCount * sizeof(int16_t));
        if (secondSlice > 0) {
            std::memcpy(out + firstSlice * m_channelCount, &m_ringBuffer[0], secondSlice * m_channelCount * sizeof(int16_t));
        }

        m_readFrame.store(readPos + toRead, std::memory_order_release);
        statPlayedFrames.fetch_add(toRead, std::memory_order_relaxed);
    }

    // Zero-fill remaining underrun frames and re-arm prebuffer if starved
    if (toRead < (size_t)numFrames) {
        std::memset(out + toRead * m_channelCount, 0, (numFrames - toRead) * m_channelCount * sizeof(int16_t));
        m_prebuffered.store(false, std::memory_order_relaxed);
        statUnderruns.fetch_add(1, std::memory_order_relaxed);
        statSilenceFrames.fetch_add(numFrames - toRead, std::memory_order_relaxed);
    }
    if (m_stream) statXRuns.store(AAudioStream_getXRunCount(m_stream), std::memory_order_relaxed);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

