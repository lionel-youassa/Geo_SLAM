cmake_minimum_required(VERSION 3.22.1)
project("geo_slam")

# Inclusion des répertoires d'en-tête
include_directories(
    footslam
    vslam
    ui_render
)

# Définition de la bibliothèque principale avec les fichiers de chaque module
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    footslam/footslam_engine.cpp
    vslam/vslam_engine.cpp
    ui_render/ui_engine.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    jnigraphics
)