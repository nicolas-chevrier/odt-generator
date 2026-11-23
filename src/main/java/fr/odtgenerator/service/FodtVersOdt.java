package fr.odtgenerator.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Convertit un document ODF « à plat » ({@code .fodt} : un seul fichier XML) en archive ODT.
 * <p>
 * Les sections de l'élément racine {@code office:document} sont réparties dans les fichiers
 * de l'archive comme le fait LibreOffice :
 * <ul>
 * <li>{@code content.xml} : scripts, polices, styles automatiques (hors mises en page) et corps ;</li>
 * <li>{@code styles.xml} : polices, styles, styles automatiques et pages maîtres (en-têtes/pieds de page) ;</li>
 * <li>{@code meta.xml}, {@code settings.xml} : métadonnées et paramètres ;</li>
 * <li>{@code Pictures/} : images embarquées en base64 ({@code office:binary-data}), décodées ;</li>
 * <li>{@code META-INF/manifest.xml} et {@code mimetype}, générés.</li>
 * </ul>
 */
final class FodtVersOdt {

	static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
	private static final String STYLE_NS = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
	private static final String MANIFEST_NS = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";
	private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

	private static final String MIMETYPE_TEXTE = "application/vnd.oasis.opendocument.text";
	private static final String VERSION_PAR_DEFAUT = "1.3";

	/** Partie de l'archive : fichier, élément racine et sections reprises du document à plat (dans l'ordre ODF). */
	private record Partie(String fichier, String racine, List<String> sections) {
	}

	private static final List<Partie> PARTIES = List.of(
			new Partie("content.xml", "document-content",
					List.of("scripts", "font-face-decls", "automatic-styles", "body")),
			new Partie("styles.xml", "document-styles",
					List.of("font-face-decls", "styles", "automatic-styles", "master-styles")),
			new Partie("meta.xml", "document-meta", List.of("meta")),
			new Partie("settings.xml", "document-settings", List.of("settings")));

	private FodtVersOdt() {
	}

	static void convertir(String fodt, OutputStream out) throws IOException {
		Document flat = parser(fodt);
		Element racine = flat.getDocumentElement();
		if (!OFFICE_NS.equals(racine.getNamespaceURI()) || !"document".equals(racine.getLocalName())) {
			throw new OdtGenerationException(
					"Le template n'est pas un document ODF à plat (.fodt) : élément racine office:document attendu", null);
		}
		String mimetype = attributOuDefaut(racine, "mimetype", MIMETYPE_TEXTE);
		String version = attributOuDefaut(racine, "version", VERSION_PAR_DEFAUT);

		Map<String, byte[]> images = extraireImages(flat);

		Map<String, byte[]> xml = new LinkedHashMap<>();
		for (Partie partie : PARTIES) {
			Document doc = construirePartie(racine, partie);
			if (doc != null) {
				xml.put(partie.fichier(), serialiser(doc));
			}
		}

		ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
		ecrireMimetype(zip, mimetype);
		for (Map.Entry<String, byte[]> e : xml.entrySet()) {
			ecrire(zip, e.getKey(), e.getValue());
		}
		for (Map.Entry<String, byte[]> e : images.entrySet()) {
			ecrire(zip, e.getKey(), e.getValue());
		}
		ecrire(zip, "META-INF/manifest.xml", manifest(mimetype, version, xml.keySet(), images));
		zip.finish();
	}

	/**
	 * Remplace chaque {@code <office:binary-data>} (image en base64) par un lien
	 * {@code xlink:href="Pictures/..."} vers un fichier de l'archive.
	 */
	private static Map<String, byte[]> extraireImages(Document flat) {
		NodeList noeuds = flat.getElementsByTagNameNS(OFFICE_NS, "binary-data");
		List<Element> binaires = new ArrayList<>();
		for (int i = 0; i < noeuds.getLength(); i++) {
			binaires.add((Element) noeuds.item(i));
		}
		Map<String, byte[]> images = new LinkedHashMap<>();
		for (Element binaire : binaires) {
			byte[] data = Base64.getMimeDecoder().decode(binaire.getTextContent().trim());
			String chemin = "Pictures/image" + (images.size() + 1) + "." + TypeImage.de(data).extension();
			Element parent = (Element) binaire.getParentNode();
			parent.removeChild(binaire);
			parent.setAttributeNS(XLINK_NS, "xlink:href", chemin);
			parent.setAttributeNS(XLINK_NS, "xlink:type", "simple");
			parent.setAttributeNS(XLINK_NS, "xlink:show", "embed");
			parent.setAttributeNS(XLINK_NS, "xlink:actuate", "onLoad");
			images.put(chemin, data);
		}
		return images;
	}

	/** @return la partie, ou {@code null} si le document à plat ne contient aucune de ses sections */
	private static Document construirePartie(Element racine, Partie partie) {
		Document doc = nouveauDocument();
		Element racinePartie = doc.createElementNS(OFFICE_NS, "office:" + partie.racine());
		copierDeclarations(racine, racinePartie);
		doc.appendChild(racinePartie);

		boolean vide = true;
		for (Node enfant = racine.getFirstChild(); enfant != null; enfant = enfant.getNextSibling()) {
			if (enfant instanceof Element section && OFFICE_NS.equals(section.getNamespaceURI())
					&& partie.sections().contains(section.getLocalName())) {
				Element copie = (Element) doc.importNode(section, true);
				if ("content.xml".equals(partie.fichier()) && "automatic-styles".equals(section.getLocalName())) {
					supprimerEnfants(copie, STYLE_NS, Set.of("page-layout"));
				}
				racinePartie.appendChild(copie);
				vide = false;
			}
		}
		boolean obligatoire = Set.of("content.xml", "styles.xml", "meta.xml").contains(partie.fichier());
		return vide && !obligatoire ? null : doc;
	}

	/** Reprend les déclarations de namespaces et la version ODF de la racine du document à plat. */
	private static void copierDeclarations(Element source, Element cible) {
		NamedNodeMap attributs = source.getAttributes();
		for (int i = 0; i < attributs.getLength(); i++) {
			Attr attr = (Attr) attributs.item(i);
			if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())
					|| (OFFICE_NS.equals(attr.getNamespaceURI()) && "version".equals(attr.getLocalName()))) {
				cible.setAttributeNS(attr.getNamespaceURI(), attr.getName(), attr.getValue());
			}
		}
		if (!cible.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xlink")) {
			cible.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xlink", XLINK_NS);
		}
	}

	private static void supprimerEnfants(Element parent, String ns, Set<String> noms) {
		Node enfant = parent.getFirstChild();
		while (enfant != null) {
			Node suivant = enfant.getNextSibling();
			if (ns.equals(enfant.getNamespaceURI()) && noms.contains(enfant.getLocalName())) {
				parent.removeChild(enfant);
			}
			enfant = suivant;
		}
	}

	private static byte[] manifest(String mimetype, String version, Iterable<String> fichiersXml,
			Map<String, byte[]> images) {
		StringBuilder sb = new StringBuilder()
				.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<manifest:manifest xmlns:manifest=\"").append(MANIFEST_NS)
				.append("\" manifest:version=\"").append(echapper(version)).append("\">\n");
		entree(sb, "/", mimetype, version);
		for (String fichier : fichiersXml) {
			entree(sb, fichier, "text/xml", null);
		}
		for (Map.Entry<String, byte[]> image : images.entrySet()) {
			entree(sb, image.getKey(), TypeImage.de(image.getValue()).mediaType(), null);
		}
		return sb.append("</manifest:manifest>\n").toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void entree(StringBuilder sb, String chemin, String mediaType, String version) {
		sb.append(" <manifest:file-entry manifest:full-path=\"").append(echapper(chemin)).append('"');
		if (version != null) {
			sb.append(" manifest:version=\"").append(echapper(version)).append('"');
		}
		sb.append(" manifest:media-type=\"").append(echapper(mediaType)).append("\"/>\n");
	}

	private static String echapper(String valeur) {
		return valeur.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
	}

	/** L'entrée "mimetype" doit être la première de l'archive et non compressée (norme ODF). */
	private static void ecrireMimetype(ZipOutputStream zip, String mimetype) throws IOException {
		byte[] data = mimetype.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(data);
		ZipEntry entree = new ZipEntry("mimetype");
		entree.setMethod(ZipEntry.STORED);
		entree.setSize(data.length);
		entree.setCompressedSize(data.length);
		entree.setCrc(crc.getValue());
		zip.putNextEntry(entree);
		zip.write(data);
		zip.closeEntry();
	}

	private static void ecrire(ZipOutputStream zip, String nom, byte[] data) throws IOException {
		zip.putNextEntry(new ZipEntry(nom));
		zip.write(data);
		zip.closeEntry();
	}

	private static String attributOuDefaut(Element element, String nom, String defaut) {
		String valeur = element.getAttributeNS(OFFICE_NS, nom);
		return valeur.isEmpty() ? defaut : valeur;
	}

	private static Document parser(String xml) {
		try {
			return documentBuilder().parse(new InputSource(new StringReader(xml)));
		} catch (SAXException | IOException e) {
			throw new OdtGenerationException("XML invalide après application du template : " + e.getMessage(), e);
		}
	}

	private static Document nouveauDocument() {
		return documentBuilder().newDocument();
	}

	private static DocumentBuilder documentBuilder() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			// Protection XXE : un template ne doit jamais charger d'entité externe
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			return factory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] serialiser(Document doc) {
		try {
			TransformerFactory factory = TransformerFactory.newInstance();
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
			Transformer transformer = factory.newTransformer();
			transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
			transformer.setOutputProperty(OutputKeys.INDENT, "no");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			transformer.transform(new DOMSource(doc), new StreamResult(out));
			return out.toByteArray();
		} catch (TransformerException e) {
			throw new OdtGenerationException("Écriture XML impossible : " + e.getMessage(), e);
		}
	}

	/** Type d'une image embarquée, déterminé par ses premiers octets. */
	private enum TypeImage {
		PNG("png", "image/png"), JPEG("jpg", "image/jpeg"), GIF("gif", "image/gif"),
		SVG("svg", "image/svg+xml"), INCONNU("bin", "application/octet-stream");

		private final String extension;
		private final String mediaType;

		TypeImage(String extension, String mediaType) {
			this.extension = extension;
			this.mediaType = mediaType;
		}

		String extension() {
			return extension;
		}

		String mediaType() {
			return mediaType;
		}

		static TypeImage de(byte[] d) {
			if (d.length >= 4 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
				return PNG;
			}
			if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
				return JPEG;
			}
			if (d.length >= 4 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
				return GIF;
			}
			String debut = new String(d, 0, Math.min(d.length, 256), StandardCharsets.UTF_8);
			if (debut.contains("<svg")) {
				return SVG;
			}
			return INCONNU;
		}
	}
}
