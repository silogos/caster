# ZeroFrictionAudio HAL plugin (batch A, ADR-008): two loopback devices.
# Built by build.sh, which clones a pinned libASPL next to this file first.
cmake_minimum_required(VERSION 3.12.0)

project(ZeroFrictionAudio CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(DRIVER_NAME "ZeroFrictionAudio")
set(DRIVER_VERSION "1.0.0")
set(DRIVER_COPYRIGHT "Copyright (c) Zero-Friction")
set(DRIVER_IDENTIFIER "audio.localcast.zerofriction")
set(DRIVER_UID "D3DD1276-2C00-4847-AE28-E2A341F009D6")
set(DRIVER_ENTRYPOINT "ZeroFrictionAudioEntryPoint")

# libASPL is vendored at build time by build.sh (pinned commit, MIT).
add_subdirectory(libASPL EXCLUDE_FROM_ALL)

add_library(${DRIVER_NAME} MODULE
    "src/Driver.cpp"
    )

target_link_libraries(${DRIVER_NAME}
    PRIVATE libASPL
    )

find_library(LIB_CoreFoundation CoreFoundation REQUIRED)
target_link_libraries(${DRIVER_NAME}
    PRIVATE ${LIB_CoreFoundation}
    )

set_target_properties(${DRIVER_NAME} PROPERTIES
    OUTPUT_NAME "${DRIVER_NAME}"
    BUNDLE true
    BUNDLE_EXTENSION "driver"
    PREFIX ""
    SUFFIX ""
    MACOSX_BUNDLE ON
    MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_SOURCE_DIR}/Info.plist.in"
    MACOSX_BUNDLE_BUNDLE_NAME "${DRIVER_NAME}"
    MACOSX_BUNDLE_BUNDLE_VERSION "${DRIVER_VERSION}"
    MACOSX_BUNDLE_COPYRIGHT "${DRIVER_COPYRIGHT}"
    MACOSX_BUNDLE_GUI_IDENTIFIER "${DRIVER_IDENTIFIER}"
    MACOSX_BUNDLE_SHORT_VERSION_STRING "${DRIVER_VERSION}"
    )
