cmake_minimum_required(VERSION 3.22.1)
project("geo_slam")

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
    footslam/footslam_engine.cpp
    vslam/vslam_engine.cpp
    ui_render/ui_engine.cpp
)

# 3. Configuration des inclusions
# On inclut le dossier tflite pour trouver "tensorflow/lite/..." et "flatbuffers/..."
# ATTENTION : Ne pas inclure ${TFLITE_DIR}/flatbuffers directement pour éviter
# les conflits de noms avec les headers standards (ex: string.h).
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
    tensorflowlite
)
