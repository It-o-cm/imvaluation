# Spécification C3 — Mécaniques de remise manquantes

**Projet** : imvaluation · **Chantier** : C3 (phase 1 du plan P1 Promotion) · **Statut** : validé en revue de conception (session du 01/09/2026)

Ce document complète `spec-triggers-arbitrage.md` (C1+C2), dont il reprend le glossaire, la clause d'évolution du JSON et les règles d'arbitrage — tout ce qui y est gravé s'applique ici sans être répété. C3 n'ajoute **que des types d'avantages** : aucun changement au cœur (valorisation, triggers, arbitrage), un seul champ additif au panier.

---

## 1. Périmètre et lignes RFP (16)

| Mécanique | Lignes RFP | Réponse actuelle |
|---|---|---|
| Nouveau prix (simple + multi-listes) | GB-01-05-29, GB-01-05-39 · GM-06-04-15, GM-06-04-25 | 1a, 3, 1a, 3 |
| Remise = TVA (ticket + liste) | GB-01-05-32, GB-01-05-33 | 3, 3 |
| Remise % / € sur total ticket | GM-06-04-16, GM-06-04-17 | 3, 3 |
| Montant par article du lot (multi-listes) | GB-01-05-35 | 1a |
| Article le moins/plus cher offert (multi-listes) | GB-01-05-37, GB-01-05-38 · GM-06-04-23, GM-06-04-24 | 1a ×4 |
| Paliers par produit identique | GM-06-04-47 | 1a |
| Anti-gaspillage (DLC) | GB-01-06-18 · GM-06-04-50 | 3, 3 |

**Hors périmètre explicite** : liste de dotation séparée (`giftEans`), répétition de l'article offert, contrôles Egalim/vente à perte (C5), `evaluationDate` (C5), toute retouche des types existants autre que l'ajout `perProduct` au TIERED_DISCOUNT (§8). GM-06-04-21 (P2) profitera du type §5 mais n'est pas cotée ici.

---

## 2. Décisions arbitrées (rappel opposable)

1. **Nouveau prix = avantage à prix cible**, jamais une valorisation. L'invariant : *le nouveau prix fixe le résultat, pas le montant de la remise* — le client paie le prix cible, le `discountAmount` absorbe la différence, quelle que soit la base (bascule BASE_FOR_DISCOUNT comprise). Un nouveau prix ≥ prix courant ne produit rien (une remise négative n'existe pas).
2. **Remise TVA = remise ordinaire à montant dérivé de la TVA.** `vatBreakdown` ne change pas d'un iota : il reste calculé sur les montants nets finaux, sans cas spécial. Exemple normatif : 120 € TTC (TVA 20 %) → remise 20,00 €, payé 100,00 €, TVA fiscale restituée 16,67 €.
3. **Anti-gaspi livré complet en C3**, avec `bestBeforeDate` optionnel par ligne. Doctrine d'avenant réaffirmée : un champ optionnel additif avec défaut se livre avec le chantier qui en a besoin ; seul un changement structurel exige un avenant versionné.
4. **Article offert = sélection parmi les contributeurs du trigger** (l'union des listes déclencheuses). Ni le panier entier (manipulable), ni une dotation séparée (autre mécanique, extension future par champ optionnel).

---

## 3. NEW_PRICE_DISCOUNT

```json
{ "applicationMoment": "AT_TRIGGER",
  "targets": [
    { "eans": ["3300000000001", "3300000000004"], "newPrice": 1.99 },
    { "eans": ["3300000000007"],                  "newPrice": 0.99 }
  ],
  "trigger": { "conditions": [ ... ] }   // optional, like everywhere
}
```

- `targets[]` (minItems 1) : un seul élément = le nouveau prix simple (GB-01-05-29/GM-06-04-15) ; plusieurs = le multi-listes (GB-01-05-39/GM-06-04-25). Un EAN présent dans deux targets ⇒ rejet à la création.
- Sémantique par ligne ciblée : `discountAmount = max(0, base courante − newPrice × quantité)` ; si ≤ 0, la ligne ne produit **rien** (pas d'application à zéro). `newPrice` est un prix TTC unitaire, `exclusiveMinimum: 0`.
- BASE_FOR_DISCOUNT : à l'enregistrement de la remise, la ligne bascule sur le prix de référence comme pour toute remise, et la remise vaut `référence − newPrice × qté` — le payé reste `newPrice × qté`.
- Deux NEW_PRICE en concurrence sur un même article : l'arbitrage tranche (priorité) ; le second voit la base au premier nouveau prix et ne produit que s'il descend encore. Aucun cas spécial.
- Type d'application : `"New Price: <code>"` ; score d'efficacité : montant sandbox calculé (comme ImmediateVoucher).

---

## 4. VAT_REFUND_DISCOUNT

```json
{ "scope": "TICKET",                    // or "ITEMS" + targetEans
  "applicationMoment": "AT_TOTAL" }     // natural default for TICKET
```

- `scope: TICKET` (GB-01-05-32) : assiette = toutes les lignes valorisées disponibles. `scope: ITEMS` (GB-01-05-33) : `targetEans` requis, assiette = lignes ciblées. Mêmes règles croisées que le trigger MINIMUM_AMOUNT (TICKET ⇒ `targetEans` interdit).
- `discountAmount = Σ(TTC − HT)` de l'assiette **aux montants courants** — le calcul est celui de l'award `VAT_AMOUNT` des grants, porté en remise : un seul endroit du code sait ce que « valeur de la TVA » veut dire (extraire l'aide de calcul en méthode partagée est autorisé ; dupliquer la formule ne l'est pas).
- Multi-taux géré d'office (somme ligne à ligne) ; TVA 0 (cartes cadeaux) contribue 0. Distribution en `DiscountApplication` pro-rata TTC sur l'assiette, résidu d'arrondi sur la dernière ligne (même règle que TIERED_DISCOUNT).
- **`vatBreakdown` inchangé** — aucun code de ce type ne touche à la restitution fiscale.
- Type d'application : `"VAT Refund: <code>"`.

---

## 5. TICKET_DISCOUNT et AMOUNT_PER_ITEM_DISCOUNT

**TICKET_DISCOUNT** (GM-06-04-16/17) :

```json
{ "discountType": "PERCENTAGE", "value": 10.0, "applicationMoment": "AT_TOTAL" }
```

- Assiette = toutes les lignes disponibles ; `PERCENTAGE` (0 < v ≤ 100) ou `AMOUNT` (plafonné à l'assiette). Distribution pro-rata TTC, résidu sur la dernière ligne. Avec un `trigger` MINIMUM_AMOUNT TICKET, c'est le « 5 € dès 50 € » canonique de la spec C1+C2.

**AMOUNT_PER_ITEM_DISCOUNT** (GB-01-05-35) :

```json
{ "targets": [
    { "eans": ["...liste A..."], "amountPerItem": 0.50 },
    { "eans": ["...liste B..."], "amountPerItem": 1.00 }
  ],
  "trigger": { "conditions": [ ...one per list, typically... ] }
}
```

- Chaque ligne ciblée reçoit `amountPerItem × quantité`, plafonné à la base courante de la ligne. Un EAN dans deux targets ⇒ rejet. Le déclencheur multi-listes est du ressort du bloc `trigger` — ce type ne re-spécifie aucune condition.

---

## 6. GIFT_ITEM_DISCOUNT

```json
{ "selection": "CHEAPEST",              // or MOST_EXPENSIVE
  "trigger": { "conditions": [
      { "kind": "MINIMUM_QUANTITY", "eans": ["...fromages..."], "threshold": 1 },
      { "kind": "MINIMUM_QUANTITY", "eans": ["...vins..."],     "threshold": 1 }
  ]}
}
```

- **`trigger` requis**, avec au moins une condition à contributeurs (MINIMUM_AMOUNT scope ITEMS ou MINIMUM_QUANTITY) — règle croisée rejetée à la création sinon (un « offert parmi les déclencheurs » sans déclencheurs attribuables n'a pas de sens ; COUPON_CODE seul ⇒ rejet).
- Sélection parmi **les contributeurs du `TriggerResult`** : prix unitaire courant le plus bas (CHEAPEST) ou le plus haut (MOST_EXPENSIVE) ; égalité départagée par EAN croissant (déterminisme). Les lignes à quantité non entière sont exclues de la sélection (elles comptent dans les seuils, elles ne sont pas offrables).
- L'offert = **une unité** : `discountAmount = prix unitaire courant` de la ligne élue. **Une application par ticket** (hypothèse RFP, §10). Sélection vide (tous contributeurs au poids) ⇒ rien, silencieux.
- Extensions spécifiées non construites : `giftEans` (dotation) et un futur champ de répétition — champs optionnels avec défaut, conformes à la clause d'évolution.
- Type d'application : `"Gift Item: <code> (<ean>)"`.

---

## 7. ANTI_WASTE_DISCOUNT

```json
{ "targetEans": ["..."],                 // optional: absent = any line carrying a DLC
  "tiers": [
    { "maxRemainingDays": 3, "percent": 30.0 },
    { "maxRemainingDays": 1, "percent": 50.0 }
  ]
}
```

- S'applique **par ligne** portant `bestBeforeDate` (ciblée si `targetEans` présent). `remainingDays = bestBeforeDate − dateDuJour` (horloge serveur ; basculera sur `evaluationDate` en C5 — noté au Javadoc). Ligne sans DLC : ignorée, silencieux. `remainingDays < 0` (périmé) : traité comme le palier le plus strict — le moteur ne juge pas la vendabilité, c'est l'affaire de la caisse.
- Résolution : le palier au `maxRemainingDays` **le plus petit** tel que `remainingDays ≤ maxRemainingDays` (le plus strict atteint). Implémentation via `TierTable` (seuils = maxRemainingDays, `resolveHighest` sur l'axe inversé) — pas de troisième système de seuils. `tiers` minItems 1, `maxRemainingDays ≥ 0`, doublons de seuil rejetés.
- `discountAmount = base courante × percent / 100` par ligne éligible, une `DiscountApplication` par ligne.
- Pré-câblage C5 : le type est le candidat naturel au flag « non soumis Egalim » (GB-01-06-08) — aucun contrôle implémenté ici, place notée au Javadoc.

**Avenant panier** (additif, rétrocompatible) : `items[].bestBeforeDate` — `{"type": "string", "format": "date"}`, optionnel. Par **ligne** (deux lots du même EAN = deux lignes, à la charge de l'appelant). État du contrat après C3 : `couponCodes`, `closed`, `bestBeforeDate`.

---

## 8. TIERED_DISCOUNT : ajout `perProduct` (GM-06-04-47)

Champ optionnel additif au schéma existant, défaut `false` (comportement actuel inchangé) :

```json
{ "scope": "ITEMS", "targetEans": [...], "metric": "QUANTITY", "perProduct": true, ... }
```

- `perProduct: true` : la métrique est mesurée et les paliers résolus **par EAN distinct** de la cible, indépendamment (6 yaourts A + 2 yaourts B ⇒ A atteint son palier, B le sien) — c'est le « produit identique » du RFP. Restriction croisée : `perProduct` exige `scope: ITEMS` et `metric: QUANTITY` ; toute autre combinaison ⇒ rejet à la création.
- Seule retouche autorisée d'un type existant dans ce chantier.

---

## 9. Intégration à l'arbitrage (commun aux six types)

- Tous sont des `AdvantageApplierFactory` ordinaires : `EngineTrait` (schéma + injections trigger/applicationMoment/arbitration héritées), `getConfiguration()` retournant leur `Offer`, score d'efficacité = montant sandbox calculé, assiettes construites sur `getAvailableOffers()` (lignes consommées exclues), `DiscountApplication` positives.
- Aucun n'introduit de règle d'arbitrage nouvelle : cumul, priorités, limites, consommation, vagues et panier ouvert s'appliquent tels que C2 les a définis.
- Groupe-scope : tous via `getOffers` (magasin + groupes), comme TIERED_DISCOUNT.

---

## 10. Hypothèses à porter dans la réponse RFP

1. **Remise TVA** : « la remise est égale au montant de la TVA de l'assiette au moment du calcul ; la TVA fiscale du ticket reste calculée sur les montants nets payés, conformément à la réglementation. » (La lecture « TVA = 0 sur le ticket » est fiscalement impossible pour un moteur de promotion.)
2. **Article offert** : « une application par ticket ; l'article offert est choisi parmi les articles des listes déclencheuses. » La répétition (2× les listes ⇒ 2 offerts) est une extension chiffrable, non incluse.
3. **Nouveau prix** : « le prix actif est remplacé en effet (le client paie le nouveau prix), la remise correspondante étant tracée sur le ticket » — et non remplacé en donnée dans le référentiel.

---

## 11. Impacts fichiers

**Nouveaux** : `engine/offers/NewPriceDiscountFactory.java`, `VatRefundDiscountFactory.java`, `TicketDiscountFactory.java`, `AmountPerItemDiscountFactory.java`, `GiftItemDiscountFactory.java`, `AntiWasteDiscountFactory.java` + leurs tests.

**Modifiés** : `engine/Basket.java` (schéma : `items[].bestBeforeDate`), `engine/offers/TieredDiscountFactory.java` (champ `perProduct`, §8 uniquement), `engine/offers/InstrumentGrantFactory.java` **seulement si** l'aide de calcul VAT est extraite en méthode partagée (§4).

**Interdits** : tout le reste — en particulier le cœur (ValuationEngine, BasketEvaluation, Trigger*) qui ne doit pas bouger d'une ligne. C'est un critère d'acceptation : `git diff` sur ces fichiers = vide.

---

## 12. Plan de tests

- **Par type** : cas nominal, assiette vide, plafonds (AMOUNT ≤ assiette, per-item ≤ ligne, NEW_PRICE ≥ courant ⇒ rien), distribution pro-rata + résidu, interaction trigger (satisfait / non), `@QuarkusTest @TestTransaction`, modèle `TieredDiscountFactoryTest`.
- **Normatifs** : l'exemple TVA 120/100/20 → remise 20,00, payé 100,00, `vatBreakdown` 16,67 (bout en bout) ; NEW_PRICE sous bascule BASE_FOR_DISCOUNT (le payé reste `newPrice × qté`) ; GIFT_ITEM égalité de prix (départage EAN), contributeurs au poids exclus, trigger sans contributeurs rejeté ; ANTI_WASTE périmé = palier le plus strict, ligne sans DLC ignorée, doublon de seuil rejeté ; `perProduct` 6A+2B ; deux NEW_PRICE en concurrence (priorités).
- **Arbitrage hérité** : chaque nouveau type traversé une fois par cumul/limites/consommation/panier ouvert (un test par dimension suffit — la mécanique C2 est déjà couverte).
- **Régression** : suite existante 1632/1632 sans modification ; `perProduct` absent ⇒ TIERED_DISCOUNT bit à bit identique.
- **Definition of done** : Javadoc anglais sur toutes les méthodes sans exception ; couverture complète des nouvelles classes ; les 16 lignes RFP du §1 basculées en « 1 » ; rapport de couverture mis à jour.
