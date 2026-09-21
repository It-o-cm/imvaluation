# Spécification C1 + C2 — Triggers & Arbitrage

**Projet** : imvaluation · **Chantiers** : C1 (brique déclencheurs) + C2 (arbitrage) · **Statut** : validé en revue de conception (session du 01/09/2026)

Ce document est le contrat d'implémentation des deux premiers chantiers de la phase 1 du plan P1 Promotion. Chaque décision ci-dessous a été arbitrée explicitement ; les alternatives écartées sont rappelées quand leur rejet mérite d'être opposable.

---

## 1. Glossaire

Le vocabulaire ci-dessous fait foi dans tout le document — et diverge volontairement de l'Excel du RFP, qui appelle tout « offre ».

| Terme | Définition |
|---|---|
| **Offre** | Mécanisme qui **fournit un prix** à un ensemble de produits (Basic, N+M, MIXED_BUNDLE, valorisation sans EAN). Produit des `OfferApplication`. |
| **Avantage** | Mécanisme qui **module le prix** établi par les offres (remises, grants, vignettes, meal voucher). Produit des `AdvantageApplication`. Un avantage n'est pas une offre. |
| **Configuration** | Une ligne de la table `Offer` (nom hérité) : code, type, spécification JSON, rattachement magasins/groupes. Certaines configurations sont des offres, d'autres des avantages. |
| **Candidat** | Un `OfferApplier` de valorisation présenté à un avantage via `isApplicable` pour constituer son assiette. |
| **Assiette** | L'ensemble des candidats acceptés par `isApplicable` — ce sur quoi l'avantage se calcule et se répartit. |
| **Contributeur** | Ligne valorisée qui a satisfait une condition du trigger (sélectionnée par la *condition*, pas par la cible de l'avantage). |
| **Porteur consommé** | Contributeur d'un avantage **appliqué** dont le flag d'exclusion est actif — retiré des assiettes et des mesures de triggers des configurations arbitrées ensuite. |

Ligne de partage fondamentale : **le trigger mesure, l'arbitrage tranche, `isApplicable` distribue.** Tout ce qui dépend du panier global ou de l'état de la configuration se décide dans l'arbitrage ; tout ce qui dépend d'une ligne se décide dans `isApplicable`.

---

## 2. Périmètre et lignes RFP

### C1 — Brique déclencheurs (12 lignes)
GB-01-05-41, 42, 43, 44, 45 · GB-01-07-08 · GM-06-03-01, 02, 03, 05, 06 · GM-06-04-01.

### C2 — Arbitrage (10 lignes)
GB-01-07-09, 14, 15, 22 · GB-01-06-13 · GM-06-01-03 · GM-06-06-06, 09, 10, 11.

### Couverture différée (à déclarer honnêtement dans le questionnaire)
- **GB-01-07-08 / GM-06-03-06** (`applicationMoment`) : déclaré en C1, effectif en C2 (vagues) + avenant panier (`closed`). Basculent en « 1 » à l'issue des trois livraisons ; « 1a » entre-temps.
- **DG-02-02-01/02** (« il vous reste X € ») : la matière (triggers véridiques, `details`) est posée ici ; la restitution API est en C7. Rien dans C1/C2 ne les fait basculer.

### Hors périmètre explicite
Le kind `ANY_OF` (OU), le kind `ALL_OF`, la sortie `pendingAdvantages`, les `details` de `TriggerResult`, la bibliothèque de triggers partagés, le branch & bound d'optimalité, tout renommage de l'existant (`getOffers()`, table `Offer`). Chacun de ces points a été discuté et écarté ; leur éventuel retour passe par une décision explicite, pas par un « tant qu'on y est ».

---

## 3. C1 — Le bloc `trigger`

### 3.1 Contrat JSON

Le bloc `trigger` est **optionnel sur toute configuration**, uniforme pour tous les types. Il est le frère d'`applicationMoment` (§3.6), jamais son parent.

```json
{
  "targetEans": ["3300000000021"],
  "discountType": "PERCENTAGE",
  "value": 15.0,
  "applicationMoment": "AT_TOTAL",
  "trigger": {
    "conditions": [
      { "kind": "MINIMUM_AMOUNT", "scope": "ITEMS",
        "eans": ["3300000000001", "3300000000007"], "threshold": 40.00 },
      { "kind": "COUPON_CODE", "code": "HIVER24" }
    ]
  }
}
```

**Schéma JSON** (fragment injecté — voir §3.5) :

```json
"trigger": {
  "type": "object",
  "additionalProperties": false,
  "required": ["conditions"],
  "properties": {
    "conditions": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/definitions/triggerCondition" }
    }
  }
},
"triggerCondition": {
  "oneOf": [
    { "type": "object", "additionalProperties": false,
      "required": ["kind", "scope", "threshold"],
      "properties": {
        "kind": { "const": "MINIMUM_AMOUNT" },
        "scope": { "enum": ["TICKET", "ITEMS"] },
        "eans": { "type": "array", "minItems": 1,
                  "items": { "type": "string", "minLength": 1 } },
        "threshold": { "type": "number", "exclusiveMinimum": 0 }
      }
    },
    { "type": "object", "additionalProperties": false,
      "required": ["kind", "eans", "threshold"],
      "properties": {
        "kind": { "const": "MINIMUM_QUANTITY" },
        "eans": { "type": "array", "minItems": 1,
                  "items": { "type": "string", "minLength": 1 } },
        "threshold": { "type": "number", "exclusiveMinimum": 0 }
      }
    },
    { "type": "object", "additionalProperties": false,
      "required": ["kind", "code"],
      "properties": {
        "kind": { "const": "COUPON_CODE" },
        "code": { "type": "string", "minLength": 1 }
      }
    }
  ]
}
```

### 3.2 Les trois kinds

| Kind | Sémantique | Contributeurs |
|---|---|---|
| `MINIMUM_AMOUNT` scope `TICKET` | Somme TTC courante de toutes les lignes valorisées ≥ `threshold`. `eans` **interdit**. | Aucun (le déclencheur n'est pas attribuable à des lignes — voir §4.4). |
| `MINIMUM_AMOUNT` scope `ITEMS` | Somme TTC courante des lignes dont l'EAN ∈ `eans` ≥ `threshold`. `eans` **requis**. | Les lignes sommées. |
| `MINIMUM_QUANTITY` | Somme des quantités des lignes dont l'EAN ∈ `eans` ≥ `threshold`. Quantités décimales sommées telles quelles (une ligne au poids de 0.5 compte 0.5 — cohérent avec la sémantique quantité du moteur). | Les lignes comptées. |
| `COUPON_CODE` | `code` ∈ `couponCodes[]` du panier. Comparaison exacte, sensible à la casse, après `trim`. | Aucun. |

Seuils : la condition est satisfaite quand la valeur atteinte est **≥** au seuil (« à atteindre » = atteint compris). Les montants sont les montants **courants** : ceux des lignes telles que l'arbitrage les voit à l'instant de l'évaluation — après bascule BASE_FOR_DISCOUNT et après remises déjà retenues (§4.3).

Le **multi-listes** (2 à 10 listes déclencheuses, chacune son seuil) s'écrit comme plusieurs conditions dans `conditions[]` — ce n'est pas un kind.

### 3.3 Sémantique d'évaluation

- `conditions[]` dénote un **nœud ET implicite** : toutes les conditions doivent être satisfaites. Contributeurs du trigger = union des contributeurs des conditions.
- Bloc `trigger` absent ⇒ `Trigger.ALWAYS` : toujours satisfait, zéro contributeur. Jamais `null`.
- Le trigger est évalué **une fois par configuration** par l'arbitrage, mémoïsé dans `BasketEvaluation` (`Map<Offer, TriggerResult>`), jamais par les appliers. Invalidation : §4.3.
- Le trigger **dit toujours la vérité** : il n'est jamais forcé à `false` par le contexte (panier ouvert, moment). Ces décisions appartiennent à l'arbitrage (§4.2). Le trigger est un capteur, pas un acteur.

```java
/** Outcome of evaluating a configuration's trigger against the basket. */
public record TriggerResult(boolean satisfied, List<OfferApplication> contributors) { }
```

Deux champs, deux consommateurs de phase 1 : `satisfied` → l'arbitrage (C1), `contributors` → l'exclusion des porteurs (C2). Le champ `details` (atteint/manquant par condition, pour « il vous reste X € ») est **spécifié comme évolution C7** : il s'ajoutera au record interne sans impact de contrat.

### 3.4 Clause d'évolution (immuable)

> Le bloc `trigger` n'évolue que **par ajout de kinds ou de champs optionnels avec défaut**. La structure existante — `conditions[]` en ET au sommet, format des feuilles — est immuable.
>
> `conditions[]` est **LA** notation du ET : elle se désérialise en nœud AND racine. Aucun kind `ALL_OF` n'existe, et n'existera jamais à la racine (deux graphies d'un même nœud = specs non canoniques).
>
> Le OU futur entrera comme kind `ANY_OF`, portant lui-même un `conditions[]` (combinés en OU puisque c'est la sémantique de *son* nœud). Règle générale : `conditions[]` = les enfants d'un nœud ; le combinateur est porté par le nœud. Un OU racine s'écrit comme unique enfant du ET implicite (`ALL(x) ≡ x`). Un `ALL_OF` imbriqué n'apparaîtra que si `ET(a,b)` sous un OU devient nécessaire — jamais à la racine.
>
> La sémantique des contributeurs sous un nœud OU (lignes des seules branches satisfaites) sera spécifiée avec `ANY_OF`, pas avant.
>
> Kind inconnu ⇒ configuration **invalide à la création** (donc jamais rencontré à l'évaluation).

### 3.5 Portée par type et injection du schéma

Le fragment de schéma (§3.1) est injecté par `EngineTrait.processSpecification` dans le schéma de **chaque** factory — aucune modification factory par factory, uniformité garantie, un futur type l'hérite d'office.

| Types | Trigger |
|---|---|
| Avantages : `IMMEDIATE_VOUCHER`, `VIGNETTE_DISCOUNT`, `MEAL_VOUCHER`, `TIERED_DISCOUNT`, `VOUCHER_GRANT`, `COUPON_GRANT`, `FREE_DELIVERY_THRESHOLD`, upsells | **Autorisé** (cas nominal). |
| Offres optionnelles : `N+M`, `MIXED_BUNDLE` | **Autorisé** — trigger non satisfait ⇒ la mécanique ne joue pas, les lignes retombent sur la valorisation Basic. |
| `DELIVERY`, `DEPOSIT_BASKET`, `MANUAL_GESTURE` | Autorisé (légal mais rarement pertinent — documenté, pas interdit : interdire au cas par cas recréerait de la spécificité par type). |
| Valorisation Basic (prix du référentiel) | **Sans objet** : la Basic n'est pas une configuration. Invariant protégé : toute ligne a un prix. |

### 3.6 `applicationMoment`

Propriété de la **configuration d'avantage**, au même niveau que `trigger` (un avantage sans condition peut en avoir besoin — ex. remise TVA du ticket).

```json
"applicationMoment": { "enum": ["AT_TRIGGER", "AT_TOTAL"], "default": "AT_TOTAL" }
```

- `AT_TRIGGER` : l'avantage s'applique dès qu'il est applicable, y compris sur panier ouvert.
- `AT_TOTAL` (défaut = comportement actuel) : l'avantage ne s'applique que sur panier clos, dans la seconde vague (§4.2).
- Restitué dans chaque `AdvantageApplication` de la réponse (servira l'impression ligne/pied de ticket, GB-01-07-10).
- C1 le **déclare** (schéma, validation, stockage, restitution) ; son effet est livré par C2 (vagues) et l'avenant panier (`closed`). On ne déclare dans le schéma que ce dont la sémantique est écrite — c'est fait ici.

### 3.7 Avenant au schéma panier

Deux champs, additifs, rétrocompatibles :

```json
"couponCodes": { "type": "array", "items": { "type": "string", "minLength": 1 } },
"closed":      { "type": "boolean", "default": true }
```

- `couponCodes[]` : codes présentés en caisse/en ligne. Doublons ignorés (ensemble).
- `closed` : **fait sur le panier**, déclaré par l'appelant — jamais deviné par le moteur (deux paniers identiques ⇒ deux réponses identiques, invariant stateless intact). `true` = panier définitif, tout s'applique (défaut = comportement actuel). `false` = panier en cours (flux caisse pendant le scan). Le panier e-commerce est **clos par nature** : chaque état du panier web doit afficher le total payable, tout appliqué immédiatement.
- Résumé opérationnel : *panier ouvert ⇒ les avantages `AT_TOTAL` ne tombent jamais* (mécanique exacte en §4.2 — le « false » vit dans l'arbitrage, pas dans le trigger).

### 3.8 Règles croisées de validation (à la création, `IllegalArgumentException`)

1. `MINIMUM_AMOUNT` scope `ITEMS` ⇒ `eans` requis, non vide.
2. `MINIMUM_AMOUNT` scope `TICKET` ⇒ `eans` interdit.
3. `conditions[]` vide ⇒ rejet (un trigger sans condition n'existe pas ; l'absence de bloc = ALWAYS).
4. Kind inconnu ⇒ rejet.
5. `applicationMoment` hors enum ⇒ rejet.
6. Doublons d'EANs dans une liste ⇒ tolérés, dédupliqués (même tolérance que les cibles existantes).

Messages par le canal existant : `IllegalArgumentException("Error validating offer: ...")`.

### 3.9 Architecture Java

Composite **interne**, contrat **plat** — l'abri pour le futur OU est dans le typage, pas dans le JSON :

```java
/** A trigger node: evaluates itself against the current basket state. */
public interface TriggerCondition {
    ConditionOutcome evaluate(BasketEvaluation evaluation);
}

// Leaves — one class per kind
final class MinimumAmountCondition implements TriggerCondition { ... }
final class MinimumQuantityCondition implements TriggerCondition { ... }
final class CouponCodeCondition implements TriggerCondition { ... }

/** The AND node — today the only composite, and the root the engine sees. */
public final class Trigger implements TriggerCondition {
    public static final Trigger ALWAYS = ...;      // empty AND, always satisfied
    private final List<TriggerCondition> children; // typed on the INTERFACE
    public static Trigger of(JsonNode triggerNode) { ... }
    public TriggerResult evaluate(BasketEvaluation evaluation) { ... }
}
```

Les enfants sont typés sur l'interface : un `OrTrigger` futur = une classe + une entrée de schéma, zéro refonte. L'arbitrage ne connaît que `Trigger.evaluate() → TriggerResult` ; personne ne peut interroger une condition isolément.

Emplacement : `engine/Trigger.java`, `engine/TriggerCondition.java`, `engine/TriggerResult.java`, conditions dans `engine/` (même niveau que `TierTable`). Tout le code et les Javadoc en anglais ; **toutes** les méthodes documentées, sans exception.

---

## 4. C2 — Arbitrage

### 4.1 Nouveaux paramètres de configuration

Injectés dans tous les schémas comme le trigger (§3.5), regroupés dans un bloc optionnel :

```json
"arbitration": {
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "priority":                { "type": "integer", "minimum": 0, "maximum": 1000, "default": 500 },
    "cumulable":               { "type": "boolean", "default": true },
    "exclusionGroups":         { "type": "array", "items": { "type": "string", "minLength": 1 } },
    "maxApplicationsPerTicket":{ "type": "integer", "minimum": 1 },
    "maxApplicationsPerLine":  { "type": "integer", "minimum": 1 },
    "consumesContributors":    { "type": "boolean", "default": false }
  }
}
```

| Paramètre | Sémantique | RFP |
|---|---|---|
| `priority` | **Plus petit = arbitré plus tôt.** Défaut 500 (médiane) : les configurations existantes, toutes sans priorité, restent départagées par score — comportement inchangé tant que personne ne paramètre. | GB-01-07-15, GM-06-06-10 |
| `cumulable` | `false` ⇒ incompatible avec tout autre avantage `cumulable: false` sur le même ticket (groupe implicite partagé). | GB-01-07-14, GM-06-06-06 |
| `exclusionGroups` | Au plus **un** avantage appliqué par groupe nommé et par ticket. Contrôle fin, complémentaire de `cumulable`. | idem |
| `maxApplicationsPerTicket` | Plafond d'applications de la configuration sur un ticket (mécaniques répétables). Absent = illimité. | GB-01-07-09, GM-06-06-09 |
| `maxApplicationsPerLine` | Plafond d'applications par ligne d'article du ticket. Absent = illimité. | GB-01-07-22, GM-06-06-11 |
| `consumesContributors` | `true` ⇒ à l'application, les contributeurs du trigger deviennent **porteurs consommés** (§4.4). | GB-01-06-13, GM-06-01-03 |

Bloc `arbitration` absent ⇒ tous les défauts ⇒ comportement actuel. **Activation par la donnée, jamais par le code.**

### 4.2 L'algorithme

```
ENTRÉE : panier (closed, couponCodes, lignes), configurations candidates du magasin

A. Valorisation (inchangée) : les offres produisent les lignes valorisées.
   Offre optionnelle (N+M, MIXED_BUNDLE) porteuse d'un trigger non satisfait :
   écartée, lignes valorisées par la Basic.

B. Arbitrage des avantages, en DEUX VAGUES :
     vague 1 : avantages AT_TRIGGER
     vague 2 : avantages AT_TOTAL          (exécutée seulement si closed = true)
   L'appartenance à une vague prime sur tout le reste de l'ordre.

   Dans chaque vague, boucle :
     1. Trier les candidats restants :
          priority croissante  >  score sandbox courant décroissant  >  code (stable)
     2. Prendre le premier ; il s'applique ssi :
          trigger satisfait                                   (mémoïsé, §3.3)
        ∧ assiette non vide                                   (isApplicable, inchangé)
        ∧ (closed ∨ applicationMoment = AT_TRIGGER)           (règle panier ouvert)
        ∧ cumulable compatible avec les avantages déjà appliqués
        ∧ aucun exclusionGroup déjà consommé
        ∧ maxApplicationsPerTicket / PerLine non atteints
     3. S'il s'applique : enregistrer les AdvantageApplication ;
        si consumesContributors : marquer les porteurs (§4.4) ;
        invalider le cache des triggers (§4.3).
        Sinon : l'écarter définitivement pour cette évaluation.
     4. Reboucler (re-scoring sandbox sur l'état courant) jusqu'à épuisement.

SORTIE : advantages[] (avec applicationMoment), totalPrice, vatBreakdown — format actuel.
```

**Le moteur n'optimise pas, il obéit.** L'ordre de consommation est la priorité paramétrée ; le score sandbox ne départage que les ex æquo (et garde son rôle interne : choisir la meilleure variante d'application d'un même applier). Si un avantage prioritaire « affame » une combinaison globalement meilleure (A = 6 € consomme les porteurs de B + C = 7 €), c'est le paramétrage qui l'a voulu et le remède est dans le BO. Contrepartie assumée et documentée : ticket explicable en une phrase (« les avantages s'appliquent dans l'ordre de priorité défini par l'enseigne »), déterminisme total. Ce cas A/B+C est un **cas de test officiel** (§7).

**Garanties** (ce que le moteur promet) : déterminisme — même panier, mêmes configurations ⇒ même ordre, même résultat, départage stable jusqu'au code de configuration. **Non-garantie** (ce qu'il ne promet pas) : l'optimalité globale de la combinaison.

### 4.3 Montants courants et invalidation du cache

Règle : *chaque configuration voit le panier tel qu'il est au moment où son tour arrive* — c'est le comportement observable d'une caisse réelle, donc recettable.

Le cache des triggers (`Map<Offer, TriggerResult>`) est invalidé (`clear()`) dans exactement **deux** cas :
1. une remise s'applique réellement (les montants courants ont changé — un trigger « ≥ 50 € » qui passait à 52 € peut ne plus passer à 48 €) ;
2. des porteurs sont consommés (les lignes consommées sortent des mesures — §4.4).

Le sandbox n'invalide jamais rien (il ne modifie pas l'état). Conséquence assumée : l'arbitrage est dépendant de l'ordre — il l'est déjà (scores), et l'ordre est désormais gouverné par les priorités.

Interaction vagues × montants courants : les avantages `AT_TOTAL` voient le panier **après** tous les `AT_TRIGGER` — y compris sur un panier e-commerce clos, sinon le total web divergerait du ticket magasin sur les mêmes configurations.

### 4.4 Exclusion des porteurs

> **réservation ⟺ l'avantage a produit une application retenue ∧ `consumesContributors` = true**

- Jamais de réservation sur simple trigger satisfait : un avantage à l'assiette vide (trigger OK, aucun candidat accepté) est écarté **sans rien consommer** — ses contributeurs restent libres. Trigger satisfait sans application = non-événement pour le reste de l'arbitrage (mais tracé).
- Effet de la consommation : les lignes consommées sortent (a) des assiettes (`isApplicable` ne les reçoit plus comme candidats pour les configurations suivantes) et (b) des mesures des triggers suivants (un montant consommé ne compte plus vers le seuil de personne).
- La contention entre avantages est résolue par l'ordre d'arbitrage (§4.2) : le mieux classé se sert en premier. Le « vol » de porteurs ne peut se produire que dans l'ordre décidé, jamais contre lui.
- `MINIMUM_AMOUNT` scope `TICKET` et `COUPON_CODE` n'ont pas de contributeurs : `consumesContributors` y est sans effet (documenté — un déclencheur non attribuable à des lignes ne consomme rien).

### 4.5 Sortie

- `advantages[]` : chaque `AdvantageApplication` porte son `applicationMoment`. Aucun autre changement de format.
- **Pas** de section `pendingAdvantages` : aucun consommateur identifié (l'e-commerce envoie des paniers clos ; l'affichage caisse « avantage acquis » serait une décision IHM d'IMPOS non prise). Sur panier ouvert, les avantages `AT_TOTAL` sont silencieusement écartés. Le champ reviendra si un consommateur se déclare — ajout additif trivial, le `TriggerResult` mémoïsé le rend quasi gratuit.
- Chaque élément de sortie de ce document est adossé à un consommateur nommé ; ce qui n'en a pas n'y figure pas.

---

## 5. Cas limites normatifs

1. **Seuil exact** : atteint = satisfait (`≥`). 40,00 € pile déclenche un seuil à 40.
2. **Trigger « ≥ 50 € ticket », remise antérieure fait passer de 52 à 48 €** : non satisfait à son tour d'arbitrage (montants courants). Cas de test officiel.
3. **Trigger satisfait, assiette vide** : rien produit, rien consommé, pas d'erreur (ex. : « 15 % surgelés dès 40 € d'épicerie », ticket à 42 € sans surgelé).
4. **Panier ouvert (`closed:false`), avantage `AT_TOTAL`** : écarté, silencieux. Trigger évalué véridiquement (mémoïsé pour la suite).
5. **Panier sans `closed`** : clos. Panier sans `couponCodes` : aucun `COUPON_CODE` satisfiable.
6. **Deux conditions sur la même liste** (« ≥ 40 € **et** ≥ 3 articles d'épicerie ») : légal, ET ordinaire, contributeurs = union (ici identiques).
7. **`COUPON_CODE` avec code présenté deux fois** : ensemble — satisfait une fois ; le trigger ne compte pas les présentations.
8. **N+M sous trigger non satisfait** : lignes valorisées par la Basic (jamais de ligne sans prix).
9. **`maxApplicationsPerLine` sur mécanique répétable** : la répétition est plafonnée par ligne, le reste de la répétition s'applique ailleurs si possible.
10. **Deux avantages `cumulable:false`** : le mieux classé s'applique, l'autre est écarté par la règle, pas par le score.
11. **Épuisement d'`exclusionGroup`** : au plus un avantage appliqué par groupe, quel que soit le nombre de candidats du groupe.
12. **Lignes sans EAN** (valorisation générique) : participent aux mesures `TICKET`, jamais aux listes d'EANs.
13. **A prioritaire affame B+C globalement meilleurs** : comportement nominal, documenté, testé (§4.2).
14. **Configuration `AT_TRIGGER` sans bloc `trigger`** : légale (`ALWAYS`) — s'applique dès la vague 1, y compris panier ouvert. C'est la sémantique voulue de `AT_TRIGGER` : « en direct ».

---

## 6. Impacts fichiers

**Nouveaux** : `engine/TriggerCondition.java`, `engine/Trigger.java`, `engine/TriggerResult.java`, `engine/conditions/MinimumAmountCondition.java`, `MinimumQuantityCondition.java`, `CouponCodeCondition.java` (ou regroupées dans `engine/` selon la convention du projet — au choix de l'implémenteur, à dire dans le rapport).

**Modifiés** :
- `engine/Basket.java` : schéma — `couponCodes[]`, `closed` (additifs).
- `engine/EngineTrait.java` : injection des fragments `trigger`, `applicationMoment`, `arbitration` dans tous les schémas ; parsing vers `Trigger`/paramètres d'arbitrage.
- `engine/BasketEvaluation.java` : cache des triggers, deux vagues, tri priorité/score/code, garde `(closed ∨ AT_TRIGGER)`, cumul/groupes/limites, consommation des porteurs, invalidations.
- `engine/AdvantageApplication.java` (ou équivalent) : restitution d'`applicationMoment`.
- Factories : **aucune modification individuelle** (c'est un critère d'acceptation — si l'implémentation exige de toucher chaque factory, l'injection est mal placée).

**Interdits** : renommages, réorganisation d'imports, refactorings opportunistes. Toute modification hors de cette liste est signalée dans le rapport, pas appliquée.

---

## 7. Plan de tests

### Unitaires (plain JUnit, modèle `TierTableTest`)
- Chaque condition isolée : seuil exact, en dessous, au-dessus, liste vide de correspondances, quantités décimales, casse/trim du code coupon, doublons.
- `Trigger` : ET (toutes/une seule/aucune satisfaite), union des contributeurs, `ALWAYS`, parsing depuis JSON, rejets des règles croisées (§3.8), kind inconnu.

### Intégration (`@QuarkusTest` + `@TestTransaction`, modèle `TieredDiscountFactoryTest`)
- Trigger sur chaque famille : un avantage (BASIC/TIERED), une offre optionnelle (N+M → repli Basic), un grant.
- Exemple canonique complet : « 15 % surgelés dès 40 € d'épicerie » — satisfait / non satisfait / assiette vide (cas limite 3).
- `couponCodes` : présent, absent, doublons.
- `closed:false` × `AT_TOTAL` (écarté) et × `AT_TRIGGER` (appliqué) ; défaut `closed` absent.
- Vagues : `AT_TOTAL` voit les montants après `AT_TRIGGER` (cas limite 2 en version bout en bout).
- Priorités : ordre imposé contre le score ; défaut 500 ; départage par code.
- Cumul : `cumulable:false` × 2 ; `exclusionGroups` partagé × 3 candidats.
- Limites : `maxApplicationsPerTicket`, `maxApplicationsPerLine` sur mécanique répétable.
- Porteurs : consommation → trigger suivant non satisfait ; assiette amputée ; assiette vide ⇒ pas de consommation ; `consumesContributors` sur scope `TICKET` (sans effet).
- Cas A=6 € vs B+C=7 € : le prioritaire gagne, résultat documenté.

### Régression
- **Toute la suite existante passe sans modification** : aucun bloc nouveau dans les specs ⇒ comportement bit à bit identique (défauts = existant). C'est le critère d'acceptation n°1.
- Jamais d'assertion par index sur les collections HashSet.

### Definition of done
Code + Javadoc anglais sur toutes les méthodes sans exception ; `mvn test` vert ; couverture complète des nouvelles classes ; lignes RFP de §2 basculées (« 1 » pour C1/C2 pleines, « 1a » pour les différées) ; rapport de couverture mis à jour.
