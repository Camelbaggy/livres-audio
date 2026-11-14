# Formes de noms observées dans les Drive de l'utilisateur

Synthèse des arborescences fournies le 2026-11-14 (prérequis partie D). Elle sert de référence aux tâches 1.4 (règles dossier → livre, analyse des noms) et 3.x (informations en ligne).

**Les exemples ci-dessous sont inventés** : ils reproduisent la forme des noms réels, jamais les titres réels (CLAUDE.md §11.2, point 3). Les tests doivent suivre la même règle.

## Vue d'ensemble

| | Drive personnel | Drive commun |
|---|---|---|
| Racine surveillée | `Documents/Livres audio` | `Famille/Livres audio` |
| Volume | ≈ 230 livres | ≈ 15 livres, ≈ 970 fichiers |
| Format dominant | **un fichier par livre** (`.m4b`, parfois `.mp3`) | **un dossier par livre**, un `.mp3` par chapitre |
| Langue des titres | anglais | français |
| Profondeur | Auteur / [Série \| Sous-série numérotée] / fichiers | Série / livre / fichiers |

Le Drive personnel de la compagne est vide pour l'instant mais pourra contenir des livres.

## Cas 1 — Dossier contenant plusieurs livres d'un seul fichier (cas dominant, Drive personnel)

```
Auteur Exemple/
  La Saga des Brumes/
    La Saga des Brumes - 1 - Le Premier Pas.m4b
    La Saga des Brumes - 0 - Avant le commencement.m4b
    La Saga des Brumes - 10 - Le Retour.m4b
    La Saga des Brumes - 3.5 - Une nouvelle.m4b
```

- **Chaque fichier est un livre complet**, pas un chapitre. La règle §5.2-1 d'origine (« un dossier contenant de l'audio = un livre ») donnerait ici un seul livre de 15 fichiers : c'est faux. Voir la règle de décision plus bas.
- Forme du nom : `Série - N - Titre`. N peut être `0`, entier, décimal (`3.5`, `5.1`, `1.6`). L'ordre de listage de Drive n'est pas l'ordre des tomes.
- Variante sans titre : `Série - 1.m4b`, `Série - 2.m4b`.
- Formats mélangés dans une même série : `.m4b` et `.mp3` d'un seul fichier.

## Cas 2 — Dossier d'auteur mêlant livres isolés et séries

```
Auteur Exemple/
  Un roman isolé.m4b
  Autre Roman.mp3
  11 22 33.m4b                          ← titre fait de nombres : pas un numéro de piste
  Cycle Sombre - 2 - La Suite.m4b       ← série reconnue par le nom du fichier
  Cycle Sombre - 1 - Le Début.m4b
  La Grande Quête/                      ← sous-dossier de série
    La Grande Quête - 1 - Départ.m4b
```

- Le dossier d'auteur est un **regroupement** (indice d'auteur), même s'il contient aussi des fichiers audio directement.
- Plusieurs séries peuvent cohabiter dans le même dossier, distinguées seulement par le préfixe du nom de fichier.
- Des fichiers non audio sont présents (`.gsheet`, `description.txt`) : à ignorer.

## Cas 3 — Saga découpée en sous-séries numérotées

```
Auteur Exemple/
  1 - Planète Sable/
    Planète Sable - 1 - Planète Sable.m4b
  5 - Préludes/
    Préludes - 5.1 - Maison Une.m4b
    Préludes - 5.2 - Maison Deux.m4b
```

- Le préfixe `N - ` du dossier est l'ordre de la sous-série dans la saga, pas un numéro de tome.
- Dans les fichiers, `5.1` signifie « sous-série 5, tome 1 ». Pour le tri à l'intérieur d'une série, prendre le nombre tel quel (5.1 < 5.2 < 5.3).

## Cas 4 — Un dossier par livre, un fichier par chapitre (Drive commun)

```
Série Exemple/                                         ← regroupement = série
  Auteure Exemple - Série Exemple 1/                   ← tome sans titre
  Auteure Exemple - Série Exemple 2 - Le Second/
  Auteure Exemple - Série Exemple 3 5 - Le Hors-série/ ← « 3 5 » = tome 3.5
  Auteure Exemple - Série Exemple 0 - Les Origines/
```

Le nom du dossier a la forme `Auteur - Série N - Titre`, sans tiret entre la série et le numéro. La casse de la série varie d'un dossier à l'autre (`Verre` / `verre`) : normaliser avant de regrouper.

Formes des fichiers de chapitre rencontrées (toutes dans un même Drive) :

| Forme | Exemple inventé | Ordre correct |
|---|---|---|
| `NN - Chapitre N.mp3` | `07 - Chapitre 7.mp3` | par NN |
| `NN - Prologue.mp3`, `NN - Remerciements.mp3` | `00 - Prologue.mp3`, `81 - Remerciements.mp3` | par NN |
| `NN Titre - Auteur Chapitre MM.mp3` | `15 Le Second - Auteure Exemple Chapitre 14.mp3` (NN = MM + 1, `01 … Présentation`) | par NN |
| `NN Auteur Titre Chapitre NN.mp3` | `07 Auteure Exemple Le Second Chapitre 07.mp3` | par NN |
| `Auteur - Série N- Titre Chapitre NN.mp3` | `Auteure Exemple - Series Example 4- Le Quatrième Chapitre 05.mp3` (pas de numéro en tête) | par le nombre final |
| `Titre_ Série, tome N - NN - Chapitre N.mp3` | `Le Sixième_ Series Example, tome 6 - 09 - Chapitre 9.mp3` | par NN (le « 6 » du tome est commun à tous) |
| `NNN-Chapitre N.mp3`, chapitres coupés en deux | `002-Chapitre 2-1.mp3`, `002-Chapitre 2-2.mp3`, `141 - Chapitre 141` | NNN puis la partie |

Le **tri naturel** sur le nom complet donne le bon ordre dans tous ces cas, car le préfixe commun est identique d'un fichier à l'autre. Il faut aussi garder `00 - Prologue` avant `01`.

Les noms de dossier et de fichier mêlent anglais et français (série anglaise, titre français).

## Règle de décision « un livre ou plusieurs ? » (adoptée, CLAUDE.md §5.2)

Pour un dossier contenant directement au moins deux fichiers audio :

1. On retire de chaque nom l'extension, puis le **préfixe et le suffixe communs** à tous les fichiers.
2. On retire de ce qui reste les nombres, la ponctuation et les mots génériques : `chapitre`, `chapter`, `chap`, `partie`, `part`, `piste`, `track`, `disque`, `disc`, `cd`, `prologue`, `épilogue`, `epilogue`, `introduction`, `présentation`, `remerciements`, `crédits`, `credits`, `annexe`, `interlude`.
3. Si, pour **tous** les fichiers, il ne reste rien, les fichiers sont les **chapitres d'un seul livre** (cas 4).
4. Sinon, si au moins deux fichiers ont un reste différent et non vide, **chaque fichier est un livre** (cas 1 et 2). Les fichiers sont alors regroupés en série d'après la forme `Série - N - Titre`.
5. En cas de doute, si un seul fichier a un reste non vide : chapitres d'un seul livre. Le titre de chapitre vient alors du reste.

Vérification sur les cas ci-dessus :

- `La Saga des Brumes - 1 - Le Premier Pas` / `- 2 - La Suite`. Le préfixe commun est `La Saga des Brumes - `. Les restes, `Le Premier Pas` et `La Suite`, sont différents, donc plusieurs livres.
- `07 - Chapitre 7` / `81 - Remerciements` / `00 - Prologue`. Il n'y a pas de préfixe commun. Les restes ne contiennent que des nombres et des mots génériques, donc un seul livre.
- `Le Sixième_ Series Example, tome 6 - 09 - Chapitre 9`. Le préfixe commun est `Le Sixième_ Series Example, tome 6 - `, il ne reste que `09 - Chapitre 9`, donc un seul livre.

## Informations en ligne (lot 3)

Les livres du Drive personnel sont **en anglais**. La recherche en ligne ne doit donc pas être limitée au français. Elle choisira le catalogue selon la langue détectée du titre, ou tentera d'abord le français puis l'anglais (Audible US/UK, iTunes US). Ce point est à trancher au lot 3, il est noté au §6.
