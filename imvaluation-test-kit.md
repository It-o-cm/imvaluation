# Kit de campagne de tests — imvaluation (moteur de valorisation)

Réutilisation directe de la campagne impos (1557 tests unitaires + calibrage e2e).
Ce kit = ce qui change. Tout le reste (conventions, script, économie) transfère tel quel.

## 1. Delta CLAUDE.md (à ajouter au CLAUDE.md du repo imvaluation)

Reprendre le CLAUDE.md d'impos (unitaire pur sans H2/@QuarkusTest, mockStatic(PanacheEntityBase)
pour les finders, mockConstruction pour persist, DEUX BRAS de chaque garde null, un commit par
package, jamais de push) et ajouter :

- **Appliers d'offres = logique pure** : tester SANS mock Panache quand possible (construire
  Basket/Offer en mémoire, asserter la math). C'est le cœur de valeur du moteur — viser
  l'exhaustivité des cas au centime : quantités fractionnaires (kg), arrondis HT/TTC/taux,
  résidus de centime, portions multiples d'une même ligne (le 2+1 du 2FOR1).
- **Golden files sur /valuation** : pour chaque scénario d'offre, une paire (basket.json →
  evaluation.json attendue) versionnée. Un changement de sortie du moteur casse un golden =
  le contrat avec la caisse est protégé. Les JSON d'exemple échangés pendant la phase 7
  (2FOR1, bundle multi-taux, gestes manuels, MEAL_VOUCHER, upsell) sont les premiers goldens.
- **Tests rouges d'abord** : épingler les deux défauts connus AVANT toute campagne —
  (a) mutation GraphQL createPrice (champ priceUsage inexistant, ?5 pour 3 arguments) ;
  (b) processPriceLogic : clé de doublon lue sur les mauvaises colonnes (start = parts[6] =
  endDate) → un test qui ré-importe le même CSV et compte les lignes. Rouge assumé jusqu'au fix.

## 2. Catalogue de scénarios moteur (squelette — à compléter par grep dans le repo)

Méthode éprouvée sur impos : le catalogue se dérive DU CODE (grep de la surface), pas de la
mémoire. Groupes proposés, niveau entre parenthèses (U = unitaire pur, R = RestAssured) :

- **VA. Contrat /valuation (R)** : requête minimale ; lineId STRING échoïsé partout (y compris
  portions 2+1) ; trio de surcharge tout-ou-rien (rejet du partiel) ; gestes manuels exclusifs
  entre eux ; priceDate = résolution à date ; customerCode absent/inconnu ; panier vide ;
  auth Basic (401 sans, 200 avec) ; timeout côté client simulé (le moteur n'y peut rien mais
  la latence P99 se mesure ici).
- **OF. Chaque applier, la math au centime (U + golden)** : basic, N+M (2FOR1 : bornes 1/2/3/4
  unités, kg fractionnaires), bundle multi-taux (TTC par item autoritaire, le taux mélangé
  0.2020 documenté décoratif), meal voucher (assiette + threshold), upsell (suggestion sans
  montant), delivery/deposit basket en IN_STORE (verdict « ignore » d'Henri = tester qu'ils
  ne SORTENT pas).
- **GE. Gestes manuels (U + golden)** : remise € / % / prix forcé — assiette (le forcé
  rejoue-t-il les promos ?), interaction geste×offre, montant net en « Manual Gesture »,
  arithmétique alignée caisse au centime (les 5 points de contrat posés en phase 7 : chaque
  réponse devient un test).
- **PR. Résolution de prix (U)** : priorité (promo 1 sur DEFAULT), fenêtres start/end (bornes
  incluses/excluses, endDate passée), BASE_FOR_DISCOUNT interne, magasin sans prix.
- **IM. Imports CSV (R)** : fallback étagé (ligne malformée → skip loggé, pas d'abandon),
  idempotence du ré-import (LE test rouge des colonnes), familles autoritaires, StoreGroup
  hiérarchie (parent/enfants/magasins).
- **GQ. Surface GraphQL (R)** : chaque mutation existante (create/update par entité), le
  createPrice rouge, requêtes de lecture si présentes.
- **CO. Acceptance côté consommateur** : déjà écrit — le groupe E du catalogue e2e d'impos,
  joué au navigateur contre le moteur réel. Rien à réécrire : c'est la même campagne, l'autre
  bout du câble.

## 3. Adaptation du script de campagne

Le script impos transfère avec trois retouches : (1) la validation des lettres devient
VA/OF/GE/PR/IM/GQ ; (2) messages « test: engine scenarios group X », le commit reste le seul
juge (git log -1) ; (3) purge des *Test/*IT partiels non commités AVANT toute (re)lance — la
leçon du redémarrage Mac : le skip se fait sur l'existence du FICHIER, pas du commit.
RestAssured = failsafe : câbler skipUTs/skipITs proprement dans le pom dès le départ (le
faux-vert -DskipITs du calibrage impos ne doit pas se rejouer ici).

## 4. Ordre de bataille proposé

1. Tests rouges des 2 défauts connus (10 min, au chat ou à l'agent).
2. Campagne unitaire OF + GE + PR (le cœur, la valeur).
3. Goldens /valuation depuis les JSON de la phase 7.
4. IM + GQ en RestAssured.
5. Groupe E d'impos joué contre le moteur = l'acceptance finale.
