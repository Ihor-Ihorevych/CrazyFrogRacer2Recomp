#pragma once
#include <cstdint>

// On-screen touch controls of the Android app (host/android_touch.cpp): the Java overlay sends its buttons and left stick through JNI,
// the window's poll merges them into controller port 1. Elsewhere there is nothing to merge.
#ifdef __ANDROID__
void android_touch_merge(std::uint16_t &pressed, std::uint8_t &lx, std::uint8_t &ly);
// the pictures shown per second (window.cpp show_speed, once a second) and the console's rate, for the app's FPS counter
void android_note_fps(float fps, float hz);
#else
inline void android_touch_merge(std::uint16_t &, std::uint8_t &, std::uint8_t &) {}
inline void android_note_fps(float, float) {}
#endif
