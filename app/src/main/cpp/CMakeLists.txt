cmake_minimum_required(VERSION 3.22.1)
project("geo_slam")

# OpenCV (Narcisse : vSLAM) — linkage statique direct
set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opencv)
set(OPENCV_INCLUDE ${OPENCV_DIR}/jni/include)
set(OPENCV_LIBS_DIR ${OPENCV_DIR}/staticlibs/${ANDROID_ABI})
set(OPENCV_3RDPARTY_DIR ${OPENCV_DIR}/3rdparty/libs/${ANDROID_ABI})

add_library(opencv_core       STATIC IMPORTED)
add_library(opencv_imgproc    STATIC IMPORTED)
add_library(opencv_features2d STATIC IMPORTED)
add_library(opencv_calib3d    STATIC IMPORTED)
add_library(opencv_flann      STATIC IMPORTED)
add_library(cpufeatures       STATIC IMPORTED)
add_library(tbb               STATIC IMPORTED)
add_library(ittnotify         STATIC IMPORTED)

set_target_properties(opencv_core       PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS_DIR}/libopencv_core.a)
set_target_properties(opencv_imgproc    PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS_DIR}/libopencv_imgproc.a)
set_target_properties(opencv_features2d PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS_DIR}/libopencv_features2d.a)
set_target_properties(opencv_calib3d    PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS_DIR}/libopencv_calib3d.a)
set_target_properties(opencv_flann      PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS_DIR}/libopencv_flann.a)
set_target_properties(cpufeatures       PROPERTIES IMPORTED_LOCATION ${OPENCV_3RDPARTY_DIR}/libcpufeatures.a)
set_target_properties(tbb               PROPERTIES IMPORTED_LOCATION ${OPENCV_3RDPARTY_DIR}/libtbb.a)
set_target_properties(ittnotify         PROPERTIES IMPORTED_LOCATION ${OPENCV_3RDPARTY_DIR}/libittnotify.a)

# Libs spécifiques par architecture
if(ANDROID_ABI MATCHES "arm")
    add_library(tegra_hal STATIC IMPORTED)
    set_target_properties(tegra_hal PROPERTIES IMPORTED_LOCATION ${OPENCV_3RDPARTY_DIR}/libtegra_hal.a)
    set(ARCH_SPECIFIC_LIBS tegra_hal)
elseif(ANDROID_ABI MATCHES "x86")
    add_library(ippicv STATIC IMPORTED)
    add_library(ippiw  STATIC IMPORTED)
    set_target_properties(ippicv PROPERTIES IMPORTED_LOCATION ${OPENCV_3RDPARTY_DIR}/libippicv.a)
    set_target_properties(ippiw  PROPERTIES IMPORTED_LOCATION ${OPENCV_3RDPARTY_DIR}/libippiw.a)
    set(ARCH_SPECIFIC_LIBS ippicv ippiw)
else()
    set(ARCH_SPECIFIC_LIBS "")
endif()

# Inclusion des répertoires d'en-tête
include_directories(
    footslam
    vslam
    ui_render
    ${OPENCV_INCLUDE}
)

# Lionel : TFLite via Prefab (nécessite que le AAR expose des métadonnées Prefab).
# Le AAR tensorflow-lite:2.14.0 de Maven Central ne les expose pas.
# Solution temporaire : stub jusqu'à résolution du setup TFLite C++.
find_package(tensorflow-lite CONFIG)

if(tensorflow-lite_FOUND)
    set(FOOTSLAM_SRC footslam/footslam_engine.cpp)
    set(TFLITE_LIB tensorflow::tensorflow-lite)
    message(STATUS "TFLite Prefab trouvé - module FootSLAM complet actif.")
else()
    set(FOOTSLAM_SRC footslam/footslam_engine_stub.cpp)
    set(TFLITE_LIB "")
    message(WARNING "TFLite Prefab introuvable - stub FootSLAM actif. Lionel : résoudre le setup TFLite NDK.")
endif()

# Définition de la bibliothèque principale
# Force l'alignement des segments mémoire à 16 Ko pour la compatibilité Android 15+
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Configuration des répertoires TFLite extraits manuellement
set(TFLITE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/tflite)

# Définition de la bibliothèque TensorFlow Lite comme bibliothèque importée
add_library(tensorflowlite SHARED IMPORTED)
set_target_properties(tensorflowlite PROPERTIES
    IMPORTED_LOCATION "${TFLITE_DIR}/${ANDROID_ABI}/libtensorflowlite_jni.so"
)

# 2. Définition de la bibliothèque principale
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    ${FOOTSLAM_SRC}
    vslam/vslam_engine.cpp
    ui_render/ui_engine.cpp
)

# 3. Configuration des inclusions
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${TFLITE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/footslam
    ${CMAKE_CURRENT_SOURCE_DIR}/vslam
    ${CMAKE_CURRENT_SOURCE_DIR}/ui_render
)

# 4. Liaison des bibliothèques
target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    jnigraphics
    ${TFLITE_LIB}
    opencv_calib3d
    opencv_features2d
    opencv_flann
    opencv_imgproc
    opencv_core
    ${ARCH_SPECIFIC_LIBS}
    tbb
    ittnotify
    cpufeatures
    z
    dl
)
    tensorflowlite
)
