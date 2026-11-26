#pragma once
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <deque>

/**
 * What a DOS game last said, for the troubleshooter: the text on screen while in a text mode (DOS/4GW crash dumps,
 * "Not enough memory", "Illegal command", a setup program's error) and the core's own recent warnings and errors.
 *
 * The core gives no access to DOS memory, so the text screen is read from the frame itself: in a text mode every
 * 8x16 character cell is drawn from the VGA ROM font, so each cell is matched exactly against the font's 256 glyphs.
 * Cells that match no glyph mean the frame is not text (a graphics mode), and the snapshot is not taken.
 */
class ScreenText {
public:
    /** Called with every frame; looks at text-mode frames at most every few frames. */
    void onFrame(const void* data, unsigned width, unsigned height, size_t pitch, bool rgb565);
    void onLog(int level, const char* line);
    /** The last text screen read (trailing blanks trimmed), empty when none. */
    std::string screen();
    /** The core's recent warnings and errors, oldest first, one per line. */
    std::string log();
    void clear();

private:
    std::mutex m_lock;
    std::string m_screen;
    std::deque<std::string> m_log;
    unsigned m_frameCounter = 0;
};

ScreenText& screenText();
