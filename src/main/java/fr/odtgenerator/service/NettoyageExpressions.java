package fr.odtgenerator.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Répare les expressions FreeMarker telles que LibreOffice les enregistre dans le XML d'un ODT.
 * <p>
 * Une expression tapée dans Writer ({@code ${client.nom}}, {@code [#list ...]}) n'arrive pas
 * toujours intacte dans {@code content.xml} :
 * <ul>
 * <li>découpée par des balises : {@code ${client.<text:span text:style-name="T1">nom</text:span>}}
 * (mise en forme, correction partielle, sessions d'édition successives) ;</li>
 * <li>caractères échappés : {@code &quot;}, {@code &gt;}... ;</li>
 * <li>guillemets typographiques de l'autocorrection : {@code « »}, {@code “ ”}, {@code ‘ ’} ;</li>
 * <li>espaces insécables, ou espaces multiples écrits {@code <text:s/>}.</li>
 * </ul>
 * Ce nettoyage reconstitue chaque expression en texte brut et replace juste après elle les balises
 * qu'elle contenait, dans le même ordre : le XML reste bien formé.
 */
final class NettoyageExpressions {

	private static final Pattern DEBUT = Pattern.compile("\\$\\{|\\[/?#");
	/** Balises représentant un blanc : espaces multiples, tabulation, saut de ligne. */
	private static final Pattern BALISE_BLANC = Pattern.compile("<text:(s|tab|line-break)\\b[^>]*/>");

	private NettoyageExpressions() {
	}

	static String nettoyer(String xml) {
		StringBuilder sb = new StringBuilder(xml.length());
		Matcher debut = DEBUT.matcher(xml);
		int position = 0;
		while (debut.find(position)) {
			char ouvrant = xml.charAt(debut.start()) == '$' ? '{' : '[';
			char fermant = ouvrant == '{' ? '}' : ']';
			StringBuilder expression = new StringBuilder(debut.group());
			StringBuilder balises = new StringBuilder();
			int profondeur = 1;
			int i = debut.end();
			boolean ferme = false;
			while (i < xml.length() && !ferme) {
				char c = xml.charAt(i);
				if (c == '<') {
					int finBalise = xml.indexOf('>', i);
					if (finBalise < 0) {
						break;
					}
					String balise = xml.substring(i, finBalise + 1);
					if (BALISE_BLANC.matcher(balise).matches()) {
						expression.append(' ');
					} else {
						balises.append(balise);
					}
					i = finBalise + 1;
					continue;
				}
				if (c == ouvrant) {
					profondeur++;
				} else if (c == fermant && --profondeur == 0) {
					ferme = true;
				}
				expression.append(c);
				i++;
			}
			if (!ferme) {
				// Expression non refermée : laissée telle quelle, FreeMarker signalera l'erreur
				sb.append(xml, position, debut.end());
				position = debut.end();
				continue;
			}
			sb.append(xml, position, debut.start()).append(normaliser(expression.toString())).append(balises);
			position = i;
		}
		return sb.append(xml, position, xml.length()).toString();
	}

	/** Texte d'une expression : entités XML décodées, guillemets typographiques et espaces insécables remplacés. */
	static String normaliser(String expression) {
		return expression
				.replace("&quot;", "\"").replace("&apos;", "'")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
				.replaceAll("«[\\u00A0\\u202F ]?", "\"").replaceAll("[\\u00A0\\u202F ]?»", "\"")
				.replaceAll("[“”„‟]", "\"").replaceAll("[‘’‚‛]", "'")
				.replaceAll("[\\u00A0\\u202F]", " ");
	}
}
