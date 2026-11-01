#pragma once
// Host-only, deterministic Euler checkpoints. Never enabled by prompt or HTTP fields.
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <wincrypt.h>
#include <filesystem>
#include <fstream>
#include <vector>
#include <string>
#include <stdexcept>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#pragma comment(lib, "crypt32.lib")

namespace ziphap {
class EulerCheckpoint {
    static constexpr size_t limit = 32 * 1024 * 1024;
    std::filesystem::path path;
    std::string binding;
    std::vector<float> sigmas;
    int interval = 5;
    bool enabled = false;
    static void require(bool ok) { if (!ok) throw std::runtime_error("ZIPHAP_CHECKPOINT_INVALID"); }
    template<class T> static void put(std::vector<unsigned char>& out, const T& value) {
        const auto* begin = reinterpret_cast<const unsigned char*>(&value);
        out.insert(out.end(), begin, begin + sizeof(T));
    }
    template<class T> static T get(const std::vector<unsigned char>& in, size_t& offset) {
        require(offset <= in.size() && sizeof(T) <= in.size() - offset);
        T value; std::memcpy(&value, in.data() + offset, sizeof(T)); offset += sizeof(T); return value;
    }
    static std::vector<unsigned char> crypt(const std::vector<unsigned char>& input, bool decrypt) {
        require(!input.empty() && input.size() <= limit);
        DATA_BLOB source{static_cast<DWORD>(input.size()), const_cast<BYTE*>(input.data())}, output{};
        const BOOL ok = decrypt ? CryptUnprotectData(&source, nullptr, nullptr, nullptr, nullptr,
            CRYPTPROTECT_UI_FORBIDDEN, &output) : CryptProtectData(&source, L"ZipHap image recovery", nullptr,
            nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output);
        require(ok != FALSE);
        std::vector<unsigned char> result(output.pbData, output.pbData + output.cbData);
        SecureZeroMemory(output.pbData, output.cbData); LocalFree(output.pbData);
        require(result.size() <= limit); return result;
    }
public:
    explicit EulerCheckpoint(const std::vector<float>& schedule):sigmas(schedule) {
        const wchar_t* location = _wgetenv(L"ZIPHAP_EULER_CHECKPOINT");
        if (!location || !*location) return;
        const char* identity = std::getenv("ZIPHAP_CHECKPOINT_BINDING");
        require(identity && std::strlen(identity) == 64);
        binding = identity;
        require(binding.find_first_not_of("0123456789abcdef") == std::string::npos);
        require(sigmas.size() >= 2 && sigmas.size() <= 51);
        path = location; require(path.is_absolute()); enabled = true;
    }
    template<class Tensor> int restore(Tensor& x) const {
        if (!enabled || !std::filesystem::exists(path)) return 0;
        const auto length = std::filesystem::file_size(path);
        require(length > 0 && length <= limit);
        std::vector<unsigned char> encrypted(static_cast<size_t>(length));
        std::ifstream stream(path, std::ios::binary);
        require(static_cast<bool>(stream.read(reinterpret_cast<char*>(encrypted.data()), encrypted.size())));
        auto plain = crypt(encrypted, true); size_t offset = 0;
        require(plain.size() >= 88 && std::memcmp(plain.data(), "ZHCP0001", 8) == 0); offset = 8;
        const auto next = get<uint32_t>(plain, offset), total = get<uint32_t>(plain, offset);
        const auto dimensions = get<uint32_t>(plain, offset);
        require(total + 1 == sigmas.size() && next <= total && dimensions == x.shape().size());
        require(offset + 64 <= plain.size() && std::memcmp(plain.data()+offset, binding.data(), 64)==0); offset += 64;
        for (const auto dimension : x.shape()) require(get<int64_t>(plain, offset) == dimension);
        const size_t sigmaBytes = sigmas.size() * sizeof(float), tensorBytes = static_cast<size_t>(x.numel()) * sizeof(float);
        require(offset + sigmaBytes <= plain.size() && std::memcmp(plain.data()+offset, sigmas.data(), sigmaBytes)==0);
        offset += sigmaBytes;
        require(tensorBytes <= limit && plain.size()-offset == tensorBytes);
        std::memcpy(x.data(), plain.data()+offset, tensorBytes);
        return static_cast<int>(next);
    }
    template<class Tensor> void save(const Tensor& x, int next) const {
        if (!enabled || (next != 1 && next % interval != 0 && next + 1 != static_cast<int>(sigmas.size()))) return;
        require(next >= 1 && next < static_cast<int>(sigmas.size()) && x.shape().size() <= 8);
        std::vector<unsigned char> plain{'Z','H','C','P','0','0','0','1'};
        put(plain, static_cast<uint32_t>(next)); put(plain, static_cast<uint32_t>(sigmas.size()-1));
        put(plain, static_cast<uint32_t>(x.shape().size()));
        plain.insert(plain.end(), binding.begin(), binding.end());
        for (const auto dimension : x.shape()) put(plain, static_cast<int64_t>(dimension));
        const auto* sigmaBegin = reinterpret_cast<const unsigned char*>(sigmas.data());
        plain.insert(plain.end(), sigmaBegin, sigmaBegin + sigmas.size()*sizeof(float));
        const auto* tensorBegin = reinterpret_cast<const unsigned char*>(x.data());
        require(x.numel() > 0 && static_cast<uint64_t>(x.numel())*sizeof(float) < limit-4096);
        plain.insert(plain.end(), tensorBegin, tensorBegin + x.numel()*sizeof(float));
        auto encrypted = crypt(plain, false);
        const auto temporary = std::filesystem::path(path.wstring()+L".next");
        HANDLE file = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH, nullptr);
        require(file != INVALID_HANDLE_VALUE);
        DWORD written = 0;
        const bool ok = WriteFile(file, encrypted.data(), static_cast<DWORD>(encrypted.size()), &written, nullptr)
            && written == encrypted.size() && FlushFileBuffers(file);
        CloseHandle(file); require(ok);
        require(MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH));
    }
};
} // namespace ziphap
