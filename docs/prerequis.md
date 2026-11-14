# Prérequis à fournir (lot 0) — guide pas à pas

Ce guide vous accompagne pour tout ce que je ne peux pas faire moi-même. Il faut agir dans la console Google, sur votre ordinateur et sur vos téléphones. Chaque étape dit **où cliquer**, **quoi taper** et **à quoi vous attendre**. Chaque partie se termine par une case « C'est fait si… » pour vérifier que tout est bon avant de continuer.

**Durée totale** : environ 1 h 30. Vous pouvez faire une partie à la fois et vous arrêter entre deux.

**Ordre conseillé** : la partie A d'abord, puis la partie B, car la B a besoin d'un résultat de la A. Les parties C, D et E sont indépendantes et peuvent se faire n'importe quand.

| Partie | Sujet | Durée | Ce que j'en fais |
|---|---|---|---|
| A | Clé de signature de l'application | 25 min | Signer chaque version de l'application |
| B | Projet Google Cloud (accès à Drive) | 30 min | Permettre à l'application de se connecter à vos comptes Google |
| C | Informations sur les deux téléphones | 5 min | Choisir les réglages de compatibilité |
| D | Aperçu de vos dossiers de livres | 15 min | Écrire les tests de reconnaissance des noms |
| E | Atelier de voix de synthèse | 10 min | Choisir les voix testables et le budget |

> **Règle de sécurité valable pour tout le guide**
> Ne m'envoyez **jamais** de mot de passe, ni le fichier de clé (`.jks`), ni son contenu encodé. Ces éléments ne vont qu'à deux endroits : votre gestionnaire de mots de passe et les « secrets » GitHub (partie A, étape 5). Tout ce que vous devez me renvoyer est indiqué dans le récapitulatif final.

---

## Partie A — La clé de signature (25 min)

### Pourquoi ?

Android n'installe une mise à jour que si elle est « signée » avec la même clé que la version déjà installée. Cette clé est un petit fichier protégé par mot de passe. Google s'en sert aussi pour reconnaître votre application lors de la connexion à Drive (partie B).

**Si la clé est perdue**, aucune mise à jour ne pourra plus s'installer. Il faudrait désinstaller l'application sur les deux téléphones et recommencer la configuration Google. D'où les sauvegardes de l'étape 4.

### Étape A1 — Installer Java (s'il n'est pas déjà installé)

L'outil qui fabrique la clé, `keytool`, est fourni avec Java.

**Vérifier si Java est déjà là :**

- **Windows** : appuyez sur la touche Windows, tapez `powershell`, puis ouvrez **Windows PowerShell**. Dans la fenêtre bleue (ou noire), tapez `keytool -help` puis Entrée.
- **Mac** : ouvrez **Terminal** (Launchpad → Autres → Terminal, ou Cmd+Espace puis « Terminal »). Tapez `keytool -help` puis Entrée.

Deux cas :

- Une longue liste de commandes s'affiche : Java est présent, **passez à l'étape A2**.
- Un message du type « n'est pas reconnu » ou « command not found » s'affiche : installez Java comme suit.

**Installer Java :**

1. Allez sur <https://adoptium.net/fr/temurin/releases/>.
2. Choisissez votre système (Windows, macOS), l'architecture (x64 en général ; **aarch64** pour un Mac à puce Apple M1/M2/M3/M4), le type **JDK** et la version **21 – LTS**. Téléchargez le fichier `.msi` (Windows) ou `.pkg` (Mac).
3. Lancez l'installateur et acceptez les choix par défaut. **Sous Windows**, sur l'écran « Custom Setup », vérifiez que « Set JAVA_HOME variable » et « Add to PATH » sont cochés : cliquez sur l'icône à gauche de chaque ligne, puis sur « Will be installed on local hard drive ».
4. **Fermez** la fenêtre PowerShell ou Terminal, ouvrez-en une nouvelle et retapez `keytool -help`. La liste doit maintenant s'afficher.

### Étape A2 — Créer un dossier de travail

Créez un dossier facile à retrouver : sous Windows `Documents\livres-audio-cle`, sur Mac `Documents/livres-audio-cle`.

Dans PowerShell ou Terminal, placez-vous dans ce dossier :

- **Windows** : `cd $HOME\Documents\livres-audio-cle`
- **Mac** : `cd ~/Documents/livres-audio-cle`

### Étape A3 — Fabriquer la clé

Copiez-collez cette commande **en une seule fois**. Sous Windows, dans PowerShell, un clic droit colle le texte.

**Windows (PowerShell)** — tout sur une ligne :

```
keytool -genkeypair -v -keystore livres-audio.jks -alias livres-audio -keyalg RSA -keysize 4096 -validity 36500
```

**Mac** — la même commande :

```
keytool -genkeypair -v -keystore livres-audio.jks -alias livres-audio -keyalg RSA -keysize 4096 -validity 36500
```

Le programme pose ensuite des questions. Voici quoi répondre :

| Question affichée (en anglais ou en français) | Quoi répondre |
|---|---|
| `Enter keystore password` / « Entrez le mot de passe du fichier de clés » | Un mot de passe solide (au moins 12 caractères). **Rien ne s'affiche quand vous tapez, c'est normal.** Entrée. |
| `Re-enter new password` / « Ressaisissez… » | Le même mot de passe. Entrée. |
| `What is your first and last name?` / « Quels sont vos nom et prénom ? » | Ce que vous voulez, par exemple `Livres audio`. Personne ne le verra. |
| Unité organisationnelle, organisation, ville, région | Appuyez simplement sur **Entrée** à chaque fois (laisser vide). |
| Code pays à deux lettres | `FR` |
| `Is CN=… correct? [no]` / « Est-ce correct ? [non] » | Tapez `yes` (ou `oui` si la question est en français) puis Entrée. |
| Éventuel mot de passe de la clé elle-même | S'il est demandé, appuyez sur **Entrée** pour reprendre le même mot de passe. |

Le message final ressemble à « Generating 4,096 bit RSA key pair… » ou « Stockage de livres-audio.jks ». Le fichier `livres-audio.jks` apparaît dans le dossier.

**Notez tout de suite** dans votre gestionnaire de mots de passe, dans une fiche « Livres audio — clé de signature » :

- le mot de passe ;
- l'alias : `livres-audio`.

### Étape A4 — Sauvegarder la clé (2 copies)

Copiez le fichier `livres-audio.jks` à **deux** endroits sûrs, en dehors de l'ordinateur. Par exemple :

1. en pièce jointe de la fiche du gestionnaire de mots de passe (Bitwarden, 1Password, KeePass… le permettent) ;
2. sur une clé USB rangée à la maison, ou dans un dossier privé de votre Drive personnel (pas le Drive commun).

⚠️ Ne le mettez **jamais** dans le dépôt GitHub, ni dans un courriel, ni dans une conversation.

### Étape A5 — Relever l'empreinte SHA-1 (pour la partie B)

Toujours dans le même dossier, tapez :

```
keytool "-J-Duser.language=en" -list -v -keystore livres-audio.jks -alias livres-audio
```

Les guillemets autour de `"-J-Duser.language=en"` sont indispensables sous PowerShell, qui sinon coupe l'option au point. Entrez le mot de passe. L'option `-J-Duser.language=en` affiche le résultat en anglais. Elle contourne un bogue de `keytool` en français, qui s'arrête sur `MissingFormatArgumentException: Format specifier '%2$s'` avant d'afficher l'empreinte. Dans le texte qui s'affiche, cherchez la ligne qui commence par **`SHA1:`**. Elle ressemble à ceci (vos chiffres seront différents) :

```
SHA1: 3A:7F:0C:91:...:E4:22
```

Copiez toute la suite après `SHA1: ` (20 paires de caractères séparées par `:`) dans la fiche du gestionnaire de mots de passe. Elle servira à l'étape B6. L'empreinte n'est pas secrète, vous pourrez aussi me la donner si besoin.

### Étape A6 — Donner la clé à GitHub (secrets)

GitHub fabrique l'application signée pour vous. Il a donc besoin de la clé, qu'il garde chiffrée dans des « secrets » que personne ne peut relire, pas même moi.

**1. Préparer le contenu du fichier en texte (base64)**

- **Windows (PowerShell)**, dans le dossier de la clé :

  ```
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\livres-audio.jks")) | Set-Clipboard
  ```

  Rien ne s'affiche, mais le texte est maintenant **dans le presse-papiers**.
- **Mac** :

  ```
  base64 -i livres-audio.jks | pbcopy
  ```

  Même résultat : le texte est dans le presse-papiers.

**2. Ouvrir la page des secrets**

1. Allez sur <https://github.com/Camelbaggy/livres-audio> (connecté à votre compte GitHub).
2. Cliquez sur l'onglet **Settings** (roue dentée, en haut à droite de la barre d'onglets du dépôt).
3. Dans le menu de gauche : **Secrets and variables**, puis **Actions**.
4. Bouton vert **New repository secret**.

**3. Créer les 4 secrets**, un par un. Pour chacun, remplissez **Name** et **Secret**, puis cliquez sur **Add secret** :

| Name (copier exactement) | Secret |
|---|---|
| `LA_KEYSTORE_BASE64` | Collez le presse-papiers (Ctrl+V / Cmd+V) : un très long texte sans espaces |
| `LA_KEYSTORE_PASSWORD` | Le mot de passe de l'étape A3 |
| `LA_KEY_ALIAS` | `livres-audio` |
| `LA_KEY_PASSWORD` | Le même mot de passe (sauf si vous en avez saisi un second à l'étape A3) |

Une fois les quatre créés, la page affiche ces 4 noms dans « Repository secrets ». Les valeurs ne sont plus jamais visibles, c'est normal.

**4. Vider le presse-papiers** : copiez n'importe quel mot pour remplacer le long texte.

### ✅ C'est fait si…

- [ ] `livres-audio.jks` existe, avec 2 copies hors de l'ordinateur ;
- [ ] le mot de passe, l'alias et l'empreinte SHA-1 sont notés dans le gestionnaire de mots de passe ;
- [ ] les 4 secrets apparaissent dans GitHub → Settings → Secrets and variables → Actions.

---

## Partie B — Projet Google Cloud : autoriser l'application à lire Drive (30 min)

### Pourquoi ?

Pour qu'une application se connecte à un compte Google, Google exige qu'elle soit déclarée dans un « projet » de sa console développeur. C'est gratuit, sans carte bancaire. On y déclare trois choses :

- quelles données l'application demande (lecture de Drive) ;
- qui peut l'utiliser (n'importe quel compte Google, donc les vôtres) ;
- comment la reconnaître (son nom de paquet et l'empreinte SHA-1 de la partie A).

### Avant de commencer

- Utilisez un ordinateur (la console est pénible sur téléphone).
- Choisissez **le compte Google propriétaire du projet**. Je conseille votre compte **personnel**. Ce compte n'a rien de spécial pour l'application : vos deux comptes et ceux de votre compagne pourront s'y connecter quoi qu'il arrive.
- La console peut s'afficher en français ou en anglais. Ce guide donne les deux libellés, sous la forme « Français / English ». Google réorganise parfois ses menus : si un libellé diffère un peu, cherchez le plus proche, ou utilisez la barre de recherche en haut de la console.

### Étape B1 — Ouvrir la console et accepter les conditions

1. Allez sur <https://console.cloud.google.com/> et connectez-vous avec le compte choisi.
2. À la première visite, une fenêtre demande d'accepter les conditions d'utilisation. Choisissez le pays **France**, cochez l'acceptation, puis **Accepter et continuer / Agree and continue**.
3. Si on vous propose un « essai gratuit » avec carte bancaire, **ignorez-le** (fermez ou « Ignorer / Dismiss »). Il n'est pas nécessaire.

### Étape B2 — Créer le projet

1. En haut à gauche, à côté du logo « Google Cloud », cliquez sur le **sélecteur de projet** (une liste déroulante, parfois libellée « Sélectionner un projet / Select a project »).
2. Dans la fenêtre qui s'ouvre, en haut à droite : **Nouveau projet / New project**.
3. **Nom du projet / Project name** : `Livres audio`. Laissez « Organisation / Emplacement » sur « Aucune organisation / No organization ».
4. **Créer / Create**. Attendez environ 30 secondes : une cloche de notification indique la fin.
5. **Vérifiez** que le sélecteur de projet en haut affiche bien « Livres audio ». Sinon, cliquez dessus et choisissez-le. Toutes les étapes suivantes doivent se faire **dans ce projet**.

### Étape B3 — Activer l'API Google Drive

1. Dans la barre de recherche en haut, tapez `Google Drive API` et choisissez le résultat **Google Drive API** (catégorie « Marketplace » ou « API »).
2. Sur la page de l'API, cliquez sur le bouton bleu **Activer / Enable**.
3. Le bouton devient « Gérer / Manage » : c'est activé.

### Étape B4 — Configurer l'écran de consentement (Google Auth Platform)

C'est l'écran que Google montre au moment de connecter un compte : « Livres audio souhaite accéder à… ».

1. Menu ☰ en haut à gauche → **API et services / APIs & Services** → **Écran de consentement OAuth / OAuth consent screen**. Ou tapez `Google Auth Platform` dans la barre de recherche.
2. Si la page affiche « Google Auth Platform n'est pas encore configuré », cliquez sur **Commencer / Get started**. Un assistant en 4 parties s'ouvre :
   1. **Informations sur l'application / App information**
      - Nom de l'application / App name : `Livres audio`
      - Adresse e-mail d'assistance utilisateur / User support email : choisissez votre adresse dans la liste.
      - **Suivant / Next**.
   2. **Audience** : choisissez **Externe / External**. L'option « Interne » n'existe que pour les entreprises. **Suivant**.
   3. **Coordonnées / Contact information** : votre adresse e-mail. **Suivant**.
   4. **Terminer / Finish** : cochez l'acceptation du règlement sur les données utilisateur des API Google, puis **Continuer**, puis **Créer / Create**.

### Étape B5 — Déclarer les autorisations demandées (« champs d'application »)

1. Dans le menu de gauche de Google Auth Platform : **Accès aux données / Data access**.
2. Bouton **Ajouter ou supprimer des champs d'application / Add or remove scopes**. Un panneau s'ouvre à droite.
3. Tout en bas du panneau, repérez la zone **Ajouter manuellement des champs d'application / Manually add scopes**. Collez-y ces trois lignes, d'un coup :

   ```
   https://www.googleapis.com/auth/drive.readonly
   https://www.googleapis.com/auth/drive.appdata
   https://www.googleapis.com/auth/drive.file
   ```

4. Cliquez sur **Ajouter au tableau / Add to table**, puis **Mettre à jour / Update** en bas du panneau.
5. De retour sur la page, les trois apparaissent :
   - `drive.file` sous « Champs d'application non sensibles » ;
   - `drive.appdata` et `drive.readonly` sous « … sensibles » ou « … restreints ».
6. **Enregistrer / Save** en bas de la page.

Ce que signifient ces trois autorisations :

- `drive.readonly` : **lire** vos livres. L'application ne pourra ni les modifier ni les supprimer.
- `drive.appdata` : un dossier caché, propre à l'application, pour sauvegarder vos positions d'écoute.
- `drive.file` : écrire uniquement les fichiers que l'application crée elle-même (le dossier `.livres-audio/` du Drive commun).

### Étape B6 — Créer l'identifiant de l'application Android

1. Menu de gauche : **Clients**. Si cette entrée n'existe pas : ☰ → API et services → **Identifiants / Credentials**.
2. **Créer un client / Create client**, ou bien **+ Créer des identifiants → ID client OAuth / Create credentials → OAuth client ID**.
3. **Type d'application / Application type** : **Android**.
4. **Nom / Name** : `Livres audio Android`.
5. **Nom du package / Package name** : copiez exactement

   ```
   io.github.camelbaggy.livresaudio
   ```

6. **Empreinte du certificat SHA-1 / SHA-1 certificate fingerprint** : collez l'empreinte relevée à l'étape A5, au format `3A:7F:0C:…` avec les deux-points.
7. Laissez le reste par défaut (pas de « schéma d'URI personnalisé »). Cliquez sur **Créer / Create**.
8. Une fenêtre affiche un « ID client » (`…apps.googleusercontent.com`). **Vous n'avez rien à en faire** : l'application est reconnue grâce au nom de paquet et à l'empreinte. Fermez la fenêtre.

### Étape B6 bis — Compléter la page Branding

Le bouton « Publier » de l'étape B7 reste grisé tant que la page **Branding** n'est pas complète. Google y exige une page d'accueil et des règles de confidentialité. Je les ai rédigées : elles sont publiées gratuitement par GitHub Pages.

1. **Activer GitHub Pages (une seule fois)** : sur GitHub, ouvrez le dépôt → **Settings** → **Pages** (menu de gauche). Sous « Build and deployment », réglez **Source** sur **GitHub Actions**. Il n'y a rien d'autre à faire, l'enregistrement est automatique.
2. Ouvrez l'onglet **Actions** du dépôt → workflow **« Site (pages d'accueil et de confidentialité) »** → **Run workflow** → **Run workflow**. Après 1 minute environ, la page <https://camelbaggy.github.io/livres-audio/> doit s'afficher.
3. Dans la console Google : **Branding**, puis remplissez :
   - **Logo** : **ne rien mettre**, car un logo déclenche une validation par Google ;
   - **Page d'accueil de l'application / Application home page** : `https://camelbaggy.github.io/livres-audio/` ;
   - **Lien vers les règles de confidentialité / Privacy policy link** : `https://camelbaggy.github.io/livres-audio/confidentialite.html` ;
   - **Lien vers les conditions d'utilisation / Terms of service link** (s'il est demandé) : `https://camelbaggy.github.io/livres-audio/conditions.html` ;
   - **Domaines autorisés / Authorized domains** : **Ajouter un domaine**, puis `camelbaggy.github.io` ;
   - **Coordonnées du développeur** : votre adresse.
4. **Enregistrer / Save**.

### Étape B7 — Publier l'application « en production » (important)

Tant que l'application est « en test », Google déconnecte les comptes **tous les 7 jours**. C'est contraire à votre souhait de rester connecté durablement.

1. Menu de gauche : **Audience**.
2. Section « État de publication / Publishing status » : il est écrit **Test / Testing**.
3. Cliquez sur **Publier l'application / Publish app**, puis confirmez.
4. Si Google indique qu'une **validation** (« verification ») est nécessaire à cause des champs d'application sensibles ou restreints, **ne lancez pas** la demande de validation. On n'en a pas besoin pour un usage à deux. L'état doit afficher **En production / In production**, éventuellement avec la mention « Validation requise / Needs verification ». C'est attendu.

**Conséquence normale** : lors de la toute première connexion de chaque compte, Google affichera un avertissement « **Google n'a pas validé cette application** ». Il faudra cliquer sur **Paramètres avancés / Advanced**, puis **Accéder à Livres audio (non sécurisé) / Go to Livres audio (unsafe)**. Ce sera expliqué dans la recette de l'essai E1. Une application non validée est limitée à 100 utilisateurs : largement assez pour deux.

### ✅ C'est fait si…

- [ ] le projet « Livres audio » existe et est sélectionné ;
- [ ] Google Drive API est « activée » ;
- [ ] l'audience est **Externe** et l'état de publication **En production** ;
- [ ] les 3 champs d'application `drive.readonly`, `drive.appdata` et `drive.file` sont listés dans « Accès aux données » ;
- [ ] un client **Android** existe avec le paquet `io.github.camelbaggy.livresaudio` et votre SHA-1.

En cas de doute, envoyez-moi une capture d'écran de la page **Audience** et de la page **Clients**. Ces pages ne montrent aucun secret, mais masquez votre adresse e-mail si vous préférez.

---

## Partie C — Les deux téléphones (5 min)

Pour **chaque** téléphone (le vôtre et celui de votre compagne) :

1. **Marque et modèle** : Paramètres → **À propos du téléphone**. Le nom est en haut, par exemple « Galaxy S23 » ou « Pixel 8 ».
2. **Version d'Android** : même écran, ligne **Version d'Android**. Sur Samsung : À propos du téléphone → **Informations sur le logiciel**.
3. **Android Auto** : l'utilisez-vous en voiture ? (oui/non). Si oui, est-ce avec un câble ou sans fil ?
4. **Quelle application de livres audio** est utilisée aujourd'hui sur ce téléphone (CloudBeats, Smart AudioBook Player…) ?

Rien à installer pour l'instant. Quand le premier APK sera prêt, la recette de l'essai E1 expliquera comment autoriser l'installation hors Play Store.

---

## Partie D — Un aperçu de vos dossiers de livres (15 min)

Je dois tester que l'application comprend vos noms de dossiers : auteur, titre, série, tome, « CD1 », etc. Pour cela, j'ai besoin de **voir à quoi ils ressemblent**, pas des fichiers eux-mêmes.

**Ce que j'attends** : 30 à 50 lignes représentatives, avec :

- l'emplacement : **Drive commun** ou **Drive personnel** (le vôtre ou celui de votre compagne) ;
- les **noms des dossiers** sur 2–3 niveaux ;
- pour quelques livres, **2–3 noms de fichiers** (par exemple `01 - Chapitre 1.mp3`, `Partie 2.m4b`) ;
- en priorité les cas **les moins bien rangés** : noms bizarres, séries, livres en plusieurs CD, fichiers seuls, etc.

**Comment faire le plus simplement** (au choix) :

- **Option 1 — à la main** : ouvrez <https://drive.google.com>, naviguez dans vos dossiers et recopiez les noms dans un message, avec une indentation pour montrer les niveaux :

  ```
  [Drive commun] Livres audio/
    Druon, Maurice - Les Rois maudits/
      T1 - Le Roi de fer/
        CD 1/   01 Piste 1.mp3, 02 Piste 2.mp3
        CD 2/   ...
    Un livre seul (lu par Untel).m4b
  [Drive perso] Mes livres/
    ...
  ```

- **Option 2 — captures d'écran** de Drive en vue Liste, une par dossier intéressant.
- **Option 3 — si « Google Drive pour ordinateur » est installé** (le Drive apparaît comme un lecteur `G:` sous Windows) : dans PowerShell, `tree "G:\Mon Drive\Livres audio" /F > $HOME\Desktop\arbo.txt`, puis envoyez-moi le fichier `arbo.txt` en retirant ce qui est trop personnel.

Ces noms ne seront **pas** recopiés dans le dépôt. Les tests utiliseront des noms inventés **de même forme**.

---

## Partie E — Atelier de voix de synthèse (10 min)

L'outil qui crée des livres audio à partir d'EPUB tourne sur **votre ordinateur**. J'ai besoin de :

1. **Votre ordinateur** :
   - **Windows** : Ctrl+Maj+Échap → onglet **Performance**. Notez :
     - **Processeur** : le nom en haut à droite de « Processeur / CPU » ;
     - **Mémoire** : le total ;
     - **GPU** : le nom et la « Mémoire GPU dédiée ».
   - **Mac** : menu  → **À propos de ce Mac**. Notez la puce (M1, M2… ou Intel) et la mémoire.
   - Système (Windows 10/11, macOS …) et espace disque libre (au moins 20 Go souhaités).
2. **Votre plafond de dépense** si une voix payante l'emporte : par tome (ordre de grandeur : 15 à 30 € par tome chez les fournisseurs courants, jusqu'à 80 € chez les plus chers) et au total pour la saga (13 tomes). « 0 € » est une réponse valable : on restera alors sur les voix gratuites.
3. **Votre accord, ou non, pour tester des voix payantes sur l'extrait** de 3 minutes. Cela coûte quelques centimes par voix, mais nécessite de créer un compte chez chaque fournisseur. Je vous guiderai le moment venu.
4. **L'EPUB du tome 1** de « Fortune de France », **sans DRM**. Gardez-le de votre côté pour l'instant : ne le mettez pas dans GitHub. Je vous dirai où le déposer au lot T1.

En attendant, je prépare un premier banc d'écoute avec les voix gratuites, que je vous enverrai.

---

## Récapitulatif — ce que vous me renvoyez

Copiez ce bloc dans votre réponse et complétez-le :

```
A. Clé de signature : faite, 2 sauvegardes, 4 secrets GitHub créés  → oui / non / bloqué à l'étape …
B. Google Cloud : projet, API Drive, Externe + En production, 3 scopes, client Android → oui / non / bloqué à l'étape …
C. Mon téléphone : marque/modèle …, Android …, Android Auto oui/non (câble/sans fil), appli actuelle …
   Téléphone de ma compagne : marque/modèle …, Android …, Android Auto oui/non, appli actuelle …
D. Arborescence : (collée ci-dessous / captures jointes / arbo.txt joint)
E. Ordinateur : système …, processeur …, mémoire …, GPU … (mémoire …), disque libre …
   Plafond : … € par tome, … € au total. Essais payants sur l'extrait : oui / non
```

**Ne mettez dans ce message** ni mot de passe, ni fichier `.jks`, ni texte base64.

Dès que A et B sont faits, je lance la construction de l'APK signé et vous envoie la recette de l'essai E1 : installation sur le téléphone et connexion des deux comptes.
