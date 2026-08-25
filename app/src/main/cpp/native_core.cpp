#include <jni.h>

#include <cstdint>

namespace {

const char* CurrentAbi() noexcept {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

constexpr bool NeonAvailable() noexcept {
#if defined(__aarch64__) || defined(__ARM_NEON)
    return true;
#else
    return false;
#endif
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_top_r2dblog_justcamera_nativecore_NativeCore_nativeVersion(
        JNIEnv* env,
        jobject /* this */) noexcept {
    return env->NewStringUTF("JustCamera Native Core 0.2");
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_r2dblog_justcamera_nativecore_NativeCore_nativeProcessingVersion(
        JNIEnv* env,
        jobject /* this */) noexcept {
    return env->NewStringUTF("JustCamera Processing 1");
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_r2dblog_justcamera_nativecore_NativeCore_nativeAbi(
        JNIEnv* env,
        jobject /* this */) noexcept {
    return env->NewStringUTF(CurrentAbi());
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_r2dblog_justcamera_nativecore_NativeCore_nativeCapabilityFlags(
        JNIEnv* /* env */,
        jobject /* this */) noexcept {
    // Bit 0 means the ABI exposes NEON. No SIMD kernel is active in PH4 scalar processing.
    return NeonAvailable() ? static_cast<jlong>(1) : static_cast<jlong>(0);
}
