package fr.odtgenerator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@ExtendWith(MockitoExtension.class)
class OdtGeneratorServiceTest {

	private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
	private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
	private static final String DC_NS = "http://purl.org/dc/elements/1.1/";

	/** Template lu dans src/test/resources/templates/template.odt. */
	private static final String TEMPLATE = "templates/template.odt";

	/** Le service n'a aucune dépendance : Mockito l'instancie simplement via son constructeur. */
	@InjectMocks
	private OdtGeneratorService service;

	@TempDir
	private Path tempDir;

	private Map<String, byte[]> entries;
	private List<ZipEntry> zipEntries;

	@BeforeEach
	void generer() throws IOException {
		// Modèle saisi directement (Map/List), indépendamment des classes du package model :
		// montant et total sont donc fournis tels quels, le test ne valide que le remplissage du template.
		Object modele = Map.of("facture", Map.of(
				"numero", "F-2026-0042",
				"date", LocalDate.of(2026, 11, 20),
				"client", Map.of(
						"nom", "Dupont & Fils",
						"adresse", "12 rue des Lilas",
						"codePostal", "75011",
						"ville", "Paris"),
				"lignes", List.of(
						ligne("Développement module ODT", 3, "450.00", "1350.00"),
						ligne("Recette fonctionnelle", 1, "300.00", "300.00"),
						ligne("Licence <support> annuel", 2, "125.50", "251.00")),
				"total", new BigDecimal("1901.00")));

		// Fichier temporaire, supprimé automatiquement par JUnit à la fin du test
		Path odt = tempDir.resolve("document-test.odt");
		try (OutputStream out = Files.newOutputStream(odt)) {
			service.generer(TEMPLATE, modele, out);
		}
		lireArchive(odt);
	}

	private void lireArchive(Path odt) throws IOException {
		entries = new LinkedHashMap<>();
		zipEntries = new ArrayList<>();
		try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(odt))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				zipEntries.add(e);
				entries.put(e.getName(), zin.readAllBytes());
			}
		}
	}

	@Test
	void utiliseLeTemplateDesRessourcesDeTest() throws IOException {
		// src/test/resources (target/test-classes) passe avant src/main/resources dans le classpath de test
		assertThat(new ClassPathResource(TEMPLATE).getURL().toString())
				.endsWith("/test-classes/" + TEMPLATE);
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
		for (String name : List.of("content.xml", "styles.xml", "meta.xml")) {
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
	void remplitLesVariablesDesMetadonnees() throws Exception {
		assertThat(textes(parse("meta.xml"), DC_NS, "title")).containsExactly("Facture F-2026-0042");
	}

	@Test
	void recopieTelsQuelsLesFichiersSansExpression() throws IOException {
		try (ZipInputStream template = new ZipInputStream(new ClassPathResource(TEMPLATE).getInputStream())) {
			ZipEntry e;
			while ((e = template.getNextEntry()) != null) {
				if (e.getName().equals("META-INF/manifest.xml")) {
					assertThat(entries.get(e.getName())).isEqualTo(template.readAllBytes());
					return;
				}
			}
		}
		throw new AssertionError("manifest absent du template");
	}

	@Test
	void signaleUneVariableAbsenteDuModele() {
		assertThatThrownBy(() -> service.genererEnOctets(TEMPLATE, Map.of()))
				.isInstanceOf(OdtGenerationException.class)
				.hasMessageContaining("facture");
	}

	@Test
	void accepteLesExpressionsTellesQueLibreOfficeLesEnregistre() throws Exception {
		// Expressions découpées par des <text:span>, guillemets « » de l'autocorrection, ">" échappé
		String content = CONTENT_DEBUT
				+ "<text:p>Facture n°${facture.<text:span text:style-name=\"T1\">numero</text:span>}</text:p>"
				+ "<text:p>Date : ${formatDate(facture.date, « dd/MM/yyyy »)}</text:p>"
				+ "<text:p>[#if facture.total &gt; 1000]Montant élevé[/#if]</text:p>"
				+ CONTENT_FIN;

		lireArchive(genererDepuisContent(content, Map.of("facture", Map.of(
				"numero", "F-1", "date", LocalDate.of(2026, 11, 23), "total", new BigDecimal("1500")))));

		assertThat(textes(parse("content.xml"), TEXT_NS, "p"))
				.containsExactly("Facture n°F-1", "Date : 23/11/2026", "Montant élevé");
	}

	@Test
	void indiqueLExtraitFautifEnCasDErreurDeSyntaxe() {
		String content = CONTENT_DEBUT + "<text:p>Client : ${facture.client.nom</text:p>" + CONTENT_FIN;

		assertThatThrownBy(() -> genererDepuisContent(content, Map.of()))
				.isInstanceOf(OdtGenerationException.class)
				.hasMessageContaining("Syntaxe FreeMarker invalide dans test.odt!content.xml")
				.hasMessageContaining("Près de : ")
				.hasMessageContaining("Client : ${facture.client.nom");
	}

	private static final String CONTENT_DEBUT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
			+ " xmlns:text=\"" + TEXT_NS + "\" office:version=\"1.3\"><office:body><office:text>";
	private static final String CONTENT_FIN = "</office:text></office:body></office:document-content>";

	/** Génère un document à partir d'un template minimal en mémoire, contenant uniquement ce content.xml. */
	private Path genererDepuisContent(String contentXml, Object modele) throws IOException {
		ByteArrayOutputStream template = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(template)) {
			zip.putNextEntry(new ZipEntry("mimetype"));
			zip.write("application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII));
			zip.putNextEntry(new ZipEntry("content.xml"));
			zip.write(contentXml.getBytes(StandardCharsets.UTF_8));
		}
		Path odt = tempDir.resolve("test.odt");
		try (OutputStream out = Files.newOutputStream(odt)) {
			service.generer("test.odt", new ByteArrayInputStream(template.toByteArray()), modele, out);
		}
		return odt;
	}

	private static Map<String, Object> ligne(String designation, int quantite, String prixUnitaire, String montant) {
		return Map.of(
				"designation", designation,
				"quantite", quantite,
				"prixUnitaire", new BigDecimal(prixUnitaire),
				"montant", new BigDecimal(montant));
	}

	private Document parse(String entry) throws Exception {
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
