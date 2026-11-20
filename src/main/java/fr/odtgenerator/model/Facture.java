package fr.odtgenerator.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record Facture(String numero, LocalDate date, Client client, List<LigneFacture> lignes) {

	public BigDecimal total() {
		return lignes.stream().map(LigneFacture::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
