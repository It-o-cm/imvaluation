# imvaluation — Moteur de Valorisation de Panier

Service Quarkus de calcul de prix pour systèmes POS (Point de Vente). Il détermine le montant d'une transaction à partir d'un panier d'articles, en appliquant offres commerciales et avantages (remises, frais de livraison, suggestions).

## Prérequis

- Java 21
- Maven 3.9+
- (Optionnel) GraalVM pour la compilation native

## Démarrage rapide

```bash
# Mode développement (hot reload)
./mvnw quarkus:dev

# Packager
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

# Compilation native
./mvnw package -Dnative
```

L'interface de développement Quarkus est disponible sur http://localhost:8080/q/dev/

---

## Architecture

### Vue d'ensemble

Le cœur du système est le **ValuationEngine**. Il orchestre deux phases :

1. **Phase Offres** — couvre l'intégralité des articles du panier avec des `OfferApplier` triés par score d'efficacité (le plus avantageux passe en premier).
2. **Phase Avantages** — modifie les résultats financiers des offres (remises, frais supplémentaires, suggestions) via des `AdvantageApplier`.

### Pattern Factory / Applier / Application

Toute règle commerciale est structurée en trois classes :

| Classe | Rôle |
|--------|------|
| **Factory** (`@ApplicationScoped`) | Bean CDI auto-découvert. Interroge la BDD et instancie les Appliers au moment de la valorisation. |
| **Applier** | Contient l'algorithme métier. Calcule un score d'efficacité, consomme les articles du panier via `pick()`, et produit une Application. |
| **Application** | Résultat passif : montant (HT/TTC) et articles couverts. Correspond à une ligne du ticket de caisse. |

### Objets centraux

- **`Basket`** — DTO d'entrée (immutable). Photographie brute du panier : articles (EAN + quantité), code magasin, mode de livraison, vignettes, instructions.
- **`BasketEvaluation`** — État dynamique du moteur. La méthode `feedFrom()` agrège les lignes par EAN en une `Map<EAN, Item>` (`toEvaluate`). Les Appliers y piochent via `pick()`.

### Détermination du prix d'un article

Ordre de priorité décroissant :

1. **Prix fourni explicitement** dans le `Basket.Item` (réévaluation de commande passée).
2. **Prix à une date donnée** (`priceDate` ISO-8601) — tarif actif à cet instant.
3. **Prix du jour** — tarif en vigueur via `DateTimeProvider.now()`.

En cas de conflit (plusieurs prix valides), le champ `priority` détermine le gagnant (valeur la plus haute).

---

## API REST — Valorisation

### Soumettre un panier

```
POST /valuation
Content-Type: application/json
```

**Corps de la requête (`Basket`) :**

```json
{
  "customerCode": "CUST_12345",
  "storeCode": "STORE_PARIS_01",
  "createdAt": "2023-10-27T14:30:00",
  "deliveryMode": "HOME_DELIVERY",
  "deliveryAddress": {
    "streetLine1": "10 Avenue des Champs-Élysées",
    "postalCode": "75008",
    "city": "Paris",
    "country": "France",
    "latitude": 48.8698,
    "longitude": 2.3078
  },
  "instructions": ["Deposit basket"],
  "vignettes": {
    "EAN_CAFE": 5,
    "EAN_THE": 2
  },
  "items": [
    { "lineId": 1, "produceEan": "EAN_BURGER", "quantity": 2.0 },
    { "lineId": 2, "produceEan": "EAN_SODA",   "quantity": 2.0 }
  ]
}
```

**Réponse (`BasketEvaluation`) :**

```json
{
  "offers": [
    {
      "type": "MixedBundle: MENU_BEST x1 for 10.00€",
      "amount": { "amountExcludingTax": 9.09, "amountIncludingTax": 10.00, "vatRate": 0.100 },
      "items": [
        { "produceEan": "EAN_BURGER", "quantity": 1.0, "lineId": 1 },
        { "produceEan": "EAN_SODA",   "quantity": 1.0, "lineId": 2 }
      ]
    },
    {
      "type": "Standard: EAN=EAN_BURGER, Qty=1.0",
      "amount": { "amountExcludingTax": 5.00, "amountIncludingTax": 5.50, "vatRate": 0.100 },
      "items": [{ "produceEan": "EAN_BURGER", "quantity": 1.0, "lineId": 1 }]
    }
  ],
  "advantages": [
    {
      "type": "Immediate Voucher Discount : PROMO_1EUR",
      "discountAmount": { "amountExcludingTax": -0.91, "amountIncludingTax": -1.00, "vatRate": 0.100 }
    }
  ],
  "totalPrice": { "amountExcludingTax": 17.35, "amountIncludingTax": 19.50, "vatRate": 0.000 }
}
```

**Erreur — `422 Unprocessable Entity`** : retournée si un ou plusieurs articles du panier n'ont pu être couverts par aucune offre (EAN inconnu, prix manquant).

---

## API GraphQL — Administration

Endpoint unique : `/graphql`

### Sécurité (RBAC)

| Rôle | Droits |
|------|--------|
| `MANAGER` | Lecture (`@Query`) |
| `ADMIN` | Lecture + Écriture (`@Mutation`) |

### Ressources disponibles

| Ressource | Queries notables | Mutations |
|-----------|-----------------|-----------|
| **Product** | `allProducts`, `productByEan(ean)` | `createProduct`, `updateProduct`, `deleteProduct` |
| **Store** | `allStores`, `storeByCode(code)` | `createStore`, `updateStore`, `deleteStore` |
| **Price** | `allPrices`, `currentPrice(productId, storeId)` | `createPrice`, `updatePrice`, `deletePrice` |
| **Offer** | `allOffers`, `offerByCode(code)`, `offersByStoresAndType(storeCodes, type)` | `createOffer`, `updateOffer`, `deleteOffer` |
| **StoreGroup** | `allStoreGroups`, `storeGroupByCode(code)` | `createStoreGroup`, `updateStoreGroup`, `deleteStoreGroup` |
| **ProductFamily** | `allProductFamilies`, `productFamilyByCode(code)` | `createProductFamily`, `updateProductFamily`, `deleteProductFamily` |
| **ProductCategoryStorage** | `allProductCategoryStorages` | `createProductCategoryStorage`, `updateProductCategoryStorage`, `deleteProductCategoryStorage` |

Les mutations utilisent des **codes métier** (EAN, code magasin) plutôt que des IDs techniques.

### Exemple — Créer un produit

```graphql
mutation {
  createProduct(input: {
    ean: "3017620422003",
    name: "Nutella",
    productType: "UNIT",
    referenceWeight: 0.400,
    active: true
  }) {
    id
    ean
    active
  }
}
```

### Exemple — Créer une offre N+M

```graphql
mutation {
  createOffer(input: {
    code: "PROMO_2+1_PARIS",
    type: "N+M",
    specification: "{\"targetEans\":[\"EAN1\"],\"quantityToPay\":2,\"discountedQuantity\":1,\"selectionStrategy\":\"CHEAPEST\",\"discountType\":\"PERCENTAGE\",\"discountValue\":100.0}",
    storeCodes: ["STORE_PARIS_01"]
  }) {
    id
    code
  }
}
```

### Gestion des erreurs GraphQL

| Exception Java | Comportement |
|----------------|-------------|
| `AlreadyExistsException` | Erreur GraphQL avec message clair (ex: "EAN already exists") |
| `NoSuchElementException` | Erreur "ressource introuvable" |
| `PersistenceException` | Erreur générique "Database error" (sans fuite SQL) |

---

## Offres et Avantages implémentés

### Offres (calcul du prix de base)

#### N+M — Achat groupé avec remise sélective

Promotion "achetez N, remise sur M". Exemple : 2+1 gratuit.

```json
{
  "targetEans": ["EAN1", "EAN2"],
  "quantityToPay": 2,
  "discountedQuantity": 1,
  "selectionStrategy": "CHEAPEST",
  "discountType": "PERCENTAGE",
  "discountValue": 100.0
}
```

`selectionStrategy` : `CHEAPEST` (les moins chers sont remisés) ou `MOST_EXPENSIVE`.
`discountType` : `PERCENTAGE` ou `FIXED_AMOUNT`.

#### Mixed Bundle — Lot forfaitaire à composition variable

Prix fixe pour un ensemble de produits avec possibilité de substitution.

```json
{
  "bundlePrice": 10.00,
  "vatRate": 0.055,
  "contents": [
    { "ean": "EAN_BURGER", "quantity": 1.0, "substituteEans": [] },
    { "ean": "EAN_COKE",   "quantity": 1.0, "substituteEans": ["EAN_WATER", "EAN_JUICE"] }
  ]
}
```

#### Delivery — Frais de livraison géo-conditionnés

Calcule la distance entre le magasin et l'adresse de livraison (formule Haversine) et applique la tranche tarifaire correspondante. Nécessite `deliveryMode: "HOME_DELIVERY"` et des coordonnées GPS valides pour le magasin et le client. Une seule offre DELIVERY autorisée par magasin.

```json
{
  "vatRate": 0.20,
  "tiers": [
    { "maxDistance": 10.0, "price": 5.00 },
    { "maxDistance": 20.0, "price": 8.00 }
  ]
}
```

#### Basic — Prix standard (offre de repli)

Aucune configuration JSON. Couvre automatiquement tous les articles non traités par une autre offre au prix catalogue.

#### Deposit Basket — Paniers consignés

Facture des paniers de transport si l'instruction `"Deposit basket"` est présente. Le nombre de paniers est calculé à partir du volume total des articles.

```json
{
  "basketVolume": 50.0,
  "basketPrice": 2.00,
  "vatRate": 0.20
}
```

---

### Avantages (modificateurs post-offres)

#### Immediate Voucher — Remise immédiate ciblée

Applique une remise sur une offre et un produit spécifiques, après le calcul des offres.

```json
{
  "targetOfferClass": "BasicOfferApplier",
  "targetEans": ["EAN_CAFE"],
  "discountType": "FIXED_AMOUNT",
  "value": 1.00
}
```

`targetOfferClass` supporte la correspondance partielle (ex: `"NPlusMOffer"` matche `NPlusMApplication`).

#### Vignette Discount — Remise par vignettes

Échange des vignettes (fournies dans `basket.vignettes`) contre des remises sur des produits éligibles.

```json
{
  "catalog": [
    {
      "ean": "EAN_CAFE",
      "vignettesRequired": 5,
      "discount": { "type": "FIXED_AMOUNT", "value": 1.00 }
    }
  ]
}
```

#### Free Delivery Threshold — Remise livraison conditionnelle

Réduit ou annule les frais de livraison si le montant marchandise dépasse un seuil.

```json
{
  "tiers": [
    { "threshold": 30.00, "value": 50,   "type": "PERCENTAGE"   },
    { "threshold": 50.00, "value": 5.00, "type": "FIXED_AMOUNT" }
  ]
}
```

#### Meal Voucher — Plafond titre-restaurant

Calcule le montant du panier payable en titres-restaurant (non une remise, mais une information transmise au système de paiement).

```json
{
  "flag": "FOOD",
  "threshold": 19.00
}
```

Seuls les produits dont la `ProductFamily` porte le flag défini sont éligibles.

#### Mixed Bundle Upsell / N+M Upsell — Suggestions d'achat

Détectent les opportunités manquées (ex : 3 Burgers mais 0 Boisson alors qu'un menu existe) et retournent une `UpsellSuggestion` (EAN + quantité à ajouter) pour affichage en caisse ou application mobile. Ne génèrent aucune remise.

---

## Modèle de données

```
Product        — EAN unique, type (UNIT/WEIGHT/VOLUME), poids/volume de référence
Store          — Code unique, adresse avec coordonnées GPS
Price          — Lien Product × Store, validité temporelle, priorité, usage (DEFAULT | BASE_FOR_DISCOUNT)
Offer          — Type + specification JSON, ciblage via Store / StoreGroup / EANs extraits
StoreGroup     — Hiérarchie DAG de magasins (code, parent_group_id)
ProductFamily  — Hiérarchie DAG de produits, flags d'éligibilité (ex: "FOOD")
ProductCategoryStorage — 5 niveaux de catégories dénormalisés par produit
```

Toutes les entités héritent de `BaseEntity` : `id`, `version` (verrouillage optimiste), `created_at`, `updated_at`, `checksum`.

---

## Import CSV en masse

Endpoint : `POST /{ressource}/import` — rôle `ADMIN` requis.

L'algorithme **Staged Fallback** traite les données par lots de 1000, puis 100, 10, 1 en cas d'échec, pour isoler les lignes corrompues sans bloquer les données valides.

**Réponse type :**
```json
{
  "createdCount": 1520,
  "updatedCount": 450,
  "errors": ["Line 503 (EAN_X): Store code 'UNKNOWN' not found."]
}
```

### Formats CSV

**`/products/import`**
```
ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active
3017620422003|Nutella|Pâte à tartiner|Ferrero|0.400||UNIT|pot|true
```

**`/stores/import`**
```
code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude
STORE_PAR_01|Paris Centre|10 Rue de Rivoli||75001|Paris|France|48.8566|2.3522
```

**`/prices/import`**
```
ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime
3017620422003|STORE_PAR_01|3.50|4.20|0.200|DEFAULT|0|2023-01-01T00:00:00|2023-12-31T23:59:59
```

**`/offers/import`**
```
code|type|specification|storeCodes|storeGroupCodes
PROMO_NOEL|N+M|{"quantityToPay":2,"discountedQuantity":1,...}|STORE_PAR_01,STORE_PAR_02|
```

**`/store-groups/import`**
```
code|name|storeCodes|storeGroupCodes
REGION_NORD|Region Nord||PARIS_HUB;NORD_FLAGSHIP
```

**`/product-category-storages/import`**
```
ean|level1|level2|level3|level4|level5
3017620422003|Alimentaire|Petit Déjeuner|Pâtes à tartiner|Chocolat|Bio
```

---

## Étendre le service

### Ajouter un nouveau type d'offre

1. **Définir le JSON Schema** — constante `OFFER_SCHEMA` dans la factory.
2. **Créer la Factory** — `@ApplicationScoped`, implements `OfferApplierFactory + EngineTrait`. La méthode `buildAppliers()` récupère les offres via `getOffers(evaluation, "MON_TYPE")`, valide le JSON via `processSpecification()`, et instancie les Appliers.
3. **Créer l'Applier** — extends `OfferApplier`. La méthode `apply()` consomme les articles via `evaluation.pick()` et retourne des Applications.
4. **Créer l'Application** — implements `OfferApplication`. La méthode `getAmount()` retourne un `AmountEvaluation`.

Le moteur découvre automatiquement la nouvelle Factory via l'injection CDI `Instance<OfferApplierFactory>`.

### Ajouter un nouveau type d'avantage

Même pattern, en remplaçant :
- `OfferApplierFactory` → `AdvantageApplierFactory`
- `OfferApplier` → `AdvantageApplier`
- `OfferApplication` → `AdvantageApplication` (ou `DiscountApplication` si remise monétaire)

Pour une remise, `getDiscountAmount()` doit retourner un `AmountEvaluation` avec des montants **négatifs**.
