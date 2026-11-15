# Recette du lot 2 — résistance au réseau

Deux parties : la lecture (coupures réseau, reprise) puis la bibliothèque (livres ajoutés ou retirés du Drive). Pour chaque point : ✅ conforme, ⚠️ avec remarque, ❌ non conforme (décrire).


À fusionner dans `docs/recettes/lot-2.md` (avec `lot-2-bibliotheque.md`). Critères du lot : mode avion 2 min en pleine lecture → aucune coupure ; passage au fichier suivant sans attente ; coupure longue → « En attente du réseau… » puis reprise seule ; position intacte dans tous les cas.

**Durée** : environ 30 minutes. **Matériel** : le téléphone, le Wi-Fi, des écouteurs (ou le haut-parleur), et le panneau rapide d'Android pour le **mode avion**.

**Livres à utiliser** : un livre **multi-fichiers du Drive commun**, jamais téléchargé (les chapitres sont des fichiers de 5 à 15 min). Pour le test 4, un livre ou un fichier **que vous n'avez pas encore écouté** (rien en cache).

Notez ✅ / ⚠️ / ❌ pour chaque point, en précisant ce que vous avez vu. Toutes les durées sont approximatives.

---

## 1. Mode avion 2 minutes en pleine lecture

| # | À faire | Résultat attendu |
|---|---|---|
| 1.1 | Sur le Wi-Fi, lancez le livre au début d'un chapitre et écoutez **2 minutes** (le temps que l'appli charge la suite). | Lecture normale. |
| 1.2 | Activez le **mode avion** (Wi-Fi coupé aussi). Laissez la lecture continuer **2 minutes**. | **Aucune coupure**, aucun message d'erreur, pas de « Chargement… ». Le son ne s'interrompt pas. |
| 1.3 | Désactivez le mode avion. | La lecture continue sans à-coup. La position affichée est cohérente (pas de retour au début). |

## 2. Passage au fichier suivant sans attente

| # | À faire | Résultat attendu |
|---|---|---|
| 2.1 | Lecture sur Wi-Fi, avancez (barre du chapitre, ou avance 30 s) jusqu'à **environ 30 secondes avant la fin** d'un fichier. Laissez passer la fin. | Le chapitre suivant démarre **sans blanc** ni « Chargement… », titre du chapitre mis à jour. |
| 2.2 | Recommencez en **mode avion** : écoutez 1 minute sur Wi-Fi, activez le mode avion, puis passez au **chapitre suivant** (bouton), puis au suivant. | Les 3 ou 4 chapitres qui suivent votre position démarrent **immédiatement**, sans attente (la suite de l'écoute — jusqu'à environ une heure — est gardée sur le téléphone). |

## 3. Coupure longue : « En attente du réseau… » puis reprise seule

| # | À faire | Résultat attendu |
|---|---|---|
| 3.1 | Lecture sur Wi-Fi. Activez le **mode avion**, puis avancez dans le livre **bien au-delà** de ce qui a été chargé (une partie éloignée, par exemple en choisissant un chapitre situé 3 heures plus loin dans la liste des chapitres). | La lecture s'arrête. Le lecteur affiche **« En attente du réseau… — N min disponibles »** (N = ce qu'il reste à écouter sans réseau à partir de là, souvent « 0 s » ici). |
| 3.2 | Sans toucher à l'application, regardez : le **mini-lecteur** (retour à la bibliothèque), la **notification** et l'**écran de verrouillage**. | Le même texte apparaît partout : mini-lecteur à la place du titre de chapitre, notification sur la 2ᵉ ligne (à la place de l'auteur). |
| 3.3 | Attendez 1 minute, puis désactivez le mode avion. | Dans les secondes qui suivent, la lecture **repart toute seule**, sans appui. Le texte d'attente disparaît. |
| 3.4 | Notez la position avant et après. | Elle est la **même, ou légèrement en arrière** de 3 à 10 secondes pour une coupure de 1 à 5 minutes (retour arrière de reprise), jamais au début du livre. |
| 3.5 | Refaites 3.1 mais, **pendant** l'attente, appuyez sur **Pause**, puis désactivez le mode avion. | La lecture **ne repart pas** : elle reste en pause. Un appui sur **Lecture** la relance normalement (une seule fois suffit). |
| 3.6 | Refaites 3.1 mais, pendant l'attente, **lancez un autre livre**. Désactivez le mode avion. | Le nouveau livre se lit normalement. L'ancien ne redémarre pas. |

## 4. Position intacte

| # | À faire | Résultat attendu |
|---|---|---|
| 4.1 | Pendant une attente réseau (comme en 3.1), relevez la position puis **fermez l'application** d'un balayage dans la liste des applications récentes. Remettez le réseau, rouvrez l'application, appuyez sur **Reprendre**. | La position est celle d'avant la fermeture (à quelques secondes près). |
| 4.2 | Refaites 3.1 et, avant de remettre le réseau, **redémarrez le téléphone**. Rétablissez le réseau et relancez le livre. | Même position qu'avant le redémarrage. |
| 4.3 | Après toutes ces manipulations, ouvrez la bibliothèque. | Le livre affiche une progression cohérente (jamais à 0 % alors que vous aviez écouté). |

## 5. Réseau instable (optionnel)

| # | À faire | Résultat attendu |
|---|---|---|
| 5.1 | Lecture sur un Wi-Fi faible, ou activez/désactivez le mode avion plusieurs fois à 10 s d'intervalle. | Pas de plantage, pas de retour à zéro, lecture qui reprend d'elle-même à chaque retour du réseau. |
| 5.2 | Passez de Wi-Fi à **données mobiles** en cours d'écoute. | Aucune coupure. (Sur données mobiles, l'appli ne garde que 30 minutes d'avance au lieu de 60.) |

## 6. Fichier introuvable (optionnel, sur un livre de test)

Ne le faites pas sur un livre que vous aimez : il faut **supprimer définitivement** un fichier dans Google Drive (corbeille vidée) alors qu'il n'est pas en cache.

| # | À faire | Résultat attendu |
|---|---|---|
| 6.1 | Lancez le chapitre dont le fichier a été supprimé de Drive. | Le lecteur affiche **« Fichier introuvable »**. L'appli ne se ferme pas et la position enregistrée du livre n'est pas remise à zéro. |

---

## En cas de problème

Notez l'heure, le texte exact affiché (« En attente du réseau… — … » ou autre) et ce que vous faisiez. Joignez le journal : menu ⋮ → Outils de diagnostic → Copier le journal.

---


À fusionner dans `docs/recettes/lot-2.md`. Prérequis : application installée, deux comptes connectés, au moins une racine surveillée déjà parcourue (la bibliothèque affiche vos livres).

## 1. Un livre ajouté apparaît vite

1. Sur l'ordinateur, dans Google Drive (site web), ajoutez dans une racine surveillée un petit dossier contenant 2 fichiers audio (ou copiez un livre existant dans un autre dossier de la racine).
2. Sur le téléphone, ouvrez l'application (ou tirez la bibliothèque vers le bas pour rafraîchir).
3. Attendu : en quelques secondes à une minute, le nouveau livre apparaît (titre déduit du nom du dossier) ; les couvertures et durées arrivent ensuite, comme avant.

## 2. Un livre mis à la corbeille disparaît

1. Dans Google Drive, mettez ce dossier à la corbeille.
2. Rafraîchissez dans l'application.
3. Attendu : le livre disparaît de la bibliothèque (il est seulement masqué, jamais supprimé de la base).

## 3. Restauré, il revient avec sa progression

1. Avant l'étape 2, écoutez quelques minutes de ce livre (une progression existe).
2. Restaurez le dossier depuis la corbeille de Google Drive, puis rafraîchissez.
3. Attendu : le livre revient avec la même position d'écoute et les mêmes marque-pages.

## 4. Un changement sans rapport ne fait rien

1. Modifiez ou créez un document quelconque dans Drive, hors des dossiers de livres.
2. Rafraîchissez : rien ne change dans la bibliothèque, et le rafraîchissement est quasi instantané.

## 5. Coupure réseau pendant la mise à jour

1. Ajoutez un livre dans Drive, passez le téléphone en mode avion, rafraîchissez.
2. Attendu : aucun livre ne disparaît ; rien ne change.
3. Désactivez le mode avion et rafraîchissez : le nouveau livre apparaît (les changements ne sont pas perdus).

## 6. Contrôle hebdomadaire (vérification indirecte)

Au plus tard 7 jours après le dernier parcours complet d'une racine, la tâche périodique (toutes les 12 h) relit entièrement la racine. Test possible : déplacer un dossier intermédiaire (ni auteur ni livre, par exemple un regroupement) hors de la racine puis attendre ; les livres concernés passent en « absents » au plus tard à ce contrôle.

## Journal de diagnostic

Un parcours « complet » forcé existe côté code (`LibraryScanScheduling.requestFullScanNow`) pour le diagnostic ; il n'est pas encore relié à un bouton.
