# Recette — Essai E2 : lecture depuis Google Drive (lot 0)

Objectif : valider sur votre téléphone que le lecteur (Media3 / ExoPlayer) lit un livre audio stocké dans Google Drive à travers une source authentifiée et un **cache disque** (CLAUDE.md §5.3 et §5.4), et qu'il :

- démarre vite et permet de **se déplacer** dans le fichier (milieu, fin) ;
- supporte les vitesses 1×, 1,5×, 2× sans déformer la voix ;
- continue **60 secondes en mode avion** grâce à ce qu'il a déjà en mémoire et sur le disque ;
- attend le réseau au lieu de planter quand il est coupé longtemps ;
- relit hors ligne un passage déjà écouté (il vient du cache disque).

Durée : 30 à 40 minutes. À faire avec du réseau stable au départ (Wi-Fi de préférence), puis en coupant le réseau aux étapes indiquées.

Prérequis : la recette E1 est faite (au moins un compte connecté), l'APK à jour est installé (même procédure que l'étape 1 de `essai-e1.md`), et votre Drive contient :

- un **MP3** (n'importe quel livre ou chapitre, quelques dizaines de Mo) ;
- un **gros M4B** de plus de 300 Mo (un livre complet).

Pour le M4B, notez à l'avance son nom exact et sa taille dans Drive (clic droit → Informations).

## 1. Ouvrir l'écran d'essai

1. Lancer **Livres audio** : l'écran E1 s'affiche.
2. Appuyer sur **Essai lecture (E2)**. Android demande peut-être l'autorisation d'envoyer des notifications : **Autoriser** (la notification de lecture sert à tester l'écran éteint).
3. Sous « Compte utilisé pour la lecture », cocher le compte où se trouvent vos fichiers.

## 2. Lire un MP3

1. Dans le champ de recherche, taper **le début d'un mot** du nom du MP3 (Drive cherche les mots qui commencent par le texte saisi, pas n'importe quelle partie du mot), puis **Chercher**. Champ vide + **Chercher** = les 50 premiers fichiers audio.
2. Appuyer sur le fichier voulu dans la liste. Chaque ligne indique la taille et le type du fichier.
3. Observer l'écran lecteur :
   - l'état passe de **Chargement…** à **En lecture** (le journal indique « Prêt à jouer N ms après la demande » : **noter N**) ;
   - la position avance, **Position tamponnée** et **Audio en mémoire d'avance** augmentent, **Cache disque pour ce fichier** augmente.
4. Tester : **Pause** puis **Lecture**, **Recul 10 s**, **Avance 30 s**, la barre de position (glisser vers le milieu puis lâcher). Le journal note « Reprise N ms après le déplacement » : **noter N** pour un déplacement au milieu.
5. Tester les vitesses **1×**, **1,5×** et **2×** : la voix doit accélérer **sans devenir plus aiguë**.

Attendu : tout fonctionne sans erreur ; un MP3 qui ne se laisse pas déplacer (la barre revient au début) est à signaler (notez le nom).

## 3. Lire le gros M4B

1. Chercher et choisir le M4B (étape 2.1 et 2.2). Si **rien** n'apparaît alors que le fichier existe, notez son nom : son type Drive n'est peut-être pas « audio » ; l'essai le dira (chaque ligne affiche le type).
2. Noter dans le journal « Prêt à jouer N ms après la demande ». Un M4B dont l'index est en fin de fichier peut demander quelques secondes de plus : c'est le point à mesurer.
3. Vérifier que la **durée totale** s'affiche (heures : minutes : secondes), puis laisser jouer 1 minute.
4. **Déplacement au milieu** : glisser la barre vers le milieu du livre et lâcher. Noter le temps de reprise du journal (« Reprise N ms après le déplacement »). Attendu : quelques secondes au plus.
5. **Déplacement vers la fin** : glisser vers les 95 % du livre et lâcher. Noter encore le temps de reprise. Attendu : pareil, quelques secondes, **sans télécharger tout le fichier** : surveiller **Cache disque pour ce fichier**, qui doit rester très inférieur à la taille totale (quelques Mo à quelques dizaines de Mo seulement).
6. Tester les vitesses 1,5× et 2× sur le M4B.
7. Revenir au début : glisser la barre à zéro. Laisser jouer.

## 4. Mode avion pendant 60 secondes (cache et tampon)

1. Toujours sur le M4B, au début du livre, **laisser jouer 3 minutes sans toucher à rien**, sur Wi-Fi. Pendant ce temps, **Audio en mémoire d'avance** doit monter (jusqu'à plusieurs minutes) et **Cache disque pour ce fichier** grossir. Noter ces deux valeurs à la fin des 3 minutes.
2. Activer le **mode avion** (Wi-Fi et données coupés). La ligne « Réseau » passe à « indisponible ».
3. Laisser jouer **60 secondes** en regardant l'écran.
4. Attendu : la lecture **ne s'arrête pas** ; l'état reste **En lecture**. Noter si un arrêt, un « Chargement… » ou une erreur est apparu, et à quelle seconde.
5. Désactiver le mode avion : la lecture doit continuer sans intervention (l'état peut passer un instant par « Chargement… »).

## 5. Écran éteint (service de lecture)

1. Lancer la lecture, puis **éteindre l'écran** (bouton latéral). Attendre 2 minutes.
2. Allumer l'écran et vérifier : la lecture a continué, la notification « Livres audio » existe avec les boutons pause/lecture, et la lecture se pilote depuis l'écran verrouillé.
3. Optionnel : avec un casque Bluetooth, appuyer sur le bouton lecture/pause du casque.
4. Fermer l'application depuis la liste des applications récentes (glisser vers le haut) : la lecture doit s'arrêter (comportement voulu pour cet essai).

## 6. Mode avion prolongé (attente du réseau)

1. Relancer la lecture du M4B depuis le début, réseau actif, et laisser jouer 1 minute.
2. Glisser la barre **loin dans le livre** (vers 50 %) : cette zone n'est ni en mémoire ni en cache.
3. **Immédiatement**, activer le **mode avion**.
4. Attendu : l'état affiche **En attente du réseau…** (la lecture ne peut pas continuer, mais l'application ne plante pas et ne revient pas à zéro). Le journal peut montrer des lignes « Erreur de chargement … nouvelle tentative automatique » : c'est normal.
5. Attendre 1 minute puis **désactiver le mode avion**.
6. Noter : la lecture **reprend-elle toute seule** (sans appuyer sur Lecture) ? en combien de secondes après le retour du réseau ? Si elle ne reprend pas, appuyer sur **Lecture** et le noter. (La reprise automatique complète est prévue au lot 2 : un échec ici n'est pas bloquant, mais je dois le savoir.)

## 7. Relire un passage déjà écouté en mode avion (cache disque)

1. Choisir le **MP3** (ou le M4B), lancer la lecture et la laisser aller **jusqu'à 2 minutes** au moins. Noter la valeur de **Cache disque pour ce fichier**.
2. Glisser la barre au début, puis relire les 30 premières secondes pour qu'elles soient bien en cache. Mettre en **pause**.
3. Activer le **mode avion**.
4. Appuyer sur **Lecture**, puis glisser la barre sur un **passage déjà écouté** (par exemple 1 minute) et lâcher.
5. Attendu : la lecture reprend **sans réseau** presque aussitôt, depuis le cache disque. Un déplacement vers un passage **jamais écouté** doit au contraire afficher **En attente du réseau…**.
6. Réactiver le réseau.

## 8. Vider le cache

1. Appuyer sur **Vider le cache**. La lecture s'arrête ; **Cache disque total** doit retomber à environ 0 Mo.
2. Relancer un fichier : le cache se remplit de nouveau depuis zéro.

## 9. Copier le journal et répondre

Appuyer sur **Copier le journal** (il ne contient ni jeton ni adresse de lien de téléchargement ; il contient le nom des fichiers que vous avez lus, retirez-les si vous préférez) et le coller dans un message.

À me renvoyer :

1. Modèle du téléphone, version d'Android, type de connexion (Wi-Fi ou 4G/5G) pendant l'essai.
2. MP3 : taille du fichier, délai de démarrage (ms), temps de reprise après déplacement (ms).
3. M4B : taille, durée totale affichée, délai de démarrage, temps de reprise au milieu puis vers la fin (ms), et valeur de « Cache disque pour ce fichier » après chaque déplacement (le fichier entier a-t-il été téléchargé ?).
4. Vitesses 1,5× et 2× : la voix reste-t-elle naturelle (oui/non) ?
5. Après 3 minutes de lecture : valeurs de « Audio en mémoire d'avance » et de « Cache disque pour ce fichier ».
6. Mode avion 60 s (étape 4) : la lecture a-t-elle continué sans coupure (oui/non) ? Sinon, à quelle seconde ?
7. Écran éteint (étape 5) : la lecture a-t-elle continué 2 minutes (oui/non) ? La notification et les boutons du casque fonctionnent-ils ?
8. Mode avion prolongé (étape 6) : « En attente du réseau… » apparaît-il ? La lecture reprend-elle seule, et après combien de secondes ? Y a-t-il eu une erreur affichée (texte exact) ?
9. Relecture hors ligne d'un passage écouté (étape 7) : oui/non ; passage jamais écouté : « En attente du réseau… » oui/non.
10. Toute erreur affichée (texte exact) et tout comportement inattendu (blocage, retour à zéro, fermeture de l'application).

## Interprétation (pour le chef de projet)

- Démarrage ou reprise après déplacement de plus de ~5 s sur le M4B, ou cache disque proche de la taille du fichier après un déplacement vers la fin : le M4B n'est pas lu par plages (vérifier `Range` côté Drive, index `moov` en fin de fichier) ; envisager de précharger l'index (CLAUDE.md §5.2, essai E3).
- Coupure à l'étape 4 : tampon mémoire/cache insuffisant ou `LoadControl` mal réglé (§5.4) ; à corriger avant le lot 2.
- Étape 6 : si la lecture ne reprend pas seule, ce comportement est attendu avant le lot 2 (reprise automatique au retour du réseau, §5.4) ; si l'application plante ou se ferme, c'est un défaut de la politique d'erreurs.
- Écran éteint sans lecture continue : revoir le service de premier plan et les permissions (§5.3) au lot 1.6.
- Erreur 403 / 401 persistante : jeton non renouvelé sur les requêtes de lecture (revoir `TokenAuthenticator` et la portée `drive.readonly`).
