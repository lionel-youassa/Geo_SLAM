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

Ce guide regroupe les commandes fondamentales selon notre flux de travail.

### 1. Gestion des Branches (Navigation & Création)
- `git branch` : Liste les branches locales. (`*` indique la branche actuelle).
- `git checkout <nom-de-la-branche>` : Bascule sur une branche existante.
- `git checkout -b feature/<prenom>-<nom-de-la-tache>` : Crée une nouvelle branche et y bascule.
  - *Exemple* : `git checkout -b feature/lionel-local-routes-injection`

### 2. Synchronisation avec le Dépôt Distant
- `git pull origin develop` : Récupère et fusionne les dernières modifications du serveur dans votre branche.

### 3. Enregistrement des Modifications (Commits)
- `git add .` : Ajoute tous les fichiers modifiés à la zone de staging.
- `git commit -m "<type>(<portée>): <description>"` : Enregistre l'instantané de votre code.
  - *Types* : `feat`, `fix`, `docs`, `refactor`, `style`.
  - *Exemple* : `git commit -m "feat(navigation): complete french instructions parser"`

### 4. Intégration des Changements (Merge)
- `git merge develop` : Fusionne les nouveautés de la branche `develop` dans votre branche active (indispensable pour rester à jour).

### 5. Publication vers le Serveur Distant
- `git push origin <nom-de-la-branche>` : Envoie vos commits vers GitHub avant de créer une Pull Request.
  - *Exemple* : `git push origin feature/lionel-navigation-parser`

> **Note** : Avant chaque push, effectuez toujours un pull de la branche principale pour résoudre les conflits localement.
