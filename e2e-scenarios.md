# Catalogue des scénarios e2e — imvaluation (moteur de valorisation)

Couche complémentaire des tests unitaires et du `ValuationEndToEndTest` existant : ici
l'application réelle démarre sur **:8090**, la base H2 **en mémoire** vit
(`drop-and-create` : tout est perdu au redémarrage), le référentiel est seedé par les
**7 imports CSV** (jeu miroir d'`ImportAllClient` : magasins `0101`–`0105`, groupes
`REGION_NORTH`/`DEPT_59`/…, produits `3300000000001`–`033`, 9 familles, ~96 prix
valides à partir de `2026-01-12T00:00:00`, 11 offres). Compte d'amorçage `admin`/`admin`
(profils dev/test).

Infrastructure par groupe : **[D]** = profil dev/test (défaut : `admin`/`admin`,
`app.password-change.enforced=false`) ; **[P]** = profil prod-like
(`VALUATION_SESSION_KEY` + `VALUATION_ADMIN_PASSWORD` exigées, changement de mot de
passe forcé actif) ; **[W]** = navigateur réel requis (JS : autocomplete, workbench,
auto-refresh, bascule Form/JSON) ; sans marque = pur HTTP (RestAssured, Basic).

Convention : chaque scénario = parcours → attendus vérifiables (code HTTP, JSON, base,
log, écran). Les libellés cités sont les **textes littéraux** du code. Deux garde-fous
transverses : `offers` et `advantages` sont des `HashSet` → **ne jamais assertionner
sur un index**, toujours par `find{…}` ; les corps des réponses 4xx/5xx de `/valuation`
(`WebApplicationException` sans entité) sont **à calibrer une fois** sur l'environnement
cible avant toute assertion textuelle — le message est en revanche garanti dans
`valuation_traces.error_message`.

## A. Démarrage & amorçage [D]

- **A1 Boot sur base vierge** : démarrage → schéma recréé (`drop-and-create`),
  `import.sql` inerte (100 % commenté), aucune donnée métier ; log WARN
  `No user found: created bootstrap administrator 'admin'. Change its password before
  exposing this instance.` ; en base : `admin`, rôles `VIEWER,MANAGER,ADMIN` (ordre
  canonique), `displayName = Bootstrap administrator`, `active = true`,
  `mustChangePassword = true`.
- **A2 Bootstrap conditionnel** : créer un compte quelconque (même non-admin), purger
  `admin`, redémarrer... impossible — la base est en mémoire : tout redémarrage repart
  de zéro et **re-crée** l'admin. À la place : vérifier en une seule vie de JVM que
  `AppUser.count() > 0` bloque toute re-création (le bootstrap ne se déclenche que sur
  table totalement vide).
- **A3 Démarrage prod incomplet** [P] : sans `VALUATION_ADMIN_PASSWORD` → échec de
  démarrage ; sans `VALUATION_SESSION_KEY` → échec de démarrage. Aucun repli silencieux.
- **A4 Amnésie assumée** : seed complet → redémarrage → catalogue vide, l'écran offres
  l'affiche : `The in-memory database is reset at every restart — run the CSV imports
  to load a catalog.` Re-seed obligatoire.
- **A5 Seed miroir & idempotence** : rejouer `ImportAllClient` dans l'ordre imposé
  (Stores → StoreGroups → Products → ProductFamilies → Categories → Prices → Offers) →
  chaque import répond 200 `{"createdCount":N, "updatedCount":0}` ; rejouer le MÊME
  seed immédiatement → `createdCount:0, updatedCount:0` partout (idempotence par
  checksum). ⚠ exception connue : magasin sans adresse → update « inutile » possible
  (checksums d'adresse asymétriques), à figer.

## B. Authentification & session

- **B1 Chaîne de redirections nominale** : `GET /` → 303 `/ui/offers` → (anonyme) 302
  `/ui/login` → `POST /j_security_check` (`j_username`/`j_password`) → 302 `/ui/offers`
  (landing-page). Cookie `quarkus-credential` posé.
- **B2 Login refusé** : mauvais mot de passe → redirection `/ui/login?error=true` →
  `Invalid username or password.` ; le message ne s'affiche que pour la valeur **exacte**
  `error=true` émise par l'échec d'authentification ; toute autre valeur (`?error=`
  vide, `?error=false`, casse différente, paramètre absent) n'affiche rien.
- **B3 Logout** : `POST /ui/logout` → cookie `quarkus-credential` vidé (maxAge 0,
  httpOnly, path /), 303 `/ui/login?notice=You+have+been+signed+out.` → bandeau vert
  `You have been signed out.` Aucun `GET /ui/logout` n'existe (405/404 à figer).
- **B4 Chemins publics exacts** : seuls `/ui/login`, `/ui/base.css`, `/ui/auth.css`
  sont `permit`. Sans session, `/ui/offer.css`, `/ui/valuation.css` et tous les `*.js`
  sont refusés → la page de login ne charge QUE base+auth (et reste correcte).
- **B5 Basic vs formulaire** : `/valuation`, `/graphql` et les 7 `/*/import` sont
  forcés en Basic → sans `Authorization` : **401 challenge**, jamais de redirection
  HTML ; une requête UI portant `Authorization: Basic` est traitée en Basic
  (priorité 2000 > form 1000).
- **B6 Compte désactivé peut se connecter** : passer un compte `Disabled` dans l'UI →
  le login **réussit quand même** (`active` n'est pas lu par quarkus-security-jpa).
  Test NÉGATIF documentant le symptôme — le drapeau ne sert qu'à l'affichage et aux
  gardes « dernier admin ».
- **B7 Changement forcé de mot de passe** [P] : login `admin` initial → toute
  navigation HTML est redirigée **303 `/ui/password`** (mode forcé : pas de champ
  `Current password`, sous-titre `This password was set by someone else. Pick your own
  before continuing.`) ; chemins exemptés : `/ui/password`, `/ui/logout`, `/ui/login`,
  `/j_security_check` ; les appels API en Basic **passent** (le filtre ignore tout ce
  qui n'est pas une navigation `Accept: text/html` sans Basic).
- **B8 Les cinq refus du changement de mot de passe** (ordre exact) : courant faux →
  `The current password is incorrect.` (jamais vérifié en mode forcé) ; vide →
  `The password is mandatory.` ; < 8 → `The password must be at least 8 characters
  long.` ; confirmation ≠ → `The two passwords do not match.` ; identique à l'actuel →
  `The new password must differ from the current one.` Succès → 303
  `/ui/offers?notice=Password+updated.`, `mustChangePassword=false`, **session
  conservée** (pas de re-login).
- **B9 Reset par l'admin, boucle complète** [P] : admin édite un compte avec un nouveau
  mot de passe → `mustChangePassword=true` → au prochain login de l'utilisateur,
  redirection forcée `/ui/password`, choix d'un mot de passe ≥ 8 différent → accès
  rendu.

## C. Comptes & rôles

- **C1 Création de compte** : `POST /ui/users/new` — refus dans l'ordre :
  `The username is mandatory.` → `An account named '{u}' already exists.` →
  politique mot de passe (B8) → `At least one role must be granted.` Échec = **200**
  re-rendu (mot de passe jamais réaffiché) ; succès = 303 avec bandeau vert
  `Account '{u}' created.` et **`mustChangePassword=true` forcé**.
- **C2 Édition** : username immuable (input disabled non soumis, hint `The login name
  cannot be changed.`) ; mot de passe vide = inchangé ; renseigné = reset +
  `mustChangePassword=true` ; succès → `Account '{u}' updated.` ; id inconnu → **404**
  texte `User {id} not found`.
- **C3 Gardes du dernier admin** : retirer ADMIN ou décocher `Account enabled` au
  dernier admin actif → `This is the last administrator: keep the role and the account
  enabled.` ; le supprimer → `The last administrator cannot be deleted.` ; se supprimer
  soi-même (URL directe, le bouton est masqué) → `You cannot delete your own account.` ;
  id inconnu → `Account not found.` Tout en 303 + notice.
- **C4 Rôles NON hiérarchiques** : compte MANAGER seul → queries GraphQL OK, mutations
  **403** ; compte ADMIN seul → mutations OK, **queries refusées** (piège assumé) ;
  imports CSV = ADMIN seul ; `/valuation` = tout authentifié — un **VIEWER** peut
  valoriser.
- **C5 Parcours VIEWER en UI** : liste offres + `Export CSV`, store-groups (lecture),
  valuations + `New test` accessibles ; pas de boutons `New offer`/`Import CSV`/carte
  `Recording` ; `POST /ui/offers/new` en direct → 403 ; `/ui/users` → 403 (et absent de
  la nav).
- **C6 Assainissement des rôles** : soumettre `roles=HACKER` au formulaire → ignoré
  silencieusement (seuls VIEWER/MANAGER/ADMIN passent) ; case `active` décochée ⇒
  compte créé `Disabled` (croiser B6).

## D. Imports CSV — mécanique commune

- **D1 Nominal** : POST du CSV brut (`Content-Type: text/plain`, séparateur `|`,
  ligne 1 = en-tête toujours sautée) → **200**
  `{"createdCount":N, "updatedCount":M}`. Lignes vides ignorées silencieusement (mais
  comptées dans `lineNumber`).
- **D2 Colonnes insuffisantes** : ligne trop courte → collectée dans `errors` :
  `Line N ignored (not enough columns): <ligne>`, HTTP **200 quand même** ; colonnes en
  trop acceptées sans bruit.
- **D3 Le JSON de réponse est malformé** : avec erreurs, le corps est
  `{"createdCount":n, "updatedCount":m, "errors":[msg1","msg2"]}` — **guillemet
  ouvrant du premier message manquant**, aucun échappement. Les assertions doivent être
  textuelles (`contains`), jamais un parsing JSON. À figer comme contrat de fait.
- **D4 Première ligne vide** : fichier commençant par une ligne vide → l'en-tête
  (ligne 2) est traité comme une donnée et échoue en erreur métier. Piège documenté.
- **D5 Repli transactionnel étagé** : fichier de 25 lignes dont 1 fautive au milieu →
  échec du lot, log WARN `Failed to process chunk of size X with step Y. Retrying with
  step Z…`, découpage 1000 → 100 → 10 → 1 → les 24 saines passent, la fautive isolée :
  `Line N (<code>): <message>`. Ni tout-ou-rien global, ni ligne-à-ligne : meilleur
  effort prouvé.
- **D6 Idempotence par checksum** : réimport strictement identique → `updatedCount:0`,
  aucune écriture (vérifier `updated_at` inchangé en base) ; un seul champ modifié →
  `updatedCount:1`.
- **D7 Doublon de clé dans le même fichier** : deux lignes même EAN → la 2ᵉ échoue en
  violation d'unicité, repli, puis devient un **update** au niveau 1 → dernière ligne
  gagnante. Résultat : `createdCount:1, updatedCount:1`.
- **D8 Erreurs de flux** : flux coupé → **500** texte `Error reading file: <msg>` ;
  Throwable inattendu → **500** `Unexcepted error: <msg>` (faute de frappe présente
  dans le code, à asserter telle quelle).
- **D9 Rôle insuffisant** : MANAGER (sans ADMIN) → 403 sur les 7 endpoints d'import.

## E. Imports CSV — spécificités par ressource

- **E1 Stores** (`code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude`,
  7 col. min) : upsert par code, lat/lon optionnelles (`safeParseDouble` silencieux) ;
  `name` vide → violation `Store name is mandatory` remontée en erreur de ligne.
- **E2 Store-groups** (`group_code|group_name|store_codes|store_group_codes`, listes
  séparées par **`;`**) : stratégie **additive** — réimporter avec une liste amputée ne
  retire aucun lien (contrat à figer, opposé aux familles/offres) ; ordre
  parent-avant-enfant requis : sous-groupe inconnu →
  `StoreGroup '<code>' not found. Check CSV order (Parent must be defined before
  Child).` ; magasin inconnu → `Store '<code>' not found.` ; **aucun contrôle
  d'auto-référence ni de cycle** (un groupe peut se contenir — test négatif, croiser
  P4).
- **E3 Products** (9 col.) : ⚠ colonne `active` vide ⇒ **`false`** (pas `true` — piège
  majeur du seed) ; `productType` inconnu → `null` + WARN `Unknown ProductType value:
  X at index 6` puis violation `Product type is mandatory` au commit.
- **E4 Product-families** (`code|description|flags|product_eans|family_codes`, listes
  par `,`) : stratégie de **remplacement** (colonne vide = liens vidés) ; messages :
  `Product EAN '<ean>' not found.`, `SubFamily code '<code>' not found.`,
  `Family '<code>' cannot contain itself.` ; ⚠ **bug figé** : la mise à jour d'une
  famille EXISTANTE est comptée (`updatedCount:1`) mais **non persistée** (entité
  détachée jamais ré-attachée) — le test vérifie l'état réel en base et documente
  l'écart.
- **E5 Categories** (`productEan|level1|…|level5`) : clé fonctionnelle
  `(ean, level1, level5)` ; une modification portant uniquement sur `level2/3/4` est
  bien détectée comme update ; EAN inconnu → `Product with EAN '<ean>' not found.`
- **E6 Prices** (9 col.) : clé composite `ean:store:usage:start:priority` — changer
  `endDateTime` **met à jour** le prix ; changer `priority`, `startDateTime` ou
  `priceUsage` **crée un prix supplémentaire** sans supprimer l'ancien (chevauchement
  silencieux, résolu par priorité — croiser H2) ; date non-ISO → silencieusement
  `null` + WARN (change la clé !) ; `priceUsage` invalide →
  `PriceUsage is mandatory at column 5` ; `priority` vide → violation au commit ;
  EAN inconnu → `Product with EAN <ean> not found.` (sans quotes — asymétrie avec E5).
- **E7 Offers** (`offer_code|offer_type|specification|store_code|store_group_code`) :
  aucune cible → `Line N: Must define at least one store_code or store_group_code.` ;
  cible inconnue → `Store code '<c>' not found.` / `StoreGroup code '<c>' not found.` ;
  spec JSON invalide → `Failed to parse specification for Offer <code>: <msg>` (au
  flush, dans `errors[]`) ; spec > 1000 caractères → violation de longueur ; **type
  inconnu accepté** et stocké mais jamais appliqué par le moteur (test canari : l'offre
  existe, la valorisation l'ignore) ; cibles en **remplacement** (clear + re-link).

## F. GraphQL (`POST /graphql`, Basic)

- **F1 Matrice de sécurité** : toutes les queries = `MANAGER`, toutes les mutations =
  `ADMIN` ; vérifier les deux sens du piège C4 sur au moins `allStores` et
  `createStore`.
- **F2 Conflits de création — messages exacts** : code/EAN/nom déjà pris →
  `Store with code '<c>' already exists.`, `Store with name '<n>' already exists.`
  (le **nom** de magasin est traité comme unique sans contrainte DB),
  `Product with ean '<e>' already exists.`, `Product with name '<n>' already exists.`,
  `StoreGroup with code/name…`, `ProductFamily with code '<c>' already exists.`,
  `ProductFamily with description '<d>' already exists.` (unicité de **description** !),
  `Offer with code '<c>' already exists.`,
  `A price with the same priority, start date, and usage already exists for this
  product and store.`,
  `A storage link for this product and category path already exists.` — le message
  métier transite intact via `GraphQLException`.
- **F3 Not-found — calibrage** : les `NoSuchElementException` (`Store with id X not
  found`, etc.) sont relancées **non enveloppées** → le rendu SmallRye côté client
  (message générique probable) est LA inconnue à constater au premier scénario, puis à
  figer pour tout le groupe.
- **F4 Messages masqués** : offre sans cible → `An error occurred during createOffer.`
  (le message `Offer must be linked to at least one Store OR one StoreGroup.` est
  perdu) ; auto-référence groupe/famille à l'update → `An error occurred during
  updateStoreGroup.` / `…updateProductFamily.` ; `productType` invalide →
  `An error occurred during createProduct.` ; violation FK à la suppression →
  `Database error while performing deleteXxx. Please check your data.`
- **F5 Updates partiels** : champ `null` = inchangé (impossible de remettre à null),
  liste `[]` = **vidage** ; ⚠ `updateOffer(id, {storeCodes: [], storeGroupCodes: []})`
  produit une offre **sans aucune cible** (aucune revalidation — incohérence avec la
  création, test négatif).
- **F6 Clés immuables** : `code` jamais modifié à l'update d'`Offer`, `StoreGroup`,
  `ProductFamily` (l'input est ignoré) — à prouver.
- **F7 Suppressions** : id inexistant → `false` sans exception ; magasin référencé par
  des prix/offres → violation FK → message F4 ; ⚠ **`deleteProductFamily` cascade ALL :
  supprime les PRODUITS liés et les sous-familles** — scénario destructif à prouver sur
  un jeu jetable, c'est le comportement le plus dangereux de l'API.
- **F8 Prix, cas vicieux** : `currentPrice` sans prix actif → **`null`** + WARN, pas
  d'exception ; doublon avec `startDateTime` null **non détecté** (le JPQL `= null` ne
  matche jamais) → deux prix identiques créés ; `updatePrice` d'un prix à start null →
  NPE masquée `An error occurred during updatePrice.` Tests négatifs documentant.
- **F9 Asymétrie createStoreGroup** : sous-groupe inconnu à la création → `null` ajouté
  au Set → erreur générique ; à l'update → message propre
  `StoreGroup with code '<c>' not found.`
- **F10 Cycles indirects acceptés** : construire A→B puis B→A via deux updates → accepté
  (`wouldCreateCycle` jamais appelée) ; vérifier que `findAllStoreGroups` et la
  valorisation par groupe survivent (sets `visited`) — croiser K4.
- **F11 offersByStoresAndType** : un doublon dans `storeCodes` → `One or more Store
  codes provided do not exist.` (le contrôle compare les tailles) — piège à figer.

## G. API `/valuation` — le contrat

- **G1 Authentification** : sans `Authorization` → **401** challenge Basic, pas de
  redirection ; un VIEWER valorise (aucun `@RolesAllowed`) ; `mustChangePassword=true`
  n'empêche pas l'appel [P].
- **G2 Rejets 400 (schéma)** : chacun → 400 + trace `REJECTED` avec le message
  `Error validating offer: <détails networknt>` : `items: []` ; `items` absent ;
  `storeCode` absent ou vide ; `quantity: 0` et négative (`exclusiveMinimum: 0`) ;
  `manualDiscountPercent: 101` ; `deliveryMode: "DRONE"` (hors enum) ;
  `deliveryAddress.latitude: 91` ; `vignettes: {"<ean>": -1}`. Champ inconnu au panier
  → **toléré** (re-sérialisation NON_NULL le perd avant validation).
- **G3 422 items non consommés** : panier dont un item n'est couvert par aucun applier
  → **422**, trace `FAILED` **avec** `responsePayload`, message
  `Valuation failed: Some items could not be processed by any offer.`
- **G4 500 mal alignés avec l'intuition** (chacun → trace `FAILED`,
  `responsePayload=null`) : EAN inconnu → `Error building appliers from factory:
  Configuration Error: Product not found for EAN '9999999999999'` ; **magasin inconnu →
  500** aligné sur le produit inconnu : `error_message` explicite `Configuration Error:
  Store not found for code '<code>'` (plus de NPE), même famille de statut que l'EAN
  inconnu ; `priceDate: "2026-01-12"` (date seule) → `Invalid date format
  '…' for item EAN '…'. Expected ISO-8601 format.` ; prix expiré/absent →
  `Configuration Error: No active price found for Product '…' (ID: …) in Store '…'
  (Checked at date: …)`.
- **G5 Forme de la réponse 200** : exactement `offers[]` (`amount`/`items`/`type`),
  `advantages[]`, `totalPrice` (`vatRate` **toujours `0.0000`**), `vatBreakdown[]`
  (trié par taux croissant), `availableToUpcell{}` ; `Delivery`/`Deposit` → `items`
  vide ; discriminants d'avantage : `discountAmount` ≠ null = vraie remise,
  `suggestion` ≠ null = upsell, `type == 'MEAL_VOUCHER'` = titre-restaurant.
- **G6 Invariants structurels** (sur TOUT panier valorisé du catalogue) : Σ
  `items[].amount` = `amount` de l'offre (2 déc. HALF_UP) ; par ligne de ventilation
  `vatAmount = TTC − HT` ; Σ ventilation TTC = `totalPrice` ; aucune offre sans items
  ET à montant nul ; `vatRate` de chaque item = taux réel du produit, jamais un taux
  moyen.
- **G7 Transport** : `Content-Type: text/plain` → **415** ; JSON syntaxiquement
  invalide → **400** du provider Jackson, **aucune trace** (l'exception précède la
  méthode) — la distinction « 400 avec trace / 400 sans trace » est le contrat.

## H. Résolution des prix & lignes standard

- **H1 Ligne simple** : 1 × lait `…002` sur `0101` → une offre
  `Standard: EAN=3300000000002, Qty=1.0`, total 3.00, ventilation TVA 20 %.
- **H2 Chevauchement & priorité** : deux prix DEFAULT valides simultanément, priorités
  0 et 1 → le **1** gagne (`order by priority DESC`) ; créer une égalité de priorité →
  gagnant indéterminé, test documentaire (l'assertion accepte l'un OU l'autre et le
  journalise).
- **H3 Fenêtre `[start, end)`** : prix borné à `end = T` → valorisation avec
  `priceDate = T` → le prix est **exclu** (borne exclusive) ; produit dont l'unique
  prix a `endDateTime` passée → 500 `No active price found…` (croiser G4). `null` =
  infini des deux côtés.
- **H4 `priceDate` explicite** : `2026-01-11T23:59:59` (avant le seed) → 500 ;
  `2026-01-12T00:00:00` → 200 ; format date-seule → 500 (G4). Levier déterministe :
  `DateTimeProvider.setFixedDateTime` pour les scénarios « aujourd'hui ».
- **H5 Prix porté par la ligne** : `pricePerUnitExclTax` + `pricePerUnitInclTax` +
  `vatRate` **tous trois** fournis → prix transient, aucun accès base, et
  `DEFAULT` ≡ `BASE_FOR_DISCOUNT` (les remises se calculent sur le prix porté) ;
  fournir seulement 1 ou 2 des 3 → **silencieusement ignorés**, retour catalogue
  (test des 3 combinaisons partielles).
- **H6 Fusion de lignes** : deux lignes même EAN même profil → agrégées (une entrée,
  `sourceLines` conservées, restitution ligne à ligne exacte) ; profils différents →
  tranches séparées → **deux** offres `Standard` ; ⚠ `1.0` vs `1.00` dans un champ
  prix → PAS de fusion (`BigDecimal.equals` sensible à l'échelle) — à figer.
- **H7 Produits au poids/volume** : jambon `…008` (WEIGHT, ref 0.100) qty 0.5 →
  0.5/0.100 × prix de référence ; produit WEIGHT sans `referenceWeight` → 500
  `Configuration Error: Product '…' (EAN: …) is typed as WEIGHT but has no valid
  reference weight defined.` ; idem VOLUME/`reference volume` ; ⚠ quirk figé :
  `standardQuantity` divise par `referenceWeight` **même pour un produit VOLUME**
  (impacte consigne et remises fixes — croiser L6).
- **H8 Bascule DEFAULT → BASE_FOR_DISCOUNT** : même panier avec et sans offre
  `IMMEDIATE_VOUCHER` applicable → dès qu'une remise est enregistrée sur l'applier
  standard, la ligne est valorisée au prix `BASE_FOR_DISCOUNT` (+10 % dans le seed) —
  l'écart de total prouve la bascule. C'est LE comportement le plus contre-intuitif du
  moteur : le prix de référence change quand une remise existe.

## I. Gestes manuels (portés par la ligne)

- **I1 Les trois gestes** : `manualForcedPrice: 1.00` sur `…005` → 1.00 TTC (taux
  catalogue conservé) ; `manualDiscountAmount: 0.50` sur `…006` (6.00) → 5.50 ;
  `manualDiscountPercent: 50` sur `…031` (14.40) → 7.20. Types littéraux :
  `Manual Gesture: EAN=… (forced price 1.0)` / `(amount -0.5)` / `(percent -50%)`.
- **I2 Plancher zéro** : remise 20.00 sur un produit à 12.00 → ligne à **0.00** (jamais
  négatif, par unité).
- **I3 Double geste interdit** : `manualForcedPrice` + `manualDiscountPercent` sur la
  même ligne → **500** `Item EAN '…' carries more than one manual gesture (amount,
  percentage, forced price); only one is allowed.` (tester les 3 paires + le triplet).
- **I4 Ultra-priorité & exclusion totale** : ligne gestée (score `Double.MAX_VALUE`)
  consommée avant tout → invisible pour `IMMEDIATE_VOUCHER`, `VIGNETTE`, l'assiette
  titre-restaurant ET le total marchandise du franco ; absente
  d'`availableToUpcell` ; `advantages.findAll{discountAmount != null}` **vide** sur un
  panier 100 % gesté.
- **I5 Geste ciblé multi-lignes** : L1 `…001` ×3 sans geste + L2 `…001` ×2 avec
  `manualDiscountAmount: 0.30` → le geste couvre **exactement** L2 (qty 2.0,
  `pickMatching` sur le profil), L1 reste éligible aux offres — le geste ne déborde
  jamais sur la ligne voisine du même EAN.
- **I6 Geste par unité** : qty 2 + prix forcé 5.00 → 10.00 (le geste se multiplie par
  la quantité, pas un forfait ligne).

## J. N+M & bundles mixtes

- **J1 N+M exact** : 3 × pommes `…001` (offre `PROMO_2FOR1_3300`, 2+1 CHEAPEST,
  PERCENTAGE 100) → une application exposant **2 items** (bloc payé + bloc gratuit),
  tous deux `lineId` de la ligne source ; montant = 2 × prix `BASE_FOR_DISCOUNT` ; ⚠
  type littéral **trompeur** à figer : `Mixed Bundle Promo: PROMO_2FOR1_3300`.
- **J2 Reliquat + suggestion** : 5 pommes → 1 lot N+M + 2 en `Standard`, et un
  avantage `suggestion` `{ean, quantity: 1.0, offerCode: "PROMO_2FOR1_3300"}`, type
  `Upsell N+M: PROMO_2FOR1_3300 (Need 1,00 of …)` — ⚠ `%.2f` **dépendant de la locale
  JVM** (virgule en fr_FR) : figer la locale de l'app ou l'assertion.
- **J3 Stratégies de sélection** : offre multi-EAN, prix différents — `CHEAPEST` : les
  créneaux **remisés** sont remplis d'abord avec les moins chers ; `MOST_EXPENSIVE` :
  l'inverse. Vérifier le montant au centime dans les deux sens.
- **J4 Remise fixe plafonnée** : N+M `FIXED_AMOUNT 50.0` sur un bloc remisé de 12.00 →
  remise plafonnée au bloc, jamais de négatif.
- **J5 Multi-lots hétérogènes — bug d'aliasing** : offre 1+1 CHEAPEST, panier 2
  produits chers + 2 pas chers (4 unités = 2 lots différents) → les applications
  partagent les mêmes listes vidées entre lots → **total faux**. Test NÉGATIF gravant
  le symptôme exact pour le diagnostic (montant observé ≠ somme attendue).
- **J6 Configurations toxiques** : `quantityToPay: 0` — acceptée par l'offre, refusée
  par la factory d'upsell (`minimum: 1`) → **500 systématique sur tout panier du
  magasin** ; `quantityToPay: 0` ET `discountedQuantity: 0` → division par zéro → 500.
  L'offre empoisonne le magasin entier : à graver.
- **J7 Bundle prix fixe & substitut** : café `…004` + biscuits `…013`
  (`PROMO_COFFEE_PACK`, 4.50) → `MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€` ; café +
  chips `…014` (substitut) → **4.50 aussi** ; café seul → aucun applier, panier au
  tarif standard (composant manquant = offre entière inerte).
- **J8 Bundle en mode remise & TVA dérivée** : bundle `discount` PERCENTAGE et
  FIXED_AMOUNT (× nombre de lots), plancher 0 ; composants multi-taux (20 % + 5,5 %) →
  le taux effectif est **dérivé des produits couverts** (`TTC/HT − 1`), le `vatRate`
  déclaré n'est qu'un repli — la ventilation TVA du ticket reste aux taux réels
  (croiser G6). ⚠ MAIS voir J9 avant de seeder.
- **J9 Bundle `discount` = poison global** : une offre `MIXED_BUNDLE` en mode
  `discount` est valide pour l'offre mais **rejetée par le schéma divergent de
  `MixedBundleUpsellAdvantageFactory`** (exige `bundlePrice`, interdit `discount`) →
  `createDiscountAppliers` s'exécutant en premier, **toute valorisation du magasin part
  en 500** `Error building appliers from factory: Error validating offer: …`. Test
  négatif prioritaire : c'est un déni de service par configuration.
- **J10 Capacité de lots** : `maxBundles = min` par composant (troncature) ;
  consommation EAN principal d'abord, puis substituts **dans l'ordre déclaré** — prouver
  avec 5 cafés + 2 biscuits + 1 chips → 2 lots max, chips consommée en dernier recours.
- **J11 Upsell bundle** : panier avec café seul → suggestion
  `Upsell Mixed Bundle: PROMO_COFFEE_PACK (Need …)` — cible d'« abondance » par
  `ceil`/`max` (4 cafés → viser 5 lots ? non : max des ceil par composant), quantité
  suggérée = **cumul inter-composants** pour un seul EAN (le moins cher parmi les
  déficitaires) ; fallback sans prix → premier EAN d'un `HashSet` (non déterministe —
  assertion sur l'appartenance, pas l'égalité).

## K. Remises — bons immédiats & vignettes

- **K1 Cumul de deux bons** : 1 pomme sur `0101` → `PROMO_STORE_101` (15 %) ET
  `BRI_APPLES_DISCOUNT` (0.10) s'appliquent tous deux
  (`Immediate Voucher Discount : <code>` — noter l'**espace avant les deux-points**) ;
  total < somme des offres (régression historique « remise au mauvais signe »).
- **K2 Formules & absence de plafond** : PERCENTAGE = HT × v/100 ; FIXED_AMOUNT =
  v × `standardQuantity` (par unité standard) ; ⚠ **aucun plafonnement** : valeur 150 %
  ou montant > prix → **total de panier négatif possible**. Test négatif gravant le
  montant négatif exact.
- **K3 Ciblage par classe** : `targetOfferClass: ["BasicOffer"]` matche par `contains`
  insensible à la casse (donc `basicoffer` aussi) ; cibler `NPlusMOffer` → la remise
  s'applique aux blocs N+M ; classe ne matchant rien → aucune remise, pas d'erreur.
- **K4 Asymétrie de portée magasin/groupe — LA couture** : la même offre
  `IMMEDIATE_VOUCHER` rattachée au magasin `0101` s'applique ; rattachée au groupe
  `REGION_NORTH` (qui contient `0101`) → **ignorée silencieusement**. Vérifier les
  4 types « magasin seul » (`DELIVERY`, `DEPOSIT_BASKET`, `IMMEDIATE_VOUCHER`,
  `FREE_DELIVERY_THRESHOLD`) ET les 4 types « magasin + groupes » (`N+M`,
  `MIXED_BUNDLE`, `MEAL_VOUCHER`, `VIGNETTE_DISCOUNT`) dans les deux rattachements =
  8 scénarios en un.
- **K5 Vignettes nominal** : poêle `…031` + `vignettes: {"…031": 5}`
  (`VIGNETTE_CUISSON` : 5 vignettes → −50 %) → avantage
  `Vignette Discount: VIGNETTE_CUISSON (5 vignettes used, applied 1 times)` ;
  applications = `min(floor(quantité), vignettes/requis)` : qty 2 + 10 vignettes →
  applied 2 times ; qty 2 + 7 vignettes → 1 seule.
- **K6 Vignette fixe non plafonnée** : `FIXED_AMOUNT` > prix du produit → remise
  intégrale, contribution négative — test négatif (croiser K2).
- **K7 EAN inconnu dans `vignettes`** : `vignettes: {"9999999999999": 3}` → **NPE →
  500**. Test négatif documentant (le `Collectors.toMap` ne tolère pas un produit
  null).
- **K8 `vignettesRequired: 0`** : schéma l'accepte (`minimum: 0`) → division entière
  par zéro dès qu'un produit du catalogue est couvert → 500. Config toxique à graver
  (croiser J6).
- **K9 Vignettes inertes** : map `vignettes` absente/vide → aucun applier ; vignettes
  présentes mais produit absent du panier → aucun avantage, aucun échec.

## L. Livraison, consigne, franco de port

- **L1 Livraison nominale** : `deliveryMode: HOME_DELIVERY` + adresse Seclin
  (50.540/3.030, ~11 km de Lille) sur `DELIVERY_HOME_0101` (paliers 8 km → 5.90,
  16 km → 9.90) → `Delivery: DELIVERY_HOME_0101 (11,xx km) for 9.90€` (Haversine
  R=6371, premier palier `distance ≤ maxDistance`, tri croissant) ; `items: []` ; la
  livraison n'entre ni dans l'assiette TR ni dans le total marchandise du franco.
- **L2 Hors paliers** : adresse à 300 km → **aucune application de livraison**, pas
  d'erreur (message `System.err` : `Delivery distance … exceeds all defined tiers for
  offer …`), le reste du panier est valorisé normalement.
- **L3 Coordonnées manquantes** : `HOME_DELIVERY` sans `deliveryAddress` (ou sans
  lat/lon) → **500** `Delivery address or coordinates missing for basket <customer>` —
  y compris dans un magasin **sans** offre DELIVERY (la garde précède la recherche
  d'offre, à figer) ; magasin sans coordonnées → 500 `Store address or coordinates
  missing for store <code>`.
- **L4 Modes sans livraison** : `PICKUP`, `IN_STORE`, absent → aucune offre de
  livraison, aucune erreur, même avec adresse fournie.
- **L5 Offres multiples interdites** : seconde offre `DELIVERY` sur `0101` → **500**
  `Configuration Error: Multiple DELIVERY offers found for store '0101'. Expected 1,
  found 2.` ; idem `DEPOSIT_BASKET`. Encore un poison de configuration par magasin.
- **L6 Consigne** : instruction `Deposit basket` (trim + insensible à la casse —
  tester `  deposit BASKET  `) → volume = Σ `standardQuantity × referenceVolume` sur
  le panier d'origine (lignes gestées **incluses**), `ceil(volume/basketVolume)`
  paniers → `Deposit Basket: <n> x 0.5€` ; sans instruction → rien ; panier à volume
  nul (poêle seule, `referenceVolume = 0.000`) → **rien malgré l'instruction** ;
  5 pommes (ref 2.500 L) → 2 paniers de 10 L ? à calculer au centime. ⚠ produit
  VOLUME : le quirk H7 (division par `referenceWeight`) s'applique — test documentaire.
- **L7 Franco de port** : `FREE_DELIVERY_THRESHOLD_0101` (10 → 50 % ; 20 → 100 % en
  FIXED_AMOUNT plafonné) : paliers triés **décroissant**, premier
  `total marchandise ≥ threshold` gagne ; la remise est **plafonnée au coût de
  livraison** (jamais de livraison négative) ; total marchandise = offres
  `ProductAware` uniquement (livraison + consigne exclues, remises non déduites) ;
  sous le seuil → rien ; sans livraison au panier → rien ; type littéral
  `Free Delivery Threshold Discount: <code>`.

## M. Titre-restaurant (assiette MEAL_VOUCHER)

- **M1 Assiette nominale** : pommes `…001` ×2 + eau `…007` ×3 + lait `…002` ×1 sur
  `MEAL_VOUCHER_0101` (flag `RESTAURANT_VOUCHER_ELIGIBLE`, threshold 25.00) → avantage
  `type: MEAL_VOUCHER` avec `totalEligibleAmount` > 0 et **< total du panier** (lait
  exclu) ; le flag est résolu en remontant la **hiérarchie de familles**
  (`POMMES` ← `FRUITS` ← `ALIMENTAIRE`) ; `threshold` exposé dans le JSON.
- **M2 Plafond invisible** : assiette éligible > threshold → ⚠ `payableAmount`
  (= min(assiette, threshold)) est calculé mais **absent du JSON** (pas de getter) —
  le client ne voit que l'assiette NON plafonnée. Contrat de fait à graver, décision
  produit en attente.
- **M3 Assiette nette de remises** : produit éligible remisé (`IMMEDIATE_VOUCHER`) →
  les remises rattachées à l'offre sont déduites de l'assiette, plancher 0 ; ⚠ dans
  une offre mixte (éligible + non éligible), la remise du non-éligible est **aussi**
  déduite — test documentaire ; flag sensible à la casse
  (`restaurant_voucher_eligible` ne matche pas).
- **M4 Exclusions** : ligne sous geste manuel → hors assiette (I4) ; livraison et
  consigne → hors assiette ; panier sans produit flaggé → application émise avec
  assiette 0.00 (une par offre MEAL_VOUCHER, toujours émise).

## N. Traces & administration des valorisations

- **N1 Les quatre statuts, contenu exact** : 200 → `SUCCESS` (errorMessage null,
  responsePayload + totalIncludingTax renseignés) ; 400 → `REJECTED` (evaluation
  nulle) ; 422 → `FAILED` **avec** responsePayload ; 500 → `FAILED` sans.
  `requestPayload` = panier **re-sérialisé** (NON_NULL, pas le corps brut — un champ
  inconnu envoyé n'y figure pas) ; `errorMessage` > 2000 → tronqué à 1997 + `...` ;
  `itemCount`, `storeCode`, `customerCode`, `durationMs` posés.
- **N2 Désactivation** : `enabled=false` (case décochée) → valorisations normales,
  **zéro trace** ; bandeau liste : `Recording is turned off: valuations run normally
  but leave no trace.` ; réactivation effective immédiatement (config relue à chaque
  appel, sans redémarrage).
- **N3 Config** : `retentionDays: 0` → notice rouge `The retention must be at least
  one day.` ; succès → `Tracing configuration updated.` ; ADMIN seul (VIEWER/MANAGER →
  403 sur les POST config/purge).
- **N4 Purges** : `Purge all now` (confirm `Delete every recorded valuation ?`) →
  notice `{n} trace(s) deleted.` ; purge automatique : `@Scheduled(every="1h")`, seuil
  `now − retentionDays` **non mockable** (`LocalDateTime.now()` en dur) — stratégie de
  test : antidater `createdAt` des traces via `DateTimeProvider.setFixedDateTime` à
  l'insertion, puis déclencher/attendre le tick ; une exception de purge est avalée
  (log `Valuation trace purge failed`), jamais propagée.
- **N5 Liste** : filtres `store` (contains), `customer` (contains), `status`
  (strict SUCCESS/REJECTED/FAILED), tri défaut `createdAt` desc, 25/page, clamp de
  page ; badges `Success` / `REJECTED` / `FAILED` ; ⚠ [W] auto-refresh : `fetch`
  **toutes les 1 s** remplaçant le `tbody` (suspendu si `document.hidden`, requêtes en
  vol non doublées) — les assertions DOM doivent tolérer le remplacement ; vide →
  `No valuation recorded yet.` + `Submit a test basket, or wait for a client to call
  the endpoint.`
- **N6 Détail** : méta Status/HTTP/Duration/Total ; `errorMessage` en bandeau rouge ;
  cartes Request/Response avec onglets `Readable`/`JSON` (rendu client :
  `Forced {x} €`, `-{x} €`, `-{x}%`, `Add {q} × {ean} for {code}`,
  `Eligible {x} € (threshold {y} €)`) ; sans réponse → `No response was produced.` ;
  id inconnu → 303 liste + notice rouge `Valuation {id} no longer exists.`
- **N7 Formulaire de test & replay** [W] : `POST /ui/valuations/new` (champ
  `request`) — vide → `The basket is empty.` ; erreurs rendues
  `HTTP {status} — {message}` (400 schéma, 422, 500 — tiret cadratin ; message null →
  `The valuation was refused.`) ; succès → carte `Result` en JSON pretty-print ;
  chaque soumission **crée une trace** ; `Replay` précharge `requestPayload` ; replay
  d'un id sans payload → formulaire vide `{}` **sans message** (échec silencieux à
  figer) ; bouton littéral `Value this basket`.

## O. UI d'administration — offres, workbench, lookups

- **O1 Liste des offres** : filtres `q` (contains sur code), `type` (strict, options =
  types à schéma), `target` (EXISTS stores∪groups, contains), `ean` (**préfixe**) ;
  tri `code`/`type`/`eans` (tri par taille + secondaire code) ; 25/page ; `Export CSV`
  ignore la pagination, en-tête littéral
  `offer_code|offer_type|specification|store_code|store_group_code`, sanitize
  (`\r\n` → espace, `|` → `/`) — un export doit se réimporter tel quel (round-trip à
  prouver) ; vide → `No offer matches the current filters.` + le message A4.
- **O2 Formulaire d'offre — validation serveur, ordre exact** : `The offer code is
  mandatory.` → `The offer type is mandatory.` → (création) `An offer with code
  '{code}' already exists.` → `The offer must target at least one store or one store
  group.` → spec vide `The offer specification is mandatory.` / invalide
  `Error validating offer: …` / non parsable `Error parsing offer.` →
  `Unknown store codes: …` → `Unknown store group codes: …`. Échec = **200** re-rendu
  (valeurs préservées), succès = **303** `/ui/offers` sans notice ; code immuable en
  édition (disabled non soumis) ; type sans schéma → spec persistée **sans
  validation** ; id inconnu → **404** `Offer {id} not found` ; delete d'un id inexistant
  → **404** `Offer {id} not found` (aligné sur l'édition), delete d'un id existant → 303.
- **O3 Générateur de formulaire** [W] : bascule `Form`/`JSON` synchronisée ; JSON
  malformé → `The JSON is malformed: {msg}` et la bascule est **annulée** ; type sans
  schéma → `No schema is registered for type "{type}". Use the JSON tab to edit the
  specification.` ; validation client = uniquement les `required` de 1er niveau
  (`{Label} is required.`), tout le reste tranché par le serveur ; widget `rate` :
  saisi en % (20), envoyé en fraction (0.2) — vérifier l'aller-retour sans artefact
  flottant ; chips inconnues marquées `ac-chip-unknown` avec
  `No match — the value can still be used as is.` ; `Enter` ajoute la valeur sans
  soumettre.
- **O4 Import depuis l'UI** [W] : la sélection du fichier **soumet immédiatement**
  (aucune confirmation) ; sans fichier → notice rouge `No file was selected.` ; succès
  → notice verte `Import completed: {"createdCount":…}` (le JSON malformé D3 s'affiche
  tel quel) ; exception → `Import failed: {message}`.
- **O5 Workbench store-groups** [W] : tout est local jusqu'à `Save changes`
  (badge `Unsaved changes`, `beforeunload` navigateur à neutraliser en test) ; drag &
  drop mono et multi-sélection (shift-clic sur les lignes **visibles** — interaction
  avec le filtre à tester) ; création par `Enter` (doublon → `A group named "{code}"
  already exists.`) ; renommage double-clic (vide → retombe sur le code) ; suppression
  de groupe peuplé → confirm `"{nom}" contains {n} store(s) and {m} sub-group(s).\n\n
  Deleting the group moves them up one level. Continue?` ; cycle détecté côté client →
  `"{parent}" is already inside "{child}".` ; Save = `POST /ui/store-groups`
  `{"groups":[…]}` décrivant **l'état final** — les groupes absents du payload sont
  **supprimés** (à prouver : un groupe retiré localement disparaît en base) ; succès
  `{"saved":true}` ; erreurs serveur → **409** `{"error": …}` via
  `RejectedHierarchyMapper` (`A group is missing its code.` / `Unknown store code: …` /
  `Unknown group code: …` / `Group '{code}' ends up containing itself.`) avec
  **rollback complet** et état local conservé ; payload null → **400**
  `{"error":"Nothing to save."}`.
- **O6 Lookups** : 6 endpoints sous `/ui/lookup` (VIEWER+), max 20 résultats, format
  `[{value, label, detail}]` ; `q` entièrement numérique → recherche EAN par
  **préfixe** ; `targets` = magasins d'abord puis groupes ; `products/resolve?eans=`
  sans limite ; EAN inconnu absent de resolve (le widget le garde en chip
  `unknown`).
- **O7 Registre de schémas — canari** : 8 types exposés dans le `<select>`
  (alphabétique) ; ⚠ `N+M` et `MIXED_BUNDLE` sont enregistrés **deux fois** (offer
  puis advantage — le second écrase) : vérifier que le schéma servi au formulaire
  reste celui qui permet de créer une offre **valide pour le moteur** (une offre créée
  via le formulaire pour chacun des 8 types doit se valoriser sans 500).

## P. Coutures transverses (2e passe d'audit)

- **P1 Double exécution des appliers** : chaque applier est rejoué « à blanc » pour le
  score d'efficacité avant l'application réelle → prouver l'idempotence observable
  (vignettes : solde restauré, résultat identique en répétant le même appel 10×) ;
  offre dont le montant à blanc vaut 0 → division par zéro dans le score
  (`Infinity`/`NaN`) — comportement observé à figer.
- **P2 Le panier maximal** : panier « ValuationClient » complet (7 lignes, livraison
  Seclin, consigne, vignettes, N+M, bundle+substitut, deux vouchers, TR) → l'ordre
  d'application découle des scores (geste MAX → vignette 10 → vouchers → franco −1 →
  TR −2 → upsells −100) ; tous les invariants G6 tiennent ; chaque famille d'offre
  apparaît une fois. C'est le scénario de non-régression de référence.
- **P3 Spécification d'offre — deux chemins d'échec** : JSON invalide refusé **au
  persist** (`Failed to parse specification for Offer …`) ; JSON valide mais non
  conforme au schéma stocké quand même (import CSV sans validation de schéma !) puis
  refusé **à la valorisation** (500 `Error validating offer: …`) — prouver que l'UI
  (qui valide au schéma) et le CSV (qui ne valide pas) divergent.
- **P4 Index `Offer.eans`** : la liste d'EAN est extraite récursivement de la spec
  (toute clé finissant par `ean`/`eans` : `targetEans`, `contents[].ean`,
  `substituteEans`) → pilote le filtre EAN de la liste UI et le ciblage N+M/bundle ;
  modifier la spec → l'index suit (`@PreUpdate`) ; ordre non déterministe (HashSet).
- **P5 Volumétrie** : `MassProductImporterClient` (80 000 produits, lots de 1000) →
  import intégral sous un budget de temps à fixer, compteurs exacts ; puis un panier
  de 50 lignes distinctes → temps de réponse borné (coût ~quadratique du score à
  blanc : test de tenue, budget explicite).
- **P6 Vivier d'upsell** : `availableToUpcell` ne contient QUE les tranches parties en
  ligne `Standard` — un panier où tout est consommé (N+M complet + bundle complet +
  geste) → `availableToUpcell: {}` et **aucune suggestion**.
- **P7 Sensibilité à l'échelle des checksums** : réimporter un prix `1.20` en `1.2000`
  → `updatedCount:1` (hash BigDecimal sensible à l'échelle) alors que la valeur est
  identique — comportement à figer (bruit d'import récurrent en exploitation).
- **P8 Hygiène inter-scénarios** : la base est partagée par toute la vie de la JVM →
  chaque groupe de scénarios nettoie dans l'ordre inverse des dépendances
  (`Price → Offer → ProductFamily → Product → StoreGroup → Store`) ; ⚠ ne jamais
  compter sur `deleteProductFamily` GraphQL pour nettoyer (cascade F7) ;
  `DateTimeProvider.clear()` systématique en fin de scénario.

## Q. Inventaire exhaustif — messages & surfaces

Relevé PAR LE CODE. Chaque vérification = déclencheur → texte EXACT → canal.

### Q-A. Moteur & API `/valuation` (statut HTTP + `error_message` de trace)

| Déclencheur | Statut | Message exact |
|---|---|---|
| Schéma panier violé | 400 | `Error validating offer: <détails joints par ", ">` |
| JSON de spec non parsable | 400 | `Error parsing offer.` |
| Items non consommés | 422 | `Valuation failed: Some items could not be processed by any offer.` |
| EAN inconnu | 500 | `Error building appliers from factory: Configuration Error: Product not found for EAN '<ean>'` |
| Prix absent/expiré | 500 | `Configuration Error: No active price found for Product '<nom>' (ID: <id>) in Store '<code>' (Checked at date: <date>)` |
| `priceDate` invalide | 500 | `Invalid date format '<val>' for item EAN '<ean>'. Expected ISO-8601 format.` |
| Double geste manuel | 500 | `Item EAN '<ean>' carries more than one manual gesture (amount, percentage, forced price); only one is allowed.` |
| WEIGHT sans référence | 500 | `Configuration Error: Product '<nom>' (EAN: <ean>) is typed as WEIGHT but has no valid reference weight defined.` |
| VOLUME sans référence | 500 | `…has no valid reference volume defined.` |
| Adresse de livraison absente | 500 | `Delivery address or coordinates missing for basket <customerCode>` |
| Coordonnées magasin absentes | 500 | `Store address or coordinates missing for store <code>` |
| Deux offres DELIVERY | 500 | `Configuration Error: Multiple DELIVERY offers found for store '<code>'. Expected 1, found <n>.` |
| Deux offres DEPOSIT_BASKET | 500 | `Configuration Error: Multiple DEPOSIT_BASKET offers found for store '<code>'. Expected 1, found <n>.` |
| Échec applier offre | 500 | `Error applying offer logic: <msg>` |
| Échec applier remise | 500 | `Error applying discount logic: <msg>` |
| Hors paliers de livraison | 200 | (System.err) `Delivery distance <d> km exceeds all defined tiers for offer <code>` |

### Q-B. Libellés `type` des offres et avantages (JSON de sortie)

| Application | Littéral |
|---|---|
| Ligne standard | `Standard: EAN=<ean>, Qty=<qty>` |
| Geste prix forcé | `Manual Gesture: EAN=<ean> (forced price <v>)` |
| Geste montant | `Manual Gesture: EAN=<ean> (amount -<v>)` |
| Geste pourcentage | `Manual Gesture: EAN=<ean> (percent -<v>%)` |
| Livraison | `Delivery: <code> (<d> km) for <prix>€` |
| Consigne | `Deposit Basket: <n> x <prix>€` |
| N+M (⚠ trompeur) | `Mixed Bundle Promo: <code>` |
| Bundle mixte | `MixedBundle: <code> x<n> for <ttc>€` |
| Bon immédiat (⚠ espace avant `:`) | `Immediate Voucher Discount : <code>` |
| Vignettes | `Vignette Discount: <code> (<n> vignettes used, applied <m> times)` |
| Franco de port | `Free Delivery Threshold Discount: <code>` |
| Titre-restaurant | `MEAL_VOUCHER` |
| Upsell N+M (⚠ locale) | `Upsell N+M: <code> (Need <q> of <ean>)` |
| Upsell bundle (⚠ locale) | `Upsell Mixed Bundle: <code> (Need <q> of <ean>)` |

### Q-C. GraphQL — messages restitués au client

| Famille | Messages exacts |
|---|---|
| Conflits (create) | `Store with code '<c>' already exists.` · `Store with name '<n>' already exists.` · `Product with ean '<e>' already exists.` · `Product with name '<n>' already exists.` · `StoreGroup with code '<c>' already exists.` · `StoreGroup with name '<n>' already exists.` · `ProductFamily with code '<c>' already exists.` · `ProductFamily with description '<d>' already exists.` · `Offer with code '<c>' already exists.` · `A price with the same priority, start date, and usage already exists for this product and store.` · `A storage link for this product and category path already exists.` |
| Conflits (update) | `Another store with code '<c>' already exists.` · `Another store with name '<n>' already exists.` · `Another product with ean '<e>' already exists.` · `Another product with name '<n>' already exists.` · `Another group with name '<n>' already exists.` · `Another family with description '<d>' already exists.` · `Another price with this priority, start date, and usage already exists.` · `Another storage link with this product and category path already exists.` |
| Not-found (rendu SmallRye à calibrer, F3) | `Store with id <id> not found` · `Store with code <c> not found` · `Product with id <id> not found` · `Product with ean <e> not found` · `Price with id <id> not found` · `Offer with id/code … not found` · `StoreGroup with id/code … not found` · `ProductFamily with id/code … not found` · `ProductCategoryStorage with id <id> not found` · `The following Store codes were not found: [A, B]` · `The following StoreGroup codes were not found: [A, B]` · `One or more Store codes provided do not exist.` · `One or more StoreGroup codes provided do not exist.` |
| Génériques (masquage) | `An error occurred during <operationName>.` · `Database error while performing <operationName>. Please check your data.` |

### Q-D. Imports CSV — réponses & erreurs de ligne

| Déclencheur | Texte exact |
|---|---|
| Réponse nominale | `{"createdCount":<n>, "updatedCount":<m>}` |
| Réponse avec erreurs (⚠ JSON malformé) | `{"createdCount":<n>, "updatedCount":<m>, "errors":[<msg1>","<msg2>"]}` |
| Colonnes insuffisantes | `Line <n> ignored (not enough columns): <ligne>` |
| Échec de ligne (niveau 1) | `Line <n> (<code>): <message>` |
| IOException | 500 `Error reading file: <msg>` |
| Throwable (⚠ typo) | 500 `Unexcepted error: <msg>` |
| Prix — produit inconnu | `Product with EAN <ean> not found.` |
| Prix — magasin inconnu | `Store with code <code> not found.` |
| Prix — usage manquant | `PriceUsage is mandatory` / `PriceUsage is mandatory at column 5` |
| Catégories — EAN inconnu | `Product with EAN '<ean>' not found.` |
| Familles — produit inconnu | `Product EAN '<ean>' not found.` |
| Familles — sous-famille inconnue | `SubFamily code '<code>' not found.` |
| Familles — auto-référence | `Family '<code>' cannot contain itself.` |
| Groupes — magasin inconnu | `Store '<code>' not found.` |
| Groupes — sous-groupe inconnu | `StoreGroup '<code>' not found. Check CSV order (Parent must be defined before Child).` |
| Offres — sans cible | `Line <n>: Must define at least one store_code or store_group_code.` |
| Offres — cible inconnue | `Store code '<c>' not found.` / `StoreGroup code '<c>' not found.` |
| Offres — spec invalide | `Failed to parse specification for Offer <code>: <msg>` |

### Q-E. UI — messages de page & bandeaux (par écran)

- **Login** : `Invalid username or password.` (tout `?error`) · `You have been signed
  out.` (`?notice`) · titre `Sign in — Valuation admin`.
- **Mot de passe** : `The current password is incorrect.` · `The password is
  mandatory.` · `The password must be at least 8 characters long.` · `The two
  passwords do not match.` · `The new password must differ from the current one.` ·
  `Password updated.` · mode forcé : `Choose a password` + `This password was set by
  someone else. Pick your own before continuing.`
- **Users** : `The username is mandatory.` · `An account named '<u>' already exists.` ·
  `At least one role must be granted.` · `This is the last administrator: keep the
  role and the account enabled.` · `Account '<u>' created.` / `…updated.` /
  `…deleted.` · `Account not found.` · `You cannot delete your own account.` ·
  `The last administrator cannot be deleted.` · 404 `User <id> not found` · confirm
  `Delete account <u> ?`
- **Offres** : les 7 messages de validation (O2) · `Offer <id> not found` (404) ·
  `No file was selected.` · `Import completed: <json>` · `Import failed: <msg>` ·
  confirm `Delete offer <code> ?` · vide : `No offer matches the current filters.` +
  `The in-memory database is reset at every restart — run the CSV imports to load a
  catalog.`
- **Valuations** : `Recording is turned off: valuations run normally but leave no
  trace.` · `The retention must be at least one day.` · `Tracing configuration
  updated.` · `<n> trace(s) deleted.` · `Valuation <id> no longer exists.` ·
  `The basket is empty.` · `HTTP <n> — <message>` · `The valuation was refused.` ·
  `No response was produced.` · `Could not read this payload. Use the JSON tab.` ·
  `Nothing to show.` · `No valuation recorded yet.` · confirm `Delete every recorded
  valuation ?`
- **Workbench** (client + 409 serveur) : `A group named "<code>" already exists.` ·
  `"<parent>" is already inside "<child>".` · confirm de suppression multi-lignes ·
  `The hierarchy could not be saved.` · 400 `Nothing to save.` · 409 :
  `A group is missing its code.` · `Unknown store code: <c>` · `Unknown group code:
  <c>` · `Group '<c>' ends up containing itself.`
- **Générateur de formulaire** : `<Label> is required.` · `The JSON is malformed:
  <msg>` · `Select an offer type to configure its specification.` · `No schema is
  registered for type "<type>". Use the JSON tab to edit the specification.` ·
  `No match — the value can still be used as is.`
- **Pagination (3 listes)** : `No offer` / `No user` / `No valuation` ·
  `{n} {label}s — page {i} of {m}` · `Showing {a}–{b} of {n}` · `First` / `Previous` /
  `Page {i} of {m}` / `Next` / `Last`.

---

**121 scénarios** (A5 · B9 · C6 · D9 · E7 · F11 · G7 · H8 · I6 · J11 · K9 · L7 · M4 ·
N7 · O7 · P8) + inventaire Q (16 erreurs moteur, 14 libellés d'application, ~50
messages GraphQL, 18 messages d'import, ~60 messages UI).

Priorité d'exécution suggérée : **G/H (le contrat de l'API et des prix — l'argent)** →
J/K (offres & remises, dont les 3 poisons de configuration J6/J9/K8) → P2 (le panier
maximal de non-régression) → N (traces, l'observabilité) → D/E/F (référentiel) → le
reste. Les scénarios [P] demandent un lancement avec profil prod-like (variables
d'environnement + `app.password-change.enforced=true`) ; les [W] demandent un
navigateur piloté (Playwright/Selenium) avec neutralisation du `beforeunload` du
workbench et tolérance à l'auto-refresh 1 s des valuations.
