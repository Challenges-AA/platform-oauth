# Périmètre de la plateforme

Le projet provient du défi technique Portage Cybertech : démontrer l’émission et la
validation de JWT par des services distincts, l’accès à une ressource protégée et
l’acceptation de nouvelles clés de signature. Les évolutions suivantes ajoutent un
Key Service, Authorization Code avec PKCE et le cycle complet des refresh tokens.

## Fonctionnement actuel

| Besoin | Contrat |
| --- | --- |
| Services séparés | Authorization Server, Resource Server, Key Service |
| Connexion utilisateur | `/login`, cinq comptes configurés, mots de passe encodés |
| Autorisation | Authorization Code avec PKCE S256 obligatoire et callback enregistré |
| Clients | `portage-public` sans secret ; `demo-client` avec HTTP Basic |
| Identité et droits | Sujet stable `subject-N`, audience `portage-api`, scope `hello.read` |
| Signature | JWT ES256 / P-256 ; clé privée conservée uniquement par le Key Service |
| Ressource | `GET /hello` retourne HelloWorld et le sujet, après validation du JWT |
| Renouvellement | Refresh tokens opaques, liés à l’autorisation, rotatifs et expirables |
| Rejeu | Révocation de la famille lorsqu’un ancien refresh token est réutilisé par son client |
| Persistance | Autorisations et sessions JDBC dans H2 chiffré ; clés dans un fichier chiffré distinct |
| Révocation | `/oauth2/revoke` ; JWT déjà délivrés acceptés jusqu’à expiration |
| Concurrence | Transaction et verrou de cycle de vie lors des échanges et révocations |
| Validation | JUnit, HTTP, Cucumber et JaCoCo ; seuil de 80 % des lignes par service et au total |

Seul le parcours Authorization Code avec PKCE S256 est maintenu pour obtenir des tokens,
avec renouvellement par refresh token et révocation. Les autres grants sont refusés.

## Limites assumées

Application locale à instance unique ; utilisateurs et clients configurés en mémoire.
Pas de révocation immédiate des JWT autonomes, de réplication, de HSM/KMS, de purge
automatique des familles expirées ou de rotation automatique des clés maîtresses.
Un déploiement nécessite TLS et une gestion opérationnelle des secrets.

Les contrats détaillés figurent dans [l’architecture](ARCHITECTURE.md), le
[guide PKCE](AUTHORIZATION_CODE_PKCE.md) et le [guide du Key Service](KEY_SERVICE.md).
