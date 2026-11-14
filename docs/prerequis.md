# Prérequis à fournir (lot 0)

Ce document liste, en une seule fois, ce dont le projet a besoin de votre part. Les agents ne peuvent pas le faire eux-mêmes : il faut agir dans la console Google, sur votre ordinateur et sur les téléphones.

Rien de ce qui suit ne doit être collé dans une conversation, un ticket ou le dépôt **sauf mention contraire**. Les mots de passe et la clé de signature restent chez vous et dans les secrets GitHub.

Temps estimé : 45 minutes à 1 heure, en une fois.

---

## 1. Clé de signature de l'application (à faire en premier)

L'application est installée à partir d'un APK signé. **Toutes** les versions doivent être signées avec la même clé. Si vous la perdez, il faudra désinstaller l'application sur les deux téléphones, ce qui efface le cache et les téléchargements. Les positions d'écoute, elles, sont sauvegardées dans Drive à partir du lot 4.

Sur votre ordinateur (Java requis ; `keytool` est fourni avec Java ou Android Studio) :

```bash
keytool -genkeypair -v \
  -keystore livres-audio.jks \
  -alias livres-audio \
  -keyalg RSA -keysize 4096 -validity 36500
```

- Choisissez un mot de passe solide et notez-le dans votre gestionnaire de mots de passe.
- Répondez aux questions sur le nom et la ville comme vous voulez. Ces informations ne sont visibles par personne.
- Faites **deux sauvegardes** de `livres-audio.jks` hors de l'ordinateur, par exemple sur une clé USB et dans votre gestionnaire de mots de passe. Ne la mettez jamais dans le dépôt.

Relevez l'empreinte SHA-1, utile à l'étape 2 :

```bash
keytool -list -v -keystore livres-audio.jks -alias livres-audio | grep SHA1
```

### Secrets GitHub

Dans GitHub, ouvrez le dépôt `livres-audio`, puis **Settings → Secrets and variables → Actions → New repository secret**. Créez ces quatre secrets :

| Nom | Valeur |
|---|---|
| `LA_KEYSTORE_BASE64` | le contenu du fichier encodé en base64 : `base64 -w0 livres-audio.jks` (Linux) ou `base64 -i livres-audio.jks` (macOS) |
| `LA_KEYSTORE_PASSWORD` | le mot de passe du fichier |
| `LA_KEY_ALIAS` | `livres-audio` |
| `LA_KEY_PASSWORD` | le mot de passe de la clé (le même si vous n'en avez saisi qu'un) |

La tâche manuelle « APK signé » de GitHub Actions s'en servira pour produire l'APK à installer.

## 2. Projet Google Cloud (accès à Google Drive)

Utilisez le compte Google de votre choix, de préférence votre compte personnel. Ce compte devient propriétaire du projet. Il n'a pas besoin d'être l'un des comptes de livres.

1. Ouvrez <https://console.cloud.google.com/>, puis **Créer un projet**, nommé par exemple « Livres audio ».
2. Allez dans **API et services → Bibliothèque**, cherchez **Google Drive API**, puis cliquez sur **Activer**.
3. Configurez l'**écran de consentement OAuth**. Selon la version de la console, c'est le menu **Google Auth Platform** ou **API et services → Écran de consentement OAuth**.
   - Type d'utilisateur : **Externe**.
   - Nom de l'application : « Livres audio » ; adresse d'assistance : la vôtre.
   - Accès aux données / Champs d'application : ajoutez `.../auth/drive.readonly`, `.../auth/drive.appdata` et `.../auth/drive.file`.
   - **Audience / État de publication** : cliquez sur **Publier l'application** pour passer « En production ». **Ne demandez pas de validation.** En mode « Test », l'accès expire au bout de 7 jours. En production non validée, Google affichera une fois par compte un écran « Google n'a pas validé cette application ». Cliquez sur **Paramètres avancés**, puis sur **Accéder à Livres audio**. C'est normal et attendu.
4. Allez dans **Identifiants** (ou **Clients**), puis **Créer des identifiants → ID client OAuth** :
   - Type : **Android**.
   - Nom du paquet : `io.github.camelbaggy.livresaudio` (exactement).
   - Empreinte SHA-1 : celle relevée à l'étape 1.
5. Rien à copier ici : l'identifiant client Android n'a pas besoin d'être mis dans le code, Google le reconnaît grâce au nom du paquet et à l'empreinte.

À me confirmer : « projet créé, API activée, publié en production, client Android créé ». Si un écran ne correspond pas à cette description, envoyez une capture **sans** identifiants visibles.

## 3. Les téléphones

Pour chacun des deux téléphones :

- marque et modèle ;
- version d'Android (**Paramètres → À propos du téléphone**) ;
- utilisation d'Android Auto en voiture (oui/non).

Sur votre téléphone, activez aussi l'installation d'applications de source inconnue pour l'application qui ouvrira l'APK (Drive ou Fichiers). Android vous le proposera de lui-même au premier essai.

## 4. Vos dossiers de livres (noms seulement)

Pour écrire les tests de reconnaissance des noms de dossiers, j'ai besoin d'un **aperçu de l'arborescence** : les noms de dossiers et quelques noms de fichiers, sans le contenu. Indiquez aussi pour chaque partie si elle se trouve dans le Drive commun ou le Drive personnel. Par exemple :

```
Livres audio/
  Maurice Druon - Les Rois maudits/
    01 - Le Roi de fer/
      CD1/  01.mp3 02.mp3 …
  Amin Maalouf - Léon l'Africain (lu par …)/
    Léon l'Africain.m4b
```

Trente à cinquante lignes représentatives suffisent, y compris les noms les plus « mal rangés ». Ces exemples ne seront **pas** recopiés tels quels dans le dépôt : les tests utiliseront des noms inventés de même forme.

## 5. Atelier de synthèse vocale (piste parallèle)

- Le fichier EPUB **sans DRM** du tome 1 de « Fortune de France », que vous déposerez vous-même dans un dossier privé. Ne le mettez pas dans le dépôt GitHub.
- Votre ordinateur : système d'exploitation, processeur, mémoire, carte graphique (modèle, mémoire vidéo) s'il y en a une. Cela détermine les voix gratuites utilisables en local.
- Votre **plafond de dépense** pour la synthèse (par tome et au total), si une voix payante l'emporte.
- Votre accord explicite avant chaque essai payant. Un essai sur l'extrait de 3 minutes coûte quelques centimes.

---

## Récapitulatif à renvoyer

- [ ] Clé de signature créée, sauvegardée deux fois, 4 secrets GitHub ajoutés
- [ ] Projet Google Cloud : API Drive activée, consentement Externe publié en production, client Android créé
- [ ] Modèles et versions Android des deux téléphones, Android Auto oui/non
- [ ] Aperçu de l'arborescence des livres (noms seulement)
- [ ] TTS : matériel de l'ordinateur, plafond de dépense ; EPUB prêt de votre côté
