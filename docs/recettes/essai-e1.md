# Recette — Essai E1 : authentification Google Drive (lot 0)

Objectif : valider sur de vrais téléphones les trois points du CLAUDE.md §5.1.

- **(a)** ajout d'un **deuxième** compte Google avec sélecteur de compte forcé ;
- **(b)** renouvellement **silencieux** de l'autorisation après plusieurs jours ;
- **(c)** un fichier créé avec `drive.file` par un téléphone est **lisible et modifiable** par l'autre téléphone (même compte, même client Google).

Durée : 15 minutes le premier jour, 2 minutes après 2 à 3 jours, 10 minutes sur le second téléphone.

Prérequis : les étapes de `docs/prerequis.md` sont faites (projet Google Cloud, écran de consentement « En production », client OAuth Android avec l'empreinte SHA-1 de **votre** clé de signature, secrets GitHub). Sans cela, la connexion Google échoue (code d'erreur Google Identity affiché dans le journal).

## 1. Installer l'APK signé

1. Sur GitHub : onglet **Actions** → workflow **APK signé** → **Run workflow** (sur la branche du projet).
2. Une fois terminé, télécharger l'artefact (fichier zip) et en extraire l'APK `.apk`.
3. Transférer l'APK sur le téléphone (câble, Drive, e-mail à soi-même) et l'ouvrir ; autoriser l'installation depuis cette source si Android le demande.
4. Lancer **Livres audio** : l'écran « Essai E1 : authentification Drive » s'affiche.

## 2. Ajouter les comptes (point a)

1. Appuyer sur **Ajouter un compte**, choisir **Drive commun**.
2. Le sélecteur de comptes Android apparaît : choisir le compte familial commun. L'écran « application non validée » de Google peut s'afficher : **Avancé → Continuer** (une seule fois par compte). Accepter les trois autorisations (lecture Drive, données de l'application, fichiers créés par l'application).
3. Le compte apparaît dans la liste avec son adresse et le rôle « commun ». Le journal indique « Compte ajouté ».
4. Appuyer de nouveau sur **Ajouter un compte**, choisir **Mon Drive personnel**.
   **Point a à vérifier : le sélecteur de comptes s'affiche bien même si un compte est déjà connecté, et on peut choisir l'autre compte.** Terminer le consentement.
5. Les deux comptes sont listés (le personnel porte la mention « compte de profil »).

Notez : le sélecteur s'est-il affiché ? Le second compte a-t-il été ajouté sans erreur ?

## 3. Tester chaque bouton (pour chaque compte)

| Bouton | Résultat attendu dans le journal |
|---|---|
| **Lister la racine** | `N élément(s) : noms…` (10 premiers éléments de « Mon Drive ») |
| **Jeton silencieux** | OK « Jeton obtenu sans interaction » (le jeton n'est jamais affiché) |
| **Écrire appDataFolder** | « Fichier créé » puis « Relu : {…horodatage, modèle du téléphone…} » ; au second appui : « mis à jour » |
| **Écrire drive.file** | recherche globale de `essai-e1.json`, création (ou retrouvaille) du dossier `.livres-audio-essai`, création/mise à jour du fichier, relecture, et ligne « Dernière modification : date par nom <adresse> » |

Vérifier ensuite dans Google Drive (site ou appli) que le dossier `.livres-audio-essai` et le fichier `essai-e1.json` existent. Le contenu d'`appDataFolder` n'est pas visible dans Drive : c'est normal.

Appuyer sur **Copier le journal** et le conserver (collé dans un message).

## 4. Renouvellement silencieux après plusieurs jours (point b)

1. Ne pas ouvrir l'application pendant **2 à 3 jours** (idéalement 3). Ne pas retirer les comptes.
2. Rouvrir l'application, sans rien ajouter. Appuyer sur **Jeton silencieux** pour chaque compte.
3. Résultat attendu : OK sans aucun écran. Si le journal affiche « Consentement à refaire », le point (b) **échoue** : noter le délai écoulé exact.
4. Appuyer aussi sur **Lister la racine** : elle doit fonctionner sans écran de connexion.

## 5. Partage entre téléphones (point c)

1. Sur le **premier téléphone** : avoir fait « Écrire drive.file » avec le compte commun (étape 3), noter l'heure.
2. Sur le **second téléphone** : installer l'APK (étape 1), ajouter **le même compte commun** (rôle « Drive commun »).
3. Appuyer sur **Écrire drive.file**. Lire le journal :
   - « Recherche globale de essai-e1.json : N résultat(s) » : N ≥ 1 signifie que le second téléphone voit le fichier du premier. Note : la portée `drive.readonly` permet de **voir** tous les fichiers ; seule la modification est réellement mesurée.
   - « Dossier … existant trouvé » : bon signe. « Dossier absent : création » alors que le premier téléphone l'a créé = problème de visibilité.
   - « Fichier mis à jour » avec « Dernière modification … par … » = **la modification par l'autre téléphone a réussi (point c réussi)**. Un message « refusé (403 …) » = **point c échoué** : noter le motif.
4. Revenir sur le premier téléphone, appuyer sur **Écrire drive.file** puis relire : le contenu doit porter le modèle du second téléphone s'il a écrit en dernier (le journal affiche « Relu »).
5. Refaire la même chose avec **Écrire appDataFolder** : chaque téléphone doit retrouver le fichier de l'autre. (Le dossier caché `appDataFolder` est propre au couple application + compte : ce point est attendu OK, c'est la base de la sauvegarde de profil du lot 4.)

## 6. À noter et à me renvoyer

Pour chaque téléphone : modèle et version d'Android, et le **journal copié** (il ne contient ni jeton ni mot de passe ; il contient vos adresses Google : retirez-les si vous préférez).

Répondre par oui / non, avec le message exact en cas d'échec :

1. **(a)** Le sélecteur de comptes s'affiche-t-il à l'ajout du second compte ? L'ajout aboutit-il ?
2. L'écran « application non validée » est-il apparu ? Combien de fois ?
3. **(b)** « Jeton silencieux » fonctionne-t-il après 2–3 jours sans ouvrir l'application ? (indiquer le délai exact)
4. **(c)** Le second téléphone peut-il modifier le fichier créé par le premier (avec `drive.file`) ? Voit-il le dossier créé par le premier ?
5. `appDataFolder` : chaque téléphone relit-il le fichier écrit par l'autre ?
6. Toute erreur affichée dans le journal (texte exact).

## Interprétation (pour le chef de projet)

- (a) ou (b) en échec : repli sur AppAuth (CLAUDE.md §5.1).
- (c) en échec : ajouter la portée `drive` au seul compte commun (accord explicite de l'utilisateur requis, §11.4).
