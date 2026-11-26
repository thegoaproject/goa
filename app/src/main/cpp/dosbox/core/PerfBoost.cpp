#include "PerfBoost.h"

#include <android/log.h>
#include <android/performance_hint.h>
#include <sys/resource.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <vector>

#define PB_LOG(...) __android_log_print(ANDROID_LOG_INFO, "GoaPerfBoost", __VA_ARGS__)

namespace perfboost {
namespace {

struct Registered { std::string role; pid_t tid; };

std::mutex g_mutex;
std::vector<Registered> g_threads;          // guarded by g_mutex
std::atomic<bool> g_dirty{false};           // threads changed: the session must be rebuilt
std::atomic<bool> g_boost{false};

// Frame-loop thread only
APerformanceHintSession* g_session = nullptr;
int64_t g_sessionTargetNs = 0;

// Display-level priority (Android's THREAD_PRIORITY_URGENT_DISPLAY): ahead of background work, below audio
constexpr int kNice = -8;

void closeSession() {
    if (g_session) APerformanceHint_closeSession(g_session);
    g_session = nullptr;
    g_sessionTargetNs = 0;
}

void openSession(int64_t targetNs) {
    closeSession();
    APerformanceHintManager* manager = APerformanceHint_getManager();
    if (!manager) return;
    std::vector<int32_t> tids;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        for (const auto& t : g_threads) tids.push_back(t.tid);
    }
    if (tids.empty()) return;
    g_session = APerformanceHint_createSession(manager, tids.data(), tids.size(), targetNs);
    g_sessionTargetNs = g_session ? targetNs : 0;
    PB_LOG("hint session %s for %zu threads, target %lld us", g_session ? "open" : "unavailable", tids.size(),
           (long long)(targetNs / 1000));
}

std::string readLine(const std::string& path) {
    std::ifstream in(path);
    std::string s;
    std::getline(in, s);
    return s;
}

/** The CPU a thread last ran on: field 39 of /proc/self/task/<tid>/stat, counted after the ")" of its name. */
int lastCpu(pid_t tid) {
    std::string stat = readLine("/proc/self/task/" + std::to_string(tid) + "/stat");
    size_t close = stat.rfind(')');
    if (close == std::string::npos) return -1;
    int field = 2;
    for (size_t i = close + 1; i < stat.size(); ++i) {
        if (stat[i] != ' ') continue;
        if (++field == 39) return atoi(stat.c_str() + i + 1);
    }
    return -1;
}

std::string ghz(const std::string& khz) {
    if (khz.empty()) return "?";
    char buf[16];
    snprintf(buf, sizeof(buf), "%.1f", atol(khz.c_str()) / 1e6);
    return buf;
}

} // namespace

void registerThread(const char* role) {
    const pid_t tid = gettid();
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        for (auto& t : g_threads) {
            if (t.role != role) continue;
            if (t.tid == tid) return;
            t.tid = tid;
            g_dirty.store(true);
            setpriority(PRIO_PROCESS, tid, kNice);
            return;
        }
        g_threads.push_back({role, tid});
    }
    setpriority(PRIO_PROCESS, tid, kNice);
    g_dirty.store(true);
    PB_LOG("%s thread %d registered", role, (int)tid);
}

void setBoost(bool enabled) {
    if (g_boost.exchange(enabled) != enabled) g_dirty.store(true);
}

void frameDone(int64_t targetFrameNs, int64_t workNs) {
    if (!g_boost.load()) {
        if (g_session) closeSession();
        return;
    }
    // Aim at half a frame: the DOS thread fills whatever time it is given (cycles auto/max), so the loop's own work is
    // never the measure; reporting each frame as over target asks for the fastest cores and clocks while boosting
    const int64_t targetNs = targetFrameNs / 2;
    if (g_dirty.exchange(false) || !g_session || g_sessionTargetNs != targetNs) openSession(targetNs);
    if (!g_session) return;
    const int64_t reported = workNs > targetNs ? workNs : targetNs + targetNs / 4;
    APerformanceHint_reportActualWorkDuration(g_session, reported);
}

std::string describe() {
    std::vector<Registered> threads;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        threads = g_threads;
    }
    std::string out;
    for (const auto& t : threads) {
        const int cpu = lastCpu(t.tid);
        if (!out.empty()) out += ' ';
        out += t.role + "=cpu" + std::to_string(cpu);
        if (cpu >= 0) {
            const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/";
            out += ' ' + ghz(readLine(base + "scaling_cur_freq")) + '/' + ghz(readLine(base + "cpuinfo_max_freq")) + "GHz";
        }
    }
    if (g_boost.load()) out += g_session ? " boost" : " boost(no-session)";
    return out;
}

void endLoop() { closeSession(); }

void reset() {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_threads.clear();
    }
    g_boost.store(false);
    g_dirty.store(false);
    // The session itself is closed by the frame loop's last frameDone (it owns it); a new loop opens a fresh one
}

} // namespace perfboost
