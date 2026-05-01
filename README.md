# Geo-SLAM : Système de Localisation Hybride (FootSLAM IA & vSLAM)

Projet de localisation en environnement industriel (GPS-Denied) via smartphone, combinant odométrie inertielle par IA et vision par ordinateur.

## 🚀 Équipe & Responsabilités

### 🦁 Lionel (Lead IA, Data & FootSLAM - Scrum Master)
- **Cœur IA** : Intégration modèles RoNIN / TLIO via TensorFlow Lite.
- **Vision Inférence** : Déploiement SuperPoint et NetVLAD.
- **Fusion** : Filtre de Kalman (EKF) pour le lissage de trajectoire.

### 👓 Narcisse (Ingénieur Vision & vSLAM)
- **Moteur SLAM** : Intégration ORB-SLAM3 (C++/NDK).
- **Géométrie** : Calibration, Tracking et Mapping 3D (Nuage de points).
- **C++ Core** : Optimisation des calculs matriciels.

### 🎨 Sonia (Ingénieure Logiciel Mobile & Rendu 3D)
- **Hardware** : Acquisition Camera2 API et SensorManager (100Hz).
- **UI/UX** : Interface de navigation et dashboard temps réel.
- **Rendu 3D** : Affichage de la carte via OpenGL ES / Google Filament.

---

## 📂 Structure du Projet

### Code Android (Kotlin)
- `com.example.geo_slam.footslam` : Espace de Lionel.
- `com.example.geo_slam.vslam` : Espace de Narcisse.
- `com.example.geo_slam.ui` : Espace de Sonia (Acquisition et Interface).

### Code Natif (C++ NDK)
- `app/src/main/cpp/footslam/` : Traitement IMU / IA.
- `app/src/main/cpp/vslam/` : Pipeline Vision.
- `app/src/main/cpp/ui_render/` : Moteur de rendu 3D.

---

## 📜 Charte de Collaboration Git

### Branches
- `main` : Stable, production uniquement.
- `develop` : Branche d'intégration.
- `feat/nom-tache` : Branches de travail personnel.

### Workflow
1. Toujours partir de `develop` à jour.
2. Commits explicites (ex: `feat: ajout du filtre de Kalman`).
3. **Pull Request obligatoire** pour fusionner vers `develop`.
4. Au moins **1 approbation** d'un collègue requise.

### Git LFS (Large File Storage)
Obligatoire pour les fichiers lourds : `*.tflite`, `*.so`, `*.a`, `*.pb`.
Assurez-vous d'avoir installé Git LFS sur votre machine : `git lfs install`.

---

## 🛠 Tech Stack
- **Langages** : Kotlin, C++ (NDK).
- **IA** : TensorFlow Lite.
- **Vision** : ORB-SLAM3, SuperPoint.
- **Rendu** : OpenGL ES / Filament.
