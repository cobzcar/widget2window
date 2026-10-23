# Widgets Flottants

Application Android qui transforme le widget d'une autre application en fenêtre flottante (par-dessus les autres applis), déplaçable et redimensionnable.

## Compiler et installer

1. Ouvrir Android Studio (Koala ou plus récent) → **File > Open** → choisir le dossier `WidgetFlottant`.
2. Laisser la synchronisation Gradle se terminer (Android Studio propose de configurer Gradle tout seul).
3. Brancher le téléphone (débogage USB activé) puis **Run ▶**, ou **Build > Build APK(s)** pour obtenir un fichier à installer.

Config : minSdk 26 (Android 8.0), cible Android 14, Kotlin, aucune dépendance externe.

## Utilisation

1. Au premier lancement, accorder l'autorisation « Affichage par-dessus les autres applis » (et les notifications sur Android 13+).
2. **Choisir un widget** : la liste présente tous les widgets des applis installées. Android peut demander une confirmation pour lier le widget, puis afficher son écran de configuration s'il en a un.
3. Le widget apparaît dans une fenêtre flottante :
   - **≡ déplacer** (barre du haut) : glisser pour déplacer ;
   - **◢** (coin en bas à droite) : glisser pour redimensionner (le widget est prévenu de sa nouvelle taille) ;
   - **✕** : fermer et retirer le widget ;
   - ces commandes se superposent au widget, disparaissent 0,7 s après le dernier toucher et réapparaissent dès qu'on touche la fenêtre.
4. Positions et tailles sont mémorisées. **Tout masquer** (dans l'appli ou la notification) ferme les fenêtres sans oublier les widgets ; **Afficher les fenêtres** les rouvre.

## Structure

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Permissions, sélecteur de widgets, liste des widgets enregistrés |
| `FloatingWidgetService.kt` | Service au premier plan : héberge les widgets (`AppWidgetHost`) et gère les fenêtres |
| `FloatingWindow.kt` | Une fenêtre : barre de déplacement, poignée de redimensionnement, bouton fermer |
| `WidgetStore.kt` | Sauvegarde des widgets, positions et tailles |

## Limites connues

- Les widgets sont des `RemoteViews` : seules les interactions prévues par l'application d'origine fonctionnent (boutons, clics simples).
- Certains constructeurs (Xiaomi, Samsung, Huawei, Oppo…) arrêtent les services en arrière-plan : désactiver l'optimisation de batterie pour cette appli si les fenêtres disparaissent.
- Certains widgets ouvrent leur appli au clic : selon la version d'Android, ce lancement depuis une fenêtre flottante peut être restreint.
