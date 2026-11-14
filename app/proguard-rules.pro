# Règles ProGuard/R8 de l'application (minify activé en release).
# Les bibliothèques (Hilt, Compose, Room, Media3…) fournissent leurs propres règles consommateur.
# Ajouter ici uniquement ce que les tests de la variante release montrent nécessaire.

# Conserver les numéros de ligne pour des traces de plantage lisibles dans le journal de diagnostic.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
