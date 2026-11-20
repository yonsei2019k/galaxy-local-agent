"""Build the shared Rust engine and generate Kotlin bindings; never downloads models or signs APKs."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess


def run(args, *, cwd, env):
    subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bindings-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    workspace = root / "rust"
    env = dict(os.environ)
    env.setdefault("CARGO_BUILD_JOBS", "2")
    cargo = shutil.which("cargo") or str(Path.home() / ".cargo/bin/cargo.exe")
    generated = root / "android-runtime/build/generated/rust"
    bindings = generated / "kotlin"
    bindings.mkdir(parents=True, exist_ok=True)
    run([cargo, "build", "--locked", "-p", "ziphap-android"], cwd=workspace, env=env)
    library = workspace / "target/debug" / ("ziphap_android.dll" if os.name == "nt" else
              "libziphap_android.dylib" if platform.system() == "Darwin" else "libziphap_android.so")
    host_library = generated / "host"
    host_library.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(library, host_library / library.name)
    run([cargo, "run", "--locked", "-p", "ziphap-android", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
         "generate", "--library", library, "--language", "kotlin", "--config", workspace / "crates/ziphap-android/uniffi.toml", "--out-dir", bindings], cwd=workspace, env=env)
    if args.bindings_only:
        return
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("ANDROID_HOME is required for the pinned Android NDK")
    ndk = Path(sdk) / "ndk/29.0.14206865/toolchains/llvm/prebuilt"
    host = "windows-x86_64" if os.name == "nt" else "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    toolbin = ndk / host / "bin"
    linker = toolbin / ("aarch64-linux-android31-clang.cmd" if os.name == "nt" else "aarch64-linux-android31-clang")
    if not linker.is_file():
        raise RuntimeError("Pinned NDK29 ARM64 compiler is missing")
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = str(linker)
    env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
    run([cargo, "build", "--locked", "--release", "-p", "ziphap-android", "--lib",
         "--target", "aarch64-linux-android"], cwd=workspace, env=env)
    destination = generated / "jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(workspace / "target/aarch64-linux-android/release/libziphap_android.so",
                    destination / "libziphap_android.so")


if __name__ == "__main__":
    main()
