# CLAUDE.md — Application privée de livres audio (Google Drive, Android)

Ce fichier est le **plan d'action de référence** du projet. Il est lu par le chef de projet (Opus) et par les sous-agents d'exécution (Sonnet). Il fait autorité sur l'architecture, les décisions et l'ordre des travaux. Le besoin d'origine est dans `docs/cahier-des-charges.md` (les renvois « CdC §x.y » y font référence).

**Règle d'or : ne jamais perdre la position d'écoute.** En cas d'arbitrage, la fiabilité passe avant les fonctions, et la simplicité avant l'exhaustivité (CdC §5).

---

## 0. Mode d'emploi de ce document

| Rôle | Ce qu'il doit lire | Ce qu'il fait |
|---|---|---|
| Chef de projet (Opus) | Tout | Suit les lots du §9 dans l'ordre, découpe en tâches, délègue, relit, intègre, tient à jour le §13 |
| Sous-agent (Sonnet) | §1 à §4, la section technique citée dans son brief, §11 | Exécute **une** tâche bornée, avec tests, sans élargir le périmètre |

- Les décisions marquées **[DÉCIDÉ]** ne se rediscutent pas sans demande explicite de l'utilisateur.
- Les points marqués **[À VÉRIFIER]** reposent sur des connaissances qui peuvent avoir évolué (API tierces, bibliothèques, tarifs). Ils doivent être vérifiés dans la documentation officielle à jour **avant** d'écrire le code concerné ; le résultat est consigné au §13.
- Une tâche qui contredit ce document : s'arrêter et remonter au chef de projet, qui tranche ou interroge l'utilisateur.
- Langue : documentation, messages de commit et textes d'interface en **français** ; identifiants de code en **anglais**.

---

## 1. Le produit en bref

Lecteur de livres audio Android pour **deux personnes** (l'utilisateur et sa compagne), usage strictement privé, jamais publié. Les livres sont dans Google Drive : un **Drive commun** (compte Google familial) et un **Drive personnel** par personne. Chaque téléphone connecte le compte commun + le compte personnel de son propriétaire.

Deux livrables indépendants :

1. **L'application Android** (`app/` et modules `core/`) — bibliothèque + lecteur, remplace CloudBeats.
2. **L'atelier de synthèse vocale** (`tts/`) — outil en ligne de commande sur ordinateur qui transforme un EPUB sans DRM en livre audio et le dépose dans le Drive commun. Projet mené en parallèle, sans dépendance de code avec l'application.

### Hors périmètre (ne pas développer)

iOS, tablette/TV/montre, publication sur le Play Store, serveur ou backend à entretenir, comptes utilisateurs maison, autres clouds que Google Drive, lecture de fichiers locaux hors cache/téléchargements, podcasts, statistiques d'écoute, égaliseur, contournement de DRM, génération de voix **dans** l'application.

---

## 2. Décisions structurantes [DÉCIDÉ]

| # | Décision | Raison |
|---|---|---|
| D1 | Application **native Kotlin**, Jetpack Compose, **Media3/ExoPlayer** | Seule voie fiable pour lecture en arrière-plan, Bluetooth, Android Auto, cache disque |
| D2 | **Aucun serveur.** Toutes les données partagées ou sauvegardées vivent dans Google Drive | Coût nul, rien à entretenir (CdC §5, §8) |
| D3 | **Le profil d'une personne = son compte Google personnel.** Ses positions, marque-pages et réglages sont sauvegardés dans le dossier applicatif caché (`appDataFolder`) de ce compte | Séparation stricte sans mécanisme de comptes ; restauration automatique après changement de téléphone |
| D4 | **Les données partagées** (dossiers surveillés du Drive commun, informations et couvertures corrigées, prononciation) vivent dans un dossier `.livres-audio/` à la racine du **Drive commun** | Les deux téléphones y accèdent déjà ; le second téléphone n'a rien à configurer |
| D5 | Identité d'un livre = `bookId` stable (UUID), **indépendant de l'identifiant de dossier Drive** ; rattachement par empreinte de contenu (sommes MD5 fournies par Drive) | Un livre déplacé entre Drive personnel et commun garde progression et corrections |
| D6 | Le **déplacement d'un livre** se fait dans Google Drive (appli ou site Drive) ; l'application le **détecte et rattache** automatiquement. Pas de déplacement piloté par l'application en V1 | Évite la permission Drive complète en écriture/suppression et un écran complexe |
| D7 | Permissions Drive minimales : `drive.readonly` + `drive.appdata` + `drive.file` | L'application ne peut ni modifier ni supprimer les livres |
| D8 | Lecture en continu **toujours à travers un cache disque**, avec pré-chargement anticipé | Résistance aux coupures réseau (CdC §4.4) |
| D9 | Chaque information d'un livre porte sa **provenance** ; une valeur saisie par l'utilisateur est verrouillée et n'est jamais écrasée | CdC §4.6 |
| D10 | Synthèse vocale = **outil séparé sur ordinateur** (Python), sortie déposée dans le Drive commun ; un fichier audio M4A par chapitre | Calcul lourd, itérations, régénération partielle ; le résultat est un livre « comme les autres » |
| D11 | Fusion des données synchronisées : **dernière écriture gagnante par élément** (par livre, par champ, par marque-page), horodatage en millisecondes UTC, suppressions par pierre tombale | Suffisant pour 2 personnes / 1 téléphone chacune, sans serveur |
| D12 | Distribution par **APK signé** déposé dans le Drive commun ; l'application y détecte les mises à jour | Installation simple pour la compagne, pas de Play Store |

### Réponses aux questions ouvertes du CdC §8

| Question | Réponse retenue |
|---|---|
| Qualité vocale suffisante / coût | Tranché par le banc d'essai (lot T0) : écoute à l'aveugle notée par l'utilisateur. Règle : on ne paie que si la meilleure voix payante bat la meilleure gratuite d'au moins 1 point sur 5 en confort d'écoute longue durée, et dans le plafond fixé par l'utilisateur |
| Création dans l'appli ou outil séparé | Outil séparé (D10) |
| Sources d'informations | Dans l'ordre : fichiers/dossiers → catalogue Audible France → iTunes France → Google Books (fr). Voir §6 |
| Espace de stockage par défaut | Cache de lecture : **2 Go** (réglable 0,5–20 Go). Téléchargements hors ligne : hors limite, jamais purgés automatiquement |
| Partage sans config lourde | D3 + D4 : la compagne installe l'APK, connecte ses deux comptes, indique lequel est le commun ; tout le reste est lu depuis le Drive commun |
| Où stocker le partagé | `.livres-audio/` dans le Drive commun (D4) ; le personnel dans `appDataFolder` du compte personnel (D3) |
| Changement de téléphone | Réinstaller, reconnecter le compte personnel : restauration automatique depuis `appDataFolder` |
| Première version | Lots 0 + 1 (§9) |

---

## 3. Pile technique

Versions : prendre les **dernières versions stables** au moment du lot 0, les figer dans `gradle/libs.versions.toml` (catalogue de versions unique), ne plus les changer sans tâche dédiée. [À VÉRIFIER] à chaque ajout de dépendance.

| Domaine | Choix |
|---|---|
| Langage / build | Kotlin 2.x, JDK 17, Gradle (Kotlin DSL), AGP stable courant |
| SDK | `minSdk 26`, `targetSdk`/`compileSdk` = dernière API stable |
| Identifiant | `applicationId = io.github.camelbaggy.livresaudio` (lié au client OAuth : **ne jamais le changer**) |
| Interface | Jetpack Compose, Material 3, Navigation Compose, thème sombre par défaut, couleurs dynamiques |
| Injection | Hilt |
| Asynchrone | Coroutines + Flow |
| Base locale | Room (source de vérité locale), DataStore Preferences pour les réglages de l'appareil |
| Lecture | Media3 : `media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`, `media3-database`, `media3-ui` non utilisé |
| Réseau | OkHttp + Retrofit + kotlinx.serialization (API REST Drive v3 appelée directement ; **pas** la bibliothèque cliente Java Google, trop lourde) |
| Authentification | Google Identity Services — `AuthorizationClient` (play-services-auth). Repli : AppAuth (voir §5.1) |
| Tâches de fond | WorkManager |
| Images | Coil 3 |
| Tests | JUnit, kotlin.test, Turbine, MockWebServer, Robolectric (Room, logique Android) |
| Qualité | ktlint + Android Lint, en CI |
| Atelier TTS | Python 3.11+, `uv`, ffmpeg ; voir §8 |

### Modules Gradle

```
app/                 UI Compose, navigation, DI, écrans (bibliothèque, lecteur, réglages, accueil)
core/model/          Kotlin pur : entités métier, interfaces (contrats), aucune dépendance Android
core/data/           Room, DataStore, dépôts (repositories)
core/drive/          Authentification, client REST Drive, source de données Media3 authentifiée
core/library/        Parcours des dossiers, règles dossier→livre, analyse des noms, lecture des étiquettes audio
core/playback/       Service Media3, lecteur, cache, pré-chargement, retour arrière, minuterie
core/metadata/       Fournisseurs en ligne, appariement, fusion par provenance
core/sync/           Sauvegarde du profil et données partagées dans Drive, fusion
tts/                 Atelier de synthèse vocale (Python, indépendant de Gradle)
docs/                Cahier des charges, recettes manuelles
```

Règles de dépendance : `core/model` ne dépend de rien ; les modules `core/*` dépendent de `core/model` et, si besoin, de `core/data` et `core/drive` ; `app` dépend de tout. Aucune dépendance entre `playback`, `metadata`, `library` et `sync` autrement que par des interfaces déclarées dans `core/model`. Cela permet de confier ces modules à des sous-agents en parallèle.

---

## 4. Modèle de données

### 4.1 Vocabulaire

- **Compte** : un compte Google connecté. Rôle `SHARED` (Drive commun) ou `PERSONAL`. Exactement un compte `PERSONAL` est le **compte de profil** (D3) ; s'il n'y a qu'un compte connecté, c'est lui.
- **Racine surveillée** : dossier Drive désigné comme contenant des livres.
- **Livre** : un dossier contenant des fichiers audio (ou un fichier audio isolé posé directement dans une racine).
- **Fichier** : un fichier audio d'un livre. **Chapitre** : repère de navigation (voir §5.4).
- **Origine** d'un livre : `SHARED` ou `PERSONAL`, déduite du compte.

### 4.2 Schéma Room (tables principales)

```
accounts(id, email, displayName, role, isProfile, addedAt)
watched_roots(id, accountId, driveFolderId, name, addedAt)
books(bookId PK, accountId, rootId, driveFolderId, origin, fingerprint, state[PRESENT|MISSING],
      addedAt, totalDurationMs?, totalBytes,
      -- champs d'information : pour chacun valeur + provenance (voir 4.3)
      title, author, narrator, seriesName, seriesIndex REAL, description, year, coverUri,
      fieldSources JSON, matchState[UNMATCHED|AUTO|NEEDS_REVIEW|USER], providerIds JSON)
book_files(id, bookId, driveFileId, name, mimeType, sizeBytes, md5, sortIndex, durationMs?,
           trackNumber?, discNumber?, probed BOOLEAN)
chapters(id, bookId, fileId, indexInBook, title, startMs, endMs)
progress(bookId PK, driveFileId, fileMd5, fileIndex, positionMs, bookPositionMs,
         status[NOT_STARTED|IN_PROGRESS|FINISHED], speed?, lastPlayedAt, updatedAt, dirty)
bookmarks(id UUID PK, bookId, fileMd5, positionMs, bookPositionMs, note?, createdAt, updatedAt, deleted, dirty)
match_candidates(id, bookId, provider, providerId, title, author, narrator, series, seriesIndex,
                 year, durationMs, coverUrl, score)
sync_state(key PK, driveFileId, remoteModifiedTime, lastSyncedAt)
```

Les téléchargements hors ligne utilisent la base interne de Media3 (`DownloadManager`).

### 4.3 Provenance des champs

Chaque champ d'information a une provenance, par priorité décroissante : `USER` > `EMBEDDED` (étiquettes des fichiers) > `ONLINE` > `FOLDER` (déduit des noms). Règle de fusion unique, dans `core/metadata` : une valeur n'est remplacée que par une provenance **strictement supérieure**, ou égale et plus récente. `USER` est définitif : aucune synchronisation ni ré-analyse ne l'écrase. « Réinitialiser ce champ » est la seule façon de le déverrouiller.

### 4.4 Identité et rattachement d'un livre (D5)

- À la première découverte d'un livre : `bookId = UUIDv5(espace de noms fixe du projet, driveFolderId)` pour un livre-dossier, `UUIDv5(espace de noms, driveFileId)` pour un livre d'un seul fichier — déterministe, donc les deux téléphones calculent le même identifiant sans se coordonner. (`books.driveFolderId` contient alors le dossier parent, et le livre est repéré par son unique fichier.)
- `fingerprint` = SHA-256 de la liste triée des MD5 des fichiers audio.
- Avant de créer un livre pour un dossier inconnu, chercher un livre existant (présent, manquant, ou connu seulement par ses informations synchronisées) partageant **≥ 80 % des MD5** : si trouvé, c'est le même livre déplacé ou copié → on met à jour son dossier/compte/origine et on **conserve `bookId`**, progression, marque-pages, corrections.
- Changement d'origine lors d'un rattachement : personnel → commun, les informations du livre sont publiées dans `.livres-audio/meta/` ; commun → personnel, elles sont copiées dans le profil et retirées du partagé (pierre tombale). Les progressions ne bougent jamais : elles sont toujours dans le profil de chacun.
- La position est restaurée d'abord par `fileMd5` + `positionMs`, sinon par `bookPositionMs`.

### 4.5 Fichiers synchronisés dans Drive (JSON, UTF-8, champ `schemaVersion` obligatoire)

**Profil — `appDataFolder` du compte de profil** (invisible, propre à l'application et au compte) :

```
profile.json     réglages d'écoute (vitesse par défaut, table de retour arrière, durées de saut, minuterie),
                 racines surveillées des comptes personnels, option « partager ma progression »
progress.json    { bookId: {driveFileId, fileMd5, fileIndex, positionMs, bookPositionMs, status, speed, lastPlayedAt, updatedAt} }
bookmarks.json   [ {id, bookId, fileMd5, positionMs, bookPositionMs, note, createdAt, updatedAt, deleted} ]
meta/<bookId>.json, covers/<bookId>.jpg     informations des livres personnels
```

**Partagé — dossier `.livres-audio/` à la racine du Drive commun** (créé par l'application) :

```
config.json                 racines surveillées du Drive commun
meta/<bookId>.json          informations d'un livre commun : champs + provenance + updatedAt par champ, MD5 des fichiers, driveFolderId
covers/<bookId>.jpg         couverture choisie
presence/<cléProfil>.json   progression publiée volontairement (opt-in) : { prénom, bookId: {bookPositionMs, pourcentage, updatedAt} }
prononciation/*.yaml        lexiques de prononciation (écrits par l'atelier TTS)
app/                        APK de mise à jour (D12)
```

Écriture : lire la version distante → fusionner (D11) → envoyer. Lecture : lister le dossier (un appel donne les dates de modification), ne télécharger que les fichiers modifiés. Le réglage de taille de cache et l'état des téléchargements restent locaux à l'appareil.

---

## 5. Spécifications techniques — application

### 5.1 Comptes et authentification (`core/drive`)

- Interface `DriveAuthProvider` dans `core/model` : `addAccount()`, `removeAccount()`, `getAccessToken(accountId, forceRefresh)`. Tout le reste du code ne connaît que cette interface.
- Mise en œuvre principale : `AuthorizationClient` (Google Identity Services) avec les portées de D7 ; le jeton d'accès est redemandé silencieusement à chaque expiration ; après le premier consentement, appeler `about.get` pour obtenir l'adresse du compte et la mémoriser ; les appels suivants ciblent ce compte.
- **[À VÉRIFIER — essai E1 du lot 0]** : (a) ajout d'un **deuxième** compte avec sélecteur de compte forcé ; (b) renouvellement silencieux après plusieurs jours ; (c) les fichiers créés avec `drive.file` par un téléphone sont lisibles et modifiables par l'autre téléphone (même compte, même projet Google Cloud). Si (a) ou (b) échoue : repli sur **AppAuth** (flux OAuth navigateur avec PKCE, `prompt=select_account`, jetons de renouvellement chiffrés via Android Keystore). Si (c) échoue : ajouter la portée `drive` au seul compte commun.
- Projet Google Cloud : écran de consentement de type « Externe », **état « En production » sans demande de validation**. En état « Test », les autorisations expirent au bout de 7 jours, ce qui violerait CdC §4.1. Conséquence acceptée : un écran « application non validée » à franchir une fois par compte. [À VÉRIFIER dans E1]
- Jetons jamais journalisés, jamais écrits dans Room ni dans les fichiers synchronisés.
- Client REST : `files.list` (pagination, `fields` restreints, `q` par parent), `files.get?alt=media` (avec `Range`), `changes.list`, `about.get`, création/mise à jour de fichiers pour `.livres-audio/` et `appDataFolder`. Un intercepteur OkHttp ajoute le jeton ; un `Authenticator` le renouvelle sur 401 ; recul exponentiel sur 403 (quota) et 5xx.

### 5.2 Construction de la bibliothèque (`core/library`)

Règles dossier → livre, appliquées à chaque racine surveillée :

1. Un dossier contenant directement des fichiers audio = **un livre** dont ces fichiers sont les parties, **sauf** si leurs noms montrent que chaque fichier est un livre complet (règle de décision détaillée dans `docs/formes-de-noms.md` : après retrait du préfixe/suffixe commun, des nombres et des mots génériques — chapitre, partie, prologue, remerciements… —, s'il reste des titres distincts, chaque fichier est un livre). C'est le cas dominant du Drive personnel : un dossier de série avec un `.m4b` par tome.
2. Un dossier ne contenant que des sous-dossiers = **regroupement** ; son nom est un indice (auteur ou série) pour les livres en dessous.
3. Un dossier dont tous les sous-dossiers contenant de l'audio ont un nom de type disque/partie (`CD 1`, `Disque 2`, `Partie 3`, `Disc1`…) = **un seul livre** ; fichiers ordonnés par disque puis par nom.
4. Un fichier audio isolé directement dans une racine ou un regroupement = un livre d'un seul fichier. Un dossier peut être à la fois regroupement (sous-dossiers) et contenir des livres d'un seul fichier.
5. Extensions audio : `mp3, m4a, m4b, aac, ogg, opus, flac, wav, wma` (ce dernier listé mais signalé « format non pris en charge »). Images du dossier = couvertures candidates (`cover.*`, `folder.*`, sinon la plus grande).

Ordre des fichiers : numéro de disque puis de piste des étiquettes s'ils sont cohérents sur tout le livre ; sinon **tri naturel** du nom (`2` avant `10`).

Analyse des noms de dossiers (fonction pure, très testée, jeu d'exemples français dans les tests) : reconnaître `Auteur - Titre`, `Auteur - Série 03 - Titre`, `Série - Tome 3 - Titre`, `Titre (Auteur)`, `[Série T3] Titre`, `03. Titre` ; retirer le bruit (`mp3`, débits, `audiobook`, `livre audio`, `intégrale`, `VF`, années, `lu par …` → narrateur) ; normaliser espaces, tirets bas, casse. Le résultat a la provenance `FOLDER`.

Parcours : premier passage en largeur depuis chaque racine ; ensuite mise à jour incrémentale par `changes.list` (un jeton par compte), plus un parcours complet de contrôle hebdomadaire. Déclencheurs : ouverture de l'application, tirer-pour-rafraîchir, tâche périodique WorkManager (12 h, réseau requis). Un livre disparu passe à l'état `MISSING` (masqué, données conservées 90 jours pour permettre le rattachement) — jamais de suppression immédiate.

Sondage des fichiers (tâche de fond, par plages HTTP, sans téléchargement complet) : étiquettes (titre, album, artiste, narrateur/compositeur, piste, disque, série si présente), couverture intégrée, **durée** (Drive ne la fournit pas pour l'audio), chapitres intégrés. Utiliser `MetadataRetriever` de Media3 quand il suffit. **[À VÉRIFIER — essai E3]** : l'extraction des chapitres M4B (atome `chpl` et piste de chapitres QuickTime) et MP3 (`CHAP`) ; prévoir un petit analyseur d'atomes MP4 maison si Media3 ne les expose pas.

### 5.3 Lecture (`core/playback`)

- `MediaLibraryService` + `MediaLibrarySession` ; service de premier plan de type `mediaPlayback` ; permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`.
- ExoPlayer : attributs audio `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH`, gestion automatique du focus audio, pause au débranchement du casque, `WAKE_MODE_NETWORK`.
- Un livre = une liste de lecture d'un `MediaItem` par fichier (enchaînement sans blanc). Clé de cache stable par fichier : `drive:<driveFileId>:<md5>`.
- **Chapitres** : couche `ChapterTimeline` au-dessus du lecteur. Livre multi-fichiers sans chapitres intégrés : un chapitre par fichier. Fichier avec chapitres intégrés : chapitres = repères dans le fichier. « Chapitre suivant/précédent » = déplacement vers le repère ; « précédent » revient au début du chapitre courant si on y est depuis plus de 3 s.
- **Sauvegarde de position** (règle d'or) : écriture Room transactionnelle toutes les 5 s en lecture, et à chaque pause, déplacement, changement de fichier, perte de focus, `onTaskRemoved`, arrêt du service et erreur du lecteur. Au démarrage, la position Room fait foi. Aucune écriture de position 0 tant que le lecteur n'a pas effectivement démarré à la position restaurée.
- **Retour arrière à la reprise** : appliqué dans un `ForwardingPlayer` sur `play()`, donc valable depuis l'écran, la notification, le Bluetooth et la voiture. Table par défaut (réglable, y compris désactivation) :

  | Durée de la pause | Retour |
  |---|---|
  | < 5 s | 0 s |
  | 5 s – 1 min | 3 s |
  | 1 – 5 min | 10 s |
  | 5 min – 1 h | 20 s |
  | 1 h – 24 h | 30 s |
  | > 24 h | 60 s |

  Le retour peut franchir une limite de fichier ; il s'arrête au début du livre. Il s'applique aussi après une interruption (appel, annonce GPS).
- **Vitesse** : 0,5× à 3,0× par pas de 0,05, hauteur de voix conservée (`PlaybackParameters(speed, 1f)`). Vitesse par défaut dans le profil, surcharge possible par livre. Durées et temps restants affichés en tenant compte de la vitesse.
- **Sauts** : avance et recul réglables séparément (défaut 30 s / 10 s). Option : boutons piste suivante/précédente du casque = sauts (défaut) ou chapitres.
- **Minuterie** : durées prédéfinies + personnalisée + « fin du chapitre » ; fondu de 10 s ; à l'échéance, pause (la position est sauvegardée) ; reprise dans les 2 min = prolongation de la même durée.
- **Voiture** : arborescence de navigation Android Auto (En cours, Récents, Tous les livres, puis chapitres). L'application n'étant pas issue du Play Store, activer « Sources inconnues » dans les options développeur d'Android Auto ; le documenter dans `docs/installation.md`.

### 5.4 Continuité réseau, cache et hors ligne (`core/playback`)

Chaîne de sources : `CacheDataSource`(téléchargements) → `CacheDataSource`(cache de lecture) → source OkHttp authentifiée vers Drive.

- **Deux caches Media3 distincts** : `downloads/` (jamais purgé automatiquement) et `stream/` (purgé).
- **Purge du cache de lecture** : limite réglable (défaut 2 Go). Éviction par **livre**, du moins récemment écouté au plus récent ; le livre en cours n'est jamais purgé. Mise en œuvre : évincteur personnalisé qui s'appuie sur `lastPlayedAt` de la table `progress`.
- **Pré-chargement anticipé** (`PreCacher`, coroutine du service, basé sur `CacheWriter`) : maintenir en cache disque la suite de l'écoute — objectif **60 min d'audio en Wi-Fi, 30 min en données mobiles** (réglable), en traversant les limites de fichiers ; toujours au moins les 2 premiers Mo du fichier suivant. Relancé à chaque changement de fichier, déplacement, et retour du réseau. Option « données mobiles : pré-chargement réduit / désactivé ».
- **Tampon mémoire** : `DefaultLoadControl` avec tampon min 2 min / max 10 min, tampon arrière 60 s conservé.
- **Tolérance aux erreurs** : `LoadErrorHandlingPolicy` personnalisée — erreurs réseau : nouvelles tentatives illimitées, délai croissant plafonné à 8 s (le lecteur reste en attente au lieu d'échouer) ; 404 : fatal, livre marqué `MISSING` ; 401 : géré par l'`Authenticator`. Si le lecteur passe malgré tout en erreur : position sauvegardée, puis `prepare()` + reprise automatique au retour du réseau (`ConnectivityManager.NetworkCallback`) si l'utilisateur était en lecture.
- **États affichés** (écran lecteur + notification) : `En lecture`, `En pause`, `Chargement…`, `En attente du réseau…` (avec la quantité d'audio encore disponible), `Fichier introuvable`. Jamais de fermeture ni de retour à zéro.
- **Hors ligne** : `DownloadManager`/`DownloadService` Media3 ; télécharger un livre entier ou une sélection de chapitres/fichiers ; option Wi-Fi seulement (défaut) ; pastille « hors ligne » sur la couverture ; écran « Stockage » listant par livre l'espace téléchargé et en cache, avec suppression en un geste. Un livre entièrement téléchargé se lit sans aucun appel réseau (y compris sa couverture, mise en cache par Coil sur disque).

### 5.5 Écrans (`app`)

1. **Accueil / première mise en route** : connecter un compte → indiquer son rôle (« Drive commun » ou « Mon Drive personnel ») → si un `.livres-audio/config.json` existe sur le Drive commun, l'appliquer sans rien demander ; sinon choisir les dossiers de livres (navigateur de dossiers Drive) → proposer d'ajouter un autre compte → si un profil existe dans `appDataFolder`, le restaurer.
2. **Bibliothèque** : grille de couvertures ; bandeau « Reprendre » avec le livre en cours ; recherche (titre, auteur, narrateur, série) ; tri (titre, auteur, date d'ajout, dernier écouté) ; filtres d'état (en cours, terminés, pas commencés), d'origine (commun, personnel), « hors ligne » ; séries repliées en une pile ouvrant la liste ordonnée par tome ; pastille d'origine discrète sur chaque couverture ; barre de progression sous la couverture. Appui long : marquer terminé, remettre à zéro, télécharger, modifier les informations.
3. **Fiche du livre** : informations, résumé, chapitres avec durées, marque-pages, téléchargement, « Identifier ce livre » (candidats), modification manuelle de chaque champ et de la couverture (galerie ou candidats en ligne), progression de l'autre personne si elle la partage.
4. **Lecteur** : grande couverture, titre du chapitre, barre de progression **du chapitre** + temps restant du livre, commandes larges dans la moitié basse de l'écran (utilisable d'une main) : recul, lecture/pause, avance, chapitre précédent/suivant, vitesse, minuterie, marque-page, liste des chapitres. Thème sombre, contrastes doux.
5. **Réglages** : comptes et dossiers, lecture (table de retour arrière, sauts, vitesse par défaut, boutons du casque), réseau et stockage (limite du cache, pré-chargement, Wi-Fi seulement), partage (publier ma progression), mise à jour de l'application, à propos/journal de diagnostic.

Textes dans `strings.xml` (français uniquement). Cibles tactiles ≥ 48 dp, descriptions de contenu pour l'accessibilité.

---

## 6. Informations enrichies (`core/metadata`)

Chaîne pour chaque livre : `FOLDER` (analyse des noms) → `EMBEDDED` (étiquettes) → `ONLINE` → `USER`.

Interface `MetadataProvider { suspend fun search(query: BookQuery): List<Candidate> }` ; un fournisseur = une classe, activable/désactivable. Ordre de consultation :

| Ordre | Fournisseur | Apport | Remarques |
|---|---|---|---|
| 1 | Catalogue **Audible France** (+ Audnexus pour le détail par ASIN) | Narrateur, série et tome, durée, résumé, couverture HD | Interfaces non officielles, sans clé. **[À VÉRIFIER]** disponibilité et format au lot 3 ; doit pouvoir être coupé sans rien casser |
| 2 | **iTunes Search** (`media=audiobook`, `country=FR`) | Titre, auteur, résumé, bonne couverture | Officielle, sans clé |
| 3 | **Google Books** (`langRestrict=fr`) | Titre, auteur, résumé, année | Pour les livres sans édition audio commerciale ; couverture souvent médiocre |

- Requête : titre + auteur nettoyés ; si échec, titre seul. Langue française privilégiée, **mais** une part importante de la bibliothèque est en anglais (`docs/formes-de-noms.md`) : prévoir les catalogues anglophones (Audible US/UK, iTunes US) selon la langue détectée — à trancher au lot 3.
- **Score** d'un candidat (0–1) : similarité du titre (0,5) + de l'auteur (0,3) + proximité de durée quand elle est connue des deux côtés (0,2 ; écart < 3 % = maximum). Normalisation avant comparaison : minuscules, sans accents, sans articles ni ponctuation.
- **Décision** : score ≥ 0,85 **et** avance ≥ 0,10 sur le second → application automatique (`matchState = AUTO`). Entre 0,50 et 0,85, ou candidats trop proches → `NEEDS_REVIEW` : jusqu'à 5 candidats proposés sur la fiche, avec un indicateur discret dans la bibliothèque. En dessous → `UNMATCHED`, on garde étiquettes et noms de dossiers.
- Les candidats et réponses sont mis en cache ; cadence limitée à 1 requête/s par fournisseur ; l'enrichissement tourne en tâche de fond (WorkManager, réseau requis) et ne bloque jamais l'affichage ni la lecture.
- **Séries** : nom + numéro de tome (décimal accepté) ; regroupement par nom de série normalisé ; ordre par tome puis titre.
- Vie privée : seuls le titre et l'auteur recherchés sont envoyés aux fournisseurs. Rien d'autre ne sort de l'appareil hors Google Drive.

---

## 7. Usage à deux et synchronisation (`core/sync`)

Correspondance avec CdC §4.8 :

| Exigence | Mécanisme |
|---|---|
| Espaces d'écoute séparés | D3 : chacun n'écrit que dans le `appDataFolder` de son compte personnel, que l'autre téléphone ne connecte pas |
| Bibliothèque commune sans double configuration | D4 : `config.json` + `meta/` du Drive commun |
| Corrections partagées (livres communs) / privées (livres personnels) | L'emplacement d'écriture dépend de l'origine du livre (§4.5) |
| Origine visible et filtrable | Champ `origin`, pastille et filtre (§5.5) |
| Déplacement sans perte | D5/D6, §4.4 |
| Voir où en est l'autre, sans l'imposer | `presence/` : publication désactivée par défaut, activable par chacun ; ne contient que des livres communs |
| Changement de téléphone | Restauration depuis `appDataFolder` à la connexion du compte personnel |
| Mise en route simple | Parcours d'accueil §5.5 + APK dans le Drive commun |

Rythme de synchronisation : profil — envoi différé de 30 s après une pause, toutes les 2 min en lecture, à la fermeture, et par tâche WorkManager avec contrainte réseau tant qu'il reste des éléments `dirty` ; réception à l'ouverture de l'application. Partagé — à l'ouverture et après chaque parcours de bibliothèque ; envoi immédiat après une correction. Toute synchronisation est **hors du chemin critique** : un échec n'empêche ni la lecture ni la sauvegarde locale, et se rejoue plus tard.

Garde-fou de fusion : une progression distante plus ancienne n'écrase jamais une progression locale plus récente ; en cas de restauration sur un nouveau téléphone, la base locale étant vide, le distant est repris tel quel.

Mise à jour de l'application (D12) : l'application liste `.livres-audio/app/`, repère un fichier `livres-audio-<versionCode>.apk` supérieur à la version installée, propose de le télécharger et lance l'installateur système (`REQUEST_INSTALL_PACKAGES`).

---

## 8. Atelier de synthèse vocale (`tts/`)

Outil Python en ligne de commande, indépendant de l'application. Livres sources : EPUB **sans DRM** uniquement ; l'outil refuse un fichier protégé. Usage privé à deux : les fichiers produits ne vont que dans le Drive commun.

### 8.1 Chaîne de traitement

```
EPUB → extraction → nettoyage → chapitrage → normalisation → lexique → découpage → synthèse (cache) → assemblage → étiquetage → dépôt
```

1. **Extraction** (`ebooklib` + analyse HTML) : ordre de lecture, table des matières, couverture, titre/auteur.
2. **Nettoyage** : retirer appels et corps de notes (`epub:type="noteref"/"footnote"`, exposants numériques), numéros de page, en-têtes/pieds répétés, pages de titre, mentions légales, sommaire, césures. Les passages retirés sont listés dans un rapport pour contrôle. Les notes peuvent être conservées sur option (lues en fin de chapitre).
3. **Chapitrage** : d'après la table des matières ; repli sur les titres. Un chapitre = un fichier de sortie.
4. **Normalisation du français** : nombres, ordinaux, **chiffres romains** (rois, siècles, tomes : « Henri IV », « XVIe siècle »), abréviations (M., Mme, Mgr, St), dates, sigles, tirets de dialogue et guillemets, ponctuation qui pilote le rythme. Fonctions pures, très testées.
5. **Lexique de prononciation** : fichiers YAML — `global.yaml` + un par série (ex. `fortune-de-france.yaml`), stockés dans `.livres-audio/prononciation/` du Drive commun et versionnés aussi dans `tts/lexiques/`. Entrée : `mot` (ou expression régulière), `dire` (réécriture phonétique en français courant), `ipa` facultatif (utilisé si le moteur l'accepte), `casse`, `commentaire`. Appliqué à toute la série et hérité par les tomes suivants.
6. **Découpage** en segments de 1 à 3 phrases, sans couper une réplique ; silence configurable entre paragraphes et entre chapitres.
7. **Synthèse avec cache** : clé = SHA-256 (texte final du segment + moteur + voix + paramètres). Après une correction du lexique, seuls les segments dont le texte final change sont regénérés, puis seuls les chapitres touchés sont réassemblés (CdC §4.7).
8. **Assemblage** (ffmpeg) : normalisation du volume (EBU R128), **M4A AAC mono 64 kb/s**, un fichier par chapitre `NN - Titre.m4a`.
9. **Étiquetage** : titre, auteur, narrateur = « Voix de synthèse (<voix>) », série, tome, année, couverture, numéro de piste.
10. **Dépôt** : dossier `Auteur/Série/NN - Titre/` dans une racine surveillée du Drive commun (via `rclone` ou envoi par l'API Drive ; à défaut, dossier local à déposer à la main). Le livre apparaît alors dans l'application comme un autre (CdC §4.7).

### 8.2 Commandes

```
tts bench <extrait.txt> --moteurs …        génère le même extrait avec chaque voix → dossier d'écoute + page de comparaison à l'aveugle
tts prepare <livre.epub>                   extraction + nettoyage + chapitrage → projet de travail + rapport
tts estimate <projet> --moteur …           nombre de caractères, coût estimé, durée estimée
tts say "<phrase>" --moteur … --serie …    extrait court pour vérifier une correction de prononciation
tts build <projet> --moteur … --budget-max <€>   synthèse (reprise sur interruption) + assemblage
tts fix <projet>                           regénère uniquement ce qu'un changement de lexique a rendu obsolète
tts publish <projet>                       dépôt dans le Drive commun
```

Interface commune `Engine { synthesize(text, voice, params) -> wav ; price_per_million_chars ; max_chars }` : un moteur = un fichier.

**Maîtrise des coûts** : `build` avec un moteur payant refuse de démarrer sans `--budget-max` ; estimation affichée et confirmée avant le premier appel ; registre des dépenses cumulées dans le projet ; arrêt net au plafond. Clés d'API dans des variables d'environnement, jamais dans le dépôt.

### 8.3 Banc d'essai (première étape, avant tout le reste)

Ordre de grandeur : un tome de « Fortune de France » ≈ 1 million de caractères ; la saga ≈ 13 tomes.

- Extrait de test : ~3 minutes tirées du tome 1, mêlant narration, dialogue, noms propres (Siorac, Mespech, Sauveterre, Sarlat…) et mots d'époque ou occitans.
- **[À VÉRIFIER — veille obligatoire en début de lot T0]** : le paysage des voix de synthèse change tous les trimestres. La liste ci-dessous est un point de départ ; la mettre à jour (modèles, qualité en français, tarifs, licences) avant de coder les moteurs.
  - Gratuits, en local : Piper (voix fr), Kokoro (voix fr), XTTS-v2, F5-TTS (variantes françaises), Chatterbox multilingue, Kyutai TTS (fr), Fish Speech/OpenAudio.
  - Gratuit, en ligne : voix neuronales Microsoft Edge (`edge-tts`, voix fr-FR dont les « Multilingual ») — accès non officiel.
  - Payants : Google Cloud (Chirp HD) / Gemini TTS, Azure Neural/HD, OpenAI TTS, ElevenLabs, Mistral (Voxtral) s'il propose de la synthèse, Cartesia, Amazon Polly (génératif).
- Les moteurs gratuits sont testés en premier. Les payants ne sont testés que sur l'extrait (coût de quelques centimes), après accord de l'utilisateur.
- Grille de notation par l'utilisateur, à l'aveugle (fichiers anonymisés) : naturel, rythme, intonation des dialogues, noms propres, fatigue estimée sur la durée — chacun sur 5. Résultat et décision consignés au §13.
- Si une licence de modèle interdit autre chose que l'usage personnel non commercial, c'est compatible avec ce projet ; le noter quand même.

### 8.4 Plus tard (seulement si la voix unique convainc)

Voix distinctes narrateur/personnages : détection des répliques (tirets, guillemets, incises), attribution des locuteurs assistée, table personnage → voix par série. Ne pas commencer avant validation du lot T3.

---

## 9. Feuille de route

Les lots de l'application se suivent dans l'ordre. La piste TTS (lots T) avance en parallèle et ne bloque jamais l'application. **Un lot n'est terminé que lorsque ses critères sont remplis et que l'utilisateur a validé la recette manuelle** (`docs/recettes/lot-N.md`, rédigée par le chef de projet à la fin du lot : liste d'actions à faire sur le téléphone et résultat attendu).

### Lot 0 — Socle et levée des risques

| Tâche | Contenu |
|---|---|
| 0.1 | Prérequis utilisateur (§10) obtenus |
| 0.2 | Squelette Gradle multi-modules, catalogue de versions, Hilt, thème Compose, écran vide, ktlint, Lint |
| 0.3 | CI GitHub Actions : `ktlintCheck lint test assembleDebug` à chaque poussée ; tâche manuelle `assembleRelease` signée (clé en secrets) produisant l'APK en artefact |
| 0.4 | Environnement cloud : script d'installation du SDK Android en ligne de commande pour que les agents puissent compiler et tester |
| E1 | Essai authentification (§5.1) : deux comptes, jeton silencieux, lecture `files.list`, écriture `drive.file` et `appDataFolder` |
| E2 | Essai lecture : un MP3 et un gros M4B du Drive lus par ExoPlayer via la source authentifiée + cache ; déplacement dans le fichier ; mode avion 60 s sans coupure sur contenu en cache |
| E3 | Essai chapitres intégrés M4B/MP3 (§5.2) |

Critère : un APK installé sur le téléphone de l'utilisateur se connecte à deux comptes et lit un fichier du Drive. Les conclusions E1–E3 sont consignées au §13 ; si un repli est activé, mettre à jour §5.

### Lot 1 — Première version utilisable (CdC priorité 1) → remplace CloudBeats

| Tâche | Module | Contenu |
|---|---|---|
| 1.1 | model, data | Schéma Room complet du §4.2 (y compris champs de provenance, origine, `dirty`) + dépôts |
| 1.2 | drive | Authentification définitive, comptes multiples avec rôle, client REST |
| 1.3 | app | Accueil : connexion, rôle du compte, navigateur de dossiers, choix des racines |
| 1.4 | library | Parcours complet, règles dossier→livre, tri naturel, analyse des noms, `bookId`/empreinte |
| 1.5 | library | Sondage : étiquettes, durées, couverture intégrée ou image du dossier, chapitres |
| 1.6 | playback | Service Media3, liste de lecture par livre, `ChapterTimeline`, notification, écran de verrouillage, Bluetooth |
| 1.7 | playback | Sauvegarde/restauration de position (§5.3), reprise après fermeture forcée |
| 1.8 | playback | Retour arrière à la reprise, vitesse, sauts réglables |
| 1.9 | playback | Chaîne de cache simple (cache de lecture, limite fixe 2 Go) |
| 1.10 | app | Bibliothèque (grille, recherche, tri de base, « Reprendre »), fiche simple, lecteur, réglages de lecture |

Critères : connexion durable ; la bibliothèque reflète les dossiers ; reprise exacte après redémarrage du téléphone ; retour arrière conforme à la table ; vitesse sans déformation ; commandes casque et écran verrouillé fonctionnelles ; tests unitaires sur règles de dossiers, analyse de noms, table de retour arrière, persistance de position.

### Lot 2 — Résistance au réseau (CdC priorité 2)

Pré-chargement anticipé, politique d'erreurs, reprise automatique au retour du réseau, états d'attente affichés, mise à jour incrémentale de la bibliothèque (`changes.list`), tâche périodique, état `MISSING`.

Critères (recette sur téléphone) : mode avion 2 min en pleine lecture en flux → aucune coupure ; passage au fichier suivant sans attente ; coupure longue → « En attente du réseau… » puis reprise seule ; position intacte dans tous les cas ; un livre ajouté au Drive apparaît, un livre retiré disparaît.

### Lot 3 — Informations enrichies, corrections, séries (CdC priorité 2)

Fournisseurs (§6), score et décision, écran de choix des candidats, modification manuelle de tous les champs et de la couverture, verrouillage `USER`, séries, tri et filtres complets.

Critères : sur un échantillon de 30 livres français de l'utilisateur, ≥ 80 % correctement identifiés automatiquement, 0 identification automatique fausse non signalée ; une correction manuelle survit à une ré-analyse complète ; tests unitaires de la fusion par provenance et du score.

### Lot 4 — Usage à deux et sauvegarde (CdC priorité 4)

Sauvegarde/restauration du profil (`appDataFolder`), données partagées `.livres-audio/` (config, informations, couvertures), fusion (D11), pastille et filtre d'origine, rattachement des livres déplacés (§4.4), présence volontaire, mise à jour par APK du Drive commun, `docs/installation.md` pour le second téléphone.

Critères : installation sur le téléphone de la compagne en moins de 5 min sans choisir de dossiers communs ; une correction faite sur un livre commun apparaît sur l'autre téléphone ; les positions ne se mélangent jamais ; désinstaller/réinstaller restitue positions et marque-pages ; un livre déplacé du Drive personnel au commun garde sa progression ; tests unitaires de toutes les fusions (dont pierres tombales et horloges décalées).

### Lot 5 — Hors ligne, stockage, confort (CdC priorité 3)

Téléchargements (livre entier ou partie), écran Stockage, limite de cache réglable avec éviction par livre, marque-pages avec note, minuterie, marquer terminé / remettre à zéro.

Critères : livre téléchargé lisible en mode avion de bout en bout ; dépassement de limite → purge du livre le moins récemment écouté, jamais du livre en cours ni des téléchargements ; marque-pages synchronisés dans le profil.

### Lot 6 — Voiture et finitions

Android Auto, option des boutons du casque, accessibilité, journal de diagnostic exportable, revue de consommation batterie/données, nettoyage.

### Piste TTS (parallèle)

| Lot | Contenu | Critère |
|---|---|---|
| T0 | Veille, squelette `tts/`, `bench`, moteurs gratuits, page d'écoute à l'aveugle | L'utilisateur a noté les voix ; décision gratuit/payant consignée |
| T1 | `prepare` : extraction, nettoyage, chapitrage, normalisation, rapport | Tome 1 préparé ; rapport de nettoyage validé sur 3 chapitres relus |
| T2 | Lexique, `say`, cache par segment, `build`, `fix`, assemblage, étiquetage | Un chapitre complet écoutable ; corriger un nom ne regénère que les segments concernés |
| T3 | Moteurs payants retenus, `estimate`, plafond, registre ; `publish` | Tome 1 entier dans le Drive commun, visible et lisible dans l'application, coût ≤ plafond |
| T4 | (Optionnel) voix multiples | Sur décision de l'utilisateur après écoute de T3 |

---

## 10. Prérequis à obtenir de l'utilisateur

À demander au début du lot 0, en une seule fois, avec un pas-à-pas rédigé par le chef de projet (`docs/prerequis.md`) :

1. **Projet Google Cloud** : activer l'API Google Drive ; écran de consentement « Externe » publié « En production » ; identifiant client OAuth de type Android (nom de paquet du §3 + empreinte SHA-1 de la clé de signature).
2. **Clé de signature** unique (utilisée pour toutes les versions installées), générée par l'utilisateur ou sur ses indications ; conservée hors du dépôt + en secrets GitHub. **Sa perte oblige à tout réinstaller.**
3. Modèles et versions Android des deux téléphones ; présence d'Android Auto ou non.
4. Un aperçu de l'arborescence réelle des dossiers de livres (noms uniquement) pour alimenter les tests d'analyse de noms.
5. Pour la piste TTS : l'EPUB du tome 1, le matériel disponible (ordinateur, carte graphique ?), le plafond de dépense, et l'accord avant tout essai payant.

Ce que les agents ne peuvent pas faire eux-mêmes : tester sur un téléphone réel (pas d'émulateur dans l'environnement cloud), agir dans la console Google Cloud, écouter et juger les voix. Ces étapes passent toujours par l'utilisateur, via les recettes.

---

## 11. Règles de travail

### 11.1 Organisation chef de projet / sous-agents

- Le chef de projet découpe chaque lot en tâches d'une demi-journée au plus, **une tâche = un module = un sous-agent**. Deux tâches touchant le même module ne tournent jamais en parallèle.
- Les contrats (interfaces et types de `core/model`) sont écrits ou validés par le chef de projet **avant** de lancer des tâches parallèles qui en dépendent.
- Brief d'une tâche (toujours ces rubriques) : objectif en une phrase ; sections de ce document à lire ; fichiers/modules autorisés ; contrats à respecter ; tests exigés ; critères d'acceptation ; ce qui est hors périmètre.
- Compte rendu attendu du sous-agent : ce qui est fait, fichiers modifiés, résultat réel des commandes de vérification, écarts ou doutes. Jamais « terminé » si les tests ne passent pas.
- Le chef de projet relit **lui-même**, ligne à ligne, tout ce qui touche : persistance de la position, fusion de synchronisation, authentification et jetons, éviction du cache, maîtrise des coûts TTS.

### 11.2 Définition de « terminé » pour une tâche

1. `./gradlew ktlintCheck lint test assembleDebug` passe (ou, pour `tts/` : `uv run ruff check && uv run pytest`).
2. La logique nouvelle est couverte par des tests unitaires (fonctions pures d'abord ; MockWebServer pour le réseau ; Robolectric pour Room).
3. Aucun secret, jeton, fichier de clé, adresse de messagerie ou nom de livre réel de l'utilisateur dans le dépôt ni dans les journaux.
4. Aucune dépendance ajoutée hors catalogue de versions ; aucune nouvelle dépendance sans justification dans le compte rendu.
5. Si une décision de ce document est affectée : §13 mis à jour dans le même commit.

### 11.3 Conventions de code

- Architecture : interface Compose sans logique → `ViewModel` (état unique exposé en `StateFlow`) → dépôts → sources (Room, Drive). Room est la source de vérité de l'affichage ; le réseau ne fait qu'alimenter Room.
- Pas de `GlobalScope`, pas de `runBlocking` hors tests ; répartiteurs de coroutines injectés.
- Erreurs réseau : types explicites (`sealed interface`), jamais d'exception qui remonte jusqu'à l'interface.
- Toute fonction de calcul (tri, analyse de noms, score, fusion, table de retour arrière, normalisation de texte) est **pure** et testée par tableaux d'exemples.
- Migrations Room : schéma exporté et versionné ; à partir du lot 1 livré, **toute** modification de schéma s'accompagne d'une migration testée — jamais de `fallbackToDestructiveMigration` (ce serait perdre des positions).
- Formats JSON synchronisés : `schemaVersion` ; champs inconnus ignorés en lecture et **préservés** en réécriture ; lecture tolérante des versions antérieures.
- Commits petits et en français, à l'impératif (« Ajoute… », « Corrige… »). Branche de travail indiquée par la session ; pas de demande de fusion sans demande de l'utilisateur.

### 11.4 Sécurité et vie privée

- Portées Drive limitées à D7 ; toute portée supplémentaire exige l'accord explicite de l'utilisateur.
- L'application ne supprime ni ne modifie jamais un fichier de livre. Elle n'écrit que dans `.livres-audio/` et `appDataFolder`.
- Aucun service tiers d'analyse, de publicité ou de rapport de plantage. Journal de diagnostic local, exportable à la main.
- Les seules destinations réseau : API Google (authentification, Drive), fournisseurs d'informations du §6 (titre et auteur seulement).

---

## 12. Commandes

```bash
# Application
./gradlew ktlintCheck lint test          # vérifications
./gradlew assembleDebug                  # APK de développement
./gradlew :core:library:test             # tests d'un module
./gradlew assembleRelease                # APK signé (variables de signature requises)

# Atelier TTS
cd tts && uv sync
uv run ruff check && uv run pytest
uv run tts --help
```

Variables de signature attendues (jamais dans le dépôt) : `LA_KEYSTORE_FILE`, `LA_KEYSTORE_PASSWORD`, `LA_KEY_ALIAS`, `LA_KEY_PASSWORD`. Sans elles, seule la variante de développement se construit (la connexion Google n'y fonctionnera pas, l'empreinte n'étant pas déclarée) — suffisant pour compiler et tester dans le cloud.

---

## 13. État d'avancement et journal des décisions

**À tenir à jour par le chef de projet à chaque fin de tâche significative.** C'est la mémoire du projet entre les sessions.

### Avancement

| Lot | État | Notes |
|---|---|---|
| 0 Socle et essais | En cours | 0.2, 0.3, 0.4 faits ; 0.1 en cours chez l'utilisateur (`docs/prerequis.md`) ; E1, E2, E3 codés ; recettes `docs/recettes/essai-e1.md` et `essai-e2.md` à faire sur téléphone |
| 1 Première version | En cours | 1.1 fait (Room v1, dépôts, garde-fou de position) ; 1.4 partie pure faite (`LibraryBuilder`, `NameParser`, `PartsOrBooks`) ; reste : parcours Drive réel, 1.2, 1.3, 1.5–1.10 |
| 2 Réseau | À faire | — |
| 3 Informations | À faire | — |
| 4 Usage à deux | À faire | — |
| 5 Hors ligne et confort | À faire | — |
| 6 Voiture et finitions | À faire | — |
| T0 Banc d'essai TTS | En cours | `tts bench` opérationnel (edge-tts, Piper, Kokoro ; Google, Azure, ElevenLabs, Mistral avec `--budget-max`) ; notation des 8 voix gratuites attendue ; seul Google Chirp 3 HD tient dans le plafond (1 M car. gratuits/mois) |
| T1–T4 | À faire | — |

### Points [À VÉRIFIER] — résultats

| Point | Résultat | Date |
|---|---|---|
| E1 Authentification multi-comptes, durée, `drive.file` partagé | Code prêt : sélecteur système `AccountManager` puis `AuthorizationClient` ciblé par `setAccount` ; validation sur téléphone en attente | 2026-11-14 |
| E2 Lecture en flux + cache sur Drive | Code prêt : `CacheDataSource` → `ResolvingDataSource` (compte porté par un en-tête interne retiré avant envoi) → OkHttp authentifié ; validation sur téléphone en attente | 2026-11-14 |
| E3 Chapitres intégrés M4B/MP3 | Media3 lit `CHAP` (ID3) et la piste de chapitres QuickTime, **pas** l'atome Nero `chpl` → analyseur maison par plages d'octets dans `core/library/chapters` (testé sur fichiers ffmpeg ; reste à essayer sur de vrais M4B) | 2026-11-14 |
| Catalogue Audible France / Audnexus | — | — |
| Veille voix de synthèse (modèles, tarifs) | Première passe dans `tts/docs/veille-voix.md` (sources secondaires, à recouper avant tout achat) | 2026-11-14 |

### Journal des décisions

| Date | Décision | Motif |
|---|---|---|
| 2026-11-14 | Plan initial (D1–D12) | Établi à partir du cahier des charges |
| 2026-11-14 | Versions figées au lot 0 : Gradle 9.9.1, AGP 9.5.1 (Kotlin intégré, pas de plugin `kotlin-android`), Kotlin 2.4.21, compileSdk/targetSdk 37 ; build en JDK 21, cible 17 | Dernières versions stables au 2026-11-14 |
| 2026-11-14 | Environnement cloud « livres-audio » : accès `dl.google.com` (SDK), `speech.platform.bing.com` et Hugging Face (voix TTS) ; SDK installé par `scripts/install-android-sdk.sh` | Compiler et tester dans le cloud ; banc d'essai TTS |
| 2026-11-14 | `runBlocking` toléré dans les deux intercepteurs OkHttp (jeton) : API synchrone, fils d'OkHttp uniquement | Seule façon d'appeler `getAccessToken` (suspend) depuis OkHttp |
| 2026-11-14 | Écran de consentement : pages d'accueil et de confidentialité publiées par GitHub Pages depuis `site/` (domaine `camelbaggy.github.io`) | Exigées par Google pour publier « En production » |
| 2026-11-14 | Téléphones cibles : Pixel 10 Pro XL et Pixel 9, Android 17 (API 37, = targetSdk) ; Android Auto sans fil ; applications remplacées : CloudBeats (les deux) et Smart AudioBook Player (utilisateur) | Prérequis partie C |
| 2026-11-14 | §5.2 règle 1 assouplie : un dossier peut contenir plusieurs livres d'un seul fichier (détection par les noms) ; `bookId` d'un livre d'un seul fichier dérivé de `driveFileId` (§4.4) | Arborescences réelles (prérequis D) : un `.m4b` par tome dans un dossier de série, cas dominant du Drive personnel |
| 2026-11-14 | Atelier TTS : PC Windows 11, Core i9-13900H, 24 Go, **pas de GPU dédié** (Iris Xe) → moteurs locaux lourds (XTTS, F5, Chatterbox, Kyutai) écartés par défaut, locaux légers (Piper, Kokoro) et en ligne privilégiés. Plafond **5 € par tome (10 € par tome si la différence est frappante)** ; essais payants acceptés, **paliers gratuits / versions d'essai d'abord** | Prérequis partie E |
| 2026-11-14 | CLI TTS en `argparse` (pas de dépendance) ; moteurs lourds en extras `uv` optionnels | Installation de base légère |
