# Passe qualité — imvaluation (src/main)

**Date** : 21/09/2026 · **Périmètre** : 101 fichiers Java + templates Qute + application.properties · **Méthode** : 4 revues croisées (moteur, sécurité, imports/persistance, conventions), constats majeurs contre-vérifiés ligne à ligne. Chaque constat cite fichier:ligne ; sévérité CRITIQUE / HAUTE / MOYENNE / conventions.

**⚖ = décision d'arbitrage requise avant correction** (le correctif dépend d'un choix métier, pas d'une évidence technique).

---

## 1. CRITIQUE

### C1 ⚖ — La bascule BASE_FOR_DISCOUNT se déclenche à l'*enregistrement* d'un avantage, pas à son *application* : surfacturation sans remise
`engine/offers/BasicOfferFactory.java:117` — `price = this.getDiscountAppliers().isEmpty() ? this.defaultPrice : this.refPrice;`
L'enregistrement se fait sur `isApplicable` seul ; or depuis C1/C2, l'arbitrage peut **écarter** l'avantage ensuite (trigger non satisfait, non-cumul, groupe consommé). La ligne est alors valorisée au prix de référence (barré) **sans la remise qui le justifiait**. Scénario : DEFAULT 10,00 / BASE 12,00, remise gâtée par un coupon non présenté → le client paie 12,00. **Régression introduite par l'arbitrage C2** — l'invariant implicite « enregistré ⇒ appliqué » est mort. Arbitrage nécessaire : la bascule doit-elle suivre l'application *réelle* (recalcul des lignes après arbitrage) ?

### C2 — Le résultat n'est pas déterministe : `offers`/`advantages` sont des HashSet à hash d'identité
`engine/BasketEvaluation.java:127-128`, propagé via `getAvailableOffers()`.
Deux POST identiques peuvent différer : (a) résidus « sur le dernier élément » (TicketDiscount:374, VatRefund:420, Tiered:963) → la ligne qui porte le centime change, et le `vatBreakdown` peut bouger d'un centime ; (b) `capApplications` (ValuationEngine:387-389) garde les N premières applications *dans l'ordre du HashSet* → **avec un plafond par ticket, le total lui-même est aléatoire** ; (c) l'élection GiftItem départage les ex æquo par cet ordre ; (d) le tri des appliers d'offres (ValuationEngine:621) n'a pas de départage stable et son entrée vient de `new HashSet<>(offers)` avec `Objects.hash(getClass(), id)` → **deux nœuds d'un load balancer peuvent valoriser différemment le même panier**. Viole frontalement « même panier ⇒ même résultat ». Correctif mécanique : collections ordonnées (LinkedHashSet/List) + tie-breaks stables partout (code de configuration, EAN).

### C3 — XSS stockée : `{requestJson.raw}` dans l'écran de replay — un VIEWER peut détourner une session ADMIN
`templates/ValuationUiResource/test.html:57` + `ValuationUiResource.java:298-308`. Toute requête `/valuation` (accessible à tout rôle, trace écrite même sur 400) peut contenir `</script><script>…` dans une valeur JSON — Jackson n'échappe pas `<` ni `/`. L'ADMIN qui clique « Replay » exécute le script (ex. : POST `/ui/users/new` → compte ADMIN attaquant). Même racine dans `workbench.html:45` (`{modelJson.raw}`, noms de magasins du feed), `form.html:116` (`{offer.specification.raw}` — le chemin GraphQL n'a **aucune** validation de spec), et variantes `onsubmit="confirm('… {offer.code} …')"` (list.html:106, users list.html:87) où l'échappement HTML ne protège pas le contexte JS. Correctif : sérialisation avec échappement `<`/`/` pour tout `.raw` dans `<script>`, et plus aucune valeur interpolée dans un handler JS inline.

### C4 — Imports : le fallback étagé rejoue des entités détachées — mises à jour perdues en silence, compteurs mensongers
`ImporterCsvResource.java:437` (`em.clear()` en finally) + `:196` (retry avec le même `preFetchedMap`). Six importeurs se ré-attachent (`findById`) ; **deux non** : `ProductFamilyCsvResource` (mutation directe de l'instance détachée, `counters[1]++` sans écriture) et `StoreGroupCsvResource` (ré-attache seulement si le checksum — qui ignore les membres ! — a changé). Scénario : 1000 lignes, une ligne poison au milieu → les groupes des lignes rejouées perdent leurs rattachements, HTTP 200, compteurs plausibles.

---

## 2. HAUTE

### H1 ⚖ — Configurations pathologiques : deux comportements opposés, tous deux mauvais
(a) `ValuationEngine.java:167-170` (idem 218, 331, 539) : le commentaire dit « log et continue », le code **rethrow** → une spec invalide en base = 500 pour *tous* les paniers du magasin. (b) `ValuationEngine.java:453-456` : un bloc trigger qui ne parse plus retombe sur `Trigger.ALWAYS` → un « 5 € dès 50 € » corrompu donne 5 € à tout le monde (fail-open sur de l'argent). Arbitrage : doctrine unique à choisir — je recommande **fail-closed par configuration** (l'offre fautive est écartée + tracée, l'évaluation continue).

### H2 ⚖ — Famille « assiettes brutes » : plafonds et TVA mesurés sans déduire les remises déjà retenues
(a) `TicketDiscountFactory.java:315-318` : plafond au brut → 100 − 30 − 100 = **total négatif** (même motif VatRefund:346, Tiered:696). (b) `VatRefundDiscountFactory.java:338-341` : la « TVA remboursée » est celle du brut, pas des montants courants — double avantage (contredit sa propre Javadoc et la spec C3 §4). (c) `NewPriceDiscountFactory.java:346` : `netFactor` de l'application entière appliqué à la tranche d'un seul produit → base fausse sur les bundles multi-produits. La brique de netting existe (`MinimumAmountCondition.netFactor`) — à généraliser. Arbitrage mineur : confirmer que « montants courants » s'applique aussi aux plafonds.

### H3 — Reset de mot de passe : lien forgeable par l'en-tête Host
`AuthUiResource.java:183-186` + `PasswordResetService.java:85` : `uriInfo.getBaseUri()` vient du Host de la requête ; `/ui/forgot` est `@PermitAll`. Un attaquant demande un reset pour la victime avec `Host: attaquant.tld` → le mail légitime porte un lien vers l'attaquant avec le vrai token. (La mécanique token elle-même est bonne : SecureRandom 32o, SHA-256, TTL 30 min, usage unique.) Correctif : URL de base en configuration, jamais depuis la requête ; + rate-limit sur /ui/forgot.

### H4 — Pas de CSRF sur toute la surface d'écriture UI, et le mode Basic contourne le filtre « changement de mot de passe obligatoire »
Aucun token sur les POST `/ui/*` (offers, users, imports, purge, config…) ; seule défense : le SameSite implicite du cookie — et **Basic est actif globalement**, un navigateur qui a mémorisé des identifiants Basic les joint aux posts cross-site. Par ailleurs `PasswordChangeFilter.java:116-123` exempte tout appel Basic/non-HTML : un compte `mustChangePassword` (admin bootstrap au mot de passe connu) garde **tous ses pouvoirs** sur GraphQL et les imports. Correctifs : quarkus-rest-csrf + SameSite explicite ; étendre le filtre aux appels API.

### H5 — Un EAN de vignette inconnu = NPE = 500 sur entrée utilisateur
`VignetteDiscountFactory.java:294` : `Collectors.toMap(k -> k, Product::findByEan)` — valeur null interdite. Un panier avec `"vignettes": {"0000000000000": 3}` crashe l'évaluation, là où partout ailleurs un produit inconnu est ignoré. Correctif une ligne (filtrer les null).

### H6 — Prix : pas de contrainte unique sur la clé naturelle ; dates malformées avalées
(a) `Price` n'a aucun `@UniqueConstraint` (contrairement à Store/Product/VatRate) et `processPriceLogic` n'ajoute pas les créations à la map → un doublon dans un fichier crée **deux lignes de prix identiques**, `findActivePriceAtDate` en choisit une arbitrairement, pour toujours. (b) `safeParseDateTime` (ImporterCsvResource:528) transforme `2025-06-01 00:00:00` (espace au lieu de T) en **null** → clé différente → *nouveau* prix « valable depuis toujours », zéro erreur ; même piège sur `VALID_FROM`/`VALID_TO` d'une offre (fenêtre silencieusement ouverte). Correctifs : contrainte unique + rejet des dates non parsables (le null n'est légal que si la colonne est absente/vide).

### H7 — Les réponses JSON des imports n'échappent rien
`ImporterCsvResource.java:260-271` + messages embarquant la ligne brute (:122) et `rowEx.getMessage()` (:221). Un guillemet dans une cellule CSV ou dans un message H2 (« Value too long for column "NAME…" ») rend le rapport d'erreurs **illisible précisément quand on en a besoin**, et permet d'injecter de fausses clés dans la réponse. Correctif : échapper `"` `\` et les contrôles (ou construire via Jackson).

---

## 3. MOYENNE

- **StoreGroup add-only** (`StoreGroupCsvResource:243-276`) : les retraits du feed ne sont jamais appliqués, et le checksum (code+nom) ignore les membres — sémantique divergente d'Offer/ProductFamily (replace), nulle part documentée. ⚖ accumulate ou replace : à trancher, puis uniformiser.
- **`safeParseBoolean`** : tout token non reconnu (`1`, `Y`, `OUI`) devient `false` → un feed qui change de convention **désactive tout le catalogue** sans une erreur, et le checksum applique consciencieusement la désactivation. Rejeter les tokens inconnus.
- **En-têtes CSV** : BOM UTF-8 non retiré (premier header illisible → fichier rejeté avec message trompeur) ; garde de troncature comparée au *nombre de noms distincts* et non aux positions (header `A|B|B|C` + ligne courte = lecture silencieusement décalée) ; quoting non supporté et non documenté (un `|` dans une valeur décale tout).
- **`Offer.onUpdate`** reconstruit `eans` (ElementCollection) dans `@PreUpdate` — non garanti par JPA en pleine flush : index `offer_eans` potentiellement obsolète → `findInForceByEans…` rate des offres après mise à jour par import ; et l'ordre issu d'un HashSet rend la collection « sale » à chaque update.
- **DataInitializer** : garde `Store.count()>0` → un seed partiel (vat-rates.csv en échec, stores OK) devient **permanent** en live-reload ; les échecs par fichier sont logués puis ignorés.
- **Amplification d'erreurs** : liste d'erreurs non bornée embarquant les lignes brutes (réponse potentiellement en centaines de Mo) + ~4× le travail et ~1111 transactions par chunk de 1000 entièrement empoisonné ; N+1 sur les maps de pré-fetch (proxies LAZY déréférencés par ligne).
- **Scores NaN/Infinity** : `OfferApplier.computeEfficiencyScore` divise par un total potentiellement nul en `double` ; NaN trie *premier* dans le comparateur inversé.
- **Export CSV** : pas de neutralisation des formules (`=`, `+`, `-`, `@`) — payload exécutable dans Excel via un code d'offre venu du feed.
- **Fuites de messages internes** : `entity("Error reading file: " + e.getMessage())`, messages Jackson bruts dans l'UI de test.
- **Secrets dev en repo** : `%dev` admin/admin non forcé à rotation, clés de session dev/test connues — `%prod` est propre (variables exigées), mais un démarrage hors profil prod tourne avec des clés publiques.
- **Checksums, dérives résiduelles** : adresse blanche vs absente (Store), sensibilité d'échelle BigDecimal (une réécriture parasite au premier import), doublons de codes non dédupliqués côté incoming (Offer → « update » perpétuel).

---

## 4. Conventions (état : bon — 97,8 % de Javadoc)

- **Javadoc manquante : 20 méthodes** (sur 914) — ProductFamilyResource ×4, StoreGroupResource ×4, Adresse ×3, Basket ×2, AlreadyExistsException ×2, OfferUiResource.applyForm, DeliveryOfferFactory.apply, OfferGraphQLClient.main, ProductRecord(), DateTimeProvider().
- **Logs interdits ×5** : `DeliveryOfferFactory:286` (System.err, classe sans LOGGER) + 4 dans `OfferGraphQLClient`.
- **`OfferGraphQLClient.java` : fichier mort** sous src/main, jamais référencé, identifiants `admin/admin` en dur — à supprimer.
- **Collections null ×2** : `NPlusMOfferFactory.takeSourceLines:476`, `DeliveryOfferFactory.getItems:368`.
- **Horloge directe ×6** : `ValuationTraceService:87` (`LocalDateTime.now()` décide des **purges** — intestable) + 5 `System.currentTimeMillis` de chronométrage (tolérables, à statuer).
- **Français** : classe `Adresse` (5 fichiers), bloc de commentaires FR dans `AmountEvaluation:204-232`, une phrase FR dans NewPriceDiscountFactory:345. (« assiette », `priceTTC`, libellés « Taux normal » : vocabulaire métier, à assumer ou bannir — une ligne de doctrine à écrire.)
- **⚖ Quantités en `double`** dans tout le moteur (Basket.quantity, pick/consume, ProductAware.getProductQuantity…) avec epsilons `1e-9` et aller-retours BigDecimal↔double — un constat *architectural* unique, chantier à part entière si tranché.
- **Duplication ×3 familles** : bloc `applicationMoment` copié dans 14 factories (~350 lignes) ; gabarit CRUD GraphQL ×7 ; checksums entité/importeur recopiés ×8 (chaque évolution d'entité doit être miroir à la main — c'est ce qui a produit les dérives du §3).

---

## 5. Vérifié sain (à dire aussi)

Hygiène BigDecimal argent (compareTo partout, divisions gardées et arrondies) ; bornes des triggers et TierTable (seuils inclusifs, axe anti-gaspi inversé correct) ; cache des triggers et restauration sandbox des vignettes ; filtrage des porteurs consommés dans toutes les assiettes ; `perProduct` ; injection JPQL (tris whitelisted, paramètres liés partout) ; couverture des rôles GraphQL (20 queries MANAGER, 21 mutations ADMIN, zéro @PermitAll mutant) ; comptabilité des compteurs et des erreurs dans le fallback étagé ; verrou optimiste réellement en jeu ; mécanique des tokens de reset (hors Host).

---

## 6. Plan proposé

1. **Arbitrages (⚖, 30 minutes de discussion)** : C1 bascule-à-l'application · H1 doctrine fail-closed · H2 assiettes nettes pour plafonds/TVA · StoreGroup replace vs accumulate · quantités double→BigDecimal (go/no-go chantier) · vocabulaire FR métier.
2. **Lot « qualité » Claude Code** (tout le mécanique : C2 déterminisme, C3 XSS, C4 ré-attachement, H3-H7, §3, conventions §4) — un prompt, critère : suite verte + nouveaux tests de non-régression (dont un test de déterminisme : 50 évaluations identiques ⇒ 50 réponses identiques octet à octet).
3. **Recette** : re-passe ciblée sur les corrections + mise à jour du rapport de couverture.
