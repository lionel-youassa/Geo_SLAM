cmake_minimum_required(VERSION 3.22.1)
project("geo_slam")

# Activation de Prefab pour lier les bibliothèques Gradle (TFLite)
find_package(tensorflow-lite REQUIRED CONFIG)

# Inclusion des répertoires d'en-tête
include_directories(
    footslam
    vslam
    ui_render
)

# Définition de la bibliothèque principale
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    footslam/footslam_engine.cpp
    vslam/vslam_engine.cpp
    ui_render/ui_engine.cpp
)

# Liaison des bibliothèques
target_link_libraries(${CMAKE_PROJECT_NAME}
    # Bibliothèques Android système
    android
    log
    jnigraphics
    # TensorFlow Lite (Lionel : Ton module IA)
    tensorflow::tensorflow-lite
)