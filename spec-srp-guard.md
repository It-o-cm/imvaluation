# Spécification — Garde SRP (seuil de revente à perte)

**Projet** : imvaluation · **Lignes RFP** : GB-01-06-09 (volet SRP resté en réserve du
1a EGAlim), GB-01-06-10 (exemption, via `compliance.srpExempt`) · **Statut** : arbitré
le 26/09/2026 — patron « EGAlim bis », plancher porté par le référentiel prix.

## 1. La donnée : `PriceUsage.SRP_FLOOR`

Le moteur ne connaît pas le prix d'achat, et personne ne veut le transporter. La
gestion commerciale de l'enseigne pousse directement le **plancher** (prix d'achat
effectif × 1,10), tout calculé, comme une ligne de prix :

- Nouvelle valeur d'enum `SRP_FLOOR` dans `PriceUsage` (à côté de `DEFAULT`,
  `BASE_FOR_DISCOUNT`).
- Transportée par le flux prix existant, avec tout ce qu'il sait déjà faire :
  validité temporelle, priorité, contrôle de cohérence HT/TTC/taux, checksum.
- Le plancher d'un article dans un PDV = la ligne `SRP_FLOOR` active la plus
  prioritaire. **Pas de ligne = pas de contrôle** pour cet article (fail-open
  données : la loi ne vise que l'alimentaire, l'enseigne ne pousse des planchers
  que là où ils s'appliquent).

## 2. Le garde `SRP_GUARD` — EGAlim bis

`AdvantageApplierFactory` canonique, mêmes principes que `EGALIM_GUARD` :
configuration en table `Offer`, **ciblée PDV/groupes, jamais globale**, activation
normale, `specification` vide (`{}`, schéma `additionalProperties:false` sans champ),
pas de fragments trigger/arbitration.

**Ordre en fin d'arbitrage** : le plancher est une borne absolue, il passe en tout
dernier — `SRP_GUARD` prend `Integer.MAX_VALUE` et `EGALIM_GUARD` passe à
`Integer.MAX_VALUE - 1` (constante modifiée et documentée : EGAlim corrige la
générosité, SRP verrouille le prix résultant). AT_TOTAL, panier fermé seulement.

## 3. Le contrôle — par ligne du panier initial

Pour chaque ligne (via `SourceLine`) dont l'article a un plancher :

1. `floor` = prix plancher unitaire TTC × quantité (BigDecimal, échelle 2).
2. `final` = net retenu de la ligne après TOUS les avantages (EGAlim compris —
   il vient de passer).
3. `final < floor` ⇒ correction = `floor − final`, remise **négative** qui remonte
   la ligne exactement au plancher. Les lignes remisées par une configuration
   `compliance.srpExempt` sont ignorées (soldes).
4. Une seule `DiscountApplication` par évaluation, type
   `"SRP Adjustment: <code>"`, détail par ligne, score 0.

**Record** : bloc de réponse `srp[]`, émis uniquement quand une configuration
`SRP_GUARD` est in-force pour le magasin — une entrée par ligne CONTRÔLÉE
(celles qui ont un plancher) : `{lineId, ean, floorPrice, finalPrice,
marginAboveFloor, correction?}`. Les lignes sans plancher n'apparaissent pas.

## 4. Cas normatifs

1. Ligne à plancher 11,00 €, remises portant le net à 10,00 € → correction +1,00 €,
   net final 11,00 €, record complet.
2. Net au-dessus du plancher → pas de correction, `marginAboveFloor` renseigné.
3. Article sans ligne `SRP_FLOOR` → non contrôlé, absent du record.
4. Configuration `srpExempt` (soldes) → ligne ignorée par le garde, tracée.
5. Interaction EGAlim : EGAlim corrige d'abord (34 %), SRP verrouille ensuite —
   les deux records cohérents sur le même ticket.
6. Magasin non ciblé / pas de configuration / panier ouvert → ni correction ni
   bloc `srp[]`, réponse bit à bit identique.
7. Flux prix : ligne SRP_FLOOR importée, remplacée (priorité), expirée ;
   cohérence TVA contrôlée comme les autres usages.
8. Déterminisme 50×.

**Impacts** : `PriceUsage` (+ import/GraphQL qui listent l'enum), `SrpGuardFactory`
+ test, constante de priorité d'`EgalimGuardFactory`, bloc `srp[]` à l'assemblage de
la réponse. **Rien d'autre.** Prérequis : `spec-divers-offre.md` (le bloc
`compliance`). **Règles dures habituelles.**

## 5. Extension — écrêtage tracé, paliers d'alerte, dérogation ligne (arbitré le 30/09/2026)

Trois enrichissements du garde SRP (le calcul appartient au moteur ; la caisse décrit
ses gestes). Le garde n'est **pas** refait : il gagne ce qu'il ne disait pas encore.
`manualDiscountAmount` **ne change pas de sémantique** (des euros retirés, la caisse
porte la quantité) ; le prix d'achat **n'entre jamais** dans ce service.

### 5.A · Dire POURQUOI une remise est bornée (GB — écrêtage)

Sur une ligne remontée au plancher, l'entrée `srp[]` porte désormais un **code de motif
machine** (jamais un texte français du moteur), distinct du garde EGAlim :

- `reasonCode = "SRP_FLOOR_CLAMPED"` : la ligne a été écrêtée (remontée au plancher),
  le geste n'a **pas** été appliqué tel quel.
- Quand la ligne porte un geste manuel : `requestedDiscount` (remise demandée) et
  `grantedDiscount` (remise réellement accordée), TTC, avec
  `grantedDiscount = requestedDiscount − correction`. **Assiette** (assumée, §7) :
  mesurées contre le **prix de vente de référence** de la ligne (`BASE_FOR_DISCOUNT`,
  repli `DEFAULT`) — la même que la descente des paliers, pour garder un sens commun.

### 5.B · Paliers d'alerte (GB — trois niveaux)

Champ optionnel du fragment `SRP_GUARD` : `"warningThresholds": [0.5, 0.8]` — un tableau
de **fractions strictement entre 0 et 1** (schéma `exclusiveMinimum:0`,
`exclusiveMaximum:1`). Administré, **ciblé PDV/groupes** comme le garde (c'est la même
configuration `Offer`). Absent ⇒ pas de palier, plancher binaire seul.

Un palier est une **fraction de la descente vers le plancher** (assiette §7, la seule
qui ait le même sens à forte et à faible marge) :
`descentFraction = (nominal − final) / (nominal − floor)`, échelle 4, `nominal` = prix
de vente de référence. Une ligne **sans plancher** n'a pas de palier ; une ligne dont le
prix de vente est déjà ≤ plancher n'a pas d'échelle (`descentFraction` absent).

Trois niveaux dans `srp[]`, la caisse décide de la suite (bandeau, aval superviseur) :

1. **accordé sans réserve** : `reasonCode` absent ;
2. **accordé avec avertissement** : `reasonCode = "SRP_MARGIN_WARNING"`,
   `warningThreshold` = le palier le plus haut atteint, `descentFraction` renseigné ;
   **aucune** correction, **aucun** `SRP Adjustment` ;
3. **écrêté** : `reasonCode = "SRP_FLOOR_CLAMPED"` (§5.A).

### 5.C · Dérogation « produit dégradé » (GB — contournement tracé)

La vente à perte est interdite **sauf** article dégradé/invendable. La dérogation est
déclarée par la **caisse, sur la ligne** (aval superviseur), jamais inventée par le
moteur. Deux champs additifs sur la ligne du panier :

- `srpOverride` (booléen) : **désactive le garde SRP pour CETTE ligne** — elle peut
  passer sous son plancher sans correction ;
- `srpOverrideReason` (chaîne, optionnelle) : le motif de l'opérateur, recopié tel quel.

Le contournement est **tracé** dans `srp[]` (`reasonCode = "SRP_OVERRIDE"`,
`overrideReason`), sans correction, pour que le journal électronique et les exports
financiers le retrouvent — un contournement muet serait pire que pas de garde. C'est
**distinct** de `compliance.srpExempt` (GB-01-06-10, exemption de l'**article** au niveau
configuration) : ici une dérogation **ponctuelle, sur une ligne, dans un ticket**.
L'override **prime** sur `srpExempt` s'ils coexistent.

### Contrat exact ajouté

**Panier — `items[]`** (additif, rétrocompatible) :

| champ | type | sens |
|---|---|---|
| `srpOverride` | booléen | dérogation SRP ponctuelle sur la ligne ; absent = `false` |
| `srpOverrideReason` | chaîne | motif opérateur, recopié dans `srp[]` |

**Configuration `SRP_GUARD` — `specification`** : `warningThresholds` (tableau de
nombres ∈ ]0,1[, optionnel).

**Réponse — `srp[]`** (champs additifs, émis seulement quand pertinents ; une ligne
accordée sans réserve reste bit à bit identique à avant) : `reasonCode`
(`SRP_FLOOR_CLAMPED` | `SRP_MARGIN_WARNING` | `SRP_OVERRIDE`), `descentFraction`,
`warningThreshold`, `requestedDiscount`, `grantedDiscount`, `overrideReason`.

### Hypothèses (§7, nommées comme telles dans la Javadoc)

- **La onzième unité** : la remise unitaire suit la quantité de la ligne (propriété de
  la ligne) ; une unité ajoutée sur une **nouvelle** ligne n'hérite d'aucune remise.
  Sémantique de `manualDiscountAmount` inchangée — non traité ici.
- **Assiette des paliers** : fraction de la descente vers le plancher (ni % du prix de
  vente, ni euros absolus) ; `requested/grantedDiscount` mesurés sur le même prix de
  vente de référence.

### Ce que la caisse fait de son côté

- Traduire chaque `reasonCode` en message d'écran (le moteur ne renvoie jamais de texte
  français) ; décider quoi faire d'un `SRP_MARGIN_WARNING` (bandeau vs aval superviseur).
- Porter la dérogation : positionner `srpOverride`/`srpOverrideReason` sous aval, puis
  reporter le `SRP_OVERRIDE` tracé dans le journal électronique et la déclaration de
  consommation (exports financiers).

**Impacts (extension)** : `Basket.Item` (+ schéma), `SrpGuardFactory` (schéma
`warningThresholds`, `decideLine`, codes de motif) + test unitaire, bloc `SrpLine`
enrichi, scénarios e2e U11–U13. **Rien d'autre.**
