# Recette lot 2 — Bibliothèque (mise à jour incrémentale)

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
