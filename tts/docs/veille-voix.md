# Veille des voix de synthèse françaises (lot T0)

Date de la veille : **2026-11-14**. Statut : **partielle**. Les recherches web ont porté sur des
sites secondaires (blogs, comparateurs) ; `huggingface.co` n'était pas joignable depuis
l'environnement cloud (nom non résolu), donc aucune fiche de modèle officielle n'a pu être lue.
Les cellules marquées « ? » ou « (mémoire) » sont à confirmer avant d'engager quoi que ce soit.
Les tarifs viennent de blogs, pas des pages officielles : à revérifier avant la phase payante (T3).

## Tableau

| Moteur | Où | Matériel | Voix fr-FR | Licence | Tarif / M caractères | Python | Remarques |
|---|---|---|---|---|---|---|---|
| **edge-tts** (voix Edge) | en ligne | aucun | Remy/Vivienne « Multilingual », Denise, Henri, Eloise… | service Microsoft, accès **non officiel** | gratuit | `edge-tts` | Bon point de départ ; peut cesser de fonctionner sans préavis |
| **Piper** | local | CPU | siwis, upmc, tom, gilles (qualité low/medium) (mémoire) | moteur MIT ; licence propre à chaque voix | gratuit | `piper-tts` | Très rapide, voix un peu robotique ; référence « plancher » |
| **Kokoro-82M** | local | CPU possible, GPU confortable | 1 voix fr (`ff_siwis`) (mémoire) | Apache 2.0 | gratuit | `kokoro` (+ espeak-ng) | Cité comme multilingue dont le français ; léger |
| **Kyutai TTS 1.6B en/fr** | local | GPU (≈ 6–8 Go VRAM cités) | voix françaises pré-calculées | CC-BY 4.0 (fiche de modèle citée par un moteur de recherche ; une page tierce dit Apache 2.0) | gratuit | `moshi` | Conçu pour le français ; à tester en priorité si GPU |
| **Chatterbox multilingue** | local | GPU recommandé | clonage depuis un court extrait ; français à confirmer | MIT | gratuit | `chatterbox-tts` | Prosodie expressive ; qualité en fr à écouter |
| **XTTS-v2** | local | GPU recommandé | multi-voix, clonage | CPML (non commercial : compatible usage privé) | gratuit | `coqui-tts` | Entrée limitée (~230 car. en fr) ; projet d'origine arrêté, fork maintenu |
| **F5-TTS** (variantes fr) | local | GPU | clonage ; checkpoints français communautaires (?) | code MIT, poids souvent CC-BY-NC | gratuit | `f5-tts` | Qualité variable selon le checkpoint |
| Fish Speech / OpenAudio | local | GPU | ? | poids non commerciaux (?) | gratuit | oui | À écarter pour T0 sauf besoin |
| **Voxtral TTS** (Mistral) | API ou poids ouverts | API : aucun ; local : GPU | français parmi 9 langues, voix préréglées | poids ouverts (conditions à lire) | ≈ 0,016 $/1 000 car. au lancement (mars 2026), une page tierce d'octobre dit 13 $/M : **incertain** | API Mistral | Lancé en mars 2026, 4 Md de paramètres |
| **Google Cloud Chirp 3 HD** | API | aucun | plusieurs voix fr-FR | commercial | ≈ 30 $ (1 M gratuit par mois) | `google-cloud-texttospeech` | Le quota gratuit mensuel couvre à peu près 1 tome (≈ 1 M car.) |
| **Azure Neural HD** | API | aucun | nombreuses voix fr-FR | commercial | ≈ 22 $ (HD), ≈ 15–16 $ (Neural) ; 500 k gratuits/mois | `azure-cognitiveservices-speech` | Chiffres contradictoires selon les sources |
| **OpenAI TTS** (`tts-1`, `tts-1-hd`, `gpt-4o-mini-tts`) | API | aucun | voix multilingues, pas de voix « fr-FR » dédiée | commercial | 15 $ / 30 $ ; mini-tts ≈ 12–16 $ | `openai` | Un site rapporte un arrêt prévu au 6 janvier 2027 de ces modèles : **à vérifier avant tout choix** |
| **ElevenLabs** | API | aucun | très bonnes voix multilingues | commercial | ≈ 40 $ (Flash) à 80 $ (v3, Multilingual v2) ; abonnements | `elevenlabs` | Souvent le meilleur rendu, aussi le plus cher |
| Gemini TTS | API | aucun | multilingue | commercial | facturé au jeton ; chiffres incohérents entre sources | `google-genai` | À mesurer sur l'extrait |
| Amazon Polly (génératif), Cartesia | API | aucun | voix fr | commercial | non vérifié | `boto3` / `cartesia` | Non étudiés faute de sources |

Ordre de grandeur pour un tome (≈ 1 M caractères) : 15 à 30 € chez les fournisseurs standard,
jusqu'à ≈ 80 € chez ElevenLabs ; la saga de 13 tomes multiplie par 13.

## Moteurs en ligne payants : tarifs et paliers (vérification du 2026-11-14)

Plafond de l'utilisateur : **5 € par tome** (≈ 1 M caractères), **10 €** si la différence est
frappante. Conversion : 1 USD = 0,86 EUR (hypothèse, à réviser). « Lu » = page officielle lue
directement ; « recoupé » = résumés de recherche web (les sites officiels de Microsoft, ElevenLabs,
Mistral et la documentation de Google n'étaient pas joignables depuis l'environnement cloud, hôtes
non autorisés : à confirmer sur les pages officielles avant tout achat).

| Service | Tarif / M car. | Palier gratuit | Carte bancaire | Coût d'un tome (1 M) au-delà du gratuit | Dans le plafond ? | Fiabilité |
|---|---|---|---|---|---|---|
| **Google Chirp 3 HD** | 30 USD ≈ **25,8 €** | **1 M car./mois** gratuit | **Oui** (facturation à activer ; crédit d'essai 300 USD / 90 jours pour les nouveaux clients) | **0 €** si le tome ≤ 1 M car. et l'abonnement mensuel n'est pas déjà consommé ; sinon 25,8 € | **Oui pour ≈ 1 tome/mois** ; au-delà, non (> 10 €) | Lu (page tarifs) |
| Google Neural2 (fr-FR-Neural2-A…) | 16 USD ≈ 13,8 € | 1 M car./mois | Oui | 0 € le premier M du mois ; sinon 13,8 € | 1er tome du mois oui ; sinon non | Lu |
| Google WaveNet / Standard | 4 USD ≈ 3,4 € | 4 M car./mois | Oui | 0 € (4 M gratuits) | Oui, largement | Lu (qualité moindre) |
| **Azure AI Speech Neural HD** | ≈ 22 USD ≈ **18,9 €** (baisse de 30 à 22 USD en mars 2026) | F0 : **500 000 car. neuronaux/mois** (incluse ou non pour les voix HD : **à vérifier**) | F0 : abonnement Azure requis ; **carte exigée à l'inscription** (vérification d'identité), non débitée tant qu'on reste en F0 | 500 k payants ≈ 9,5 € (HD) ; Neural standard ≈ 15-16 USD ≈ 13,8 € / M | Un tome : non (> 10 €) sauf Neural standard avec le gratuit (≈ 6,9 €, > 5 €) | Recoupé, chiffres contradictoires |
| **ElevenLabs** (Multilingual v2 / v3) | 1 crédit/car. ; plan Creator 22 USD pour 100 000 crédits ≈ **190 €/M** (pire cas) | **10 000 crédits/mois** (≈ 10 min d'audio), usage non commercial | Non pour le plan gratuit | ≈ 190 € (Creator) ; ≈ 100 mois de palier gratuit | **Non** (très au-dessus) ; l'extrait de 2 600 car. tient dans le gratuit | Recoupé ; clé API gratuite : sources divergentes |
| **Mistral Voxtral TTS** | 16 USD ≈ **13,8 €** (13 USD ≈ 11,2 € pour la version « 3 », une source) | Plan « Experiment » gratuit (téléphone vérifié, **sans carte**), mais **TTS inclus ? non confirmé** ; données possiblement utilisées pour l'entraînement | Experiment : non ; plan Scale : oui | ≈ 11-14 € | **Non** pour un tome (> 10 €) ; l'extrait coûte ≈ 0,04 € | Recoupé |

Lecture : **seul Google Chirp 3 HD tient dans le plafond** pour un tome (via le million de caractères
gratuit mensuel, soit ≈ 1 tome par mois ; la saga de 13 tomes s'étalerait sur 13 mois, ou coûterait
≈ 26 € par tome au-delà). Les autres coûtent 11 à 190 € par tome ; ils ne servent ici qu'à **écouter
l'extrait** (≈ 2 600 caractères, quelques centimes, gratuit sur les paliers). Si une voix hors Google
l'emporte de façon frappante, le plafond monte à 10 € par tome : seul Mistral (11 €) s'en approche.

**Détails techniques retenus par les moteurs** (`tts/src/livres_tts/engines/`) :

| Service | Point d'accès | En-tête de clé | Réponse | Limite par requête | Voix fr-FR |
|---|---|---|---|---|---|
| Google | `POST https://texttospeech.googleapis.com/v1/text:synthesize` | `X-Goog-Api-Key` | JSON, `audioContent` en base64 (LINEAR16 = WAV) | 5 000 **octets** (on envoie ≤ 2 000 car.) ; la limite de longueur par phrase de Chirp 3 HD n'est pas officielle | `fr-FR-Chirp3-HD-{Aoede, Charon, Kore, Leda, Orus, Puck, Zephyr, …}` (30 voix au catalogue, 7 confirmées par recoupement) ; liste réelle : `GET /v1/voices?languageCode=fr-FR` |
| Azure | `POST https://<région>.tts.speech.microsoft.com/cognitiveservices/v1` (SSML) | `Ocp-Apim-Subscription-Key` | binaire WAV (`riff-24khz-16bit-mono-pcm`) | ≈ 10 min d'audio (3 000 car. par appel) | `fr-FR-VivienneMultilingualNeural`, `RemyMultilingualNeural`, `DeniseNeural`, `HenriNeural`, `EloiseNeural` ; HD `…:DragonHDLatestNeural` (nom fr-FR **non confirmé**) |
| ElevenLabs | `POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128` | `xi-api-key` | binaire MP3 | 10 000 car. (multilingual_v2), 5 000 (v3) — source tierce ; on envoie ≤ 5 000 | voix « premade » (identifiants de mémoire, à confirmer) ; le modèle multilingue parle français avec toute voix |
| Mistral | `POST https://api.mistral.ai/v1/audio/speech` | `Authorization: Bearer` | JSON, `audio_data` en base64 | non trouvée (on envoie ≤ 2 000 car.) | `voice_id` ; nom de modèle incertain (`voxtral-mini-tts-latest`, `-2603`, `-3-0` selon les pages) ; voix française « Marie » (identifiant exact non confirmé) |

OpenAI TTS : non retenu pour cette passe (Mistral existe bien, donc pas de remplacement) ; le
squelette a été supprimé. Un arrêt de `tts-1`/`gpt-4o-mini-tts` au 6 janvier 2027 est rapporté par une
source tierce : à revérifier si OpenAI revient dans la liste.

Hôtes réseau à autoriser dans l'environnement cloud : `texttospeech.googleapis.com` (Google),
`westeurope.tts.speech.microsoft.com` (Azure ; ou la région choisie), `api.elevenlabs.io`,
`api.mistral.ai`.

Sources : https://cloud.google.com/text-to-speech/pricing (lue le 2026-11-14) ·
https://docs.cloud.google.com/text-to-speech/quotas ·
https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd (résumés de recherche) ·
https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/azure-speech-%E2%80%93-neural-hd-text-to-speech-recent-voice-updates/4505380 ·
https://azure.microsoft.com/en-gb/pricing/details/speech/ (via résumé) ·
https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech ·
https://elevenlabs.io/docs/api-reference/text-to-speech/convert ·
https://docs.mistral.ai/api/endpoint/audio/speech · https://docs.mistral.ai/studio/audio/text-to-speech/generate-speech ·
https://help.mistral.ai/en/articles/450104-how-can-i-try-the-api-for-free-with-the-experiment-plan

## Recommandation

**Gratuits à tester en premier (5)** : `edge_tts` (Remy et Vivienne Multilingual), `piper`
(siwis-medium, plancher de comparaison), `kokoro`, `kyutai` (si l'utilisateur a un GPU),
`chatterbox` (si GPU). XTTS-v2 et F5-TTS seulement si ces cinq ne suffisent pas.

**Payants à proposer à l'utilisateur (3–4), après son accord, sur l'extrait seul (quelques centimes)** :
1. Google Chirp 3 HD (quota gratuit mensuel utile),
2. Azure Neural HD (voix françaises nombreuses, quota gratuit),
3. ElevenLabs (référence qualité, plus cher),
4. Voxtral TTS (Mistral, européen ; tarif à confirmer).
OpenAI : à n'ajouter qu'après vérification de la date d'arrêt annoncée.

Règle du CLAUDE.md §2 : on ne paie que si la meilleure voix payante bat la meilleure gratuite d'au
moins 1 point sur 5 en confort d'écoute longue durée.

## Questions à poser à l'utilisateur

- Un ordinateur avec carte graphique (VRAM ?) est-il disponible ? Sinon `kyutai`, `chatterbox`,
  `xtts` et `f5` sont à écarter.
- Accord pour un essai payant sur l'extrait (quel plafond ?).

## Sources (consultées le 2026-11-14)

- https://tts.ai/blog/open-source-text-to-speech-guide-2026/ (panorama open source ; éditeur commercial)
- https://huggingface.co/kyutai/tts-1.6b-en_fr (fiche de modèle citée par la recherche, non lue directement)
- https://mistral.ai/en/pricing et https://techcrunch.com/2026/03/26/mistral-releases-a-new-open-source-model-for-speech-generation/ (Voxtral TTS)
- https://techjacksolutions.com/?p=102915 (tarif Voxtral, page tierce)
- https://www.assemblyai.com/blog/tts-api-pricing, https://dev.to/leanvox/tts-api-pricing-in-2026-i-went-through-every-provider-so-you-dont-have-to-bem,
  https://futureagi.com/blog/best-text-to-speech-providers-2026/ (tarifs des API, sources secondaires)
