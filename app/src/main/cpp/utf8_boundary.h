#pragma once

#include <stdexcept>
#include <string_view>

namespace ziphap_native {

constexpr int UTF8_BOUNDARY_TOKENS = 8;

class Utf8OutputError final : public std::runtime_error {
public:
    explicit Utf8OutputError(const char * message) : std::runtime_error(message) {}
};

// Tracks raw token bytes, including bytes temporarily withheld by the chat parser.
// No user/model text is stored. Incomplete sequences may span token boundaries.
class Utf8Boundary {
public:
    void append(std::string_view text) {
        for (const unsigned char byte : text) {
            if (remaining_ > 0) {
                if (byte < minimum_ || byte > maximum_) invalid();
                --remaining_;
                minimum_ = 0x80; maximum_ = 0xBF;
            } else if (byte <= 0x7F) {
                continue;
            } else if (byte >= 0xC2 && byte <= 0xDF) {
                remaining_ = 1; minimum_ = 0x80; maximum_ = 0xBF;
            } else if (byte >= 0xE0 && byte <= 0xEF) {
                remaining_ = 2;
                minimum_ = byte == 0xE0 ? 0xA0 : 0x80;
                maximum_ = byte == 0xED ? 0x9F : 0xBF;
            } else if (byte >= 0xF0 && byte <= 0xF4) {
                remaining_ = 3;
                minimum_ = byte == 0xF0 ? 0x90 : 0x80;
                maximum_ = byte == 0xF4 ? 0x8F : 0xBF;
            } else invalid();
        }
    }
    bool incomplete() const { return remaining_ != 0; }
    void require_complete() const {
        if (incomplete()) throw Utf8OutputError("Native UTF-8 output ended inside a character; valid partial text was preserved");
    }
private:
    [[noreturn]] static void invalid() {
        throw Utf8OutputError("Native UTF-8 output contains an invalid byte sequence; valid partial text was preserved");
    }
    int remaining_ = 0;
    unsigned char minimum_ = 0x80;
    unsigned char maximum_ = 0xBF;
};

inline bool may_complete_utf8(int generated, int cap, bool incomplete) {
    return incomplete && generated >= cap && generated - cap < UTF8_BOUNDARY_TOKENS;
}

} // namespace ziphap_native
