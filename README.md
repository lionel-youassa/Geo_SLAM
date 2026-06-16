# Geo-SLAM : Système de Localisation Hybride (FootSLAM IA & vSLAM)

Projet de localisation en environnement industriel (GPS-Denied) via smartphone, combinant odométrie inertielle par IA et vision par ordinateur.

## 🚀 Équipe & Responsabilités

### 🦁 Lionel (Lead IA, Data & FootSLAM - Scrum Master)
- **Acquisition Hardware** : Gestion du `SensorManager` (IMU 100Hz) et Baromètre.
- **Cœur IA** : Intégration RoNIN via TensorFlow Lite (C++/NDK).
- **Fusion & Filtrage** : Implémentation du Filtre de Kalman Étendu (EKF 3D).

### 👓 Narcisse (Ingénieur Vision & vSLAM)
- **Moteur SLAM** : Intégration ORB-SLAM3 pour le mapping 3D.

### 🎨 Sonia (Ingénieure Logiciel Mobile & Rendu 3D)
- **UI & Rendu** : Visualisation de la trajectoire et moteur de rendu 2D/3D (Canvas & MapRenderer).

---

## 🛠 Avancées du Projet (Juin 2026)

### 1. Moteur FootSLAM : Précision & Stabilité (Lionel)
- **Proportionnalité 1:1** : Recalibrage du `SCALE_FACTOR` (4.5) pour assurer que la distance virtuelle à l'écran correspond exactement à la distance physique (test validé sur 12m).
- **Indépendance de l'Orientation** : Implémentation du `remapCoordinateSystem` permettant un suivi identique en mode **Portrait** et **Landscape** (grand écran).
- **Anti-Drift (ZUPT)** : Détection de stationnarité par **variance d'accélération**, bloquant toute dérive rectiligne lorsque le téléphone est immobile en main.
- **Robustesse Android 15** : Mise en conformité avec l'**alignement 16 KB** et sécurisation des threads via `recursive_mutex`.

### 2. Interface Utilisateur : Responsive & Fluide (Sonia)
- **Architecture de Navigation** : Passage à un système de **Fragments** (`Splash` -> `Status` -> `Map`) géré par un `NavHostFragment`.
- **Rendu Responsive** : Le moteur `MapCanvasView` adapte désormais la taille des textes des salles (POI) dynamiquement en fonction du niveau de zoom de l'utilisateur.
- **Optimisation "Zéro Latence"** : Refonte de la boucle de dessin (`onDraw`) pour éliminer les allocations d'objets, garantissant un rendu fluide à 60 FPS.
- **Décor Industriel** : Intégration complète des cloisons, zones de production et points d'intérêt contrastés pour une lecture instantanée.

### 3. Rapport de Qualification (Lionel & Sonia)
- **Bilan de Conformité** : Validation des KPI avec une erreur relative de distance (ERD) stabilisée à **< 2.5%** et une erreur de fermeture de boucle de **0.82m**.

---

## 🛠 Détails Techniques du Cœur IA (Lionel)

1. **Acquisition IMU (100Hz)** : Indispensable pour capturer les micro-accélérations de la marche.
2. **Moteur NDK** : Traitement asynchrone pour éviter tout décalage entre le pas réel et l'avatar.
3. **Altitude 3D** : Formule hypsométrique via Baromètre pour la détection d'étage.
4. **Auto-Calibration** : Calcul automatique des biais IMU durant les 4 premières secondes de repos.

---

## 📂 Structure du Projet
- `app/src/main/cpp/footslam` : Moteur C++ (Lionel)
- `com.example.geo_slam.footslam` : Acquisition Kotlin (Lionel)
- `com.example.geo_slam.ui.map` : Véritable Interface Responsive (Sonia)
