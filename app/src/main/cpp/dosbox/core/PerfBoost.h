#pragma once

#include <cstdint>
#include <string>

/**
 * Keeps demanding DOS games on the phone's fastest CPU cores at high clocks.
 *
 * DOSBox Pure emulates DOS on a thread of its own that runs ahead of GOA's frame loop, so the loop's own timing says
 * nothing about how hard the emulated CPU works. With cycles on auto or max, DOSBox Pure raises the cycle count until
 * it fills the CPU time it gets: give it a faster core and higher clocks and the game runs faster (Quake, Tomb Raider).
 *
 * The emulation threads register themselves (the DOS thread from the core's status log, the frame loop, the render
 * thread), get display-level priority, and join an Android performance-hint session (ADPF). While boosting, every
 * frame is reported as needing more time than the target, which asks the scheduler for big cores and high clocks;
 * games with a fixed cycle count don't boost.
 */
namespace perfboost {

/** Registers the calling thread under [role] ("dos", "frame", "render"); a new thread for a role replaces the old. */
void registerThread(const char* role);

/** Boost (games on auto/max cycles) or not; takes effect on the next frame. */
void setBoost(bool enabled);

/** Called by the frame loop after each frame: maintains the hint session and reports the frame. */
void frameDone(int64_t targetFrameNs, int64_t workNs);

/** Which CPU each registered thread last ran on, with that CPU's current and max clock: "dos=cpu7 3.2/4.3GHz ...". */
std::string describe();

/** The frame loop is ending: closes its hint session (call from the frame loop thread). */
void endLoop();

/** Forgets all threads and closes the session (engine shutdown). */
void reset();

} // namespace perfboost
