# Port du mécanisme « mot de passe oublié » (imfid → imvaluation)

Portage du parcours de réinitialisation en libre-service tel qu'implémenté dans
`imfid` (donneur, lecture seule) vers `imvaluation` (receveur), en adaptant au
receveur : package `com.intermarche.valuation`, entité `AppUser`, amorçage
`admin/admin` + `mustChangePassword`, page `/ui/login` (templates Qute), thème et
conventions du `CLAUDE.md`. **Aucun test écrit, aucun commit.** Vérification :
`mvn -q test-compile` → **EXIT=0** (sources principales et de test).

---

## 1. Inventaire du donneur (imfid)

Parcours en libre-service par e-mail : l'utilisateur saisit son adresse, reçoit un
lien à usage unique et limité dans le temps, puis choisit un nouveau mot de passe.

**Fichiers participant au mécanisme (imfid) :**

| Rôle | Chemin |
|---|---|
| Ressource UI JAX-RS (écrans + POST) | `ui/AuthUiResource.java` |
| Service métier + mailer | `security/PasswordResetService.java` |
| Entité jeton (table `password_reset_tokens`) | `domain/PasswordResetToken.java` |
| Entité utilisateur (hash, `email`, `findActiveByEmail`) | `domain/AppUser.java` |
| Page login (point d'entrée) | `templates/AuthUiResource/login.html` |
| Formulaire de demande | `templates/AuthUiResource/forgot.html` |
| Formulaire de réinitialisation | `templates/AuthUiResource/reset.html` |
| Abstraction d'horloge | `domain/util/DateTimeProvider.java` |
| Amorçage compte admin + e-mail | `security/SecurityBootstrap.java` |
| Config (paths publics, TTL, mailer/SMTP) | `application.properties` |
| Dépendance | `pom.xml` → `quarkus-mailer` |

**Parcours / écrans** (Qute, autonomes, sans layout authentifié, style `login-*`) :

1. **Login** — lien « Mot de passe oublié ? » vers `/ui/forgot` ; bannière verte de
   confirmation après reset (`?reset=true`).
2. **Demande** (`/ui/forgot`) — champ e-mail unique, POST `/ui/forgot` ; message neutre
   anti-énumération affiché via `{#if sent}`.
3. **E-mail** — pas d'écran ; lien `{baseUrl}/ui/reset?token={rawToken}` envoyé par mail
   (mocké dans les logs hors production).
4. **Réinitialisation** (`/ui/reset?token=…`) — jeton en champ caché, mot de passe +
   confirmation, erreurs via `{#if error}`.
5. **Confirmation** — redirection `/ui/login?reset=true`.

**Endpoints** (`@Path("/ui")`, tous `@PermitAll`) :

| Verbe | Chemin | Rôle |
|---|---|---|
| GET | `/ui/forgot` | rend le formulaire ; `?sent` bascule la bannière |
| POST | `/ui/forgot` | traite la demande, redirige **toujours** vers `?sent=true` |
| GET | `/ui/reset` | rend le formulaire avec le jeton + erreur éventuelle |
| POST | `/ui/reset` | consomme le jeton, pose le mot de passe (PRG) |

Toutes les réponses POST sont des **303 See Other** (Post/Redirect/Get) : l'issue n'est
jamais dans le corps. La correspondance des deux saisies est vérifiée dans la ressource ;
tout le reste est délégué au service.

**Matérialisation & expiration** — entité `PasswordResetToken` (table
`password_reset_tokens`, index sur `token_hash` et `user_id`) : `@ManyToOne AppUser user`,
`tokenHash` (SHA-256 hex, `unique`, 64 car. — jamais le jeton brut), `expiresAt`, `usedAt`
(nullable ⇒ à usage unique). `isUsableAt(now)` = non consommé **et** non expiré.
`findByHash`, `deletePendingFor` (au plus un lien vivant par compte). TTL config
`imfid.reset.token-ttl-minutes` (défaut **30 min**). Jeton = 32 octets `SecureRandom` →
64 hex. **Pas de purge planifiée** (uniquement suppression des jetons en attente à chaque
nouvelle demande).

**Intégration login** — lien dans `login.html`, bannière de retour via `?reset=true`,
chemins déclarés dans `quarkus.http.auth.permission.public.paths`.

**Sécurité** — `SecureRandom` 256 bits ; stockage du **seul** hash SHA-256 ; expiration +
usage unique ; **anti-énumération** (compte inconnu / sans e-mail / inactif → rien envoyé,
réponse neutre identique ; `findActiveByEmail` exclut les comptes désactivés) ; mot de
passe re-validé par `AppUser.validatePassword` (≥ 8 car.) ; hash bcrypt via
`BcryptUtil` ; les deux méthodes du service sont `@Transactional`. **Absences** (héritées,
non corrigées) : pas de rate-limiting, pas de protection CSRF, pas de purge des jetons
expirés.

**Service / livraison** — `PasswordResetService` (`@ApplicationScoped`) injecte
`io.quarkus.mailer.Mailer`. `requestReset` : résout le compte actif par e-mail, invalide
les jetons en attente, stocke un jeton frais haché, envoie le lien. `resetPassword` :
valide/consomme le jeton, pose le mot de passe, remet `mustChangePassword=false`, tamponne
`usedAt`. Hors production le mailer Quarkus est mocké → le lien est écrit dans les logs
(parcours exécutable sans SMTP).

---

## 2. Transplantation adaptée au receveur

Le **contrat fonctionnel est conservé à l'identique** ; le code est natif d'imvaluation.

**Repris tel quel (logique identique) :**
- Entité `PasswordResetToken` : mêmes champs, `isUsableAt`, `findByHash`,
  `deletePendingFor`, checksum par `username`. Étend le `BaseEntity` **d'imvaluation**
  (donc `version`/`createdAt`/`updatedAt`/`checksum` automatiques).
- `PasswordResetService` : jeton `SecureRandom` 32 o, hash SHA-256 hex, TTL, anti-
  énumération, usage unique, `@Transactional`, mailer mocké hors prod. Utilise le
  `DateTimeProvider` déjà présent dans `domain.util` d'imvaluation.
- Endpoints `AuthUiResource` : `forgot`/`requestReset`/`reset`/`doReset` + helper
  `redirectToReset`, mêmes signatures et sémantique PRG.
- Templates `forgot.html` / `reset.html` : mêmes classes CSS `login-panel` / `login-form`
  (déjà définies dans `auth.css` d'imvaluation), même structure.

**Adapté au receveur (et pourquoi) :**
- **Langue → anglais.** L'UI d'imvaluation et le `CLAUDE.md` imposent l'anglais ; imfid
  était en français. Tous les textes visibles et le corps de l'e-mail sont en anglais
  (« Forgot password? », « This reset link is invalid… », « Valuation admin — reset your
  password »).
- **Bannière de confirmation → mécanisme `notice` natif.** imfid utilise un drapeau
  booléen `reset` sur la page login. imvaluation possède déjà un paramètre `notice`
  (chaîne, `alert-ok`) réutilisé par le logout. Le reset réussi redirige donc vers
  `/ui/login?notice=Your password has been changed. You can sign in.` → **aucune
  modification de la signature `login()`** ni du modèle de la page.
- **Clés de configuration → `valuation.*` / `VALUATION_*`.** `valuation.reset.token-ttl-
  minutes`, `valuation.bootstrap.admin.email`, secrets SMTP en `${VALUATION_…}`.
- **Champ `email` sur `AppUser`.** imvaluation n'en avait pas. Ajout d'un `@Column(email,
  length 190)` nullable, de `findActiveByEmail` (insensible à la casse, exclut les comptes
  inactifs) et inclusion de `email` dans `getChecksum` (cohérence avec la détection de
  changement existante). Javadoc au style d'imvaluation.
- **E-mail du compte d'amorçage.** `UserBootstrap` pose désormais `admin.email` depuis
  `valuation.bootstrap.admin.email` (défaut `admin@valuation.local`) : l'administrateur
  initial peut utiliser le mot de passe oublié dès le premier démarrage.
- **`PasswordChangeFilter`.** Ajout de `/ui/forgot` et `/ui/reset` aux `ALLOWED_PREFIXES`
  (cohérence avec le filtre existant ; ces écrans doivent rester atteignables).
- **Chemins publics.** Ajout de `/ui/forgot,/ui/reset` à
  `quarkus.http.auth.permission.public.paths` (`/ui/auth.css` y était déjà, contrairement
  à imfid — les pages sont donc correctement stylées).
- **Dépendance `quarkus-mailer`** ajoutée au `pom.xml` (absente du receveur).

---

## 3. Fichiers créés et modifiés (liste exhaustive)

**Créés (4) :**
- `src/main/java/com/intermarche/valuation/domain/PasswordResetToken.java`
- `src/main/java/com/intermarche/valuation/security/PasswordResetService.java`
- `src/main/resources/templates/AuthUiResource/forgot.html`
- `src/main/resources/templates/AuthUiResource/reset.html`

**Modifiés (7) :**
- `pom.xml` — dépendance `quarkus-mailer`.
- `src/main/java/com/intermarche/valuation/domain/AppUser.java` — champ `email`,
  `findActiveByEmail`, `email` ajouté au checksum.
- `src/main/java/com/intermarche/valuation/security/UserBootstrap.java` — config
  `valuation.bootstrap.admin.email`, `admin.email` posé à l'amorçage.
- `src/main/java/com/intermarche/valuation/security/PasswordChangeFilter.java` —
  `/ui/forgot` et `/ui/reset` dans `ALLOWED_PREFIXES`.
- `src/main/java/com/intermarche/valuation/ui/AuthUiResource.java` — injection
  `PasswordResetService`, templates `forgot`/`reset`, endpoints
  `forgot`/`requestReset`/`reset`/`doReset` + `redirectToReset`.
- `src/main/resources/application.properties` — chemins publics `/ui/forgot,/ui/reset` ;
  bloc mot de passe oublié (TTL, mailer, SMTP `%prod`) ; e-mail d'amorçage.
- `src/main/resources/templates/AuthUiResource/login.html` — lien « Forgot password? ».

---

## 4. Écarts (une ligne chacun)

- **Édition de l'e-mail dans l'admin utilisateurs volontairement non portée** : la câbler
  changeait les signatures `save`/`update` et cassait `UserUiResourceTest` — or la consigne
  interdit d'écrire/toucher des tests ; l'e-mail est posé par l'amorçage (et modifiable en
  base), comme chez le donneur qui n'a d'ailleurs aucune UI d'administration des comptes.
- **Texte « 30 minutes » codé en dur** dans `forgot.html` (comme chez le donneur) : dérive
  possible si `valuation.reset.token-ttl-minutes` change.
- **Absences héritées non corrigées** : pas de rate-limiting, pas de CSRF, pas de purge
  planifiée des jetons expirés (identique au donneur).
- **Aucun test, aucun commit** : arbre laissé en l'état pour relecture manuelle ;
  `mvn -q test-compile` = EXIT 0.
- **Schéma** : `AppUser` gagne la colonne `email`, `password_reset_tokens` est une nouvelle
  table — pris en charge par `drop-and-create` en dev/test ; à provisionner ailleurs.
