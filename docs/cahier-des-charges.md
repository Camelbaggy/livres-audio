# Projet : mon application de livres audio personnelle

## 1. Contexte

J'écoute beaucoup de livres audio sur mon téléphone Android. Ma collection est stockée dans mon espace de stockage en ligne Google Drive. J'utilise aujourd'hui l'application CloudBeats pour les écouter, et Smart Audiobook Player pour les livres stockés directement sur le téléphone.

CloudBeats a des défauts qui gâchent l'expérience, et je n'ai pas trouvé d'application qui réunisse tout ce que je veux. Je souhaite donc créer ma propre application, pour un usage strictement privé, sans publication ni distribution. Elle sera utilisée par moi et par ma compagne, chacun sur son propre téléphone, et elle ne sortira pas de ce cercle à deux.

## 2. Problèmes à résoudre

Ce que CloudBeats fait mal ou fait payer :

1. La lecture rapide (accélérer la vitesse de lecture) est réservée à la version payante.
2. À la reprise de la lecture, l'application ne recule pas légèrement. Smart Audiobook Player le fait et c'est très agréable : on retrouve le fil sans avoir à rechercher manuellement.
3. Il n'y a pas de vraie bibliothèque de livres audio construite à partir de mes dossiers Drive. Je dois naviguer dans l'arborescence comme dans un explorateur de fichiers.
4. Se connecter à plusieurs comptes Drive est réservé à la version payante.
5. Une courte perte de réseau interrompt immédiatement la lecture.

## 3. Objectif général

Une application simple, fiable et agréable, qui :

- transforme mes dossiers Drive en une belle bibliothèque de livres audio ;
- se comporte en lecture comme le meilleur des lecteurs de livres audio ;
- continue à lire malgré les petites coupures de réseau ;
- enrichit automatiquement ma bibliothèque avec les informations des livres ;
- me permet de créer moi-même des livres audio à partir de livres numériques qui n'existent pas en version audio, avec une voix de synthèse la plus naturelle possible en français.

## 4. Fonctionnalités souhaitées

### 4.1 Sources et comptes

- Me connecter à mon compte Google Drive pour accéder à mes livres audio.
- Pouvoir connecter plusieurs comptes Drive en même temps, sans limitation, et voir leurs livres réunis dans une seule bibliothèque. Dans notre cas, chaque téléphone connecte au moins deux comptes : le compte familial commun et le compte personnel de son propriétaire.
- Choisir quels dossiers de chaque compte sont des dossiers de livres à surveiller.
- Pouvoir retirer ou ajouter un compte ou un dossier à tout moment.
- Rester connecté durablement, sans avoir à me ré-authentifier régulièrement.

### 4.2 Bibliothèque

- Construire automatiquement la bibliothèque à partir de mes dossiers : un dossier correspond à un livre, et les fichiers audio qu'il contient sont les chapitres ou parties du livre, dans le bon ordre.
- Gérer aussi bien les livres en plusieurs fichiers que ceux en un seul gros fichier avec chapitres intégrés.
- Détecter automatiquement les nouveaux livres ajoutés au Drive et ceux qui ont disparu.
- Affichage en couvertures, avec recherche, tri (titre, auteur, date d'ajout, dernier écouté) et filtres (en cours, terminés, pas commencés).
- Regrouper les livres d'une même série et les ordonner par numéro de tome.
- Permettre de marquer un livre comme terminé ou de le remettre à zéro.
- Une bibliothèque propre et lisible, même si mes dossiers sont nommés de façon approximative.

### 4.3 Lecture

- Lecture en arrière-plan, écran éteint, avec commandes sur l'écran de verrouillage et dans la notification.
- Compatibilité avec les écouteurs et commandes Bluetooth, et avec la voiture.
- Reprise automatique exactement là où je me suis arrêté, pour chaque livre, même après plusieurs jours ou après la fermeture de l'application.
- Léger retour en arrière à la reprise de la lecture après une pause : plus la pause est longue, plus le retour est important. Les durées doivent être réglables.
- Vitesse de lecture réglable finement, sans frais, avec une voix qui reste naturelle.
- Boutons d'avance et de retour rapides, dont la durée est réglable.
- Navigation par chapitres.
- Minuterie d'endormissement.
- Marque-pages avec une note facultative.
- Un écran de lecture clair et reposant, utilisable d'une seule main.

### 4.4 Continuité malgré le réseau

- Une courte coupure de réseau (quelques secondes, voire quelques dizaines de secondes ou plus) ne doit pas interrompre la lecture.
- L'application doit anticiper : le passage au chapitre suivant doit se faire sans attente.
- Lorsque le réseau revient, la lecture reprend ou continue d'elle-même.
- Si l'attente est inévitable, l'application doit l'indiquer clairement, sans planter ni perdre ma position.
- Gestion intelligente de l'espace utilisé sur le téléphone : une limite réglable, avec suppression automatique des contenus les moins récemment écoutés, sauf le livre en cours.

### 4.5 Écoute hors ligne

- Pouvoir télécharger un livre entier, ou une partie, pour l'écouter sans connexion (voyage, métro, avion).
- Voir clairement quels livres sont disponibles hors ligne et l'espace qu'ils occupent.
- Pouvoir supprimer le téléchargement d'un livre facilement.

### 4.6 Informations enrichies sur les livres

- Récupérer automatiquement, pour chaque livre, les informations utiles : titre propre, auteur, narrateur, série et numéro de tome, résumé, année, durée et couverture.
- Utiliser d'abord les informations déjà présentes dans mes fichiers et dossiers, puis compléter à partir de sources en ligne.
- Quand l'identification d'un livre est incertaine, me proposer quelques candidats parmi lesquels choisir, plutôt que de décider seule.
- Pouvoir corriger à la main n'importe quelle information (titre, auteur, couverture, série).
- Mes corrections sont définitives : une nouvelle synchronisation ne doit jamais les écraser.
- Fonctionner correctement avec des livres en français en priorité.

### 4.7 Création de livres audio par voix de synthèse

Certains livres que j'aime n'existent pas en version audio. C'est le cas, par exemple, de la saga « Fortune de France » de Robert Merle, que je souhaite pouvoir écouter. Je veux pouvoir fabriquer moi-même, pour mon usage privé, une version audio à partir d'un livre numérique que je possède.

- Partir d'un livre numérique (sans protection anti-copie) et le transformer en livre audio découpé en chapitres.
- Nettoyer automatiquement le texte avant la lecture : retirer numéros de page, en-têtes, notes et tout ce qui ne doit pas être lu à voix haute.
- Obtenir une lecture la plus naturelle possible en français : pas de voix robotique, bon rythme, bonnes intonations, dialogues convenablement rendus.
- Pouvoir comparer plusieurs voix sur un même extrait avant de choisir, de préférence en testant d'abord des solutions gratuites, afin de juger si la différence de qualité d'une solution payante justifie son coût.
- Dictionnaire de prononciation personnel : je dois pouvoir corriger la façon dont sont dits les noms propres (personnages, lieux) et certains mots d'époque ou de langue régionale, et ces corrections s'appliquent à tout le livre et aux livres suivants de la même série.
- Écouter un court extrait pour vérifier une correction avant de l'appliquer.
- Après une correction, ne regénérer que les passages concernés et non le livre entier.
- Maîtriser le coût : estimation avant toute génération payante, avec un plafond que je fixe.
- Les livres créés arrivent dans ma bibliothèque comme n'importe quel autre livre, avec leurs informations, leur couverture et leurs chapitres.
- À terme, pouvoir varier les voix selon les personnages ou le narrateur, si cela améliore vraiment l'écoute.

### 4.8 Usage à deux

L'application est utilisée par deux personnes (moi et ma compagne), chacune avec son téléphone.

- Chacun a son propre espace d'écoute : sa position dans chaque livre, ses marque-pages, ses réglages (vitesse, durées de retour en arrière, minuterie), l'état « terminé » ou « en cours » de chaque livre. Ce que l'un écoute ne modifie jamais l'espace de l'autre.
- La bibliothèque peut être commune : nous accédons aux mêmes livres, sans avoir à tout configurer deux fois (comptes Drive, dossiers à surveiller, informations des livres).
- Les corrections faites sur les informations d'un livre (titre, auteur, couverture, série) et les corrections de prononciation profitent aux deux, sans refaire le travail.
- Organisation de nos Drive : nous avons un Drive commun (compte Google familial) qui contient les livres que nous écoutons tous les deux, et chacun a son Drive personnel (compte Google perso) qui contient ses lectures individuelles.
- Sur chaque téléphone, la bibliothèque réunit donc le Drive commun et le Drive personnel de son propriétaire. Je ne vois pas les livres du Drive personnel de ma compagne, et elle ne voit pas les miens.
- Dans la bibliothèque, on doit voir d'un coup d'œil si un livre vient du Drive commun ou du Drive personnel, et pouvoir filtrer par origine.
- Un livre du Drive commun a, pour chacun, sa propre progression d'écoute. Un livre d'un Drive personnel n'a que celle de son propriétaire.
- Les informations enrichies et les corrections (titre, couverture, série, prononciation) d'un livre du Drive commun sont partagées entre nous. Celles d'un livre personnel restent personnelles.
- Pouvoir déplacer un livre d'un Drive personnel vers le Drive commun (ou l'inverse) sans perdre sa progression ni ses corrections.
- Mettre en route l'application sur le téléphone de ma compagne doit être simple et rapide, sans manipulation compliquée.
- Les livres créés par voix de synthèse sont disponibles pour nous deux, et seulement pour nous deux.
- Si nous écoutons le même livre, chacun doit pouvoir avancer à son rythme, avec la possibilité de voir où en est l'autre si on le souhaite, sans que cela soit imposé.
- Une personne qui perd ou change de téléphone doit retrouver ses positions et marque-pages.

## 5. Contraintes et principes

- Application pour téléphone Android uniquement, à usage strictement privé, limité à deux personnes (moi et ma compagne).
- Les coûts doivent rester minimes. Les fonctions que d'autres applications font payer doivent être gratuites ici.
- Nos livres et nos comptes restent privés : rien n'est partagé ni envoyé ailleurs que vers les services dont l'application a besoin. Ma compagne et moi ne voyons pas les données personnelles de l'autre (comptes, positions, notes) sauf si l'un de nous le décide.
- Les livres créés par voix de synthèse sont réservés à notre cercle privé à deux et ne sont ni partagés ni diffusés au-delà.
- Je ne souhaite pas contourner de protection anti-copie : seuls des livres numériques sans protection sont utilisés.
- Simplicité avant tout : l'application doit être agréable pour un usage quotidien, pas une usine à gaz.
- La fiabilité compte plus que le nombre de fonctions : ne jamais perdre ma position de lecture.

## 6. Ordre de priorité souhaité

1. **Indispensable** : connexion à un compte, bibliothèque issue des dossiers, lecture fiable avec reprise de position et léger retour en arrière, vitesse réglable.
2. **Très important** : lecture qui résiste aux coupures de réseau, informations enrichies sur les livres avec corrections manuelles, plusieurs comptes.
3. **Important** : écoute hors ligne, gestion de l'espace, marque-pages, minuterie, séries.
4. **Important pour que ma compagne l'adopte** : espace d'écoute séparé pour chacun avec bibliothèque commune (section 4.8), à prévoir dès la conception pour ne pas avoir à tout reprendre ensuite.
5. **Projet à part, à mener en parallèle** : création de livres audio par voix de synthèse. Commencer par un banc d'essai comparant plusieurs voix (gratuites d'abord) sur un extrait de la saga, avant de décider de la suite.

## 7. Critères de réussite

- Ma compagne et moi remplaçons complètement CloudBeats pour nos livres audio du Drive, chacun avec sa progression.
- Je ne perds jamais ma place dans un livre, quels que soient les aléas du réseau ou de l'application.
- Ma bibliothèque est propre : titres, auteurs, couvertures et séries corrects, sans que j'aie besoin de renommer mes dossiers.
- Je peux écouter un livre de la saga « Fortune de France » avec une voix agréable pendant de longues heures, sans être gêné par la prononciation des noms propres.

## 8. Questions ouvertes à trancher dans le plan d'action

- Quel niveau de qualité vocale est jugé suffisant pour les livres créés, et à partir de quel coût cela vaut-il le coup ?
- Faut-il que la création des livres audio se fasse dans l'application elle-même, ou dans un outil séparé qui dépose ses résultats dans mon Drive ?
- Quelles sources d'informations en ligne retenir pour enrichir les livres, en privilégiant la qualité pour le français ?
- Quelle taille d'espace de stockage maximale prévoir par défaut sur le téléphone ?
- Comment partager la bibliothèque et les corrections entre deux personnes tout en gardant des positions d'écoute séparées, et sans imposer à ma compagne une configuration lourde ?
- Où conserver les informations partagées (corrections de métadonnées et de prononciation du Drive commun) pour que les deux téléphones y aient accès, sans serveur à entretenir et sans les mélanger avec les données personnelles ?
- Comment faire en sorte que chacun retrouve son espace (positions, marque-pages) simplement après un changement de téléphone ?
- Quelles fonctions retenir pour une première version utilisable rapidement, afin de pouvoir remplacer CloudBeats au plus vite ?
