package fr.odtgenerator.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import fr.odtgenerator.model.Client;
import fr.odtgenerator.model.Facture;
import fr.odtgenerator.model.LigneFacture;

/**
 * Modèle de données créé en dur, en attendant une vraie source (base, API...).
 */
@Component
public class FactureDataProvider {

	public Facture getFacture() {
		Client client = new Client("Dupont & Fils", "12 rue des Lilas", "75011", "Paris");
		return new Facture("F-2026-0042", LocalDate.of(2026, 11, 20), client, List.of(
				new LigneFacture("Développement module ODT", 3, new BigDecimal("450.00")),
				new LigneFacture("Recette fonctionnelle", 1, new BigDecimal("300.00")),
				new LigneFacture("Licence <support> annuel", 2, new BigDecimal("125.50"))));
	}
}
