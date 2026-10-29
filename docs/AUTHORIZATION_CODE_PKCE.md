# Authorization Code, PKCE et refresh tokens

Pour la recette avec Postman, voir le [résumé et plan de test manuel](PLAN_TEST_MANUEL_POSTMAN_PKCE.md)
avec les requêtes, scripts et scénarios de refus, rotation, concurrence et redémarrage.

Le flux par défaut est Authorization Code avec PKCE **S256 obligatoire**, y compris
pour le client confidentiel. L'utilisateur s'authentifie sur `/login`, avec protection
CSRF Spring. Son mot de passe ne passe plus par le client OAuth.

## Configuration

Conserver les secrets utilisateurs, le secret du client et celui du signataire déjà
documentés. Ajouter `OAUTH_STORAGE_KEY` : Base64 de **32 octets aléatoires**. Garder
cette clé hors du dépôt, du volume et des logs, dans un gestionnaire de secrets.
La même clé est nécessaire après chaque redémarrage. Sauvegarder séparément cette
clé et le répertoire `OAUTH_STORAGE_DIRECTORY` (par défaut `runtime-auth`). H2 chiffre
le fichier avec AES, y compris les autorisations, tokens, consentements et sessions
HTTP Spring Session JDBC. Les comptes de démonstration et les deux clients sont
reconstruits à partir de la configuration, avec des identifiants stables.

Définir `OAUTH_REDIRECT_URI` avec l'URI exacte du callback. Valeur de développement :
`http://127.0.0.1:8080/callback`. Pour une exposition réelle, TLS est requis devant
les services et le callback doit utiliser HTTPS. Les cookies de session sont HttpOnly,
SameSite=Lax et Secure. En développement HTTP local uniquement, définir
`OAUTH_SECURE_COOKIE=false`.

| Client | Authentification au token endpoint | Grants | Scope |
| --- | --- | --- | --- |
| `demo-client` | HTTP Basic avec `OAUTH_CLIENT_SECRET` | authorization_code, refresh_token | hello.read |
| `portage-public` | client_id, sans secret | authorization_code, refresh_token | hello.read |

Ces deux clients internes de démonstration ne présentent pas d'écran de consentement.
Le service Spring de consentement JDBC est disponible pour les futurs clients qui
exigeraient un consentement explicite. Aucun enregistrement dynamique de client.

## Contrat HTTP

### Démonstration guidée dans le navigateur

Après connexion directe sur `/login`, l’accueil `/` propose **Démarrer une autorisation PKCE**.
Le JavaScript utilise Web Crypto pour créer un vérificateur aléatoire de 32 octets,
son challenge SHA-256 et un state aléatoire indépendant. Les valeurs temporaires sont
conservées dans `sessionStorage`, dans cet onglet uniquement. Le vérificateur n’est
jamais placé dans l’URL d’autorisation ni envoyé à `/demo/config`.

Le client public possède un callback supplémentaire, `/demo/callback`, configuré par
`OAUTH_DEMO_REDIRECT_URI` (défaut `http://127.0.0.1:9000/demo/callback`). Il doit être
sur la même origine que la page. Les callbacks existants des tests manuels restent enregistrés.
Au retour, la page retire les paramètres sensibles de l’URL, vérifie le state et
le callback, puis affiche le code et le vérificateur pour l’échange manuel dans Postman.
Une demande absente, incohérente ou vieille de plus de dix minutes est refusée côté démonstration.
Cette limite locale ne remplace pas l’expiration serveur du code, qui reste de deux minutes.
Le stockage temporaire est consommé au retour ; actualiser le callback nécessite une nouvelle demande.

Cette page sert à la démonstration et ne gère pas de tokens côté navigateur. Lorsqu’une
connexion est initiée par une demande OAuth existante, Spring reprend cette demande après login.

### Échanges OAuth

1. Le client génère un `code_verifier` aléatoire conforme à RFC 7636 et calcule
   `code_challenge=BASE64URL(SHA256(ASCII(code_verifier)))`, sans padding.
2. Ouvrir dans le navigateur `GET /oauth2/authorize` avec `response_type=code`,
   `client_id`, `redirect_uri`, `scope=hello.read`, un `state` aléatoire,
   `code_challenge` et `code_challenge_method=S256`.
3. Après connexion, le callback reçoit `code` et `state`. **Le client doit vérifier
   state** avant de poursuivre. Un code expire après deux minutes et n'est utilisable
   qu'une fois.
4. `POST /oauth2/token`, formulaire URL-encoded : `grant_type=authorization_code`,
   `code`, `redirect_uri`, `client_id`, `code_verifier`. Le client confidentiel ajoute
   HTTP Basic. Le serveur vérifie PKCE, le client, le callback et les droits accordés.
5. Réponse : `access_token`, `token_type=Bearer`, `expires_in`, `scope`, `refresh_token`.
   Le JWT ES256 a pour sujet l'identifiant stable `subject-N`, pour audience
   `portage-api` et dure cinq minutes. Les refresh tokens sont opaques et aléatoires.
6. Renouveler par `POST /oauth2/token` avec `grant_type=refresh_token`, `refresh_token`
   et `client_id`, plus HTTP Basic pour `demo-client`. Le `scope` optionnel doit rester
   dans les droits initiaux. Conserver le **nouveau** refresh token à chaque réponse.
7. Révoquer par `POST /oauth2/revoke` avec `token`, `token_type_hint=refresh_token`
   et `client_id`, plus HTTP Basic pour le client confidentiel. La révocation d'un
   token inconnu retourne aussi 200. Révoquer un ancien refresh token révoque sa famille.

Le refresh token expire après huit heures sans renouvellement ; la famille entière
expire au plus tard sept jours après sa création. Une nouvelle connexion est alors
nécessaire. Une demande d'un autre client, un token expiré/révoqué ou un ancien token
retourne `invalid_grant` (400). Une élévation de scope retourne `invalid_scope`.

Les endpoints `/oauth2/token` et `/oauth2/revoke` ne redirigent pas vers la page de
connexion lorsqu’aucune authentification client n’est reconnue : ils retournent
`401` avec une erreur JSON `invalid_client`, indépendamment de l’en-tête `Accept`.
Pour l’échange PKCE public, vérifier notamment `client_id`, `code_verifier` et
`Content-Type: application/x-www-form-urlencoded`. Un code expiré ou inconnu est
une erreur OAuth ; il ne nécessite pas de connecter Postman à la session navigateur.

## Rotation, réutilisation et concurrence

`JdbcOAuth2AuthorizationService` conserve le client, le principal, les droits et les
tokens Spring. Une table complémentaire conserve les empreintes SHA-256 des refresh
tokens et leur famille ; les anciennes valeurs brutes ne sont pas conservées dans
cette table. Présenter un ancien token avec le client d'origine invalide toute la
famille, y compris son dernier refresh token. Un autre client ne peut pas révoquer
la famille en présentant un token volé.

L'échange de code, le renouvellement et la révocation sont exécutés sous transaction
avec verrou JDBC, depuis la lecture jusqu'à la sauvegarde. La réponse contenant les
nouveaux tokens n'est envoyée qu'après commit. Deux demandes concurrentes utilisant
le même refresh token produisent une réussite et un refus ; la seconde est un rejeu
et invalide aussi le token obtenu par la première. **Les clients doivent sérialiser
leurs renouvellements**. Une réponse perdue impose une nouvelle connexion si le
client ne connaît plus le nouveau token ; aucune période de grâce ne permet le rejeu.

La révocation porte sur l'autorisation stockée. Les JWT déjà délivrés restent
acceptables par le Resource Server jusqu'à leur expiration (cinq minutes), car il
valide localement les signatures. La déconnexion de la session navigateur ne révoque
pas implicitement les autorisations OAuth : utiliser l'endpoint de révocation.

## Limites et migration

H2 embarqué convient à cette plateforme à instance unique. Le verrou global borne
le débit et englobe l'appel au signataire. Pour plusieurs instances en production,
migrer vers un serveur SQL partagé et des verrous par famille ; ne pas partager le
fichier H2 entre conteneurs. Les empreintes historiques sont conservées avec les
autorisations ; prévoir une politique de purge des familles expirées selon la durée
de conservation retenue. La rotation de la clé de chiffrement exige une migration
du stockage et n'est pas automatique.

Le grant pédagogique avec mot de passe est **désactivé par défaut**. Une transition
explicite est possible avec `OAUTH_LEGACY_GRANT_ENABLED=true` ; ce grant ne reçoit pas
de refresh token. `scripts/demo-legacy.py` active cette compatibilité uniquement pour ses
scénarios historiques. `postman/legacy.collection.json` concerne aussi ce mode historique.
`postman/pkce.collection.json` décrit l'échange et le cycle de renouvellement avec un
code fraîchement obtenu dans le navigateur. Aucun token réel ne doit être exporté
ou commité dans un environnement Postman.

## Vérification

`AuthorizationCodePkceHttpTest` couvre la connexion navigateur avec CSRF, PKCE S256,
le mauvais vérificateur, l'usage unique du code, les deux types de clients, la rotation,
le sujet et les droits, l'expiration, la révocation, le mauvais client, l'élévation de
scope, le rejeu et les requêtes concurrentes. Les tests utilisent un vrai Key Service
isolé. `AuthorizationPersistenceTest` vérifie une réouverture du stockage, les sessions
persistantes, les consentements et le refus d'une mauvaise clé de chiffrement.

Lancer `mvn clean verify` puis `python scripts/check-coverage.py`.
