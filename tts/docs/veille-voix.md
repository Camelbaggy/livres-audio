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
