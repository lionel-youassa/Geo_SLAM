cmake_minimum_required(VERSION 3.22.1)
project("geo_slam")

# Inclusion des répertoires d'en-tête
include_directories(
    footslam
    vslam
    ui_render
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
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    ${FOOTSLAM_SRC}
    vslam/vslam_engine.cpp
    ui_render/ui_engine.cpp
)

# Liaison des bibliothèques
target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    jnigraphics
    ${TFLITE_LIB}
)