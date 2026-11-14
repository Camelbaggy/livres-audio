# Essayer les voix payantes sans payer — guide pas à pas

But : comparer à l'aveugle, sur l'extrait de 3 minutes (≈ 2 600 caractères), les voix françaises de
quatre services en ligne, **en restant sur leurs paliers gratuits**. Coût attendu : **0 €**. Au
pire (palier dépassé par erreur), l'extrait entier coûte quelques centimes (Google ≈ 0,07 €).

**Durée** : 10 à 20 minutes par service. Les services sont **indépendants** : faites ceux que vous
voulez, dans l'ordre que vous voulez. Je vous conseille de commencer par Google (c'est le seul qui,
d'après la veille, pourrait aussi servir à produire un livre entier dans votre plafond : voir la
dernière partie).

> **Règle de sécurité pour tout le guide**
> Ne collez **jamais** une clé dans la conversation, ni dans un fichier du dépôt. Quand une clé est
> créée, copiez-la dans votre gestionnaire de mots de passe, puis dites-moi seulement
> « **clé Google prête** » (ou Azure, ElevenLabs, Mistral). Je vous présenterai alors un
> formulaire sécurisé où la coller ; je ne la verrai jamais en clair, et elle ne sera enregistrée
> que dans l'environnement du projet, pas dans le dépôt.

## Vue d'ensemble

| Service | Carte bancaire ? | Palier gratuit | Risque de facturation | Ce que vous me transmettez |
|---|---|---|---|---|
| A. Google Cloud (Chirp 3 HD) | **Oui** (pour activer la facturation) | 1 M caractères/mois | Réel si on dépasse : alerte de budget + clé restreinte | Une clé d'API |
| B. Azure AI Speech | **Oui** (inscription Azure) | F0 : 500 000 caractères/mois | Nul en niveau F0 (blocage au quota) | Une clé + la région |
| C. ElevenLabs | **Non** | 10 000 crédits/mois (≈ 1 crédit par caractère) | Nul (plafond dur, pas de dépassement automatique) | Une clé d'API |
| D. Mistral (Voxtral TTS) | **Non** (plan « Experiment ») | À confirmer pour la synthèse vocale | Nul tant qu'on ne passe pas au plan payant | Une clé d'API |

Ce que coûterait un **tome entier** (≈ 1 M caractères) hors paliers, par rapport à votre plafond de
5 € par tome (10 € si la différence est frappante) :

| Service | Un tome sans palier gratuit | Dans le plafond ? |
|---|---|---|
| Google Chirp 3 HD | ≈ 25,8 € ; **0 €** avec le million gratuit du mois | Oui, pour ≈ 1 tome par mois |
| Azure HD | ≈ 18,9 € ; ≈ 9,5 € après les 500 k gratuits | Non |
| ElevenLabs | ≈ 190 € (abonnement Creator) | Non |
| Mistral Voxtral | ≈ 11 à 14 € | Non (juste au-dessus de 10 €) |

Ces tarifs viennent de la veille du 2026-11-14 (`veille-voix.md`) : vérifiez-les sur la page
officielle du service au moment de vous inscrire.

---

## A. Google Cloud — Chirp 3 HD

### A1. Quel projet utiliser ?

Vous avez déjà le projet **« Livres audio »**, créé pour l'application (sans carte bancaire).
**Créez-en un second, séparé**, nommé `Livres audio - voix` : ainsi la facturation (carte bancaire)
ne touche que lui, et le projet de l'application reste gratuit et sans carte. Les clés et les
quotas sont propres à chaque projet.

1. Allez sur <https://console.cloud.google.com/> avec le compte propriétaire du premier projet.
2. En haut à gauche, cliquez sur le **sélecteur de projet** → **Nouveau projet / New project**.
3. Nom : `Livres audio - voix`. Organisation : « Aucune ». **Créer / Create**.
4. Vérifiez que le sélecteur affiche bien **Livres audio - voix** avant de continuer.

### A2. Activer la facturation (obligatoire, même pour le palier gratuit)

Google exige un compte de facturation pour utiliser Text-to-Speech, même dans la limite gratuite
(« vous serez facturé automatiquement si vous dépassez les caractères gratuits »).

1. Menu ☰ → **Facturation / Billing**.
2. **Associer un compte de facturation / Link a billing account** → **Créer un compte de
   facturation / Create billing account**. Pays France, renseignez la carte bancaire.
3. Si on vous propose l'**essai gratuit (300 USD pendant 90 jours)** : vous pouvez l'accepter. Il ne
   se convertit pas en abonnement tout seul ; en fin d'essai, les services sont suspendus tant que
   vous n'avez pas choisi de passer en compte payant. Rien n'est débité sans votre accord à ce stade.
4. Vérifiez : dans **Facturation**, le projet « Livres audio - voix » est bien lié à ce compte.

### A3. Poser une alerte de budget (à ne pas sauter)

> Une alerte **prévient** (par courriel), elle **ne bloque pas** les dépenses. La vraie protection
> est : clé restreinte (A5), usage minuscule (l'extrait pèse 0,07 € au tarif plein) et palier
> gratuit de 1 M de caractères.

1. Menu ☰ → **Facturation** → **Budgets et alertes / Budgets & alerts** → **Créer un budget**.
2. Nom : `Voix - 2 euros`. Portée : projet **Livres audio - voix**.
3. Montant : **Montant spécifié / Specified amount** = `2` €.
4. Seuils : laissez 50 %, 90 %, 100 % et ajoutez 100 % « **prévu / forecasted** ». Cochez l'envoi de
   courriels aux administrateurs de la facturation.
5. **Terminer / Finish**.

**Arrêt d'urgence** : Facturation → *Gérer / Manage* → **Désactiver la facturation / Disable
billing** sur le projet. Les appels cessent immédiatement.

### A4. Activer l'API Text-to-Speech

1. Barre de recherche en haut : `Cloud Text-to-Speech API` → résultat **Cloud Text-to-Speech API**.
2. Bouton bleu **Activer / Enable**.

### A5. Créer la clé d'API et la restreindre

1. Menu ☰ → **API et services / APIs & Services** → **Identifiants / Credentials**.
2. **Créer des identifiants / Create credentials** → **Clé API / API key**. Une fenêtre affiche la
   clé : **ne la copiez pas encore dans un message** ; cliquez **Fermer**.
3. Dans la liste, cliquez sur le nom de la clé (par défaut « Clé API 1 »). Renommez-la
   `voix-bench`.
4. **Restrictions relatives aux applications / Application restrictions** : laissez « Aucune »
   (l'appel part de votre ordinateur ou de l'environnement cloud, dont l'adresse change).
5. **Restrictions relatives aux API / API restrictions** → **Restreindre la clé / Restrict key** →
   cochez uniquement **Cloud Text-to-Speech API** → **OK**.
6. **Enregistrer / Save**. Une restriction peut mettre quelques minutes à s'appliquer.
7. Pour récupérer la clé : sur la même page, **Afficher la clé / Show key**. Copiez-la dans votre
   gestionnaire de mots de passe.

### A6. Me prévenir

Dites-moi : **« clé Google prête »**. Je vous présenterai le formulaire sécurisé (hôte
`texttospeech.googleapis.com`).

### A7. Après l'essai

Si vous ne gardez pas Google : **Identifiants** → ⋮ sur la clé → **Supprimer / Delete**, puis
désactivez la facturation du projet. Si vous le gardez, la clé restreinte et l'alerte suffisent.

---

## B. Azure AI Speech — niveau gratuit F0

Carte bancaire : l'inscription Azure en demande une pour vérifier votre identité. Tant que la
ressource est au niveau **Free F0**, il n'y a **pas de facturation** : au-delà du quota, le service
**refuse** les appels (erreur 429) au lieu de facturer.

### B1. Créer le compte

1. Allez sur <https://azure.microsoft.com/free/> → **Commencer gratuitement / Start free**.
2. Connectez-vous avec un compte Microsoft (créez-en un si besoin), complétez le profil, la
   vérification par téléphone et la carte. Un crédit d'essai (≈ 200 USD pendant 30 jours) peut être
   offert : **ne passez pas au « paiement à l'usage » si on vous le propose**.

### B2. Créer la ressource Speech en niveau gratuit

1. Allez sur <https://portal.azure.com/> → barre de recherche : `Speech services` → **Services
   Speech / Speech services** → **Créer / Create**.
2. **Abonnement / Subscription** : celui du compte. **Groupe de ressources / Resource group** :
   **Créer** → `voix-bench`.
3. **Région / Region** : **West Europe** (c'est celle que l'outil utilise par défaut ; sinon notez
   la vôtre, par exemple `francecentral`).
4. **Nom / Name** : un nom unique, par exemple `livres-voix-<vos initiales>`.
5. **Niveau tarifaire / Pricing tier** : **Free F0** (et **non** Standard S0). C'est le point
   essentiel : vérifiez-le avant de valider.
6. **Vérifier + créer / Review + create** → **Créer / Create**. Attendez la fin du déploiement, puis
   **Accéder à la ressource / Go to resource**.

### B3. Récupérer la clé et la région

1. Dans la ressource : menu de gauche → **Gestion des ressources / Resource Management** →
   **Clés et point de terminaison / Keys and Endpoint**.
2. Copiez **CLÉ 1 / KEY 1** dans votre gestionnaire de mots de passe, et notez **Emplacement /
   Location** (ex. `westeurope`).

### B4. Éviter toute facturation

- Niveau **F0** confirmé dans l'onglet **Vue d'ensemble / Overview** de la ressource (« Pricing tier
  : Free F0 »).
- Ne créez **aucune** ressource S0.
- **Alerte** : barre de recherche `Cost Management + Billing` → **Budgets** → **Ajouter / Add** →
  montant `2` € → alertes à 50 % et 100 % à votre courriel.
- À savoir : les voix **HD** (Dragon HD) sont facturées plus cher et le texte officiel ne dit pas
  clairement si le palier F0 les couvre. Si elles ne sont pas incluses, l'appel échouera en F0
  plutôt que de coûter. Les voix « Multilingual Neural » (Vivienne, Remy) sont couvertes.

### B5. Me prévenir

Dites-moi **« clé Azure prête »** et indiquez la région (ex. `westeurope`). Hôte à autoriser :
`<région>.tts.speech.microsoft.com`. La région est lue dans `AZURE_SPEECH_REGION`.

---

## C. ElevenLabs — plan gratuit

Pas de carte bancaire. Plan gratuit : 10 000 crédits par mois, soit environ 10 000 caractères avec
le modèle multilingue ; l'extrait en consomme ≈ 2 600. Le plan gratuit ne donne pas de licence
commerciale : sans importance pour votre usage privé. Le plan gratuit ne peut pas dépasser son
quota (pas de facturation automatique).

1. Allez sur <https://elevenlabs.io/> → **Sign up** avec votre adresse ou un compte Google.
   **Ne choisissez aucun abonnement payant** (restez sur « Free »).
2. Une fois connecté : cliquez sur votre profil (en bas à gauche ou en haut à droite selon la
   version) → **API Keys** (ou **Developers → API Keys**).
3. **Create API Key**. Nom : `voix-bench`. Si l'interface propose des **permissions** : activez
   **Text to Speech** (lecture des voix si proposée) et laissez le reste désactivé. Si elle propose
   une **limite de crédits / Credit limit** : saisissez `5000`.
4. **Create**, puis copiez la clé (elle n'est affichée qu'une fois) dans votre gestionnaire de mots
   de passe.
5. Dites-moi **« clé ElevenLabs prête »**. Hôte à autoriser : `api.elevenlabs.io`.

Réserves : certaines sources signalent que l'accès à l'API est limité pour les comptes gratuits ou
que certaines voix de la bibliothèque sont réservées aux abonnés. Les voix « premade » de départ
(Adam, Rachel…) devraient fonctionner ; si l'essai échoue avec une erreur 401 ou 402, ElevenLabs sera
simplement écarté du banc, sans coût.

---

## D. Mistral — Voxtral TTS

Le plan **Experiment** est gratuit et sans carte bancaire (vérification par téléphone). Deux
réserves à connaître : Mistral indique que les requêtes de ce plan **peuvent servir à entraîner ses
modèles** (l'extrait est un texte de roman publié : sans enjeu de vie privée, mais c'est votre
décision), et je n'ai **pas pu confirmer** que la synthèse vocale soit disponible sur ce plan. Si
elle ne l'est pas, l'essai échouera avec un message clair et rien ne sera facturé.

1. Allez sur <https://console.mistral.ai/> → **Sign up** / connexion.
2. Créez ou sélectionnez votre espace de travail (**Workspace**). Au choix du plan, prenez
   **Experiment** (« essayer gratuitement ») et confirmez votre numéro de téléphone. **Ne choisissez
   pas Scale** et ne renseignez aucune carte.
3. Menu **API Keys** → **Create new key**. Nom : `voix-bench`. Copiez la clé immédiatement dans votre
   gestionnaire de mots de passe.
4. Dites-moi **« clé Mistral prête »**. Hôte à autoriser : `api.mistral.ai`.

Incertitudes que je lèverai à la première exécution : nom exact du modèle (`MISTRAL_TTS_MODEL`) et
identifiant de la voix française (`--voix mistral=<identifiant>` ; la liste est obtenue par
`list_remote_voices`).

---

## Lancer l'essai (c'est moi qui le fais)

Une fois les clés saisies dans les formulaires sécurisés, je lancerai par exemple :

```bash
cd tts
uv run tts bench extraits/test-perigord.txt \
  --moteurs edge_tts,piper,google,google:fr-FR-Chirp3-HD-Aoede,azure,elevenlabs,mistral \
  --autoriser-payant --budget-max 1.50
```

Avant chaque moteur payant, l'outil affiche le nombre de caractères envoyés et le **coût maximal**
(sans déduire le palier gratuit), et **refuse de démarrer** si le total dépasse `--budget-max`
(0,50 € par défaut). Les estimations au tarif plein pour l'extrait : Google ≈ 0,07 €, Azure ≈ 0,05 €,
Mistral ≈ 0,04 €, ElevenLabs ≈ 0,49 € ; c'est pourquoi le plafond du banc complet doit être relevé,
alors que **le coût réel reste nul sur les paliers gratuits**.

Ensuite : vous écoutez `ecoute.html`, vous notez à l'aveugle, puis vous ouvrez `cle.json`.

## Et pour un livre entier ?

Règle du projet : on ne paie que si la meilleure voix payante bat la meilleure voix gratuite d'au
moins 1 point sur 5. Si une voix Google l'emporte, un tome (≈ 1 M caractères) passe dans le million
de caractères gratuit mensuel : ≈ 1 tome par mois à 0 €. Pour les autres services, un tome coûte
11 à 190 € : au-dessus de votre plafond de 5 € (10 € si la différence est frappante). Dans ce cas,
on les écoute seulement à titre de comparaison.
