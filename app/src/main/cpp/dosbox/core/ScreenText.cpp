#include "ScreenText.h"
#include "VgaFont16.h"
#include <array>
#include <cstring>
#include <unordered_map>

namespace {
constexpr unsigned kEveryNthFrame = 3;      // cheap (text modes only, <1 ms): an error may flash up just before the game quits
constexpr size_t kMaxLogLines = 40;
constexpr int kRetroLogWarn = 2;

// glyph rows (16 bytes) -> character; blank glyphs map to a space
struct GlyphTable {
    std::unordered_map<std::string, char> byBits;
    GlyphTable() {
        for (int c = 255; c >= 0; --c) {    // lower codes win on duplicates: plain ASCII over its look-alikes
            std::string key(reinterpret_cast<const char*>(&kVgaFont16[c * 16]), 16);
            char out;
            if (c >= 0x20 && c < 0x7f) out = static_cast<char>(c);
            else if (c == 0 || c == 0xff) out = ' ';
            else if ((c >= 0xb3 && c <= 0xda) || c == 0xc4 || c == 0xcd) out = c == 0xc4 || c == 0xcd ? '-' : '|';   // box drawing
            else out = '.';                                                                                      // other graphics
            auto it = byBits.find(key);
            if (it == byBits.end() || (c >= 0x20 && c < 0x7f)) byBits[key] = out;
        }
    }
};

const GlyphTable& glyphs() {
    static const GlyphTable t;
    return t;
}

inline uint32_t pixelAt(const uint8_t* row, unsigned x, bool rgb565) {
    return rgb565 ? reinterpret_cast<const uint16_t*>(row)[x] : (reinterpret_cast<const uint32_t*>(row)[x] & 0xFFFFFFu);
}
}  // namespace

ScreenText& screenText() {
    static ScreenText s;
    return s;
}

void ScreenText::onFrame(const void* data, unsigned width, unsigned height, size_t pitch, bool rgb565) {
    // 80x25 text modes: 720x400 (9-dot cells) or 640x400 (8-dot); 40-column modes are 360/320 wide
    unsigned cellW;
    if (height != 400) return;
    if (width == 720 || width == 360) cellW = 9;
    else if (width == 640 || width == 320) cellW = 8;
    else return;
    if (++m_frameCounter % kEveryNthFrame != 0) return;

    const unsigned cols = width / cellW, rows = height / 16;
    const auto* base = static_cast<const uint8_t*>(data);
    const auto& table = glyphs().byBits;
    std::string text;
    text.reserve((cols + 1) * rows);
    unsigned matched = 0, nonBlank = 0;

    for (unsigned r = 0; r < rows; ++r) {
        std::string line;
        for (unsigned c = 0; c < cols; ++c) {
            // a text cell has at most two colours (background and foreground)
            uint32_t colours[2] = {0, 0};
            unsigned counts[2] = {0, 0}, distinct = 0;
            bool tooMany = false;
            for (unsigned y = 0; y < 16 && !tooMany; ++y) {
                const uint8_t* row = base + (r * 16 + y) * pitch;
                for (unsigned x = 0; x < 8; ++x) {
                    uint32_t p = pixelAt(row, c * cellW + x, rgb565);
                    if (distinct > 0 && p == colours[0]) counts[0]++;
                    else if (distinct > 1 && p == colours[1]) counts[1]++;
                    else if (distinct < 2) { colours[distinct] = p; counts[distinct++] = 1; }
                    else { tooMany = true; break; }
                }
            }
            if (tooMany) return;                     // three colours in one cell: not a text mode, keep the old snapshot
            if (distinct < 2) { line += ' '; ++matched; continue; }
            // try both ways round (inverse video cells have more foreground than background)
            char found = 0;
            for (int fgIdx = 0; fgIdx < 2 && !found; ++fgIdx) {
                std::array<char, 16> bits{};
                for (unsigned y = 0; y < 16; ++y) {
                    const uint8_t* row = base + (r * 16 + y) * pitch;
                    uint8_t b = 0;
                    for (unsigned x = 0; x < 8; ++x) if (pixelAt(row, c * cellW + x, rgb565) == colours[fgIdx]) b |= 0x80 >> x;
                    bits[y] = static_cast<char>(b);
                }
                auto it = table.find(std::string(bits.data(), 16));
                if (it != table.end()) found = it->second;
            }
            if (!found) found = '?';                 // e.g. the blinking cursor drawn over a glyph
            else ++matched;
            if (found != ' ') ++nonBlank;
            line += found;
        }
        while (!line.empty() && line.back() == ' ') line.pop_back();
        text += line;
        text += '\n';
    }
    // a graphics frame that happens to be 640x400 matches almost nothing: only keep real text screens
    if (matched * 10 < cols * rows * 9 || nonBlank == 0) return;
    while (!text.empty() && (text.back() == '\n')) text.pop_back();
    std::lock_guard<std::mutex> g(m_lock);
    m_screen = text;
}

void ScreenText::onLog(int level, const char* line) {
    if (level < kRetroLogWarn && strstr(line, "[DOSBOX") == nullptr) return;
    if (strncmp(line, "[DOSBOX STATUS] Program:", 24) == 0) return;   // program switches are journaled separately
    std::string s(line);
    for (char& ch : s) if (static_cast<unsigned char>(ch) >= 0x80 || (ch < 0x20 && ch != '\n')) ch = '?';  // JNI wants ASCII here
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    if (s.empty()) return;
    std::lock_guard<std::mutex> g(m_lock);
    if (!m_log.empty() && m_log.back() == s) return;
    m_log.push_back(s);
    if (m_log.size() > kMaxLogLines) m_log.pop_front();
}

std::string ScreenText::screen() {
    std::lock_guard<std::mutex> g(m_lock);
    return m_screen;
}

std::string ScreenText::log() {
    std::lock_guard<std::mutex> g(m_lock);
    std::string out;
    for (const auto& l : m_log) { out += l; out += '\n'; }
    return out;
}

void ScreenText::clear() {
    std::lock_guard<std::mutex> g(m_lock);
    m_screen.clear();
    m_log.clear();
    m_frameCounter = 0;
}
