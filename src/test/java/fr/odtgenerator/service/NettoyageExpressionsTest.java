package fr.odtgenerator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NettoyageExpressionsTest {

	@Test
	void sortLesBalisesDUneExpressionDecoupee() {
		String xml = "<text:p>Client : ${facture.client.<text:span text:style-name=\"T1\">nom</text:span>}</text:p>";

		assertThat(NettoyageExpressions.nettoyer(xml)).isEqualTo(
				"<text:p>Client : ${facture.client.nom}<text:span text:style-name=\"T1\"></text:span></text:p>");
	}

	@Test
	void conserveLOrdreDesBalisesSurPlusieursParagraphes() {
		String xml = "<text:p>${a</text:p><text:p>b}</text:p>";

		assertThat(NettoyageExpressions.nettoyer(xml)).isEqualTo("<text:p>${ab}</text:p><text:p></text:p>");
	}

	@Test
	void remplaceLesGuillemetsTypographiquesEtEchappes() {
		assertThat(NettoyageExpressions.nettoyer("${formatDate(facture.date, « dd/MM/yyyy »)}"))
				.isEqualTo("${formatDate(facture.date, \"dd/MM/yyyy\")}");
		assertThat(NettoyageExpressions.nettoyer("${formatDate(facture.date, “dd/MM/yyyy”)}"))
				.isEqualTo("${formatDate(facture.date, \"dd/MM/yyyy\")}");
		assertThat(NettoyageExpressions.nettoyer("${formatDate(facture.date, &quot;dd/MM/yyyy&quot;)}"))
				.isEqualTo("${formatDate(facture.date, \"dd/MM/yyyy\")}");
	}

	@Test
	void decodeLesComparaisonsDansLesDirectives() {
		assertThat(NettoyageExpressions.nettoyer("[#if facture.total &gt; 0]payée[/#if]"))
				.isEqualTo("[#if facture.total > 0]payée[/#if]");
	}

	@Test
	void remplaceLesBalisesDEspacementParUnEspace() {
		assertThat(NettoyageExpressions.nettoyer("[#list facture.lignes<text:s/>as ligne]"))
				.isEqualTo("[#list facture.lignes as ligne]");
	}

	@Test
	void gereLesCrochetsEtAccoladesImbriques() {
		assertThat(NettoyageExpressions.nettoyer("[#list lignes[0..<text:span>1</text:span>] as l]x"))
				.isEqualTo("[#list lignes[0..1] as l]<text:span></text:span>x");
	}

	@Test
	void laisseTelleQuelleUneExpressionNonRefermee() {
		String xml = "<text:p>${facture.numero</text:p>";

		assertThat(NettoyageExpressions.nettoyer(xml)).isEqualTo(xml);
	}

	@Test
	void neModifiePasLeTexteHorsExpressions() {
		String xml = "<text:p>Prix « TTC » &gt; 0 &amp; [lien]</text:p>";

		assertThat(NettoyageExpressions.nettoyer(xml)).isEqualTo(xml);
	}
}
