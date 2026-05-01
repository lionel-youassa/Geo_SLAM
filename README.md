# Geo-SLAM : Système de Localisation Hybride (FootSLAM IA & vSLAM)

Projet de localisation en environnement industriel (GPS-Denied) via smartphone, combinant odométrie inertielle par IA et vision par ordinateur.

## 🚀 Équipe & Responsabilités

### 🦁 Lionel (Lead IA, Data & FootSLAM - Scrum Master)
- **Acquisition Hardware** : Gestion directe du `SensorManager` (IMU à 100Hz) pour alimenter l'IA.
- **Cœur IA** : Intégration modèles RoNIN / TLIO via TensorFlow Lite.
- **Fusion** : Filtre de Kalman (EKF) pour le lissage de trajectoire et calcul du (X, Y, Z).

### 👓 Narcisse (Ingénieur Vision & vSLAM)
- **Moteur SLAM** : Intégration ORB-SLAM3 (C++/NDK).
- **Géométrie** : Calibration, Tracking et Mapping 3D (Nuage de points).

### 🎨 Sonia (Ingénieure Logiciel Mobile & Rendu 3D)
- **Vision Acquisition** : Flux Camera2 API pour le module de Narcisse.
- **UI/UX** : Interface de navigation, dashboard et contrôles tactiles.
- **Rendu 3D** : Affichage de la Map et de l'avatar (consomme les positions calculées par Lionel).

---

## 📂 Structure du Projet

### Code Android (Kotlin)
- `com.example.geo_slam.footslam` : Espace de Lionel (IA & Acquisition IMU).
- `com.example.geo_slam.vslam` : Espace de Narcisse (Vision).
- `com.example.geo_slam.ui` : Espace de Sonia (Rendu 3D & Interface).

---

## 📜 Charte de Collaboration Git

### Branches
- `main` : Stable, production uniquement.
- `develop` : Branche d'intégration.
- `feat/nom-tache` : Branches de travail personnel.

### Git LFS (Large File Storage)
Obligatoire pour les fichiers lourds : `*.tflite`, `*.so`, `*.a`, `*.pb`.
Assurez-vous d'avoir installé Git LFS : `git lfs install`.
