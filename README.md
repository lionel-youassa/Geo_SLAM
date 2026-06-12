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
- **UI & Rendu** : Visualisation de la trajectoire et dashboard Android.

---

## 🛠 Journal de Développement : Lionel (FootSLAM IA)

Voici les étapes techniques majeures réalisées pour la mise sur pied du moteur :

### 1. Acquisition IMU Haute Fréquence (100Hz)
- **Technique** : Utilisation du `SensorManager` en mode `SENSOR_DELAY_FASTEST`.
- **Justification** : Indispensable pour le modèle RoNIN afin de capturer les micro-accélérations de la marche humaine et éviter le repliement spectral (aliasing).

### 2. Moteur d'Inférence RoNIN (C++/NDK)
- **Technique** : Traitement asynchrone par fenêtres glissantes de 200 échantillons (2 sec).
- **Justification** : Le C++ permet un traitement en temps réel sans latence, crucial pour éviter tout décalage entre le mouvement réel et l'avatar.

### 3. Couche de Contraintes Physiques
- **Technique** : Algorithme de bridage de vitesse par `MAX_STEP_LIMIT`.
- **Justification** : Sécurité contre les "hallucinations" de l'IA lors de mouvements brusques ou de chocs, garantissant une trajectoire fluide.

### 4. Filtre de Kalman Étendu (EKF) & Prédiction 100Hz
- **Technique** : Fusion statistique entre l'IMU (prédiction) et l'IA (correction).
- **Justification** : Permet un rendu à 100 FPS (très fluide) pour Sonia, tout en corrigeant la dérive naturelle de l'IMU par les prédictions globales de l'IA.

### 5. Altitude 3D & Baromètre
- **Technique** : Formule hypsométrique pour convertir la pression en mètres relatifs.
- **Justification** : Seule méthode fiable en intérieur (GPS-Denied) pour détecter les changements d'étages et les déplacements dans la dimension Z.

### 6. Zero Velocity Update (ZUPT)
- **Technique** : Détection de l'état stationnaire via seuillage adaptatif Gyro/Acc.
- **Justification** : Arrête instantanément l'accumulation d'erreurs (drift) lorsque l'utilisateur est immobile, stabilisant la position sur la carte.

### 7. Auto-Calibration des Biais
- **Technique** : Calcul automatique des offsets IMU au repos (2 premières secondes).
- **Justification** : Élimine les dérives matérielles systématiques des capteurs du smartphone, augmentant radicalement la précision long-terme.

### 8. Transformation de Repère (Body-to-World)
- **Technique** : Projection des vecteurs de déplacement via Quaternions de rotation.
- **Justification** : Garantit que si le téléphone tourne mais que l'utilisateur marche droit, la trajectoire sur la carte reste alignée avec le monde réel.

---

## 📂 Structure du Projet
- `app/src/main/cpp/footslam` : Moteur C++ (Lionel)
- `com.example.geo_slam.footslam` : Acquisition Kotlin (Lionel)
- `com.example.geo_slam.ui` : Interface Sonia
