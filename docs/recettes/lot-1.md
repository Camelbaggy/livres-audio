# Recette du lot 1 — première version utilisable

Objectif : vérifier sur votre téléphone que l'application peut remplacer CloudBeats pour l'écoute courante. Chaque étape indique quoi faire, puis ce que vous devez constater.

**Durée** : environ 45 minutes, plus deux vérifications le lendemain.

**Matériel** : votre téléphone, des écouteurs ou un casque Bluetooth, et si possible un chargeur.

Notez pour chaque point : ✅ conforme, ⚠️ conforme avec une remarque, ou ❌ non conforme (décrivez ce qui s'est passé). À la fin, envoyez-moi la liste. En cas de problème, joignez le journal : menu ⋮ → Outils de diagnostic → Essai E1 → Copier le journal.

---

## 0. Installer la version du lot 1

1. Je vous envoie le lien de l'APK quand il est prêt. Sur la page GitHub Actions, téléchargez l'artefact **livres-audio-release** et extrayez-en l'APK.
2. Ouvrez l'APK sur le téléphone. Android propose une **mise à jour** de « Livres audio » si l'essai E1 est déjà installé : acceptez.

## 1. Mise en route (comptes et dossiers)

| # | À faire | Résultat attendu |
|---|---|---|
| 1.1 | Ouvrez l'appli. Si vous aviez fait l'essai E1, les comptes sont déjà connus : allez dans **⋮ → Comptes et dossiers**. Sinon, suivez l'accueil. | Un accueil clair, en français. |
| 1.2 | Choisissez **Drive commun**, puis connectez le compte familial. | Le sélecteur de comptes Google s'affiche, puis l'autorisation (l'avertissement « application non validée » s'affiche seulement la première fois). |
| 1.3 | Dans le navigateur de dossiers, ouvrez `Famille` puis sélectionnez `Livres audio`, et appuyez sur **Choisir ce dossier**. | Le dossier apparaît dans la liste des dossiers choisis. |
| 1.4 | **Ajouter un autre compte** : choisissez **Mon Drive personnel**, connectez votre compte, puis choisissez `Documents/Livres audio`. | Les deux comptes et les deux dossiers sont listés. |
| 1.5 | Appuyez sur **Terminer**. | La bibliothèque s'ouvre. |

## 2. Bibliothèque

| # | À faire | Résultat attendu |
|---|---|---|
| 2.1 | Patientez 1 à 3 minutes (premier parcours). Tirez la liste vers le bas pour rafraîchir. | Les livres apparaissent. Il y a **un livre par tome** dans les séries du Drive personnel (pas un seul livre de 15 fichiers) et **un livre par dossier** dans le Drive commun. |
| 2.2 | Regardez 10 livres au hasard. | Titre, auteur, série et tome sont corrects ou plausibles. Notez les erreurs (exemple : « tome 3.5 affiché comme tome 3 »). |
| 2.3 | Attendez quelques minutes de plus (lecture des étiquettes). | Les durées et des couvertures apparaissent progressivement. Un livre sans image affiche une couverture générée avec ses initiales. |
| 2.4 | Cherchez un titre en tapant un mot **sans accent** (exemple : `epines`). | Le livre accentué est trouvé. |
| 2.5 | Changez le tri : titre, auteur, date d'ajout, dernier écouté. | L'ordre change en conséquence. |
| 2.6 | Repérez la petite pastille **Commun / Perso** sur les couvertures. | La pastille correspond au Drive d'où vient le livre. |

## 3. Lecture et chapitres

| # | À faire | Résultat attendu |
|---|---|---|
| 3.1 | Ouvrez la fiche d'un livre **multi-fichiers du Drive commun**, puis appuyez sur **Lire**. | La lecture démarre en quelques secondes. La liste des chapitres correspond aux fichiers, dans l'ordre (Prologue avant Chapitre 1, 2 avant 10). |
| 3.2 | Ouvrez un **gros `.m4b`** du Drive personnel. | La fiche montre les **chapitres intégrés** avec leurs titres (s'il en a). La lecture démarre. |
| 3.3 | Dans le lecteur : chapitre suivant, puis chapitre précédent (une fois dans les 3 premières secondes, une fois après). | « Précédent » revient au **début du chapitre** si on y est depuis plus de 3 s, sinon au chapitre d'avant. |
| 3.4 | Faites glisser la barre de progression du chapitre. | Le déplacement se fait dans le chapitre en cours. |
| 3.5 | Utilisez le recul (10 s) et l'avance (30 s), y compris près du début d'un fichier. | Le recul peut revenir dans le fichier précédent. |

## 4. Vitesse

| # | À faire | Résultat attendu |
|---|---|---|
| 4.1 | Mettez la vitesse à **1,5×** puis **2,0×**. | Voix accélérée **sans effet « chipmunk »** : la hauteur de la voix ne change pas. |
| 4.2 | Ouvrez un autre livre, puis revenez au premier. | Le premier livre a gardé **sa** vitesse ; l'autre livre est à la vitesse par défaut. |
| 4.3 | Regardez le temps restant du livre à 2,0×. | Il est à peu près divisé par deux par rapport à 1,0×. |

## 5. Retour arrière à la reprise

| # | À faire | Résultat attendu |
|---|---|---|
| 5.1 | Faites une pause de **2 s**, puis reprenez. | Pas de retour en arrière. |
| 5.2 | Faites une pause de **30 s**, puis reprenez. | Retour d'environ **3 s**. |
| 5.3 | Faites une pause de **2 min**, puis reprenez. | Retour d'environ **10 s**. |
| 5.4 | Mettez en pause depuis le **casque Bluetooth**, attendez 2 min, puis reprenez depuis le casque. | Même retour de 10 s : il marche aussi au casque. |
| 5.5 | Pendant la lecture, appelez-vous depuis un autre téléphone (ou demandez à quelqu'un de vous appeler), raccrochez au bout de 30 s. | La lecture reprend avec un léger retour en arrière. |
| 5.6 | Dans **⋮ → Réglages**, désactivez le retour arrière, faites une pause de 2 min, puis reprenez. | Aucun retour. Réactivez-le ensuite. |

## 6. Arrière-plan, écran verrouillé, casque

| # | À faire | Résultat attendu |
|---|---|---|
| 6.1 | Lancez la lecture et verrouillez l'écran. | La lecture continue. L'écran de verrouillage affiche les commandes et le titre. |
| 6.2 | Utilisez la notification : pause, lecture, et les boutons avance/recul. | Tout fonctionne. |
| 6.3 | Sur le casque Bluetooth : lecture/pause, puis suivant et précédent. | Lecture/pause fonctionnent. Suivant et précédent font des **sauts** (30 s / 10 s) par défaut. |
| 6.4 | Dans les Réglages, passez « Boutons du casque » sur **Chapitres**, puis refaites suivant et précédent. | Ils changent maintenant de **chapitre**. |
| 6.5 | Débranchez le casque filaire ou éteignez le casque Bluetooth en pleine lecture. | La lecture se **met en pause** (elle ne bascule pas sur le haut-parleur). |

## 7. Position : la règle d'or

| # | À faire | Résultat attendu |
|---|---|---|
| 7.1 | Écoutez 2 minutes un livre, notez la position (chapitre et temps). Balayez l'appli dans la liste des applis récentes. Rouvrez-la et reprenez. | Reprise **exactement** à la position notée, moins le retour arrière. |
| 7.2 | Écoutez un autre livre, puis **éteignez complètement le téléphone** en pleine lecture (ou laissez la batterie s'épuiser si vous en avez l'occasion). Rallumez-le et rouvrez l'appli. | Le bandeau **Reprendre** propose ce livre, à la position où vous étiez (à ~5 s près). |
| 7.3 | Lisez 3 livres différents, quelques minutes chacun, en alternant. | Chaque livre reprend à **sa** position. Rien n'est jamais remis à zéro. |
| 7.4 | Sur une fiche, faites **Remettre à zéro**. | Une confirmation est demandée. Après validation, le livre repart du début. |
| 7.5 | Sur une fiche, faites **Marquer comme terminé**. | Le livre apparaît comme terminé (barre pleine). |

## 7 bis. Comptes et dossiers

| # | À faire | Résultat attendu |
|---|---|---|
| 7b.1 | **⋮ → Réglages → Comptes et dossiers**. | Vos deux comptes, leur rôle et leurs dossiers sont listés. |
| 7b.2 | Appuyez sur **Reconnecter** pour le compte commun. | L'écran de consentement Google s'affiche pour **ce** compte ; aucune nouvelle ligne de compte n'est créée. |
| 7b.3 | Ajoutez puis retirez un dossier de test. | Le dossier apparaît puis disparaît ; ses livres disparaissent de la bibliothèque, les autres restent. |
| 7b.4 | Regardez un livre dont le dossier contient une image `cover.jpg` (sans couverture intégrée). | Sa couverture s'affiche. |

## 8. Le lendemain

| # | À faire | Résultat attendu |
|---|---|---|
| 8.1 | Sans avoir rouvert l'appli depuis la veille, ouvrez-la et reprenez le livre en cours. | Pas de demande de reconnexion. Reprise à la bonne position avec un retour de **30 s** (pause de plus d'une heure). |
| 8.2 | Ajoutez un nouveau livre dans votre Drive (depuis l'ordinateur), puis ouvrez l'appli et tirez pour rafraîchir. | Le nouveau livre apparaît (peut prendre une minute). |

---

## Ce que je sais déjà ne pas être fait (inutile de le signaler)

- Pas encore de reprise **automatique** après une longue coupure réseau, ni de pré-chargement poussé (lot 2).
- Pas encore de marque-pages, de minuterie ni de téléchargement hors ligne (lot 5).
- Pas d'informations en ligne (résumés, couvertures HD) : c'est le lot 3.
- Pas encore de synchronisation entre les deux téléphones (lot 4).
- Android Auto n'est pas encore pris en charge (lot 6).
