# Geo-SLAM : Système de Localisation Hybride (FootSLAM IA & vSLAM)

Projet de localisation en environnement industriel (GPS-Denied) via smartphone, combinant odométrie inertielle par IA et vision par ordinateur.

## 🚀 Équipe & Responsabilités

### 🦁 Lionel (Lead IA, Data & FootSLAM - Scrum Master)
- **Acquisition Hardware** : Gestion directe du `SensorManager` (IMU à 100Hz) pour alimenter l'IA.
- **Cœur IA** : Intégration modèles RoNIN / TLIO via TensorFlow Lite.
- **Fusion** : Filtre de Kalman (EKF) pour le lissage de trajectoire et calcul du (X, Y, Z).

### 👓 Narcisse (Ingénieur Vision & vSLAM)
- **Vision Acquisition** : Flux Camera2 API et gestion du flux d'images pour le SLAM.
- **Moteur SLAM** : Intégration ORB-SLAM3 (C++/NDK).
- **Géométrie** : Calibration, Tracking et Mapping 3D (Nuage de points).

### 🎨 Sonia (Ingénieure Logiciel Mobile & Rendu 3D)
- **UI/UX** : Interface de navigation, dashboard et contrôles tactiles.
- **Rendu 3D** : Affichage de la Map et de l'avatar (consomme les positions de Lionel et le nuage de points de Narcisse).

---

## 🛠 Journal de Développement : Lionel (FootSLAM IA)

Voici les étapes majeures réalisées pour la mise sur pied du moteur de localisation inertielle :

### 1. Acquisition IMU Haute Fréquence (100Hz)
- **Technique** : Utilisation du `SensorManager` avec `SENSOR_DELAY_FASTEST` (10ms).
- **Justification** : Les modèles de Deep Learning (RoNIN) nécessitent une résolution temporelle fine pour capturer les micro-mouvements de la marche humaine et réduire le bruit d'intégration.

### 2. Intégration RoNIN via TFLite (C++/JNI)
- **Technique** : Implémentation du moteur d'inférence en C++ pour minimiser la latence.
- **Justification** : Le passage par le NDK permet de traiter les fenêtres glissantes de 200 échantillons sans bloquer le thread UI de l'application.

### 3. Couche de Contraintes Physiques
- **Technique** : Algorithme de bridage de la vitesse (`MAX_STEP_LIMIT`).
- **Justification** : Élimination des "sauts" de position aberrants prédits par l'IA lors de changements brusques d'orientation ou d'interférences magnétiques.

### 4. Filtre de Kalman Étendu (EKF) & Prédiction Inertielle
- **Technique** : Fusion statistique entre les prédictions IA et l'odométrie IMU.
- **Justification** : 
    - **Fluidité** : La prédiction à 100Hz permet un rendu visuel fluide (60 FPS+) pour Sonia, même si l'IA tourne à une fréquence plus basse.
    - **Robustesse** : Le Gain de Kalman pondère la confiance accordée à l'IA selon l'incertitude accumulée.

### 5. Altitude 3D (Baromètre) & ZUPT (Zero Velocity Update)
- **Technique** : Intégration du capteur de pression et détection d'immobilité par seuillage Gyro/Acc.
- **Justification** : 
    - **Verticalité** : La formule barométrique permet la gestion des escaliers et des changements d'étages en usine.
    - **ZUPT** : Arrête instantanément la dérive (drift) de position lorsque l'utilisateur est immobile, garantissant la stabilité de l'avatar sur la carte.

---

## 📂 Structure du Projet

### Code Android (Kotlin)
- `com.example.geo_slam.footslam` : Espace de Lionel (IA & Acquisition IMU).
- `com.example.geo_slam.vslam` : Espace de Narcisse (Vision & Caméra).
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

---

## 🛠 Guide des Commandes Git Essentielles

### 1. Gestion des Branches
- `git checkout -b feat/<prenom>-<nom-de-la-tache>` : Crée une nouvelle branche.

### 2. Publication
- `git push origin <nom-de-la-branche>` : Envoie vos commits vers GitHub.

> **Note** : Toujours faire un `git pull origin develop` avant de fusionner votre travail.
