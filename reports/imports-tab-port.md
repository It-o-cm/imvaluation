# Port de l'onglet Imports (imfid → imvaluation)

Report du port de l'écran **Imports** depuis le donneur `imfid` (référence, lecture
seule absolue) vers le receveur `imvaluation`. Aucun test écrit, aucun commit. Seule
vérification exécutée : `mvn -q test-compile` → **exit 0** (arbre laissé en l'état
pour relecture manuelle).

---

## 1. Inventaire du donneur (imfid)

Écran « Imports » tel que pratiqué par imfid — relevé avant toute écriture.

| Élément | Emplacement (imfid) | Ce que c'est |
|---|---|---|
| Entrée de navigation | `templates/ui/layout.html`, bloc `<nav class="app-nav">` | Lien statique `<a href="/ui/imports">Imports</a>`, inconditionnel (tout utilisateur authentifié le voit ; l'écriture est gardée dans l'écran). Nav non data-driven. |
| Resource UI | `ui/ImportUiResource.java` | `@Path("/ui/imports")`, `@RunOnVirtualThread`, classe `@RolesAllowed(ROLE_FID_ADMIN)`. `GET /ui/imports` → Qute `Templates.imports(notice, noticeOk, canWrite)`. `POST /ui/imports/run` `@Consumes(MULTIPART_FORM_DATA)` lit `MultipartFormDataInput` (parts `domain` + `file`), `dispatch()` sur un `switch(domain)`, puis **POST → 303 → notice** (un reload ne réimporte jamais). |
| Template | `templates/ImportUiResource/imports.html` | `{#include ui/layout}` + `{#title}` + `{#body}`. Un `.page-head`, l'alerte `{#if notice}`, une `.card` contenant **un seul** formulaire (`.import-drop`) : un `<select name="domain">` de 9 domaines + un `<input type="file">`. Gardé par `{#if canWrite}…{#else}` note « droit requis ». |
| Liste des domaines / ordre conseillé | options du `<select>` + noms de seeds numérotés (`01-products.csv`…) | **Pas** de section « ordre recommandé » séparée : l'ordre est porté implicitement par l'ordre des options et par les préfixes numériques des fichiers de seed. |
| Compte rendu d'import | `ImportUiResource.summarize()` / `extract()` | Le rapport JSON de l'importeur est **résumé côté serveur** en une phrase (`X créé(s), Y mis à jour` + `(avec erreurs — voir logs)` si présence d'un tableau `errors`) affichée en notice one-shot. Pas de rendu ligne-à-ligne des échecs dans le navigateur. |
| Échecs isolés (fallback) | `imports/ImporterCsvResource.java` | Fallback étagé `1000→100→10→1` ; à la taille 1, chaque ligne dans sa propre transaction (`processLineByLine`), une ligne fautive n'arrête pas les autres, son message est empilé dans `errors`. Forme du rapport : `{"createdCount":N,"updatedCount":M,"errors":[…]}` (clé `errors` omise si vide). |
| JS | `META-INF/resources/ui/fidelity.js` (2ᵉ IIFE) | Drag & drop sur `.import-drop` : un fichier lâché atterrit dans l'input, et le domaine est présélectionné par nom de fichier via une table `DOMAINS` ordonnée (première correspondance gagne ; `famil` testé avant `product`). Pas d'AJAX : l'upload est un POST de formulaire classique. |
| Sécurité | `application.properties` + `AppUser` | Écran + endpoints réservés `fid-admin`. Navigateur en form-auth, machines en Basic ; les endpoints CSV bruts (`/products/import`, `/fidelity/*`…) sont en `policy=fid-admin-policy` `auth-mechanism=basic`. `UiSupport.canWrite(sc)` double le contrôle côté template. |
| Thème / gabarit | `templates/ui/layout.html` + `META-INF/resources/ui/{base,auth,fidelity}.css` | Section `/* Imports */` de `fidelity.css` = `.import-drop` + `.import-drop.is-dragover` ; tout le reste (`.card`, `.field-grid`, `.input-control`, `.alert`, `.btn`…) est partagé, dans `base.css`. |

**Constat clé** : imvaluation et imfid partagent le **même** `base.css` (mêmes variables
`--im-red`, `--radius`, mêmes classes `.card` / `.field-grid` / `.input-control` /
`.form-actions` / `.alert` / `.placeholder-note` / `.page-head`). Seules `.import-drop`
et `.import-drop.is-dragover` manquaient côté receveur. Le port est donc quasi 1:1 sur
le HTML/CSS.

---

## 2. Repris tel quel / adapté (et pourquoi)

### Repris tel quel (logique inchangée)
- **Machinerie d'import** : aucune ligne réécrite. Les 7 `*CsvResource` existants
  d'imvaluation (`ImporterCsvResource` + fallback étagé + checksum) sont **réutilisés**
  par injection CDI ; l'onglet ne fait que les piloter.
- **Cinématique POST → 303 → notice** : identique au donneur (un reload ne réimporte pas).
- **`summarize()` / `extract()`** : logique de résumé du rapport JSON reprise à
  l'identique (parsing de `createdCount`/`updatedCount`, drapeau sur présence de `errors`).
- **Drag & drop + présélection par nom de fichier** : algorithme identique (table
  ordonnée, première correspondance gagne).
- **Structure du template** : `{#include ui/layout}` + `{#title}` + `{#body}` +
  `.page-head` / `.card` / `.field-grid` / `{#if canWrite}` — même squelette.

### Adapté au receveur (et pourquoi)
| Adaptation | Pourquoi |
|---|---|
| **Domaines = ceux d'imvaluation** : STORES, STORE_GROUPS, PRODUCTS, PRODUCT_FAMILIES, CATEGORIES, PRICES, OFFERS (7). | L'inventaire des importeurs CSV du receveur fait foi ; les domaines fidélité du donneur (RULES, COMMUNITIES, ACCOUNTS…) n'existent pas ici. |
| **Ordre d'import EXPOSÉ** : options numérotées `1.`…`7.` + phrase « Recommended order — Stores → Store groups → … → Offers » dans une `.card-sub`. | Consigne : « l'onglet expose TOUS les domaines dans l'ordre d'import conseillé ». Le donneur laissait l'ordre implicite ; le receveur a un ordre imposé (CLAUDE.md, seed e2e) qu'on rend explicite. |
| **`dispatch()` mappe les vrais noms de méthodes** : `importStores`, `importHierarchy` (store-groups), `importProducts`, `importProductFamilies`, `importCategoryStorages`, `importPrices`, `importOffers`. | Signatures réelles des `*CsvResource` du receveur (vérifiées par grep). |
| **Multipart en bean `@MultipartForm ImportUpload`** (`@FormParam("domain")` TEXT_PLAIN + `@FormParam("file")` OCTET_STREAM) au lieu de `MultipartFormDataInput`. | C'est le gabarit du receveur : `OfferUiResource` utilise déjà `@MultipartForm` + `@PartType`. On colle à sa convention plutôt qu'au style resteasy-classic du donneur. |
| **Rôles du receveur** : classe `@RolesAllowed({VIEWER, MANAGER, ADMIN})`, `/run` `@RolesAllowed(ADMIN)`, `canWrite = isUserInRole(ADMIN)` inline. | Convention d'imvaluation (écran atteignable par tout rôle, écriture réduite à ADMIN, form gardé par `canWrite`) — même schéma que `OfferUiResource`. `UiSupport` n'existe pas côté receveur : `canWrite` et le redirect sont inlinés comme dans `OfferUiResource.redirectWithNotice`. |
| **Notices en anglais** : `N created, M updated (with errors — see logs)`, `No file or domain was selected.`, `Import failed: …`. | L'IHM d'imvaluation est en anglais (CLAUDE.md : « Code and comments in English ») ; le donneur était en français. |
| **CSS `imports.css` dédié** (juste `.import-drop` + `.is-dragover`), lié dans `layout.html`. | Convention receveur « un CSS par écran » (`offer.css`, `user.css`…) ; les variables `--im-line`/`--radius`/`--im-red` utilisées existent déjà dans `base.css`. On n'édite pas `base.css`. |
| **JS `imports.js` dédié** (uniquement l'IIFE drag & drop), inclus en bas du template. | Convention receveur « un JS par écran » ; on n'empile pas dans un fichier partagé (le donneur mêlait l'IIFE à un rendu de code-barres EAN-13 sans rapport). Table `DOMAINS` recalibrée sur les seeds du receveur (`store-groups` avant `store`, `famil` et `categ` avant `product`, `price`, `offer`). |
| **Nav** : `<a href="/ui/imports">Imports</a>` ajouté après « Valuations », inconditionnel. | Aligné sur les autres onglets non réservés-admin du receveur (Offers/Store groups/Valuations sont inconditionnels ; seul Users est gardé `{#if admin}`). Un non-admin voit l'écran avec la note « Admin rights are required ». |

---

## 3. Fichiers créés / modifiés / supprimés (exhaustif)

### Créés (4)
- `src/main/java/com/intermarche/valuation/ui/ImportsUiResource.java` — la resource de l'onglet (GET écran + POST `/ui/imports/run`, dispatch des 7 domaines, résumé du rapport, bean `ImportUpload`).
- `src/main/resources/templates/ImportsUiResource/imports.html` — le template Qute (ordre conseillé exposé, formulaire domaine+fichier, alerte notice, note « admin required »).
- `src/main/resources/META-INF/resources/ui/imports.css` — `.import-drop` + `.import-drop.is-dragover` (le reste des classes est partagé via `base.css`).
- `src/main/resources/META-INF/resources/ui/imports.js` — l'IIFE drag & drop + présélection du domaine par nom de fichier (table `DOMAINS` du receveur).

### Modifiés (5)
- `src/main/resources/templates/ui/layout.html` — ajout du lien de nav `Imports` (après « Valuations ») **et** du `<link rel="stylesheet" href="/ui/imports.css">`.
- `src/main/java/com/intermarche/valuation/ui/OfferUiResource.java` — **retrait de l'import d'offres esseulé** : suppression de la méthode `importCsv`, de la classe interne `OfferCsvUpload`, du helper `redirectWithNotice` (seul appelant : `importCsv`), du champ injecté `csvResource` (`OfferCsvResource`) et des imports devenus inutiles (`OfferCsvResource`, `MultipartForm`, `PartType`, `java.io.InputStream`).
- `src/main/resources/templates/OfferUiResource/list.html` — retrait du bouton `Import CSV` (`#import-trigger`) de la barre d'actions et du formulaire multipart caché (`#import-form`). `Export CSV` et `New offer` conservés.
- `src/main/resources/META-INF/resources/ui/list-filters.js` — retrait de la 2ᵉ IIFE « CSV import trigger » (câblage `#import-trigger`/`#import-file`/`#import-form`). L'IIFE d'autocomplétion des filtres est conservée.
- `src/test/java/com/intermarche/valuation/ui/OfferUiResourceTest.java` — **conséquence obligatoire du retrait** (sinon `test-compile` casse) : suppression des 5 cas `importCsv*` (qui référençaient `importCsv` / `OfferCsvUpload`), de l'affectation `resource.csvResource = csv` dans le helper `resource(...)` (signature conservée, param `csv` désormais ignoré → 0 appelant touché), de l'import inutilisé `ByteArrayInputStream`, et mise à jour de deux mentions désormais fausses dans le Javadoc de classe. Aucun test ajouté.

### Supprimés (0 fichier entier)
- L'import d'offres esseulé n'était pas un fichier autonome mais des **fragments** répartis dans `OfferUiResource.java`, `OfferUiResource/list.html` et `list-filters.js` : ils ont été retirés par édition chirurgicale (voir « Modifiés »). L'endpoint HTTP sous-jacent `OfferCsvResource` (`/offers/import`) est **conservé** et réutilisé par le nouvel onglet.

---

## 4. Entrées d'e2e-scenarios.md impactées par le déplacement de l'import d'offres

Sans toucher au catalogue — une ligne chacune, les seules entrées que le **déplacement**
du parcours d'import d'offres (de `/ui/offers` vers `/ui/imports`) rend obsolètes ou à
recalibrer :

- **C5 (Parcours VIEWER en UI)** — l'assertion « pas de bouton `Import CSV` » sur `/ui/offers` devient sans objet (le bouton n'existe plus pour personne) ; le contrôle d'écriture d'import est désormais sur `/ui/imports`, et un onglet `Imports` apparaît dans la nav.
- **O4 (Import depuis l'UI [W])** — scénario entièrement porté par l'ancien flux liste-offres (auto-submit à la sélection, `POST /ui/offers/import`, notices `No file was selected.` / `Import completed: {json}` / `Import failed: {msg}`) : à réécrire pour le nouvel onglet (select domaine `OFFERS` + fichier, `POST /ui/imports/run`, notice `OFFERS — N created, M updated`, garde vide `No file or domain was selected.`).
- **Q-D → « Offres » (catalogue des messages UI littéraux)** — les libellés `No file was selected.` et `Import completed: <json>` provenaient de `OfferUiResource.importCsv` (supprimé) ; ils ne sont plus émis par l'écran offres et sont remplacés par ceux de l'écran Imports.

> Non impactés (vérifiés) : B5, D6, D9 et toute la section D/E ainsi que P5–P7 portent sur
> les **endpoints CSV bruts** (`/*/import`, Basic auth, rôle ADMIN, checksum, volumétrie),
> inchangés — l'onglet réutilise ces endpoints sans les modifier.
