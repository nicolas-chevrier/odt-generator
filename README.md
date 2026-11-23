# odt-generator

Génère un document `.odt` à partir d'un template FODT (`src/main/resources/templates/template.fodt`)
et d'un modèle de données (en dur pour le moment : `FactureDataProvider`), avec FreeMarker.
Les tests utilisent leur propre copie : `src/test/resources/templates/template.fodt`.

## Lancer

```bash
mvnw test             # tests unitaires (le document est généré dans un dossier temporaire, supprimé après le test)
mvnw spring-boot:run  # génère target/document-genere.odt (propriété odt.output pour changer le chemin)
```

## Principe

Le template est un **ODT « à plat »** (`.fodt`) : un seul fichier XML contenant texte, styles,
en-têtes/pieds de page, métadonnées et images (en base64). Il se lit et se compare (`git diff`)
comme n'importe quel fichier texte.

1. `OdtGeneratorService` passe le template entier dans FreeMarker.
2. `FodtVersOdt` découpe le XML obtenu en archive `.odt` : `content.xml`, `styles.xml`, `meta.xml`,
   `settings.xml`, images décodées dans `Pictures/`, `META-INF/manifest.xml`, et `mimetype`
   en premier, non compressé (norme ODF).

## Modifier le template

Ouvrir `template.fodt` dans LibreOffice Writer, modifier, puis **Enregistrer** en conservant
le format « Document texte ODF à plat (.fodt) ». Pour repartir d'un `.odt` existant :
*Fichier > Enregistrer sous > Type : Document texte ODF à plat (.fodt)*.

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
  reste sinon dans le fichier, avec ses anciennes variables.
