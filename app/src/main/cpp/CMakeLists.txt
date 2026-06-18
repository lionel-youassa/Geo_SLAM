cmake_minimum_required(VERSION 3.22.1)
project("geo_slam")

# Chemins pour OpenCV
set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opencv)
set(OPENCV_INCLUDE ${OPENCV_DIR}/jni/include)
set(OPENCV_LIBS_DIR ${OPENCV_DIR}/staticlibs/${ANDROID_ABI})
set(OPENCV_3RDPARTY_DIR ${OPENCV_DIR}/3rdparty/libs/${ANDROID_ABI})

macro(add_opencv_lib name)
    add_library(${name} STATIC IMPORTED)
    set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${OPENCV_LIBS_DIR}/lib${name}.a")
endmacro()

add_opencv_lib(opencv_core)
add_opencv_lib(opencv_imgproc)
add_opencv_lib(opencv_features)
add_opencv_lib(opencv_calib)
add_opencv_lib(opencv_flann)
add_opencv_lib(opencv_geometry)

macro(add_3rdparty_lib name filename)
    add_library(${name} STATIC IMPORTED)
    set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${OPENCV_3RDPARTY_DIR}/${filename}")
endmacro()

add_3rdparty_lib(cpufeatures "libcpufeatures.a")
add_3rdparty_lib(tbb "libtbb.a")
add_3rdparty_lib(ittnotify "libittnotify.a")
add_3rdparty_lib(kleidicv_hal "libkleidicv_hal.a")
add_3rdparty_lib(kleidicv "libkleidicv.a")
add_3rdparty_lib(kleidicv_thread "libkleidicv_thread.a")
add_3rdparty_lib(tegra_hal "libtegra_hal.a")
add_3rdparty_lib(libjpeg-turbo "liblibjpeg-turbo.a")
add_3rdparty_lib(libpng "liblibpng.a")
add_3rdparty_lib(libwebp "liblibwebp.a")

# --- LIAISON TENSORFLOW LITE ---
add_library(libtflite SHARED IMPORTED)
set_target_properties(libtflite PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/tflite/${ANDROID_ABI}/libtensorflowlite_jni.so)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ON UTILISE LE VRAI MOTEUR FOOTSLAM
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    footslam/footslam_engine.cpp
    vslam/vslam_engine.cpp
    ui_render/ui_engine.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/tflite
    ${CMAKE_CURRENT_SOURCE_DIR}/footslam
    ${CMAKE_CURRENT_SOURCE_DIR}/vslam
    ${CMAKE_CURRENT_SOURCE_DIR}/ui_render
    ${OPENCV_INCLUDE}
)

target_link_libraries(${CMAKE_PROJECT_NAME}
      android
      log
      jnigraphics
      libtflite
      opencv_calib
      opencv_features
      opencv_flann
      opencv_geometry
      opencv_imgproc
      opencv_core
      kleidicv_hal
      kleidicv
      kleidicv_thread
      tegra_hal
      cpufeatures
      tbb
      ittnotify
      libjpeg-turbo
      libpng
      libwebp
      z
      dl
      m
      atomic
)
