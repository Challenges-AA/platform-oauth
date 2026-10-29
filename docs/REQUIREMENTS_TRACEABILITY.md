# Traçabilité du fonctionnement actuel

Ce tableau relie les besoins aux composants et aux tests maintenus. Les résultats
d’exécution proviennent des rapports générés par `mvn clean verify`, et non d’anciens diagnostics.

| Besoin | Composant principal | Vérification |
| --- | --- | --- |
| Connexion, identités et sujets distincts | `UserRegistry`, `UserDetailsService` | `UserRegistryTest`, `AuthorizationCodePkceHttpTest` |
| Code et PKCE S256 | `AuthorizationSecurityConfiguration`, providers Spring | `AuthorizationCodePkceHttpTest` : code nominal, vérificateur invalide, callback, expiration et rejeu |
| Clients public et confidentiel | Déclarations Spring, `PublicRefreshClientAuthentication` | Échanges et renouvellements HTTP pour les deux clients |
| Liaison client/utilisateur/droits | Autorisation Spring JDBC et claims JWT | Tests de sujet, audience, scope, mauvais client et élévation de droits |
| Rotation et détection de rejeu | `RotatingAuthorizationService` | Tests HTTP : ancien token refusé, dernier token invalidé, persistance de la famille |
| Expiration | `TokenSettings`, `RotatingRefreshTokenGenerator` | Expiration du code, refresh expiré et échéance absolue de famille |
| Révocation et concurrence | `TokenTransactionFilter`, verrou JDBC | Révocation HTTP ; deux renouvellements concurrents avec barrière de départ |
| Persistance chiffrée | `AuthorizationPersistenceConfiguration` | `AuthorizationPersistenceTest` : réouverture, sessions, consentements, mauvaise clé |
| Signature distante et clés publiques | `RemoteSigningConfiguration`, Key Service | `RemoteSigningConfigurationTest`, `KeyServiceHttpTest`, `KeyStoreServiceTest` |
| Protection de la ressource | `SecurityConfiguration`, `AccessTokenClaimsValidator` | `ResourceServerHttpTest`, `AccessTokenClaimsValidatorTest`, Cucumber |
| Rotation des clés | `KeyStoreService` | Signature, rotation, rétention, corruption et rechargement |
| Compatibilité facultative | `SubjectGrantAuthenticationProvider` | Tests unitaires/HTTP historiques, `demo-legacy.py`, collection `legacy.collection.json` |
| Couverture | JaCoCo et module `coverage` | `scripts/check-coverage.py`, `docs/evidence/coverage.json` |

## Où consulter les résultats

- `*/target/surefire-reports/` : tests unitaires et HTTP.
- `*/target/failsafe-reports/` : suites Cucumber.
- `*/target/site/jacoco/` : couverture par service.
- `coverage/target/site/jacoco-aggregate/` : rapport agrégé.
- `docs/evidence/coverage.json` : résumé pondéré conservé dans le dépôt.
- `.work/reports/verify-*/` : exécutions datées produites par `scripts/verify.py`, ignorées par Git.

La [recette Postman](PLAN_TEST_MANUEL_POSTMAN_PKCE.md) décrit les résultats attendus
pour les essais manuels ; elle ne constitue pas une déclaration d’exécution.
Le scan CVE, Docker et une qualification en production sont des vérifications distinctes.
