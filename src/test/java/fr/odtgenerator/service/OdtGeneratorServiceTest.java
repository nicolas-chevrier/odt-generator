package fr.odtgenerator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import fr.odtgenerator.data.FactureDataProvider;

class OdtGeneratorServiceTest {

	private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
	private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";

	private static byte[] odt;
	private static Map<String, byte[]> entries;
	private static List<ZipEntry> zipEntries;

	@BeforeAll
	static void generer() throws IOException {
		OdtGeneratorService service = new OdtGeneratorService();
		odt = service.genererEnOctets("templates/facture.odt",
				Map.of("facture", new FactureDataProvider().getFacture()));

		// Conservé pour pouvoir ouvrir le résultat dans LibreOffice
		Files.write(Path.of("target", "facture-test.odt"), odt);

		entries = new LinkedHashMap<>();
		zipEntries = new ArrayList<>();
		try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(odt))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				zipEntries.add(e);
				entries.put(e.getName(), zin.readAllBytes());
			}
		}
	}

	@Test
	void respecteLaStructureOdf() {
		ZipEntry first = zipEntries.get(0);
		assertThat(first.getName()).isEqualTo("mimetype");
		assertThat(first.getMethod()).isEqualTo(ZipEntry.STORED);
		assertThat(new String(entries.get("mimetype"), StandardCharsets.US_ASCII))
				.isEqualTo("application/vnd.oasis.opendocument.text");
		assertThat(entries).containsKeys("META-INF/manifest.xml", "content.xml", "styles.xml", "meta.xml");
	}

	@Test
	void produitDuXmlValideSansExpressionFreemarkerRestante() throws Exception {
		for (String name : List.of("content.xml", "styles.xml")) {
			String xml = new String(entries.get(name), StandardCharsets.UTF_8);
			assertThat(xml).as(name).doesNotContain("${", "[#", "[/#", "@before-row", "@after-row");
			parse(name); // lève une exception si le XML est mal formé
		}
	}

	@Test
	void remplitLesVariablesSimples() throws Exception {
		List<String> paragraphes = textes(parse("content.xml"), TEXT_NS, "p");
		String titre = textes(parse("content.xml"), TEXT_NS, "h").get(0);

		assertThat(titre).isEqualTo("Facture n° F-2026-0042");
		assertThat(paragraphes).contains(
				"Date : 20/11/2026",
				"Client : Dupont & Fils", // "&" échappé en &amp; dans le XML, restitué au parsing
				"12 rue des Lilas",
				"75011 Paris");
	}

	@Test
	void repeteLaLigneDeTableauPourChaqueLigneDeFacture() throws Exception {
		NodeList rows = parse("content.xml").getElementsByTagNameNS(TABLE_NS, "table-row");
		List<List<String>> table = new ArrayList<>();
		for (int i = 0; i < rows.getLength(); i++) {
			table.add(textes((Element) rows.item(i), TEXT_NS, "p"));
		}

		assertThat(table).containsExactly(
				List.of("Désignation", "Quantité", "Prix unitaire", "Montant"),
				List.of("Développement module ODT", "3", "450,00 €", "1350,00 €"),
				List.of("Recette fonctionnelle", "1", "300,00 €", "300,00 €"),
				List.of("Licence <support> annuel", "2", "125,50 €", "251,00 €"),
				List.of("Total", "1901,00 €"));
	}

	@Test
	void remplitLesVariablesDuPiedDePage() throws Exception {
		assertThat(textes(parse("styles.xml"), TEXT_NS, "p"))
				.containsExactly("Facture F-2026-0042 - Dupont & Fils");
	}

	@Test
	void signaleUneVariableAbsenteDuModele() {
		OdtGeneratorService service = new OdtGeneratorService();
		assertThatThrownBy(() -> service.genererEnOctets("templates/facture.odt", Map.of()))
				.isInstanceOf(OdtGenerationException.class)
				.hasMessageContaining("facture");
	}

	private static Document parse(String entry) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		return factory.newDocumentBuilder().parse(new ByteArrayInputStream(entries.get(entry)));
	}

	private static List<String> textes(Document doc, String ns, String localName) {
		return textes(doc.getDocumentElement(), ns, localName);
	}

	private static List<String> textes(Element parent, String ns, String localName) {
		NodeList nodes = parent.getElementsByTagNameNS(ns, localName);
		List<String> result = new ArrayList<>();
		for (int i = 0; i < nodes.getLength(); i++) {
			String text = nodes.item(i).getTextContent();
			if (!text.isBlank()) {
				result.add(text);
			}
		}
		return result;
	}
}
