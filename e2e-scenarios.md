# Catalogue des scénarios e2e — imvaluation (moteur de valorisation)

Couche complémentaire des tests unitaires et du `ValuationEndToEndTest` existant : ici
l'application réelle démarre sur **:8090**, la base H2 **en mémoire** vit
(`drop-and-create` : tout est perdu au redémarrage), le référentiel est seedé par les
**10 imports CSV** (jeu miroir d'`ImportAllClient` : magasins `0101`–`0105`, groupes
`REGION_NORTH`/`DEPT_59`/…, produits `3300000000001`–`033` (dont quelques
`EGALIM_REGIME` FOOD_34/DPH_40 portés en 10ᵉ colonne, le reste `EXEMPT`), 9 familles,
~96 prix valides à partir de `2026-01-12T00:00:00`, 11 offres, un référentiel
`EGALIM_REGIMES` (FOOD_34/DPH_40/EXEMPT) et un référentiel `BUDGET_GROUPS`). Compte
d'amorçage `admin`/`admin` (profils dev/test). Les prix `SRP_FLOOR`, les gardes
`EGALIM_GUARD`/`SRP_GUARD` et les offres portant les nouveaux fragments (trigger,
arbitration, budget, compliance, TIME_WINDOW, MESSAGE) sont seedés en phases locales par
les groupes qui les exercent (offres/prix remis à zéro par classe).

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

> **Note i18n (spec-exploitation §1) — assertions en fr.** Depuis le lot exploitation,
> l'IHM BO est localisée (bundles `messages_fr`/`messages_en`) et la **langue par défaut est
> `fr`** : sans préférence utilisateur (`AppUser.locale`) ni en-tête `Accept-Language`, tout
> écran `/ui/*` et tout libellé/notice/validation qu'il rend sortent en **français**. Les
> assertions textuelles e2e portant sur des libellés d'IHM se font donc **en fr** (catégorie b :
> les littéraux ont quitté les templates pour les bundles). Restent en **anglais** (contrat
> machine, inchangé) : les erreurs des API (`/valuation`, GraphQL, imports CSV, `/consumptions`),
> les corps JSON, les en-têtes CSV et `valuation_traces.error_message`. Pour asserter un écran en
> anglais, envoyer `Accept-Language: en`.

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
  (VatRates → EgalimRegimes → Stores → StoreGroups → Products → ProductFamilies → Categories → Prices → BudgetGroups → Offers ;
  le référentiel TVA est désormais un prérequis de `/prices/import`, qui rejette un taux sans
  régime) → chaque import répond 200 `{"createdCount":N, "updatedCount":0}` ; rejouer le MÊME
  seed immédiatement → `createdCount:0, updatedCount:0` partout (idempotence par
  checksum), **y compris un magasin sans adresse** : le checksum d'adresse est
  symétrique (adresse absente normalisée en adresse vide avant calcul, comme le fait
  l'import), donc un ré-import strictement identique reste un no-op — idempotence totale.

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
- **D9 Rôle insuffisant** : MANAGER (sans ADMIN) → 403 sur les 10 endpoints d'import
  (ajout des chantiers EGAlim/TVA & budgets : `/vat-rates/import`, `/egalim-regimes/import`,
  `/budget-groups/import`).

## E. Imports CSV — spécificités par ressource

- **E1 Stores** (`code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude`,
  7 col. min) : upsert par code, lat/lon optionnelles (`safeParseDouble` silencieux) ;
  `name` vide → violation `Store name is mandatory` remontée en erreur de ligne.
- **E2 Store-groups** (`group_code|group_name|store_codes|store_group_codes`, listes
  séparées par **`;`**) : stratégie de **remplacement** (A4) — réimporter avec une liste
  amputée **retire** les liens absents (`replaceStores`/`replaceSubGroups` vident puis
  ré-ajoutent), et le checksum inclut désormais les membres ; ordre
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
  `Family '<code>' cannot contain itself.` ; ⚠ le checksum d'une famille **exclut ses
  enfants** (code/description/flags seulement) : un ré-import qui ne change que les
  sous-familles est un no-op silencieux et n'atteint jamais `linkSubFamilies` où se déclenche
  le garde d'auto-référence — il faut donc modifier la description pour que la mise à jour
  s'exécute (le test le fait).
- **E5 Categories** (`productEan|level1|…|level5`) : clé fonctionnelle
  `(ean, level1, level5)` ; une modification portant uniquement sur `level2/3/4` est
  bien détectée comme update ; EAN inconnu → `Product with EAN '<ean>' not found.`
- **E6 Prices** (9 col.) : clé composite `ean:store:usage:start:priority` — changer
  `endDateTime` **met à jour** le prix ; changer `priority`, `startDateTime` ou
  `priceUsage` **crée un prix supplémentaire** sans supprimer l'ancien (chevauchement
  silencieux, résolu par priorité — croiser H2) ; date présente mais non-ISO → **ligne
  rejetée** (H6b) `Invalid date in column 'START_DATE': '<val>' (expected YYYY-MM-DDTHH:MM:SS)`
  (seule une date absente/vide reste un bord ouvert `null`) ; `priceUsage` invalide →
  `PriceUsage is mandatory at column 5` ; `priority` vide → violation au commit ;
  EAN inconnu → `Product with EAN <ean> not found.` (sans quotes — asymétrie avec E5).
- **E7 Offers** (`offer_code|offer_type|specification|store_code|store_group_code`) :
  aucune cible → `Line N: Must define at least one store_code or store_group_code.` ;
  cible inconnue → `Store code '<c>' not found.` / `StoreGroup code '<c>' not found.` ;
  spec JSON invalide → `Failed to parse specification for Offer <code>: <msg>` (au
  flush, dans `errors[]`) ; spec > 1000 caractères → violation de longueur ; **type
  inconnu accepté** et stocké mais jamais appliqué par le moteur (test canari : l'offre
  existe, la valorisation l'ignore) ; cibles en **remplacement** (clear + re-link).

- **E8 EGALIM_REGIMES (extension 27/09)** : nominal `CODE|LABEL|CAP_RATE` avec `EXEMPT`
  à cap VIDE (non plafonné, accepté) ; `CAP_RATE` > 1 ou négatif → ligne rejetée avec
  message ; idempotence checksum ; upsert par code (re-livrer FOOD_34 à 0,3000 met à
  jour, pas de doublon).
- **E9 PRODUCTS, colonne `EGALIM_REGIME` (extension 27/09)** : absente ou vide →
  `EXEMPT` (fail-open) ; valeur inconnue → ligne rejetée ; insensible à la casse ;
  le régime entre dans le checksum (re-livraison avec régime changé = `updatedCount`).
- **E10 PRICES, usage `SRP_FLOOR` (extension 27/09)** : accepté avec les mêmes
  contrôles (cohérence HT/TTC/taux au centime, validité, priorité) ; usage inconnu →
  ligne rejetée.
- **E11 BUDGET_GROUPS (extension 27/09)** : nominal, idempotence, période
  optionnelle ; livré AVANT OFFERS ; `MAX_AMOUNT` et `MAX_APPLICATIONS` tous deux
  vides → accepté (groupe sans enveloppe = simple regroupement).
- **E12 OFFERS, colonnes nouvelles (extension 27/09)** : `BUDGET_GROUP_CODE` inconnu →
  ligne rejetée ; les colonnes `EXCLUDED_STORE_CODES`/`EXCLUDED_STORE_GROUP_CODES`
  (chantier ciblage, cf. W) sont optionnelles et un code inconnu **rejette la ligne**
  (`Excluded store code '<c>' not found.`), un code valide est lié ; toutes optionnelles —
  un fichier OFFERS d'avant l'extension passe inchangé.

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
  des prix/offres → violation FK (`System error`, la contrainte ne fire qu'au commit) ; ⚠
  **`deleteProductFamily` ne cascade QUE les sous-familles** (`@OneToMany CascadeType.ALL`) :
  le `@ManyToMany` vers le catalogue Produit partagé a **abandonné `CascadeType.ALL`**
  (rapport-remediation-imvaluation.md:109), donc les PRODUITS liés **survivent** — l'ancien
  « comportement le plus dangereux » est corrigé.
- **F8 Prix, cas vicieux** : `currentPrice` sans prix actif → **`null`** + WARN, pas
  d'exception ; doublon avec `startDateTime` null **non détecté** (le JPQL `= null` ne
  matche jamais) → deux prix identiques créés ; `updatePrice` d'un prix à start null →
  NPE masquée `An error occurred during updatePrice.` Tests négatifs documentant.
- **F9 Asymétrie createStoreGroup** : sous-groupe inconnu à la création → `null` ajouté
  au Set → depuis A4 le checksum inclut les membres (`getChecksum` mappe `g.code`), donc
  `@PrePersist` **NPE** sur l'enfant null → **rejet** masqué `An error occurred during
  createStoreGroup.` + rollback ; à l'update, contrôle explicite → `NoSuchElementException`
  masquée en `System error`.
- **F10 Cycles indirects acceptés** : construire A→B puis B→A via deux updates → accepté
  (`wouldCreateCycle` jamais appelée) ; vérifier que `findAllStoreGroups` et la
  valorisation par groupe survivent (sets `visited`) — croiser K4.
- **F11 offersByStoresAndType** : un doublon dans `storeCodes` → `One or more Store
  codes provided do not exist.` (le contrôle compare les tailles) — piège à figer.

- **F12 `Product.egalimRegime` (extension 27/09)** : lecture jamais nulle (défaut
  `EXEMPT`) ; mutation avec les trois valeurs ; valeur inconnue → erreur GraphQL
  propre ; update SANS le champ → valeur conservée.
- **F13 `BudgetGroup` (extension 27/09)** : CRUD par code métier, RBAC identique aux
  autres référentiels ; `Offer.budgetGroupCode` et les exclusions PDV exposés.
- **F14 Suivi budgétaire (extension 27/09)** : `budgetStatus(offerCode)` /
  `groupBudgetStatus(groupCode)` en MANAGER ; code inconnu → erreur « introuvable »
  propre ; cohérence consommé/restant/`byStore` avec V1–V6.

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

- **G8 Rejets 400 de l'extension (27/09)** : `promotionType` hors enum, PERCENT
  `value` > 1, `value` ≤ 0 → 400 schéma ; `couponCodes:[""]` (minLength) → 400. Chacun =
  trace `REJECTED` (patron G2). ⚠ CALIBRÉ : `cardPromotions` doublon d'EAN n'est PAS un
  400 — l'unicité n'est pas exprimable au schéma, elle lève dans `engine.evaluate()` et
  ressort en **500** `FAILED` `Duplicate card promotion for EAN '<ean>'` (cf. T4) ;
  `closed:"yes"` (non booléen) est un **400 du provider Jackson SANS trace** (patron G7,
  avant la méthode), pas un `REJECTED`.
- **G9 Forme de la réponse enrichie (27/09)** : `egalim[]`, `srp[]`, `messages[]`
  n'apparaissent QUE leur configuration in-force (clé ABSENTE sinon — l'assertion
  d'absence est le critère de régression) ; les invariants G6 restent vrais avec des
  corrections NÉGATIVES au ticket (Σ ventilation TTC = totalPrice, corrections
  comprises) ; `accountingCode`/`printMode` recopiés sur les remises qui les portent,
  absents des autres.

## H. Résolution des prix & lignes standard

> **Note A5 (quantités BigDecimal)** : `Item.quantity` est désormais un `BigDecimal`, interpolé
> brut dans le libellé `Standard: …, Qty=<q>`. Une quantité entière obtenue à l'échelle 0 s'affiche
> **sans `.0`** (`Qty=1`, `Qty=2` — cas H1/H6, J2, J7) ; certains chemins arithmétiques (le reliquat
> d'un MixedBundle en J10) produisent une échelle 1 et affichent `Qty=2.0`. Chaque test épingle la
> valeur réellement sérialisée.

- **H1 Ligne simple** : 1 × lait `…002` sur `0101` → une offre
  `Standard: EAN=3300000000002, Qty=1`, total 3.00, ventilation TVA 20 %.
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
- **H5 Prix porté par la ligne (révisé lot Digital 28/10)** : `pricePerUnitExclTax` +
  `pricePerUnitInclTax` + `vatRate` **tous trois** fournis → prix transient, aucun
  accès base, et `DEFAULT` ≡ `BASE_FOR_DISCOUNT` (les remises se calculent sur le
  prix porté) ; fournir seulement 1 ou 2 des 3 → **400** nommant le ou les champs
  manquants ET la ligne concernée (test des 3 combinaisons partielles — l'ancien
  silence « retour catalogue » est ABROGÉ par spec-digital-ecommerce §4, trace
  `REJECTED` patron G2).
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

- **H9 `SRP_FLOOR` n'est pas un prix de vente (extension 27/09)** : un article dont
  la SEULE ligne de prix active est `SRP_FLOOR` → « No active price found » (le
  plancher ne tarife jamais) ; avec un `DEFAULT` actif à côté → c'est le `DEFAULT`
  qui valorise, quelle que soit la priorité de la ligne `SRP_FLOOR`.

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
- **J6 Configurations toxiques (fail-closed A2)** : `quantityToPay: 0` (+ `discountedQuantity: 1`)
  — acceptée par l'offre, refusée par la factory d'upsell (`minimum: 1`) : depuis A2 la
  `ConfigurationException` est **skippée et tracée**, la valorisation répond **200** et la trace
  `STATUS_SUCCESS` porte `Skipped configurations: … Error validating offer: …` (J6A, magasin 0106).
  En revanche `quantityToPay: 0` ET `discountedQuantity: 0` → `bundleSize = 0` → **division par zéro
  à l'application** (`ArithmeticException "/ by zero"`, pas une `ConfigurationException`) → **500**
  qui subsiste (J6B, magasin 0107). A2 désamorce le déni de service de config mais expose l'erreur
  d'exécution jusque-là masquée.
- **J7 Bundle prix fixe & substitut** : café `…004` + biscuits `…013`
  (`PROMO_COFFEE_PACK`, 4.50) → `MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€` ; café +
  chips `…014` (substitut) → **4.50 aussi** ; café seul → aucun applier, panier au
  tarif standard (composant manquant = offre entière inerte).
- **J8 Bundle en mode remise & TVA dérivée** : bundle `discount` PERCENTAGE et
  FIXED_AMOUNT (× nombre de lots), plancher 0 ; composants multi-taux (20 % + 5,5 %) →
  le taux effectif est **dérivé des produits couverts** (`TTC/HT − 1`), le `vatRate`
  déclaré n'est qu'un repli — la ventilation TVA du ticket reste aux taux réels
  (croiser G6). ⚠ MAIS voir J9 avant de seeder.
- **J9 Bundle `discount` = config invalide (fail-closed A2)** : une offre `MIXED_BUNDLE` en mode
  `discount` est valide pour l'offre mais **rejetée par le schéma divergent de
  `MixedBundleUpsellAdvantageFactory`** (exige `bundlePrice`, interdit `discount`) → depuis A2 cette
  `ConfigurationException` est **skippée et tracée** : la valorisation du magasin répond **200** et la
  trace `STATUS_SUCCESS` porte `Skipped configurations: … Error validating offer: …`. Plus de déni de
  service par configuration.
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
- **K7 EAN inconnu dans `vignettes`** : `vignettes: {"9999999999999": 3}` → depuis H5 le
  constructeur `VignetteDiscountApplier` **filtre** un EAN dont `findByEan` renvoie null (au lieu de
  laisser `Collectors.toMap` NPE) : la valorisation répond **200**, **aucun avantage vignette** n'est
  produit, la trace `STATUS_SUCCESS` ne porte aucune erreur (un EAN vignette erroné est une saisie
  utilisateur, pas un 500).
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
  `Eligible {x} € (threshold {y} €)`) ; ligne sans `lineId` → **vrai tiret cadratin
  (—)** dans la cellule, jamais l'entité `&mdash;` littérale (le placeholder ne passe
  plus par l'échappement HTML) ; sans réponse → `No response was produced.` ;
  id inconnu → 303 liste + notice rouge `Valuation {id} no longer exists.`
- **N7 Formulaire de test & replay** [W] : `POST /ui/valuations/new` (champ
  `request`) — vide → `The basket is empty.` ; erreurs rendues
  `HTTP {status} — {message}` (400 schéma, 422, 500 — tiret cadratin ; message null →
  `The valuation was refused.`) ; succès → carte `Result` en JSON pretty-print ;
  chaque soumission **crée une trace** ; `Replay` précharge `requestPayload` ; replay
  d'un id sans payload → formulaire vide `{}` **sans message** (échec silencieux à
  figer) ; bouton littéral `Value this basket`.

- **N8 Raisons d'arbitrage tracées (extension 27/09)** : refus de cumul, limites,
  `BUDGET_EXHAUSTED`/`GROUP_BUDGET_EXHAUSTED`, configurations skippées (trigger
  inconnu, famille inconnue, variable de message inconnue) — chaque refus/écartement
  de R→W laisse sa raison lisible dans la trace de la valorisation concernée.

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
  (alphabétique) ; `N+M` et `MIXED_BUNDLE` sont revendiqués par une factory d'offre **et**
  une factory d'avantage, mais le registre ne conserve qu'**une seule** inscription par
  type : les factories d'offre étant scannées en premier, le schéma qui crée une offre
  **valide pour le moteur** gagne, et tout doublon ultérieur est **rejeté et journalisé**
  (plus d'écrasement silencieux). Une offre créée via le formulaire pour chacun des 8 types
  se valorise sans 500.

- **O8 Formulaire schéma-driven à jour (extension 27/09)** : le formulaire d'offres
  rend les nouveaux fragments sans code dédié — `trigger`, `arbitration`, `budget`,
  `compliance`, `accountingCode`, `printMode`, `targetFamilyCodes`, `excludedEans` —
  pour un type qui les porte ; bascule Form/JSON fidèle dans les deux sens.
- **O9 Import de liste d'EANs par fichier (extension 27/09)** : bouton du widget
  `ean-list` — fichier texte/CSV une colonne : liste remplie, doublons dédupliqués,
  BOM strippé, binaire refusé proprement, rien n'est envoyé au serveur.
- **O10 Écrans nouveaux (extension 27/09)** : `/ui/egalim-ceilings` lecture seule
  (VIEWER ; « — » pour non plafonné ; anonyme refusé) ; `/ui/consumptions` (MANAGER ;
  agrégats = CSV téléchargé, X5) ; écran d'imports à NEUF domaines avec préselection
  par nom de fichier (`vat` → VAT_RATES, `egalim` → EGALIM_REGIMES) ; section
  « Périmètre » de l'écran utilisateurs (Y1–Y4, sémantique stricte des cases).

## P. Coutures transverses (2e passe d'audit)

- **P1 Double exécution des appliers** : chaque applier est rejoué « à blanc » pour le
  score d'efficacité avant l'application réelle → prouver l'idempotence observable
  (vignettes : solde restauré, résultat identique en répétant le même appel 10×) ; les seules
  offres qui pourraient annuler le montant sont rejetées par leur schéma AVANT de scorer
  (`bundlePrice:0.0` viole `exclusiveMinimum:0`, N+M `quantityToPay:0` viole `minimum:1`) : depuis
  A2 elles sont **skippées fail-closed** → valorisation **200** + trace `STATUS_SUCCESS`
  `Skipped configurations: … Error validating offer: …`, la division reste code mort masqué par les
  schémas (plus de 500 par configuration).
- **P2 Le panier maximal** : panier « ValuationClient » complet (7 lignes, livraison
  Seclin, consigne, vignettes, N+M, bundle+substitut, deux vouchers, TR) → l'ordre
  d'application découle des scores (geste MAX → vignette 10 → vouchers → franco −1 →
  TR −2 → upsells −100) ; tous les invariants G6 tiennent ; chaque famille d'offre
  apparaît une fois. C'est le scénario de non-régression de référence.
- **P3 Spécification d'offre — deux chemins d'échec** : JSON invalide refusé **au
  persist** (`Failed to parse specification for Offer …`) ; JSON valide mais non
  conforme au schéma stocké quand même (import CSV sans validation de schéma !) puis, depuis A2,
  **skippé fail-closed à la valorisation** (200 + trace `STATUS_SUCCESS` `Skipped configurations: …
  Error validating offer: …`) — l'UI (qui valide au schéma) et le CSV (qui ne valide pas) divergent
  toujours, mais le moteur met la config en quarantaine par panier sans faire échouer la valorisation.
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
J/K (offres & remises : configs invalides désormais skippées fail-closed en 200 — J6A/J9/P1/P3 —
sauf les vraies erreurs d'exécution qui restent en 500 — J6B division par zéro, K8) → P2 (le panier
maximal de non-régression) → N (traces, l'observabilité) → D/E/F (référentiel) → le
reste. Les scénarios [P] demandent un lancement avec profil prod-like (variables
d'environnement + `app.password-change.enforced=true`) ; les [W] demandent un
navigateur piloté (Playwright/Selenium) avec neutralisation du `beforeunload` du
workbench et tolérance à l'auto-refresh 1 s des valuations.

---

# Extension du 27/09/2026 — chantiers triggers/arbitrage, C3, promotions carte, gardes, budgets, ciblage, sorties enrichies

> Même convention que le tronc : parcours → attendus vérifiables. Les libellés cités
> entre guillemets typographiques sont ceux des **specs** (`spec-*.md` à la racine) ;
> comme pour les 4xx/5xx du tronc, calibrer une fois le littéral exact sur le code
> avant toute assertion textuelle. Trois garde-fous propres à cette extension :
> les blocs nouveaux (`egalim[]`, `srp[]`, `messages[]`) sont **absents** (clé
> inexistante, pas `null`) quand leur configuration n'est pas in-force — asserter
> l'absence, c'est le critère de régression ; toute config invalide est **skippée
> fail-closed** (200 + trace `Skipped configurations`, patron P) ; et les scénarios
> dépendant de l'heure passent par le `DateTimeProvider` pilotable, jamais par
> l'horloge réelle.

## R. Déclencheurs & arbitrage (C1/C2)

- **R1 Montant minimum, borne inclusive** : offre `TICKET_DISCOUNT` avec condition
  `MINIMUM_AMOUNT` scope `TICKET` seuil 50 → panier à 49,99 : pas de remise ;
  à 50,00 : remise (compareTo ≥ 0, jamais d'epsilon).
- **R2 Quantité minimum au poids** : condition `MINIMUM_QUANTITY` seuil 3 sur une
  liste — trois lignes de 1,000 kg déclenchent ; 2,999 kg non (BigDecimal exact).
- **R3 Code coupon** : offre à condition `COUPON_CODE` → panier sans `couponCodes` :
  rien ; avec le code : appliquée ; avec un code inconnu de toute offre : toléré,
  aucun effet, aucune erreur.
- **R4 `conditions[]` = ET implicite** : deux conditions (montant + coupon), une seule
  satisfaite → rien ; les deux → appliquée.
- **R5 Panier ouvert / fermé** : `closed:false` → les avantages AT_TOTAL (remise
  ticket, promotions carte, gardes) ne s'appliquent PAS ; `closed:true` et champ
  ABSENT → ils s'appliquent (absent ⇒ fermé, l'e-commerce est fermé par nature).
- **R6 Politique d'arbitrage** : deux avantages non cumulables sur la même assiette →
  la priorité basse passe, le refus de l'autre est tracé ; `exclusionGroups` partagé →
  un seul des deux ; `maxApplicationsPerTicket:1` respecté ;
  `consumesContributors:true` → les porteurs consommés privent d'assiette l'avantage
  suivant (ordre = priorité, patron « priorité pilote la consommation »).
- **R7 Trigger invalide = fail-closed** : `conditions:[{"kind":"UNKNOWN"}]` →
  configuration skippée, valorisation 200, trace `Skipped configurations`.

## S. Mécaniques de remise C3 & paliers

- **S1 Prix nouveau** : `NEW_PRICE_DISCOUNT` cible → l'écart prix affiché−prix cible
  sort en remise ; prix cible ≥ prix affiché → rien, silencieux.
- **S2 Remise ticket** : % sur le net du ticket, plafonnée — jamais de total négatif.
- **S3 Montant par article** : x € par unité éligible, pesée compte 1.
- **S4 Article offert** : sélection parmi les CONTRIBUTEURS du trigger, égalité
  départagée par EAN, quantité non entière exclue de la sélection, une fois par
  ticket.
- **S5 Remboursement TVA** : ligne à 20 % → remise = part de TVA (Σ TTC−HT), avantage
  `VAT Refund: <code>`. ⚠ ÉCART spec/code : la ventilation **N'est PAS gelée** — le
  remboursement est une remise réelle qui **nette** la ventilation (le taux réel 20 %
  est conservé, jamais un taux mélangé 0,0000, et Σ ventilation TTC = `totalPrice`), et
  la bascule DEFAULT→BASE_FOR_DISCOUNT s'y ajoute ; le « la ventilation ne bouge jamais »
  de `spec-c3-mecaniques.md` §S5 est contredit par le code. Invariants réels asservis.
- **S6 Anti-gaspi** : `bestBeforeDate` par ligne — deux lots du même EAN = deux
  lignes, remises différentes ; date dépassée = tranche la plus stricte ; ligne sans
  date = pas de remise.
- **S7 Paliers** : `TIERED_DISCOUNT` métrique `QUANTITY` + `perProduct:true` →
  paliers par EAN ; `PER_MULTIPLE` → multiples exacts (BigDecimal) ; tranches
  chevauchantes ou vides à l'import → config rejetée/skippée fail-closed.

## T. Promotions carte (configuration portée par le panier)

- **T1 AMOUNT nominal** : `cardPromotions:[{ean, promotionType:"AMOUNT", value:0.50}]`,
  ligne au poids 0,738 kg → remise 0,50 (la pesée compte UN), plafonnée au net de la
  ligne ; sortie type `Card Promotion: CARD_PROMO:<ean>`, `cardNumber` repris au
  détail.
- **T2 PERCENT sur le net** : promotion magasin cumulable d'abord, puis PERCENT carte
  calculé sur le net résiduel (jamais le brut).
- **T3 CAGNOTTE ignorée** : entrée `benefit:"CAGNOTTE"` → RIEN dans `advantages[]`
  (défense anti-double : c'est le monopole imfid).
- **T4 Doublon d'EAN** : deux entrées même EAN → ⚠ CALIBRÉ **500** `FAILED`
  `Duplicate card promotion for EAN '<ean>'` (l'unicité n'est pas exprimable au schéma du
  panier : le contrôle lève une `IllegalArgumentException` dans
  `CardPromotionDiscountFactory.buildAppliers` → `engine.evaluate()`, après la validation,
  et ressort en 500 tracé — pas le 400 qu'annonçaient le tronc et `spec-card-promotions.md`
  §2.3). Erreur d'appelant, jamais d'arbitrage silencieux.
- **T5 Bornes de valeur** : PERCENT `value:1.5` → 400 (max 1) ; `value:0` → 400
  (exclusiveMinimum) ; AMOUNT négatif → 400.
- **T6 EAN absent du panier** : promotion sans assiette → rien, silencieux.
- **T7 Arrondi unique** : deux lignes du même EAN → une remise, base sommée, arrondi
  une fois, résidu sur la dernière ligne ; Σ quotes-parts = montant affiché.
- **T8 Régression** : panier identique sans `cardPromotions` → réponse bit à bit
  identique au tronc.

## U. Gardes de conformité — EGAlim & SRP

- **U1 Correction EGAlim nominale** : produit `FOOD_34`, remises cumulées 50 % →
  contre-avantage négatif `EGAlim Adjustment: <code>` remontant la ligne à 34 %
  exactement (plafond autorisé arrondi au centime INFÉRIEUR) ; bloc `egalim[]` :
  une entrée PAR ligne initiale, `remainingBeforeCap: 0.00` sur la ligne corrigée,
  renseigné sur les conformes, ABSENT pour `EXEMPT`.
- **U2 Régimes** : `DPH_40` corrigé à 40 % ; `EXEMPT` jamais corrigé (70 % de remise
  légale).
- **U3 Exclusions structurelles** : remise anti-gaspi 60 % sur périssable + geste
  manuel → `excludedGenerosity` les porte, générosité comptée 0, pas de correction.
- **U4 Exemption par promotion** : configuration `compliance.egalimExempt:true` →
  ses remises comptent en exclu (cas soldes).
- **U5 Ciblage PDV du garde** : configuration `EGALIM_GUARD` ciblée PDV A → panier
  PDV B : ni correction ni bloc `egalim[]`, réponse bit à bit identique au tronc.
- **U6 Panier ouvert** : `closed:false` → ni correction ni record (AT_TOTAL).
- **U7 Plafonds administrés** : flux `EGALIM_REGIMES` abaisse FOOD_34 à 0,3000 →
  la correction suivante calcule à 30 % (la donnée, pas le code) ; cap > 1 dans le
  CSV → ligne rejetée avec message.
- **U8 SRP nominal** : ligne à plancher `SRP_FLOOR` 11,00, net après remises 10,00 →
  correction +1,00 `SRP Adjustment: <code>`, net final = plancher ; bloc `srp[]`
  avec `marginAboveFloor` sur les lignes contrôlées conformes ; article SANS ligne
  `SRP_FLOOR` : non contrôlé, absent du record.
- **U9 Ordre des gardes** : EGAlim corrige d'abord (MAX−1), SRP verrouille ensuite
  (MAX) — sur un même ticket les deux records sont cohérents entre eux et avec le
  total.
- **U10 `srpExempt`** : la ligne remisée par une configuration exemptée passe sous le
  plancher sans correction (soldes), tracé.
- **U11 Écrêtage tracé (geste manuel)** : `manualDiscountAmount` 1,00 sur `…016`
  (rayon 2,40, plancher 2,40) → net 1,40 sous le plancher : le garde écrête et
  l'entrée `srp[]` dit POURQUOI pas plus — `reasonCode: SRP_FLOOR_CLAMPED`,
  `correction` +1,00, `requestedDiscount` (2,64 − 1,40 = 1,24) et `grantedDiscount`
  (2,64 − 2,40 = 0,24) mesurés sur le prix de vente de référence.
- **U12 Paliers d'alerte** : `SRP_GUARD` porte `warningThresholds:[0.5,0.8]` ; remise
  20 % sur le jambon EXEMPT `…008` (rayon 2,40, plancher 1,80, référence 2,64) →
  descente dans la bande d'alerte SANS franchir le plancher : entrée `srp[]`
  `reasonCode: SRP_MARGIN_WARNING`, `warningThreshold` et `descentFraction`
  renseignés, AUCUNE correction, AUCUN `SRP Adjustment`. Trois niveaux distincts :
  accordé (rien), averti (U12), écrêté (U8/U11).
- **U13 Dérogation « produit dégradé »** : la remise 40 % qui écrêtait `…016` en U8,
  mais la ligne porte `srpOverride:true` + `srpOverrideReason` → le plancher est
  contourné (ni correction ni `SRP Adjustment`), le contournement est tracé dans
  `srp[]` (`reasonCode: SRP_OVERRIDE`, `overrideReason` recopié) pour le journal
  électronique et les exports. Distinct de `compliance.srpExempt` (exemption de
  l'article), ici ponctuel sur une ligne du ticket.

## V. Groupes budgétaires & consommation

- **V1 Ingestion nominale** : `POST /consumptions` (ADMIN ; MANAGER → 403) SALE avec
  deux avantages → compteurs `(offerCode, storeCode)` incrémentés en montant ET en
  tickets.
- **V2 Idempotence** : rejeu strict du même `(storeCode, ticketId, direction)` →
  200 `duplicate:true`, compteurs INCHANGÉS.
- **V3 Annulation** : `CANCELLATION` symétrique → compteurs décrémentés, l'avantage
  redevient applicable ; CANCELLATION sans SALE préalable → acceptée (relais dans le
  désordre), tracée.
- **V4 Budget d'offre épuisé** : `maxAmount 10`, consommation 9 → ticket à 1,50 de
  remise APPLIQUÉ (dépassement borné : jamais de remise partielle) ; ticket suivant :
  avantage absent, refus tracé `BUDGET_EXHAUSTED` ; `maxApplications 2` → 3e ticket
  sans l'avantage.
- **V5 Enveloppe de groupe** : deux offres du même `BUDGET_GROUP` — la consommation
  de l'une épuise le droit des deux (`GROUP_BUDGET_EXHAUSTED`) ; offre budgétée ET
  membre d'un groupe → le plus contraint refuse.
- **V6 `perStore`** : PDV A épuisé, PDV B vierge → refusé en A, appliqué en B ;
  enveloppe globale → la somme tous PDV contrôle.
- **V7 Période de groupe** : groupe hors `validFrom`/`validTo` → ses offres ne sont
  plus servies par les finders in-force.
- **V8 Flux** : `POST /budget-groups/import` nominal + idempotence checksum ;
  `OFFERS` avec `BUDGET_GROUP_CODE` inconnu → ligne rejetée avec message.
- **V9 Suivi** : GraphQL `budgetStatus`/`groupBudgetStatus` (MANAGER) → consommé /
  restant / `byStore` cohérents avec les déclarations ; compteur en erreur simulée →
  l'avantage S'APPLIQUE quand même (fail-open borné), trace posée.
- **V10 Régression** : aucun budget nulle part → réponse bit à bit identique.

## W. Ciblage, créneaux horaires & sorties enrichies

> ✅ `spec-ciblage-saisie.md` est désormais IMPLÉMENTÉE (chantier ciblage à la saisie) :
> fragments partagés `targetFamilyCodes`/`excludedEans` injectés là où `targetEans`
> existe (union sous-arbre familles − exclusions, résolus à l'évaluation via le helper
> commun `EngineTrait.resolveTargetEans`, famille inconnue → skip fail-closed) ;
> exclusions PDV `excludedStores`/`excludedStoreGroups` sur `Offer` (colonnes OFFERS
> `EXCLUDED_STORE_CODES`/`EXCLUDED_STORE_GROUP_CODES`, code inconnu → ligne rejetée ;
> soustraites par les finders in-force après résolution des groupes) ; bouton d'import
> de fichier du widget `ean-list` (`schema-form.js`, parsing 100 % client). W1–W4 sont
> couverts (GroupWIT), plus un contrôle de déterminisme 50×.

- **W1 Familles vivantes** : offre ciblant la famille F → article de F et d'une
  sous-famille couverts ; article AJOUTÉ à F par un ré-import ensuite → couvert
  SANS retoucher l'offre (résolution à l'évaluation, jamais compilée).
- **W2 L'exclusion prime** : `excludedEans` retire un EAN pourtant présent dans
  `targetEans` ET dans une famille ciblée.
- **W3 Famille inconnue** : code inexistant → configuration skippée fail-closed,
  200 + trace.
- **W4 Exclusion de PDV** : `EXCLUDED_STORE_CODES` sur l'offre → non servie dans ce
  PDV même si un groupe ciblé le contient.
- **W5 Créneau horaire** : condition `TIME_WINDOW` 17:00–19:00 → 18:30 (via
  `DateTimeProvider`) déclenché, 12:00 non ; fenêtre 22:00–02:00 : 23:50 et 00:10
  dedans, 12:00 dehors ; `days` respecté ; composée en ET avec `MINIMUM_AMOUNT`
  (happy hour conditionnelle).
- **W6 Champs recopiés** : `accountingCode` et `printMode` ressortent tels quels sur
  la `DiscountApplication` ; absents de la spec → sortie bit à bit identique.
- **W7 Offres message** : `MESSAGE` `WHEN_TRIGGERED` → présent dans `messages[]`
  avec variables résolues quand le seuil est atteint, absent sinon ;
  `BELOW_TRIGGER` → `{{missingAmount}}` exact au centime sous le seuil, absent
  au-dessus ; variable inconnue → config skippée ; aucun message applicable →
  PAS de clé `messages` dans la réponse.

## X. Exports financiers

- **X1 Détail** : `GET /consumptions/export?from=&to=&format=csv` → colonnes exactes
  `TICKET_ID|STORE_CODE|FINALIZED_AT|DIRECTION|OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|EAN|AMOUNT`,
  bornes de période respectées, montants échelle 2 plain ; le même contenu en
  `format=json`.
- **X2 Agrégats** : `groupBy=offer` → sommes exactes, CANCELLATION nettée dans les
  agrégats mais lignes négatives distinctes dans le détail.
- **X3 Vérité comptable** : offre supprimée après consommation → la ligne sort,
  `OFFER_TYPE` et `BUDGET_GROUP_CODE` vides.
- **X4 Détail article** : déclaration portant `lines:[{ean,amount}]` → EAN renseigné ;
  sans → EAN vide, totaux identiques.
- **X5 RBAC** : VIEWER → 403 sur l'endpoint ET l'écran `/ui/consumptions` ; l'écran
  MANAGER affiche des agrégats égaux au CSV téléchargé avec les mêmes filtres.

## Y. Droits par PDV & durcissement web

- **Y1 Périmètre PDV** : utilisateur `storeScope=[0101]` → liste des offres filtrée
  (intersection) ; création d'une offre ciblant `0102` → refus explicite côté
  serveur, en UI ET en GraphQL (le filtre d'écran n'est qu'un confort) ; retirer un
  PDV hors périmètre d'une offre mixte : accepté ; en ajouter un : refusé.
- **Y2 Mécaniques autorisées** : `allowedOfferTypes=[TICKET_DISCOUNT]` → le
  formulaire ne propose que ce type, la création d'un `N+M` en direct → refus
  serveur.
- **Y3 Menus masqués** : entrée absente du layout ET accès direct à l'URL refusé.
- **Y4 Régression** : les trois champs vides → comportement d'aujourd'hui bit à bit
  (listes, formulaires, GraphQL).
- **Y5 CSRF** : formulaire UI sans jeton → 403 ; avec → 200 ; les clients machine en
  Basic (`/valuation`, les 9 imports, `/consumptions`) passent SANS jeton ;
  `SameSite=Lax` toujours posé (défense en profondeur, pas remplacée).

## Z. Lot Digital — cycle e-commerce sans état & valorisation des retours

> Référence : `spec-digital-ecommerce.md`. Doctrine transverse : le moteur ne
> conserve JAMAIS de panier — chaque appel porte tout, la réponse est le seul
> produit de l'appel ; seule persistance : `ConsumptionRecord`.

- **Z1 Cycle de vie complet, zéro état** : ouverture (`closed:false`) → modification
  (panier complet renvoyé) → mise en attente → reprise → clôture (`closed:true`) →
  `POST /consumptions` SALE. Les valorisations intermédiaires sont structurellement
  identiques (mêmes blocs), UNE seule déclaration consomme les budgets, et aucune
  ligne nouvelle n'apparaît en base hors `ConsumptionRecord` (vérification table à
  table). La « suppression » d'un panier = aucun appel, rien à libérer.
- **Z2 Reprise fidèle** : un panier repris avec prix poussés et `priceDate` → le prix
  poussé reste autoritaire à la reprise ; les promotions carte relues par l'appelant
  s'appliquent sur l'état du jour.
- **Z3 Réponse enrichie** : chaque ligne porte `label`, `familyCode`, `familyLabel` —
  renseignés pour un article du référentiel, `null` tous les trois pour un article
  inconnu/sans famille, JAMAIS d'erreur ; goldens `/valuation` mis à jour une seule
  fois, diff limité à ces champs.
- **Z4 Retour FULL** : `POST /returns/valuation` mode `FULL` avec la photo de
  clôture → `totalRefund` = total encaissé de la photo au centime,
  `advantagesTakenBack` = tous les avantages de la photo, `vatBreakdown` cohérent.
- **Z5 Retour PARTIAL, remise conservée** : ligne rendue remboursée à son prix payé
  net de sa quote-part des remises globales (répartition identique à la vente,
  résidu sur la dernière ligne).
- **Z6 Retour PARTIAL, offre perdue** : le panier restant ne remplit plus la
  condition d'une offre globale → la remise perdue est REPRISE sur le remboursement
  et figure dans `advantagesTakenBack`.
- **Z7 Retours LINES, les trois sources de prix** : `CURRENT` (prix actif du PDV,
  promotions courantes NON appliquées à un retour), `DATE` (prix historique à
  `priceDate`), `PUSHED` (triplet complet autoritaire).
- **Z8 Refus 400, un test par jambe** : photo absente en FULL/PARTIAL ; `lineId`
  inconnu de la photo ; quantité rendue > quantité de la photo ; prix poussé
  incomplet ; `priceDate` absente/illisible en DATE ; `priceSource` inconnu ;
  `priceSource: ORDER` hors photo (mode LINES) — chaque message nomme sa cause.
  Article inconnu du référentiel en CURRENT/DATE → **422** nommant l'EAN.
- **Z9 Le retour ne persiste rien** : après une valorisation de retour, aucune
  écriture en base ; la `CANCELLATION` est déclarée PAR L'APPELANT (ticketId = la
  référence du retour) et décrémente les compteurs de budget — rejeu idempotent.
- **Z10 Déterminisme** : la même requête de retour, rejouée, rend la même réponse
  octet à octet.

## AA. Valorisation d'affichage e-commerce (batch, lecture seule)

> Référence : `spec-valorisation-affichage-ecommerce.md`. Doctrine transverse :
> `POST /display-valuation` est un panier (multiple) très spécial — n mini-paniers
> d'une unité, chacun sur son pseudo-ticket, évalués contre un contexte construit
> UNE fois par appel ; strictement en lecture seule, le moteur n'appelle jamais
> (promotions carte POUSSÉES par l'appelant).

- **AA1 Prix public nu** : produit sans aucune offre → `displayedPrice` =
  `basePrice`, `immediateDiscount` absent, `offerBadges` vide, `vatRate` issu de la
  ligne de prix résolue (référentiel, pas de valeur poussée).
- **AA2 Démarque inconditionnelle & cohérence panier (critère n° 1)** : produit sous
  `NEW_PRICE_DISCOUNT` (puis variante `AMOUNT_PER_ITEM_DISCOUNT`) sans déclencheur →
  reflétée dans `displayedPrice` ; le montant est comparé AU CENTIME au prix
  unitaire net d'une évaluation `/valuation` mono-ligne (qté 1, sans prix poussé),
  même magasin/date (règle 6).
- **AA3 Personnalisation poussée** : avec `cardNumber` + `cardPromotions[]`
  (`isImmediateDiscount()`) → `displayedPrice` personnalisé ; le même appel SANS le
  bloc → prix public ; une entrée `benefit: CAGNOTTE` poussée → silencieusement
  ignorée, comme au panier (spec-card-promotions §2.1).
- **AA4 Badges panier-dépendants, jamais de prix inventé** : N+M, `TIERED_DISCOUNT`,
  `MIXED_BUNDLE`, offre à `MINIMUM_QUANTITY` ciblant le produit → `offerBadges`
  (`type`, `label`, `code`) uniquement, `displayedPrice` = prix hors ces offres ;
  aucun champ de la réponse ne convertit leur effet en prix unitaire.
- **AA5 Exclusions de l'affichage** : `TICKET_DISCOUNT` inconditionnel en vigueur →
  absent de `displayedPrice` (avantage ticket, pas un prix produit) ;
  `ANTI_WASTE_DISCOUNT` en vigueur → jamais appliqué (pas de `bestBeforeDate` à
  l'affichage) ; grants d'instruments et fidélité → ni prix ni badge.
- **AA6 Planchers légaux à l'affichage** : démarque qui percerait le plafond EGAlim
  (puis le plancher SRP) → `displayedPrice` clampé au plancher, `floorGuard`
  renseigné ; le prix affiché ne descend jamais sous le plancher, mêmes règles que
  les gardes du panier.
- **AA7 Prix à l'unité de mesure** : produit avec `referenceWeight` (puis
  `referenceVolume`) → `unitPriceMeasure` calculé sur `displayedPrice`, arrondi à
  2 décimales HALF_UP, unité de `unitName` ; produit sans référence de mesure →
  champ absent.
- **AA8 Isolation entre EAN (pas de contamination croisée)** : une offre à
  `maxApplications` ciblant plusieurs EAN du même appel → chaque produit obtient sa
  démarque entière (pseudo-ticket dédié) ; la réponse d'un EAN est identique qu'il
  soit seul dans l'appel ou accompagné, et quel que soit l'ordre des `eans`.
- **AA9 Refus** : plus de `eans` que le plafond configuré → **400** nommant la
  limite et le nombre reçu ; `storeCode` inconnu ou `valuationDate` illisible →
  refus nommant sa cause ; EAN inconnu du référentiel → l'entrée de CET EAN est en
  erreur nommée, les autres EAN de l'appel sont valorisés normalement.
- **AA10 Lecture seule & empreinte** : après un appel batch, aucune écriture en base
  (vérification table à table, aucun `ConsumptionRecord`) ; `priceVersion` est
  stable pour un même (magasin, date) et change après mise à jour d'un prix ou
  d'une offre en vigueur de ce magasin.
- **AA11 Déterminisme** : le même appel batch, rejoué 50 fois, rend 50 réponses
  identiques octet pour octet ; `/valuation` reste bit-identique sur les goldens
  existants (le chantier n'altère pas l'évaluation panier).
