import pytest

from livres_tts.text.normalize import NormalizeOptions, normalize

NBSP = " "
NNBSP = " "

CASES = [
    # --- nombres ---
    ("Il y avait 3 chevaux.", "Il y avait trois chevaux."),
    ("En 1572, le roi.", "En mille cinq cent soixante-douze, le roi."),
    ("l'an 1000", "l’an mille"),
    ("2024", "deux mille vingt-quatre"),
    ("Ils étaient 200.", "Ils étaient deux cents."),
    ("Ils étaient 280 hommes", "Ils étaient deux cent quatre-vingts hommes"),
    (f"1{NBSP}000 écus", "mille écus"),
    (f"12{NNBSP}345 hommes", "douze mille trois cent quarante-cinq hommes"),
    ("1 000 000 de livres", "un million de livres"),
    ("Il a 3,5 mètres", "Il a trois virgule cinq mètres"),
    ("0,05", "zéro virgule zéro cinq"),
    ("Le numéro 007", "Le numéro zéro zéro sept"),
    ("0", "zéro"),
    ("B52 et A4 et 3D", "B52 et A4 et 3D"),
    ("1572-1610", "mille cinq cent soixante-douze à mille six cent dix"),
    ("25 %", "vingt-cinq pour cent"),
    ("12 €", "douze euros"),
    ("1 €", "un euro"),
    ("1,5 €", "un virgule cinq euro"),
    ("5 km", "cinq kilomètres"),
    ("1 kg", "un kilogramme"),
    ("10 m'a dit", "dix m’a dit"),
    ("20 °C", "vingt degrés Celsius"),
    ("½ litre", "un demi litre"),
    # --- ordinaux en chiffres ---
    ("le 1er mars", "le premier mars"),
    ("la 1re fois", "la première fois"),
    ("la 1ère fois", "la première fois"),
    ("les 1ers jours", "les premiers jours"),
    ("le 2e rang", "le deuxième rang"),
    ("le 2nd", "le second"),
    ("la 2nde", "la seconde"),
    ("le 5ème jour", "le cinquième jour"),
    ("21e", "vingt et unième"),
    ("le 3es", "le troisièmes"),
    ("16e siècle", "seizième siècle"),
    ("le 12e", "le douzième"),
    # --- dates et heures ---
    ("14 juillet 1789", "quatorze juillet mille sept cent quatre-vingt-neuf"),
    ("1er janvier 1572", "premier janvier mille cinq cent soixante-douze"),
    ("12/03/1572", "douze mars mille cinq cent soixante-douze"),
    ("01.05.1900", "premier mai mille neuf cents"),
    ("31/02/1572", "trente et un février mille cinq cent soixante-douze"),
    ("45/13/1572", "quarante-cinq/treize/mille cinq cent soixante-douze"),
    ("à 14h30", "à quatorze heures trente"),
    ("à 14 h 30", "à quatorze heures trente"),
    ("à 21 h", "à vingt et une heures"),
    ("à 1 h", "à une heure"),
    ("à 8h05", "à huit heures zéro cinq"),
    # --- chiffres romains : rois, papes ---
    ("Henri IV", "Henri quatre"),
    ("Henri IV.", "Henri quatre."),
    ("François Ier", "François premier"),
    ("François Ier,", "François premier,"),
    ("Louis XIV et Louis XVI", "Louis quatorze et Louis seize"),
    ("Napoléon III", "Napoléon trois"),
    ("Jean-Paul II", "Jean-Paul deux"),
    ("Charles V", "Charles cinq"),
    ("HENRI IV", "HENRI quatre"),
    ("Pie IX", "Pie neuf"),
    ("Élisabeth Ire", "Élisabeth Ire"),  # « Ire » : jamais converti (mot « ire »)
    ("Louis CD", "Louis CD"),
    # --- chiffres romains : siècles ---
    ("XVIe siècle", "seizième siècle"),
    ("XVIème siècle", "seizième siècle"),
    ("au XIXe", "au dix-neuvième"),
    ("Ier siècle", "premier siècle"),
    ("IIe siècle", "deuxième siècle"),
    ("les XVIe et XVIIe siècles", "les seizième et dix-septième siècles"),
    ("la Ve République", "la cinquième République"),
    ("XVI siècle", "seizième siècle"),
    ("les XIIes", "les douzièmes"),
    # --- chiffres romains : tomes, chapitres ---
    ("tome III", "tome trois"),
    ("Tome IV", "Tome quatre"),
    ("Chapitre XII", "Chapitre douze"),
    ("chapitre XIV.", "chapitre quatorze."),
    ("Livre I", "Livre un"),
    ("Partie II", "Partie deux"),
    ("Acte V, scène III", "Acte cinq, scène trois"),
    ("chapitres IV et V", "chapitres quatre et cinq"),
    ("Chapitre D", "Chapitre D"),
    ("XII", "douze"),
    ("XII.", "douze"),
    ("IV. La rencontre", "quatre. La rencontre"),
    ("MIX", "MIX"),
    # --- mots qui ressemblent à des chiffres romains ---
    ("Le roi dit : Mère, viens.", "Le roi dit: Mère, viens."),
    ("Des cris. Ce soir, Les loups, Me voici", "Des cris. Ce soir, Les loups, Me voici"),
    (
        "Vie et mort. Mie de pain. Lie du vin. Ver luisant.",
        "Vie et mort. Mie de pain. Lie du vin. Ver luisant.",
    ),
    ("Dix hommes, Vi, Mi, Di", "Dix hommes, Vi, Mi, Di"),
    ("Il a un CV et un MIX", "Il a un C V et un MIX"),
    ("MI, CI, LI, DI", "MI, CI, LI, DI"),
    ("Ire et colère", "Ire et colère"),
    ("IL dit : CE soir", "IL dit: CE soir"),
    ("Louis M. dit", "Louis Monsieur dit"),
    # --- abréviations ---
    ("M. de Siorac", "Monsieur de Siorac"),
    ("MM. de Siorac et Sauveterre", "Messieurs de Siorac et Sauveterre"),
    ("Mme de Mespech", "Madame de Mespech"),
    ("Mme. de Mespech", "Madame de Mespech"),
    ("Mmes de Mespech", "Mesdames de Mespech"),
    ("Mlle Fontaine", "Mademoiselle Fontaine"),
    ("Mgr de Guise", "Monseigneur de Guise"),
    ("Dr Rabelais", "Docteur Rabelais"),
    ("Pr. Lemaire", "Professeur Lemaire"),
    ("St-Denis et St Jean", "Saint-Denis et Saint Jean"),
    ("Ste Catherine", "Sainte Catherine"),
    ("Stéphane", "Stéphane"),
    ("Dupont et Cie", "Dupont et compagnie"),
    ("avant J.-C.", "avant Jésus-Christ"),
    ("en 52 av. J.-C.", "en cinquante-deux avant Jésus-Christ"),
    ("en 33 apr. J.-C.", "en trente-trois après Jésus-Christ"),
    ("p. ex. ainsi", "par exemple ainsi"),
    ("p.ex. ainsi", "par exemple ainsi"),
    ("cf. plus haut", "voir plus haut"),
    ("c.-à-d. ceci", "c’est-à-dire ceci"),
    ("des fruits, etc. Puis il partit.", "des fruits, et cetera. Puis il partit."),
    ("des fruits, etc.", "des fruits, et cetera."),
    ("des fruits, etc., et du vin", "des fruits, et cetera, et du vin"),
    ("n° 5", "numéro cinq"),
    ("§ 3", "paragraphe trois"),
    ("pain & vin", "pain et vin"),
    # --- sigles ---
    ("la SNCF et le TGV", "la S N C F et le T G V"),
    ("l'ONU", "l’onu"),
    ("la NASA", "la NASA"),
    # --- guillemets, tirets, ponctuation ---
    ("« Bonjour », dit-il.", "« Bonjour », dit-il."),
    ("«Bonjour»", "« Bonjour »"),
    (f"«{NBSP}Quoi{NNBSP}?{NBSP}»", "« Quoi? »"),
    ("“Bonjour”", "« Bonjour »"),
    ('"Bonjour" dit-il', "« Bonjour » dit-il"),
    ('Un seul " guillemet', 'Un seul " guillemet'),
    ("l’épée", "l’épée"),
    ("l'épée", "l’épée"),
    ("- Bonjour, dit-il.", "— Bonjour, dit-il."),
    ("–Bonjour", "— Bonjour"),
    ("— Bonjour", "— Bonjour"),
    ("Il dit – tout bas – merci", "Il dit — tout bas — merci"),
    ("peut-être, porte-clés", "peut-être, porte-clés"),
    ("Alors...", "Alors..."),
    ("Alors…", "Alors..."),
    ("Alors ... non", "Alors... non"),
    ("Alors.....", "Alors..."),
    ("Bonjour ,monsieur ;oui !non ?", "Bonjour, monsieur; oui! non?"),
    ("Il dit:  « non »", "Il dit: « non »"),
    ("a\tb   c", "a b c"),
    ("tou­jours", "toujours"),
    ("1ᵉʳ mars", "premier mars"),
    ("***", "* * *"),
    ("* * *", "* * *"),
    ("___", "* * *"),
    ("—", "—"),
    ("", ""),
    ("Première ligne\nXII\n— Oui.", "Première ligne\ndouze\n— Oui."),
]


@pytest.mark.parametrize(("source", "expected"), CASES)
def test_normalize(source, expected):
    assert normalize(source) == expected


@pytest.mark.parametrize(("source", "expected"), CASES)
def test_normalize_is_idempotent(source, expected):
    once = normalize(source)
    assert normalize(once) == once


def test_enough_cases():
    assert len(CASES) >= 80


def test_custom_acronyms_and_names():
    opts = NormalizeOptions(acronyms={"NASA": "nasa"}, regnal_names=("Sigebert",))
    assert normalize("la NASA et la SNCF", opts) == "la nasa et la SNCF"
    assert normalize("Sigebert III et Henri IV", opts) == "Sigebert trois et Henri IV"


def test_paragraph_lines_are_independent():
    assert normalize("12\n\n3 hommes") == "douze\n\ntrois hommes"
