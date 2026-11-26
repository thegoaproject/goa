cmake_minimum_required(VERSION 3.22.1)
project("goa_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Android system libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(gles-lib GLESv3)
find_library(egl-lib EGL)
find_library(aaudio-lib aaudio)
find_library(dl-lib dl)
find_library(z-lib z)

# DOSBox Emulation & Audio Engine Native Library
add_library(dosbox_engine SHARED
    dosbox/jni_bridge.cpp
    dosbox/core/DosBoxPureCore.cpp
    dosbox/core/ScreenText.cpp
    dosbox/core/PerfBoost.cpp
    dosbox/audio/OboeAudioSink.cpp
    dosbox/render/GpuRenderer.cpp
    dosbox/render/HwRenderContext.cpp
    dosbox/render/filter/FilterBundle.cpp
    dosbox/render/filter/FilterChain.cpp
    dosbox/render/filter/ImportPass.cpp
)

target_include_directories(dosbox_engine PRIVATE
    dosbox
    dosbox/core
    dosbox/audio
    dosbox/render
)

target_link_libraries(dosbox_engine PRIVATE
    ${log-lib}
    ${android-lib}
    ${gles-lib}
    ${egl-lib}
    ${aaudio-lib}
    ${dl-lib}
    ${z-lib}
)

# Google Play requires native libraries to support 16 KB memory pages (apps targeting Android 15+): align the ELF
# segments to 16 KB. (NDK r28+ does this by default; the pinned NDK may be older.)
target_link_options(dosbox_engine PRIVATE -Wl,-z,max-page-size=16384)
