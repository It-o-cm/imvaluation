# Remises manuelles et garde de marge — consignes au moteur

Un expert fonctionnel de la ligne de caisse a relevé, sur les remises, trois
exigences qui touchent le calcul du ticket. Le calcul appartient à ce service :
la caisse ne fait que décrire ses gestes, et l'évaluateur embarqué d'impos n'est
qu'un repli quand tu n'es pas joignable. Ce prompt dit ce qui te revient, et ce
qui ne te revient pas.

---

## 1. Ce qui existe déjà et qu'il ne faut surtout pas refaire

Avant d'écrire une ligne, relis `spec-srp-guard.md`. Le seuil de revente à perte
est **arbitré depuis le 26 septembre 2026** et codé : `SrpGuardFactory`,
`PriceUsage.SRP_FLOOR`, la prise en compte dans `BasketEvaluation`. Son principe
est explicite et il tient en une phrase :

> Le moteur ne connaît pas le prix d'achat, et personne ne veut le transporter.
> La gestion commerciale pousse directement le **plancher** — prix d'achat
> effectif × 1,10 — tout calculé, comme une ligne de prix.

Donc : pas de prix de revient en base, pas de colonne de coût dans un flux, pas
de calcul de marge. Le plancher est une ligne de prix d'usage `SRP_FLOOR`, avec
la validité, la priorité et les contrôles que le flux prix sait déjà faire, et
l'absence de ligne vaut absence de contrôle. Le garde passe en tout dernier de
l'arbitrage, `Integer.MAX_VALUE`, parce qu'un plancher est une borne absolue.

`spec-tiered-discount.md` existe aussi, mais il traite d'un **type d'offre**
promotionnel à paliers. Ce n'est pas le sujet ici : l'expert parle des paliers
d'une remise que la caissière saisit à la main. Les deux ne se confondent pas.

## 2. Ce qui ne te revient pas — la remise en euros

L'expert demande que la remise en euros soit **une remise à l'article et non sur
la ligne** : dix articles à 10 €, remise de 2 €, le client paie 80 €. Et un
onzième article identique scanné ensuite revient à 10 €, la remise ne collant
pas au produit.

Ton contrat ne change pas pour autant. Le champ `manualDiscountAmount` de la
ligne du panier vaut, et continuera de valoir, **des euros retirés du total de la
ligne**. C'est la caisse qui multiplie la remise unitaire par la quantité avant
de te l'envoyer, parce que c'est elle qui porte le geste de l'opérateur et sa
quantité. Redéfinir silencieusement la sémantique d'un champ existant casserait
tout appelant qui l'utilise correctement aujourd'hui.

Tu n'as donc rien à faire ici. C'est écrit pour que tu ne le fasses pas.

## 3. Ce qui te revient

### A · Dire POURQUOI une remise est bornée

Le garde SRP fait son travail : il empêche que le prix tombe sous le plancher.
Mais du point de vue de la caissière, une remise de 3 € qui n'en retire que
1,20 € est un bug tant que rien ne le lui dit. La loi qu'elle applique sans le
savoir mérite une phrase.

Ce que la réponse doit porter, pour la ligne concernée :

- que le geste manuel a été **écrêté** par le plancher, et non appliqué tel quel ;
- le **montant réellement accordé** et celui qui avait été demandé ;
- un **code de motif** stable, distinct de celui du garde EGAlim, que la caisse
  traduit en un message d'écran — jamais un texte français qui viendrait de toi.

La caisse affiche, elle ne juge pas. Elle a besoin d'un fait et d'un code.

### B · Les paliers d'alerte de la remise manuelle

L'expert demande « des paliers » et un avertissement quand la remise entame la
marge. Le plancher SRP est binaire : on est au-dessus ou en dessous. Entre le
prix de vente et le plancher, il y a de la place, et c'est là que les paliers
vivent.

Un palier est une **fraction de la descente vers le plancher** — par exemple 50 %
et 80 % de l'écart entre le prix de vente et le plancher — au-delà de laquelle la
réponse porte un avertissement sans rien écrêter. Configuration administrée,
ciblée PDV et groupes comme le reste, jamais globale ni codée en dur. Une ligne
sans plancher n'a pas de palier : sans plancher il n'y a pas d'échelle.

Trois niveaux et non deux, donc, dans ce que tu renvoies : accordé sans réserve,
accordé avec avertissement, écrêté. La caisse décidera de ce qu'elle en fait —
un simple bandeau, ou un aval superviseur.

### C · La dérogation « produit dégradé »

La vente à perte est interdite **sauf** sur un article dégradé et invendable. Il
faut donc pouvoir passer outre le plancher, et cette dérogation doit se voir.

Elle ne s'invente pas côté moteur : c'est la caisse qui la déclare, sur la ligne,
avec le motif choisi par l'opérateur sous aval superviseur. Ce que tu fournis est
la porte : un drapeau sur la ligne du panier qui **désactive le garde SRP pour
cette ligne**, et la trace de ce contournement dans la réponse, motif compris,
pour que le journal électronique et les exports financiers puissent le retrouver.
Un contournement muet serait pire que pas de garde du tout.

Note que `compliance.srpExempt` existe déjà pour l'exemption **de l'article**
(GB-01-06-10). Ce n'est pas la même chose : là il s'agit d'une dérogation
**ponctuelle, sur une ligne, dans un ticket**. Ne confonds pas les deux et ne
détourne pas la première.

## 4. Portée

- Tu ne traites que les points A, B et C du §3.
- Tu ne touches ni au prix d'achat — il n'entre pas dans ce service — ni à la
  sémantique de `manualDiscountAmount`.
- Tu ne refais pas le garde SRP : tu l'enrichis de ce qu'il ne dit pas encore.
- Toute évolution du contrat du panier ou de la réponse se documente **dans
  `spec-srp-guard.md`**, à sa suite, et jamais dans un fichier satellite.
- Un refactoring rencontré en chemin se signale en une ligne ; il ne se fait pas.
- Git : un commit, sur la branche courante. Jamais de branche, jamais de push.

## 5. Tests

- JUnit 5 + Mockito nu, selon `CLAUDE.md`. Jamais `@QuarkusTest`, jamais H2.
- **Chaque jambe de chaque expression booléenne composée a son cas de test**,
  nullité comprise : une ligne sans plancher, une ligne au plancher exact, une
  ligne sous le plancher, une ligne dérogée, et le croisement avec le garde
  EGAlim qui passe juste avant.
- Les scénarios e2e se déclarent dans `e2e-scenarios.md`, dans le groupe qui leur
  correspond, **dans le fichier existant**.
- `mvn verify` vert avant de rendre.

## 6. Ce que tu rends

Le contrat exact — champs ajoutés au panier et à la réponse, codes de motif, leur
sémantique — sous une forme que la caisse puisse consommer sans deviner. Plus, en
une ligne chacune, les questions restées ouvertes et ce que la caisse doit faire
de son côté pour que l'ensemble tienne.

## 7. Les deux questions restées sans réponse

Elles ne bloquent pas : prends l'hypothèse, écris-la dans la Javadoc en la
nommant comme telle, et signale-la.

- **La onzième unité.** Une ligne de dix articles remisés de 2 € l'unité passe à
  onze : l'unité ajoutée est-elle remisée elle aussi ? Hypothèse retenue — oui,
  la remise unitaire est une propriété de la ligne et suit sa quantité.
- **L'assiette des paliers.** Ils se comptent en fraction de la descente vers le
  plancher, et non en pourcentage du prix de vente ni en euros absolus — c'est la
  seule mesure qui ait le même sens sur un article à forte et à faible marge.
