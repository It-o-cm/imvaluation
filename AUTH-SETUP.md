# Form authentication setup

## 1. `application.properties`

Replace the basic-auth line with form authentication:

```properties
# Remove:
# quarkus.security.basic.enabled=true

quarkus.http.auth.form.enabled=true
quarkus.http.auth.form.login-page=/ui/login
quarkus.http.auth.form.error-page=/ui/login?error=true
quarkus.http.auth.form.landing-page=/ui/offers
quarkus.http.auth.form.post-location=/j_security_check
quarkus.http.auth.form.username-parameter=j_username
quarkus.http.auth.form.password-parameter=j_password

# The cookie is signed with this key; set a real one outside development.
quarkus.http.auth.session.encryption-key=change-me-to-a-long-random-string-at-least-32-chars

# Let anonymous requests reach the login page and the static assets.
quarkus.http.auth.permission.public.paths=/ui/login,/ui/base.css,/ui/auth.css,/ui/offer.css,/ui/user.css,/ui/schema-form.js,/ui/list-filters.js
quarkus.http.auth.permission.public.policy=permit
```

Keep the bootstrap overrides if you set them:

```properties
valuation.bootstrap.admin.username=admin
valuation.bootstrap.admin.password=admin
```

Note: the CSV import client (`OfferImporterClient`) posts with a Basic `Authorization`
header. Form authentication does not accept that. Either keep basic auth enabled
alongside (`quarkus.security.basic.enabled=true` works together with form auth) or adapt
the client to sign in first and reuse the session cookie.

## 2. File placement

| File | Destination |
|---|---|
| `AuthUiResource.java` | `ui/` |
| `PasswordChangeFilter.java` | `security/` |
| `auth-login.html` | `templates/AuthUiResource/login.html` |
| `auth-password.html` | `templates/AuthUiResource/password.html` |
| `layout.html` | `templates/ui/` |
| `users-list.html` | `templates/UserUiResource/list.html` |
| `users-form.html` | `templates/UserUiResource/form.html` |
| `form.html` | `templates/OfferUiResource/` |
| `AppUser.java` | `domain/` |
| `UserBootstrap.java`, `UserUiResource.java` | `security/`, `ui/` |
| `base.css`, `auth.css`, `offer.css`, `user.css` | `META-INF/resources/ui/` |

## 3. Screens

| URL | Access | Purpose |
|---|---|---|
| `GET /ui/login` | anonymous | Sign-in form, posts to `/j_security_check` |
| `POST /ui/logout` | authenticated | Clears the session cookie |
| `GET /ui/password` | authenticated | Change own password |
| `POST /ui/password` | authenticated | Applies the change, forces a new sign-in |

## 4. Password rules

- Minimum 8 characters, no other constraint.
- Changing a password requires the current one, even when already signed in.
- The new password must differ from the current one.
- No lockout after failed attempts.

## 5. Forced change

`mustChangePassword` is set when:

- the bootstrap account is created (its password comes from the configuration);
- an administrator creates an account;
- an administrator sets a new password on an existing account.

While the flag is set, `PasswordChangeFilter` redirects every request to `/ui/password`,
except the password screen itself, sign-out, the login page and `/j_security_check`.

The user list shows a "Password pending" badge for accounts in that state.

## 6. Schema note

`AppUser` gains a `must_change_password` column. In dev this is handled by
`drop-and-create`; elsewhere add the column before deploying.
