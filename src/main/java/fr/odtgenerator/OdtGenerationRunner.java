package fr.odtgenerator;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import fr.odtgenerator.data.FactureDataProvider;
import fr.odtgenerator.service.OdtGeneratorService;

/**
 * Au démarrage de l'application, génère la facture d'exemple dans le fichier configuré.
 */
@Component
public class OdtGenerationRunner implements CommandLineRunner {

	private static final Logger LOG = LoggerFactory.getLogger(OdtGenerationRunner.class);

	private final OdtGeneratorService generator;
	private final FactureDataProvider dataProvider;
	private final Path output;

	public OdtGenerationRunner(OdtGeneratorService generator, FactureDataProvider dataProvider,
			@Value("${odt.output:target/document-genere.odt}") Path output) {
		this.generator = generator;
		this.dataProvider = dataProvider;
		this.output = output;
	}

	@Override
	public void run(String... args) throws Exception {
		if (output.getParent() != null) {
			Files.createDirectories(output.getParent());
		}
		try (OutputStream out = Files.newOutputStream(output)) {
			generator.generer("templates/template.odt", Map.of("facture", dataProvider.getFacture()), out);
		}
		LOG.info("Document généré : {}", output.toAbsolutePath());
	}
}
