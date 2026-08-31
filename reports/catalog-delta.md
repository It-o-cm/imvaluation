# Delta catalogue e2e — surfaces ajoutées depuis le gel

Comparaison de `e2e-scenarios.md` (121 scénarios, gelé) à la surface **actuelle** du code.
Objet : lister les écrans, actions, endpoints et messages **présents dans le code mais
couverts par aucun scénario**, puis rédiger les scénarios manquants dans le style exact du
catalogue. **Aucune renumérotation** : les ids existants (A1…P8) sont référencés par les
classes de test et restent intouchés ; on **étend** les groupes concernés (`B10`…, `O8`…)
et on **ajoute** des puces à l'inventaire Q. Aucun test n'est modifié. Ce fichier est un
delta d'arbitrage, pas une réécriture du catalogue.

## Périmètre relevé PAR LE CODE

Deux fonctionnalités ont été portées depuis le donneur `imfid` **après** le gel du
catalogue (cf. `reports/forgot-password-port.md`, `reports/imports-tab-port.md`) :

| Feature | Écran(s) | Endpoints | Classe(s) | Statut catalogue |
|---|---|---|---|---|
| Mot de passe oublié (self-service) | `/ui/forgot`, `/ui/reset` | `GET`/`POST /ui/forgot`, `GET`/`POST /ui/reset` | `AuthUiResource`, `PasswordResetService`, `PasswordResetToken` | **Absent** — le groupe B s'arrête à B9 |
| Onglet Imports | `/ui/imports` | `GET /ui/imports`, `POST /ui/imports/run` | `ImportsUiResource`, `imports.js` | **Absent** — remplace l'ancien flux O4 |

> **« Intégration fidélité » : néant dans ce dépôt.** Le donneur s'appelle `imfid`
> (fidélité) ; `grep -rniE "fidel|loyal|fid-admin" src/main` est **vide**. Aucune surface
> fidélité n'existe côté receveur `imvaluation` — l'item n'est pas applicable ici, rien à
> couvrir.

---

## Groupe B — Authentification & session (extension B10 → B13)

Le parcours « mot de passe oublié » : l'utilisateur saisit son adresse, reçoit un lien à
usage unique (30 min), choisit un nouveau mot de passe, revient au login. Écrans Qute
autonomes (`login-panel`/`login-form`, style `auth.css`), tous `@PermitAll`. Jeton haché
SHA-256 (jamais le brut), un seul lien vivant par compte (`deletePendingFor`), anti-
énumération stricte. Hors production le mailer Quarkus est **mocké** : le lien part dans la
`MockMailbox` (levier de test du token brut — la base ne stocke que le hash).

- **B10 Demande de reset — point d'entrée & anti-énumération** : la page login expose un
  lien `Forgot password?` → `GET /ui/forgot` (titre `Forgot password — Valuation admin`,
  label `Your e-mail address`, bouton `Send the link`, retour `← Back to sign in`) ;
  `POST /ui/forgot` (form-urlencoded, champ `email`) répond **toujours 303**
  `/ui/forgot?sent=true` — **quelle que soit** l'adresse — et le GET avec `?sent` affiche
  la bannière neutre `alert-ok` : `If an account matches this address, a reset link has
  just been sent to it. It is valid for 30 minutes and can be used once.` Anti-énumération
  à prouver via `MockMailbox` : compte actif avec e-mail → **1** mail capté ; compte
  inconnu / sans e-mail / **désactivé** (`findActiveByEmail` exclut `active=false`,
  insensible à la casse) → 303 identique, **0** mail. Le compte d'amorçage porte
  `admin@valuation.local` (`valuation.bootstrap.admin.email`, défaut) : l'admin peut donc
  se réinitialiser dès le premier démarrage.

- **B11 Émission du jeton — hash, TTL, un seul lien vivant** [D] : une demande sur un
  compte actif → **exactement une** `PasswordResetToken` en base : `tokenHash` = SHA-256
  hex **64 caractères** (le brut n'est jamais stocké, colonne `unique`), `expiresAt` =
  `now + 30 min` (`valuation.reset.token-ttl-minutes`, défaut 30), `usedAt = null` ; le
  mail capté porte le sujet `Valuation admin — reset your password` et un corps dont la
  ligne active est `{baseUrl}/ui/reset?token={brut}` (baseUrl dérivé de la requête, slash
  final retiré). Une **seconde** demande sur le même compte → `deletePendingFor` purge le
  jeton non consommé précédent (au plus un lien vivant) : l'ancien lien devient inopérant.
  Levier déterministe : `DateTimeProvider.setFixedDateTime` pour `expiresAt` et pour
  franchir la borne d'expiration. `isUsableAt(now)` = `usedAt == null && now < expiresAt`.

- **B12 Réinitialisation — les refus, ordre exact, puis succès** : `GET /ui/reset?token=…`
  rend le formulaire (jeton en champ caché, `New password (at least 8 characters)`,
  `Confirm the password`, bouton `Change the password`). `POST /ui/reset`
  (champs `token`/`password`/`confirm`), ordre **exact** des refus, chacun **303**
  `/ui/reset?token=…&error=<msg>` :
  1. confirmation ≠ (vérifiée **dans la ressource**, avant le service) →
     `The two passwords do not match.` ;
  2. jeton vide/null → `This reset link is invalid.` ;
  3. jeton inconnu / déjà utilisé / expiré → `This reset link is invalid, already used or
     expired. Please request a new one.` ;
  4. politique mot de passe (déléguée à `AppUser.validatePassword`, cf. B8) : vide →
     `The password is mandatory.` ; < 8 → `The password must be at least 8 characters
     long.`
  Succès → **303** `/ui/login?notice=Your password has been changed. You can sign in.` →
  bannière verte du login `Your password has been changed. You can sign in.` En base :
  mot de passe re-haché (bcrypt), `mustChangePassword=false`, jeton tamponné `usedAt` →
  **rejouer le même lien** retombe sur le refus 3 (usage unique prouvé). Croiser B8/B9.

- **B13 Portée publique & filtre de changement forcé** [D]/[P] : `/ui/forgot` et
  `/ui/reset` sont atteignables **anonymement** — non pas via `public.paths` (qui reste
  **littéralement** `/ui/login,/ui/base.css,/ui/auth.css`, B4 intact) mais parce que les
  endpoints sont `@PermitAll` et qu'aucune politique `authenticated` ne couvre `/ui/*` ;
  les deux pages ne chargent que `base.css`+`auth.css` (publiques) → rendu correct sans
  session. En mode changement forcé [P], `PasswordChangeFilter.ALLOWED_PREFIXES` inclut
  désormais `/ui/forgot` et `/ui/reset` (aux côtés de `/ui/password`, `/ui/logout`,
  `/ui/login`) : un admin `mustChangePassword=true` n'est **pas** rebondi 303
  `/ui/password` s'il ouvre ces écrans. Croiser B4/B7.

---

## Groupe O — UI d'administration (extension O8 → O10)

L'onglet **Imports** regroupe les 7 imports CSV derrière un seul écran (`/ui/imports`,
`@RolesAllowed VIEWER/MANAGER/ADMIN`, écriture réservée ADMIN). Il ne duplique pas la
machinerie : il **pilote** les mêmes `*CsvResource` que les endpoints bruts `/*/import`
(fallback étagé, checksum, repli transactionnel — groupes D/E inchangés). Cinématique
**POST → 303 → notice one-shot** : un reload ne réimporte jamais.

- **O8 Onglet Imports — écran & garde d'écriture** : le lien de nav `Imports`
  (inconditionnel, tous rôles authentifiés, après `Valuations`) ; `GET /ui/imports` rend
  `.page-head` `h1` `Imports`, sous-titre `Bulk CSV import, staged fallback
  1000→100→10→1, checksum no-op on unchanged rows.`, une `.card` titrée `Drop a CSV file`
  et un `.card-sub` d'ordre conseillé **exposé** : `Recommended order — each domain
  references the ones declared before it: Stores → Store groups → Products → Product
  families → Categories → Prices → Offers.` ; le `<select name="domain">` liste **7**
  options numérotées, ordre imposé : `1. STORES (referential stores)`,
  `2. STORE_GROUPS (store hierarchy)`, `3. PRODUCTS (referential products)`,
  `4. PRODUCT_FAMILIES`, `5. CATEGORIES (product category storages)`, `6. PRICES`,
  `7. OFFERS` (labels `Domain`, `File (pipe-delimited)`, bouton `Import`). Garde
  d'écriture : **VIEWER/MANAGER** → formulaire absent, note `placeholder-note`
  `Admin rights are required to run an import.` ; **ADMIN** → formulaire présent
  (`canWrite = isUserInRole(ADMIN)`, doublé côté serveur par `@RolesAllowed`).

- **O9 Exécution d'un import via l'onglet** : `POST /ui/imports/run` (**ADMIN**,
  `multipart/form-data`, parts `domain` TEXT_PLAIN + `file` OCTET_STREAM) → dispatch
  `switch(domain)` vers le bon importeur (`importStores`, `importHierarchy`,
  `importProducts`, `importProductFamilies`, `importCategoryStorages`, `importPrices`,
  `importOffers`) → **303** `/ui/imports?notice=<msg>&noticeOk=<bool>`. Le rapport JSON de
  l'importeur est **résumé côté serveur** en une phrase (le détail ligne-à-ligne reste
  dans les logs) :
  - succès sans erreur → `<DOMAIN> — N created, M updated` (`noticeOk=true`,
    bannière `alert-ok`) ;
  - avec échecs isolés (présence de `"errors"`) →
    `<DOMAIN> — N created, M updated (with errors — see logs)` ;
  - fichier ou domaine manquant → `No file or domain was selected.` (`noticeOk=false`) ;
  - exception → `Import failed: <message>` (`noticeOk=false`) ;
  - domaine inconnu (POST forgé hors `<select>`) → l'importeur renvoie **400**
    `{"error":"Unknown domain '<domain>'"}`, résumé en `<domain> — ? created, ? updated`
    (`extract` renvoie `?` si le champ est absent, `noticeOk=false`).
  Un reload de `/ui/imports` **ne rejoue pas** l'import (la notice est portée par l'URL,
  pas par un re-POST). **VIEWER/MANAGER** → `POST /run` **403** (croiser C4/D9 : les
  endpoints bruts restent ADMIN eux aussi). L'onglet réutilisant les `*CsvResource`, toute
  la mécanique D1–D8 (JSON malformé D3, repli étagé D5, doublon D7, idempotence D6)
  s'observe **à l'identique** derrière lui — à ne pas re-spécifier, seulement à croiser.

- **O10 Drag & drop et présélection du domaine** [W] : déposer un fichier n'importe où sur
  `.import-drop` le place dans l'`input[type=file]` et **présélectionne** le domaine par
  nom de fichier via une table ordonnée, **première correspondance gagne** :
  `store-group`→`STORE_GROUPS` **avant** `store`→`STORES`, `famil`→`PRODUCT_FAMILIES` et
  `categ`→`CATEGORIES` **avant** `product`→`PRODUCTS`, puis `price`→`PRICES`,
  `offer`→`OFFERS` (les seeds `stores.csv`…`offers.csv` tombent tous juste ;
  `product-families.csv` → `PRODUCT_FAMILIES` car `famil` testé avant `product`). Classe
  `is-dragover` posée sur `dragenter`/`dragover`, retirée sur `drop`/`dragleave` (le
  `dragleave` ne se déclenche que si la cible sort réellement de la zone). L'upload reste
  un **POST de formulaire classique — aucun AJAX** ; l'événement `change` de l'input
  présélectionne aussi. Hint littéral : `Drag & drop the file anywhere onto this frame —
  the domain is preselected from its name (stores.csv → STORES, offers.csv → OFFERS,
  etc.).`

---

## Additions à l'inventaire Q (ajouts, aucune renumérotation)

Nouvelles puces à insérer dans **Q-E (UI — messages de page & bandeaux)** — elles
n'altèrent aucune entrée existante :

- **Forgot / Reset** : lien login `Forgot password?` · bannière `If an account matches
  this address, a reset link has just been sent to it. It is valid for 30 minutes and can
  be used once.` · boutons `Send the link` / `Change the password` · labels
  `Your e-mail address` / `New password (at least 8 characters)` / `Confirm the password`
  · retour `← Back to sign in` · refus reset `The two passwords do not match.` /
  `This reset link is invalid.` / `This reset link is invalid, already used or expired.
  Please request a new one.` · succès (notice login) `Your password has been changed. You
  can sign in.` · titres `Forgot password — Valuation admin` / `New password — Valuation
  admin` · mail sujet `Valuation admin — reset your password`.
- **Imports** : nav `Imports` · sous-titre `Bulk CSV import, staged fallback
  1000→100→10→1, checksum no-op on unchanged rows.` · titre carte `Drop a CSV file` ·
  ordre conseillé `Recommended order — each domain references the ones declared before it:
  Stores → Store groups → Products → Product families → Categories → Prices → Offers.` ·
  `Admin rights are required to run an import.` · notice `<DOMAIN> — N created, M updated`
  / `… (with errors — see logs)` · `No file or domain was selected.` ·
  `Import failed: <msg>` · 400 `{"error":"Unknown domain '<d>'"}`.

---

## Dérives détectées sur le catalogue existant (recalibrage, pour arbitrage)

Le **déplacement** de l'import d'offres (de `/ui/offers` vers `/ui/imports`) et l'ajout de
la nav rendent trois entrées existantes partiellement obsolètes. Elles ne sont **pas**
réécrites ici (ids gelés) ; à trancher :

- **C5 (Parcours VIEWER en UI)** — l'assertion « pas de bouton `Import CSV` » sur
  `/ui/offers` est désormais **sans objet** : le bouton `#import-trigger` et le formulaire
  multipart caché ont été retirés de `list.html` pour **tous** les rôles ; le contrôle
  d'écriture d'import vit maintenant sur `/ui/imports` (garde ADMIN, note `Admin rights
  are required to run an import.`), et un onglet `Imports` apparaît dans la nav de tout
  utilisateur authentifié. À recalibrer via **O8**.
- **O4 (Import depuis l'UI [W])** — entièrement porté par l'ancien flux liste-offres
  (auto-submit à la sélection, `POST /ui/offers/import`, notices `No file was selected.` /
  `Import completed: {json}` / `Import failed: {msg}`) : ce chemin **n'existe plus**.
  Remplacé fonctionnellement par **O9/O10** (select domaine + fichier bouton `Import`,
  `POST /ui/imports/run`, notices `<DOMAIN> — …`). À marquer « obsolète, voir O9 ».
- **Q-E → « Offres »** — les libellés `No file was selected.` et `Import completed:
  <json>` provenaient de `OfferUiResource.importCsv` (**méthode supprimée**) ; ils ne sont
  plus émis par l'écran offres. Remplacés par les libellés Imports ci-dessus.
- **B7 (Changement forcé) [P]** — la liste `ALLOWED_PREFIXES` du filtre a grossi de deux
  entrées (`/ui/forgot`, `/ui/reset`) ; l'énumération citée par B7 est à compléter (voir
  **B13**).

> Non impactés (vérifiés) : B4 reste **littéralement** vrai (`public.paths` inchangé) ;
> B5, D1–D9, E1–E7, F, P5–P7 portent sur les endpoints CSV bruts (`/*/import`, Basic,
> ADMIN, checksum, volumétrie), **réutilisés sans modification** par l'onglet.

## Écarts code ↔ `reports/forgot-password-port.md` (à arbitrer avant de figer)

Deux affirmations du rapport de port **ne correspondent pas** au code committé — à trancher
car elles conditionnent les tags d'infrastructure des scénarios B10–B13 :

1. **`public.paths` — divergence.** Le rapport (§2, §3) affirme avoir ajouté
   `/ui/forgot,/ui/reset` à `quarkus.http.auth.permission.public.paths`. La valeur
   committée (ligne 55) reste `/ui/login,/ui/base.css,/ui/auth.css`. **Impact
   fonctionnel : nul** — les endpoints étant `@PermitAll` et aucune politique
   `authenticated` ne couvrant `/ui/*`, les pages restent atteignables anonymement (d'où
   la formulation de B13). Mais la doc et le code divergent : soit corriger le rapport,
   soit ajouter réellement les chemins. **Recommandation : figer le comportement observé
   (B13), aligner le rapport.**
2. **Mailer / SMTP — configuration absente.** Le rapport (§2) mentionne un bloc mailer
   `%prod` sur secrets `${VALUATION_…}`. `grep -niE "mailer|smtp|mail" application.
   properties` est **vide**. En dev/test, Quarkus **auto-mocke** le mailer (`MockMailbox`)
   → le flux B10–B12 est exécutable [D] tel quel. En **prod**, sans configuration SMTP,
   `mailer.send` échouerait : comme `requestReset` est `@Transactional` et **ne rattrape
   pas** l'exception, `POST /ui/forgot` renverrait alors **500** au lieu du 303 neutre —
   l'anti-énumération tomberait. À **arbitrer** avant tout scénario [P] du reset : provisionner
   un SMTP prod, ou documenter la limite. (Le TTL « 30 minutes » est par ailleurs codé en
   dur dans `forgot.html` : dérive d'affichage si `valuation.reset.token-ttl-minutes`
   change.)

---

## Récapitulatif

- **7 scénarios ajoutés** : B10, B11, B12, B13 (mot de passe oublié) · O8, O9, O10
  (onglet Imports).
- **2 puces Q-E ajoutées** (Forgot/Reset, Imports) — ~30 libellés littéraux nouveaux.
- **4 recalibrages** d'entrées gelées (C5, O4, Q-E Offres, B7) — signalés, non réécrits.
- **2 écarts code ↔ port report** (public.paths, mailer SMTP) — remontés pour arbitrage.
- **Fidélité** : hors périmètre (aucun code dans `imvaluation`).
- Ids existants A1…P8 : **intacts**. Aucun test touché.
