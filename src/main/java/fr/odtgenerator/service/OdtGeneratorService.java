package fr.odtgenerator.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import freemarker.core.XMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;
import freemarker.template.utility.DeepUnwrap;

/**
 * Génère un document ODT à partir d'un template FODT (ODT « à plat » : un seul fichier XML,
 * enregistrable depuis LibreOffice) contenant des expressions FreeMarker.
 * <p>
 * Le template entier passe dans FreeMarker, puis le XML obtenu est découpé en archive ODT
 * ({@code content.xml}, {@code styles.xml}, images...) par {@link FodtVersOdt}.
 * <p>
 * Syntaxe utilisable dans le template (saisie directement dans LibreOffice Writer) :
 * <ul>
 * <li>{@code ${client.nom}} : valeur, échappée automatiquement pour le XML ;</li>
 * <li>{@code ${formatDate(facture.date, "dd/MM/yyyy")}} : formatage des dates java.time ;</li>
 * <li>{@code [#if ...]}, {@code [#list ...]} : directives en syntaxe crochets (pas de conflit avec le XML) ;</li>
 * <li>{@code @before-row[#list facture.lignes as ligne]} et {@code @after-row[/#list]} placés dans une
 * cellule d'une ligne de tableau : la directive est déplacée autour de la ligne pour la répéter.</li>
 * </ul>
 */
@Service
public class OdtGeneratorService {

	private static final Pattern TABLE_ROW = Pattern.compile("<table:table-row\\b[^>]*>.*?</table:table-row>",
			Pattern.DOTALL);
	private static final Pattern BEFORE_ROW = Pattern.compile("@before-row(\\[#[^\\]]*\\])");
	private static final Pattern AFTER_ROW = Pattern.compile("@after-row(\\[/#[^\\]]*\\])");

	private final Configuration freemarker;

	public OdtGeneratorService() {
		freemarker = new Configuration(Configuration.VERSION_2_3_34);
		freemarker.setDefaultEncoding(StandardCharsets.UTF_8.name());
		freemarker.setOutputFormat(XMLOutputFormat.INSTANCE);
		freemarker.setTagSyntax(Configuration.SQUARE_BRACKET_TAG_SYNTAX);
		freemarker.setLocale(Locale.FRANCE);
		freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
		freemarker.setLogTemplateExceptions(false);
		freemarker.setWrapUncheckedExceptions(true);
		freemarker.setFallbackOnNullLoopVariable(false);
		freemarker.setSharedVariable("formatDate", (TemplateMethodModelEx) OdtGeneratorService::formatDate);
	}

	/**
	 * FreeMarker 2.3 ne sait pas formater les types java.time :
	 * {@code ${formatDate(facture.date, "dd/MM/yyyy")}}.
	 */
	private static Object formatDate(List<?> args) throws TemplateModelException {
		if (args.size() != 2) {
			throw new TemplateModelException("formatDate(date, pattern) attend 2 arguments");
		}
		Object date = DeepUnwrap.unwrap((TemplateModel) args.get(0));
		String pattern = DeepUnwrap.unwrap((TemplateModel) args.get(1)).toString();
		if (!(date instanceof TemporalAccessor temporal)) {
			throw new TemplateModelException("formatDate : type de date non supporté " + date.getClass());
		}
		return DateTimeFormatter.ofPattern(pattern, Locale.FRANCE).format(temporal);
	}

	/**
	 * @param templatePath chemin du template FODT dans le classpath
	 * @param model        modèle de données exposé au template
	 * @param out          flux recevant le document ODT généré (non fermé par cette méthode)
	 */
	public void generer(String templatePath, Object model, OutputStream out) {
		try (InputStream template = new ClassPathResource(templatePath).getInputStream()) {
			generer(templatePath, template, model, out);
		} catch (IOException e) {
			throw new UncheckedIOException("Lecture du template impossible : " + templatePath, e);
		}
	}

	/**
	 * @param templateName nom du template (utilisé dans les messages d'erreur)
	 * @param template     contenu du template FODT (XML UTF-8)
	 */
	public void generer(String templateName, InputStream template, Object model, OutputStream out) {
		try {
			String fodt = new String(template.readAllBytes(), StandardCharsets.UTF_8);
			FodtVersOdt.convertir(appliquerTemplate(templateName, fodt, model), out);
		} catch (IOException e) {
			throw new UncheckedIOException("Génération ODT impossible depuis " + templateName, e);
		}
	}

	private String appliquerTemplate(String name, String fodt, Object model) throws IOException {
		String source = deplacerDirectivesDeLigne(fodt);
		try {
			Template template = new Template(name, new StringReader(source), freemarker);
			StringWriter result = new StringWriter(source.length());
			template.process(model, result);
			return result.toString();
		} catch (TemplateException e) {
			throw new OdtGenerationException("Erreur FreeMarker dans " + name + " : " + e.getMessage(), e);
		}
	}

	/**
	 * Déplace les directives marquées {@code @before-row}/{@code @after-row} hors de la
	 * {@code <table:table-row>} qui les contient, pour que FreeMarker répète la ligne entière.
	 */
	static String deplacerDirectivesDeLigne(String xml) {
		Matcher row = TABLE_ROW.matcher(xml);
		StringBuilder sb = new StringBuilder(xml.length());
		while (row.find()) {
			String rowXml = row.group();
			StringBuilder before = new StringBuilder();
			StringBuilder after = new StringBuilder();
			rowXml = extraire(BEFORE_ROW, rowXml, before);
			rowXml = extraire(AFTER_ROW, rowXml, after);
			row.appendReplacement(sb, Matcher.quoteReplacement(before + rowXml + after));
		}
		row.appendTail(sb);
		return sb.toString();
	}

	private static String extraire(Pattern marker, String rowXml, StringBuilder directives) {
		Matcher m = marker.matcher(rowXml);
		while (m.find()) {
			directives.append(m.group(1));
		}
		return m.replaceAll("");
	}

	/** Génère dans un tableau d'octets (pratique pour un retour HTTP ou un test). */
	public byte[] genererEnOctets(String templatePath, Object model) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		generer(templatePath, model, out);
		return out.toByteArray();
	}
}
