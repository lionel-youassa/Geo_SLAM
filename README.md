# Geo-SLAM : Système de Localisation Hybride (FootSLAM IA & vSLAM)

**Geo-SLAM** est une solution de pointe pour le positionnement en intérieur dans des environnements industriels où le signal GPS est indisponible (usines, entrepôts, complexes souterrains). Le projet fusionne l'intelligence artificielle, l'odométrie inertielle et le filtrage spatial pour offrir un suivi fluide, précis et réactif.

## 🎯 Contexte et Objectifs

Dans les grands complexes industriels, la navigation est critique mais complexe en raison des interférences métalliques et de l'absence de GPS. L'objectif de Geo-SLAM est de fournir une alternative robuste capable de :
*   **Localiser avec précision** sans infrastructure externe (balises, Wi-Fi).
*   **Garantir une réactivité maximale** (latence zéro) entre le mouvement réel et le curseur à l'écran.
*   **Synchroniser parfaitement** la détection des pas avec la distance virtuelle parcourue.
*   **Respecter les contraintes spatiales** en utilisant uniquement le périmètre extérieur comme limite infranchissable, tout en permettant une libre circulation entre les zones internes.

## 🏗 Architecture du Projet

Le projet repose sur une architecture hybride Kotlin/C++ (NDK) optimisée pour la performance.

### 1. Acquisition et Gestion (Kotlin)
*   **FootSlamManager.kt** : Gère l'acquisition IMU à 100Hz et diffuse les flux de position via `StateFlow`.
*   **Logique de Plan** : Filtre les données cartographiques pour ne transmettre au moteur que les `outerWalls` (murs extérieurs), libérant ainsi le mouvement entre les pièces.

### 2. Moteur de Calcul Natif (C++/NDK)
*   **Inférence IA (TensorFlow Lite)** : Intègre un modèle RoNIN pour transformer les accélérations brutes en vecteurs de vitesse.
*   **Calibration V14 (Boost +30%)** : Paramétrage agressif (`SCALE_FACTOR = 5.5`, `alpha = 0.95`) pour supprimer tout retard de traitement.
*   **Synchronisation des Pas** : Système hybride (Hardware + Fallback par distance à 0.50m/pas) pour une cohérence totale du compteur.
*   **Moteur de Collision** : Bloque le pion contre les limites périmétriques globales.

### 3. Interface et Rendu (UI)
*   Rendu fluide à 60 FPS via `MapCanvasView`.
*   Visualisation responsive s'adaptant dynamiquement au niveau de zoom.

## 🧪 Comment tester le projet ?

Pour obtenir les meilleurs résultats, suivez cette procédure :

### 1. Initialisation et Calibration
*   **Sélection du point initial** : Vous pouvez définir votre point de départ exact en effectuant un **appui long** (quelques secondes) sur la zone spécifique de la carte où vous vous trouvez.
*   **Calibration (4s)** : Une fois positionné, restez immobile pendant les 4 premières secondes. Le système calculera automatiquement les biais des capteurs pour annuler la dérive (drift).

### 2. Test de Déplacement
*   Commencez à marcher normalement.
*   **Réactivité** : Observez le pion. Il doit coller instantanément à vos pas.
*   **Compteur** : Vérifiez que chaque pas physique est comptabilisé et synchronisé avec l'avance du curseur.

### 3. Test de Navigation Inter-Zones
*   Traversez les frontières entre deux pièces ou zones. Le pion doit circuler librement.
*   Dirigez-vous vers les bords de la carte : le pion doit être stoppé net par le périmètre extérieur.

### 4. Configuration Technique
*   **Android 15+** (Alignement 16 KB requis).
*   **Capteurs** : Accéléromètre, Gyroscope (obligatoires) et Baromètre (recommandé).
