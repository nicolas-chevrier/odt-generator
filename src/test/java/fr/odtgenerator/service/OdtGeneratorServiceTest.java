package fr.odtgenerator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@ExtendWith(MockitoExtension.class)
class OdtGeneratorServiceTest {

	private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
	private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
	private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
	private static final String STYLE_NS = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
	private static final String DRAW_NS = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
	private static final String MANIFEST_NS = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";
	private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

	/** Image PNG de 1x1 pixel. */
	private static final String PNG_1X1 =
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

	/** Template lu dans src/test/resources/templates/template.fodt. */
	private static final String TEMPLATE = "templates/template.fodt";

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
	void repartitLeDocumentAPlatDansLesFichiersDeLArchive() throws Exception {
		Element content = parse("content.xml").getDocumentElement();
		assertThat(content.getLocalName()).isEqualTo("document-content");
		assertThat(enfants(content)).containsExactly("automatic-styles", "body");
		assertThat(content.getElementsByTagNameNS(STYLE_NS, "page-layout").getLength()).isZero();

		Element styles = parse("styles.xml").getDocumentElement();
		assertThat(styles.getLocalName()).isEqualTo("document-styles");
		assertThat(enfants(styles)).containsExactly("styles", "automatic-styles", "master-styles");

		Element meta = parse("meta.xml").getDocumentElement();
		assertThat(meta.getLocalName()).isEqualTo("document-meta");
		assertThat(meta.getTextContent()).contains("Modèle de facture");

		assertThat(cheminsDuManifest()).containsExactly("/", "content.xml", "styles.xml", "meta.xml");
	}

	@Test
	void extraitLesImagesEmbarquees() throws Exception {
		byte[] png = Base64.getDecoder().decode(PNG_1X1);
		String fodt = """
				<?xml version="1.0" encoding="UTF-8"?>
				<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
				    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
				    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
				    office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.text">
				 <office:body><office:text><text:p>
				  <draw:frame draw:name="logo"><draw:image><office:binary-data>%s</office:binary-data></draw:image></draw:frame>
				 </text:p></office:text></office:body>
				</office:document>
				""".formatted(PNG_1X1);

		Path odt = tempDir.resolve("image.odt");
		try (OutputStream out = Files.newOutputStream(odt)) {
			service.generer("image.fodt", new ByteArrayInputStream(fodt.getBytes(StandardCharsets.UTF_8)), Map.of(), out);
		}
		lireArchive(odt);

		assertThat(entries.get("Pictures/image1.png")).isEqualTo(png);
		Element image = (Element) parse("content.xml").getElementsByTagNameNS(DRAW_NS, "image").item(0);
		assertThat(image.getAttributeNS(XLINK_NS, "href")).isEqualTo("Pictures/image1.png");
		assertThat(image.getElementsByTagNameNS(OFFICE_NS, "binary-data").getLength()).isZero();
		assertThat(cheminsDuManifest()).contains("Pictures/image1.png");
		assertThat(new String(entries.get("META-INF/manifest.xml"), StandardCharsets.UTF_8)).contains("image/png");
	}

	@Test
	void refuseUnTemplateQuiNestPasUnDocumentAPlat() {
		InputStream template = new ByteArrayInputStream("<html/>".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> service.generer("page.html", template, Map.of(), OutputStream.nullOutputStream()))
				.isInstanceOf(OdtGenerationException.class)
				.hasMessageContaining("office:document");
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
		assertThatThrownBy(() -> service.genererEnOctets(TEMPLATE, Map.of()))
				.isInstanceOf(OdtGenerationException.class)
				.hasMessageContaining("facture");
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

	private static List<String> enfants(Element parent) {
		List<String> noms = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e) {
				noms.add(e.getLocalName());
			}
		}
		return noms;
	}

	private List<String> cheminsDuManifest() throws Exception {
		NodeList fichiers = parse("META-INF/manifest.xml").getElementsByTagNameNS(MANIFEST_NS, "file-entry");
		List<String> chemins = new ArrayList<>();
		for (int i = 0; i < fichiers.getLength(); i++) {
			chemins.add(((Element) fichiers.item(i)).getAttributeNS(MANIFEST_NS, "full-path"));
		}
		return chemins;
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
