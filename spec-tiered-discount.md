# Spécification — type d'offre `TIERED_DISCOUNT` (imvaluation)

> **Note (01/09/2026)** : le champ `trigger` de ce type a été renommé `metric` (même enum, même sémantique) pour libérer le nom `trigger` au profit du bloc transverse de conditions défini par `spec-triggers-arbitrage.md`. Ce document utilise le nom actuel.

Nouveau type d'offre configurable : remises par paliers. Neuvième type du moteur, il
s'insère dans la chaîne existante sans toucher les huit autres. Une brique partagée
`TierTable` porte la résolution des paliers ; `FreeDeliveryThresholdDiscountFactory`
sera migrée dessus (iso-comportement) comme preuve de non-régression.

## 1. Positionnement dans le moteur

`TIERED_DISCOUNT` est une **remise** (`AdvantageApplierFactory` → `DiscountApplication`),
pas une offre consommatrice : comme les bons immédiats, elle s'applique **après** les
offres, ne consomme rien dans le pool, et son montant (stocké positif) est soustrait du
total. Conséquences directes, héritées de l'architecture :

- La ligne standard d'un produit ciblé bascule automatiquement sur `BASE_FOR_DISCOUNT`
  (mécanisme existant d'enregistrement des remises sur les appliers).
- Les lignes sous geste manuel sont hors d'atteinte (non `ProductAware`).
- La ventilation TVA de la remise suit la mécanique existante (taux de l'offre ciblée,
  prorata multi-taux, résidu au taux le plus élevé).
- Portée magasin **et groupes de magasins** (`getOffers(eval, type)`, comme N+M et
  vignettes — pas comme les bons immédiats, volontairement).
- Toutes les récompenses sont exprimées comme des montants de remise, y compris
  « article offert » (= remise du prix de l'article) et « nouveau prix »
  (= remise de `prixRéf − nouveauPrix` par unité) : aucune mutation de ligne.

## 2. Schéma JSON (spécification d'offre)

```json
{
  "scope":      "ITEMS" | "TICKET",
  "targetEans": ["ean", ...],            // requis si scope = ITEMS
  "metric":     "AMOUNT" | "QUANTITY",   // dimension des seuils
  "mode":       "HIGHEST_REACHED" | "PROGRESSIVE" | "PER_MULTIPLE",
  "priceUsage": "BASE_FOR_DISCOUNT" | "DEFAULT",   // défaut BASE_FOR_DISCOUNT
  "tiers": [ { "threshold": number, "award": { ... } }, ... ],   // modes HIGHEST/PROGRESSIVE
  "every":  { "step": number, "award": { ... } }                 // mode PER_MULTIPLE
}
```

`award` (les quatre récompenses, toutes disponibles en v1) :

```json
{ "type": "PERCENTAGE",        "value": 5.0 }
{ "type": "AMOUNT_PER_ITEM",   "value": 0.50 }
{ "type": "ITEM_FREE",         "selection": "CHEAPEST" | "MOST_EXPENSIVE", "quantity": 1 }
{ "type": "NEW_PRICE",         "value": 4.75 }
```

Contraintes de validation (JSON Schema draft-07, comme les autres types) :
`tiers` trié non requis (trié par le moteur), `threshold ≥ 0` strictement croissants,
`oneOf` entre `tiers` (HIGHEST/PROGRESSIVE) et `every` (PER_MULTIPLE) ;
`targetEans` requis ssi `scope = ITEMS` ; `NEW_PRICE` et `ITEM_FREE` interdits en mode
`PROGRESSIVE` (sans signification par tranche) ; `NEW_PRICE` et `AMOUNT_PER_ITEM`
interdits en scope `TICKET` ; annotations `x-label`/`x-widget` pour le formulaire
back-office auto-généré.

## 3. Sémantique des trois modes

L'**assiette de déclenchement** B est, selon `scope` et `metric` :
- `ITEMS` + `AMOUNT` : somme des montants TTC (`priceUsage`) des articles ciblés
  couverts par les applications `ProductAware` ;
- `ITEMS` + `QUANTITY` : somme des quantités standard de ces articles ;
- `TICKET` + `AMOUNT` : total marchandises (somme des applications `ProductAware`,
  livraison et consigne exclues — même règle que le franco).

### 3.1 `HIGHEST_REACHED` — « 5 % dès 50 €, 10 % dès 100 € »

Le palier de `threshold` le plus élevé tel que `B ≥ threshold` s'applique, **une
fois, sur toute l'assiette**. B = 120 € → 10 % × 120 = 12,00 €.
B < premier seuil → aucune remise. C'est la sémantique de tous les exemples de
remises du questionnaire, et celle du franco de port existant.

### 3.2 `PROGRESSIVE` — « 5 % sur les 50 premiers euros, 10 % sur les 50 suivants »

Les `threshold` sont des **planchers de tranches** : le palier de seuil `t_i`
s'applique à la part d'assiette comprise entre `t_i` et `t_{i+1}` ; la dernière
tranche est ouverte (s'étend au-delà de son plancher).

Exemple `tiers = [{0, 5%}, {50, 10%}, {100, 15%}]`, B = 120 € :
5 % × 50 + 10 % × 50 + 15 % × 20 = 2,50 + 5,00 + 3,00 = **10,50 €**.

Une première tranche à `{0, 0%}` exprime « rien avant le seuil » : le mode marginal
(« seuls les euros/articles au-delà du seuil sont remisés ») est donc un cas
particulier de ce mode — il n'y a pas de paramètre d'assiette séparé.
En `metric = QUANTITY` avec `AMOUNT_PER_ITEM`, les tranches portent sur les unités :
`[{0, 0€}, {3, 0.50€}]` sur 5 stylos → 0,50 € × 2 = 1,00 € (les unités 4 et 5).

### 3.3 `PER_MULTIPLE` — « 1 € tous les 50 € », « 1 vignette tous les 10 € »

`n = floor(B / step)` ; la récompense s'applique `n` fois.
B = 120 €, step 50, award 1 € → 2,00 €. `ITEM_FREE` autorisé (« 1 article offert
par tranche de 50 € » → `quantity × n` articles offerts, sélection CHEAPEST/
MOST_EXPENSIVE parmi les articles ciblés couverts).

## 4. Calcul du montant par récompense

Base produit : `getProductAmount` / `getProductQuantity` des applications
`ProductAware` (donc net de la logique de chaque offre : un article déjà dans un
bundle contribue pour sa part bundle). Arrondis : échelle 2 HALF_UP aux mêmes points
que les remises existantes ; taux : celui du produit (ou prorata multi-taux pour
`TICKET`, via la mécanique de ventilation existante).

- `PERCENTAGE` : `part d'assiette × value/100` (par tranche en PROGRESSIVE).
- `AMOUNT_PER_ITEM` : `value × nombre d'unités concernées` (unités standard).
- `ITEM_FREE` : tri des articles ciblés couverts par prix unitaire (`priceUsage`),
  sélection CHEAPEST/MOST_EXPENSIVE, remise = somme des prix des `quantity` articles
  sélectionnés. Réutilise la logique de tri du N+M.
- `NEW_PRICE` : remise = `max(0, prixUnitaire(priceUsage) − value) × unités`.

**Plafonnement** : la remise totale est plafonnée à l'assiette (jamais de ligne ni de
total négatif) — durcissement volontaire par rapport aux bons immédiats existants
(non plafonnés, défaut documenté au catalogue e2e).

## 5. Ordonnancement et sortie JSON

- Score d'efficacité : constante `5.0` — après les vignettes (10.0), avant le franco
  (−1.0) et le titre-restaurant (−2.0), au voisinage des bons immédiats. Documenté et
  ajustable.
- Sortie : une `TieredDiscountApplication` par (offre × application ciblée), forme
  standard des remises : `{ "type": "Tiered Discount: <code> (tier <threshold|xN>)",
  "offer": <type de l'application ciblée>, "discountAmount": {HT, TTC, taux} }`.
  Scope `TICKET` : une application par offre, `offer` = null-safe (rattachement au
  panier, comme le titre-restaurant).

## 6. `TierTable` (brique partagée)

Classe valeur sans état CDI : parse le fragment (`tiers`/`every`), trie, expose
`resolveHighest(B)`, `slices(B)` (PROGRESSIVE), `multiples(B)` (PER_MULTIPLE).
Consommateurs : `TieredDiscountFactory` (v1), `FreeDeliveryThresholdDiscountFactory`
(migration iso-comportement : HIGHEST_REACHED, assiette = total marchandises,
plafond = coût de livraison), futurs `VOUCHER_GRANT`/attribution de vignettes.

## 7. Hors périmètre v1 (explicitement)

Egalim (plafond réglementaire transverse), limites budgétaires/compteurs, dates de
validité d'offre, cumulabilité paramétrable : chantiers séparés déjà cartographiés.
Le cumul de `TIERED_DISCOUNT` avec les autres remises suit la règle implicite du
moteur (additif, non plafonné entre types).

## 8. Couverture questionnaire attendue

Passent de 3 → 1 : GB-01-05-02 à -14 (paliers B, y c. article offert et nouveau
prix), GB-01-05-30/31 (remises ticket = HIGHEST à un palier), GM-06-04-29 à -39 et
-42/-43/-46 à -50 (paliers M), GM-06-03-01/02 en partie (déclencheurs minimum
d'achat, couverts comme palier unique). Reste 3 : les variantes TVA (90/91/185/186),
coupons déclencheurs, et tout le bloc instruments (dépendra de `VOUCHER_GRANT`,
qui réutilisera `TierTable`).

## 9. Plan de tests

Unitaires par mode × récompense × metric (matrice ~20 cas dont bornes exactes de
tranches, B = seuil exact, assiette nulle, plafonnement, arrondis au centime,
multi-taux en TICKET) ; invariants e2e existants (Σ items = montant, ventilation =
total) ; non-régression franco (mêmes réponses avant/après migration TierTable) ;
idempotence sous double exécution du score (sandbox) ; offre d'exemple au seed.
