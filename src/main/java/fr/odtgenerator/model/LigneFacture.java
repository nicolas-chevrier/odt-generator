package fr.odtgenerator.model;

import java.math.BigDecimal;

public record LigneFacture(String designation, int quantite, BigDecimal prixUnitaire) {

	public BigDecimal montant() {
		return prixUnitaire.multiply(BigDecimal.valueOf(quantite));
	}
}
