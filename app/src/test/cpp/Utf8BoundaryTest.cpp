// Portable test, no Android/model dependency:
// c++ -std=c++17 -Iapp/src/main/cpp app/src/test/cpp/Utf8BoundaryTest.cpp -o utf8-boundary-test
#include "utf8_boundary.h"
#include <initializer_list>
#include <stdexcept>
#include <string_view>

using ziphap_native::Utf8Boundary;
using ziphap_native::Utf8OutputError;

static void check(bool condition) { if (!condition) throw std::runtime_error("UTF-8 boundary regression"); }
static bool rejected(std::initializer_list<std::string_view> parts) {
    try { Utf8Boundary value; for (const auto part : parts) value.append(part); value.require_complete(); }
    catch (const Utf8OutputError &) { return true; }
    return false;
}

int main() {
    Utf8Boundary korean;
    korean.append("\xEA"); check(korean.incomplete());
    korean.append("\xB0"); check(korean.incomplete());
    korean.append("\x80"); check(!korean.incomplete()); korean.require_complete();

    Utf8Boundary emoji;
    for (const auto byte : {"\xF0", "\x9F", "\x99"}) { emoji.append(byte); check(emoji.incomplete()); }
    emoji.append("\x82"); check(!emoji.incomplete());
    emoji.append(" ascii "); emoji.append("\xED\x95\x9C\xEA\xB8\x80"); emoji.require_complete();

    check(rejected({"\xEA"})); // EOS/cap while the chat parser could still withhold these bytes.
    check(rejected({"\xF0", "\x9F", "\x99"}));
    check(rejected({"\x80"})); // Unpaired continuation byte.
    check(rejected({"\xC0\xAF"})); // Overlong encoding.
    check(rejected({"\xE0\x80\x80"}));
    check(rejected({"\xED\xA0\x80"})); // UTF-16 surrogate is not a Unicode scalar.
    check(rejected({"\xF4\x90\x80\x80"})); // Greater than U+10FFFF.
    check(rejected({"\xF5\x80\x80\x80"}));
    check(rejected({"\xEA", "x"})); // Adding unrelated text cannot repair a partial scalar.
    check(!rejected({"\xED\x9F\xBF", "\xF4\x8F\xBF\xBF"}));

    for (int generated = 16; generated < 24; ++generated) {
        check(ziphap_native::may_complete_utf8(generated, 16, true));
        check(!ziphap_native::may_complete_utf8(generated, 16, false));
    }
    check(!ziphap_native::may_complete_utf8(15, 16, true));
    check(!ziphap_native::may_complete_utf8(24, 16, true));
    check(!ziphap_native::may_complete_utf8(25, 16, true));
    return 0;
}
