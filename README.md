# odt-generator

Génère un document `.odt` à partir d'un template ODT (`src/main/resources/templates/template.odt`)
et d'un modèle de données (en dur pour le moment : `FactureDataProvider`), avec FreeMarker.

## Lancer

```bash
mvnw test             # tests unitaires (le document est généré dans un dossier temporaire, supprimé après le test)
mvnw spring-boot:run  # génère target/document-genere.odt (propriété odt.output pour changer le chemin)
```

## Principe

Un `.odt` est une archive ZIP. `OdtGeneratorService` ouvre le template et passe dans FreeMarker
chaque fichier XML qui contient une expression (`${`, `[#`, `@before-row`...), où qu'elle soit :
corps (`content.xml`), en-têtes/pieds de page (`styles.xml`), titre du document (`meta.xml`)...
Les autres entrées (images, manifest...) sont recopiées telles quelles, et l'archive est réécrite
avec `mimetype` en premier, non compressé (norme ODF).

## Syntaxe dans le template (à taper dans LibreOffice Writer)

| Besoin | Syntaxe |
|---|---|
| Valeur (échappée XML automatiquement) | `${facture.client.nom}` |
| Nombre formaté | `${ligne.montant?string("0.00")}` |
| Date (`LocalDate`...) | `${formatDate(facture.date, "dd/MM/yyyy")}` |
| Condition / boucle dans un paragraphe | `[#if ...]...[/#if]`, `[#list ...]...[/#list]` (syntaxe crochets) |
| Répéter une ligne de tableau | `@before-row[#list facture.lignes as ligne]` dans une cellule de la ligne, `@after-row[/#list]` dans une autre |

## Points d'attention lors de l'édition du template

- Taper chaque `${...}` d'un seul trait, sans changer la mise en forme au milieu : sinon LibreOffice
  peut découper l'expression en plusieurs `<text:span>` et FreeMarker ne la reconnaît plus.
  En cas de doute, sélectionner l'expression et faire *Format > Effacer le formatage direct*.
- Désactiver l'autocorrection des guillemets (`"` → `« »`) qui casse les chaînes FreeMarker.
- Éviter `>` / `<` dans les conditions (LibreOffice les écrit `&gt;` / `&lt;`) : utiliser `gt`, `lt`, `gte`, `lte`.
- Désactiver le suivi des modifications (*Édition > Suivi des modifications*) : le texte supprimé
  reste sinon dans `content.xml`, avec ses anciennes variables.
