# Résumé et plan de test manuel Postman — Authorization Code avec PKCE

## 1. Ce qui a été implémenté

- Connexion utilisateur sur `/login`, protégée contre les requêtes sans jeton CSRF.
- Authorization Code avec PKCE S256 obligatoire pour les clients public et confidentiel.
- Client public `portage-public`, sans secret ; client confidentiel `demo-client`, avec HTTP Basic.
- Scope autorisé : `hello.read`. Les JWT ont pour audience `portage-api` et pour sujet `subject-1` à `subject-5`.
- Codes à usage unique, valables deux minutes ; access tokens ES256 valables cinq minutes.
- Refresh tokens opaques, liés à l'autorisation Spring : client, utilisateur, scopes.
- Un nouveau refresh token à chaque renouvellement ; expiration après huit heures sans utilisation.
- Une famille de refresh tokens expire au plus tard sept jours après sa création.
- Historique SHA-256 des refresh tokens : un rejeu par le client d'origine révoque la famille.
- Transaction et verrou en base pour les échanges de codes, renouvellements et révocations.
- Autorisations, sessions HTTP et service de consentement Spring persistés dans H2 chiffré AES.
- Cookies HttpOnly, SameSite=Lax, Secure par défaut ; sessions navigateur inactives expirant après 30 minutes.
- Grant pédagogique désactivé par défaut, disponible uniquement par activation explicite.

Les clients et les comptes de démonstration restent reconstruits depuis la configuration au démarrage.
Les deux clients configurés n'exigent pas d'écran de consentement explicite.

## 2. Nouveaux fichiers et classes

Les classes de production se trouvent dans `authorization-server/src/main/java/org/portage/authorizationserver/`.

| Fichier / classe | Rôle |
| --- | --- |
| `config/AuthorizationPersistenceConfiguration.java` | DataSource Hikari/H2 chiffrée, schémas Spring, transactions, Spring Session JDBC et cookies. |
| `security/PublicRefreshClientAuthentication.java` | Identification des clients publics pour le renouvellement et la révocation ; laisse l'échange PKCE aux composants Spring. |
| `security/RotatingAuthorizationService.java` | Enveloppe `JdbcOAuth2AuthorizationService`, conserve l'historique des empreintes et invalide une famille sur rejeu. |
| `security/RotatingRefreshTokenGenerator.java` | Génère les refresh tokens opaques et applique la limite absolue de sept jours. |
| `security/TokenTransactionFilter.java` | Transaction autour de l'échange, du renouvellement et de la révocation ; réponse envoyée après commit. |
| `authorization-server/src/main/resources/authorization-lifecycle.sql` | Tables de verrouillage et d'historique des refresh tokens. |
| `authorization-server/src/test/java/org/portage/authorizationserver/AuthorizationCodePkceHttpTest.java` | Tests HTTP du flux, des cas de sécurité et des erreurs JSON sans redirection. |
| `authorization-server/src/test/java/org/portage/authorizationserver/config/AuthorizationPersistenceTest.java` | Deux tests du stockage, de sa réouverture, des sessions et des clés de chiffrement. |
| `docs/AUTHORIZATION_CODE_PKCE.md` | Contrat HTTP, configuration, politique de rotation et limites. |
| `postman/pkce.collection.json` | Dossiers Automated PKCE (connexion et cycle complet) et Manual code (code obtenu dans le navigateur). |
| `docs/PLAN_TEST_MANUEL_POSTMAN_PKCE.md` | Présent guide. |

Fichiers existants modifiés : `AuthorizationSecurityConfiguration.java`, `application.properties`,
`authorization-server/pom.xml`, `AuthorizationFixture.java`, `README.md`, `.gitignore`,
`Dockerfile.authorization-server`, `compose.yaml`, `scripts/demo-pkce.py` et le rapport `docs/evidence/coverage.json`.

## 3. Préparation

### Exécution automatisée avec Newman

Prérequis : Java 25, Node.js et le module Node `newman` (`npm install --no-save --package-lock=false newman`).

```bash
python scripts/demo-pkce.py
```

Le script construit et vérifie le projet, lance les trois services sur les ports 9000–9002
avec des secrets et un stockage temporaires, exécute Newman puis arrête les services.
Les ports doivent être libres. Pour utiliser des JAR déjà construits :

```bash
python scripts/demo-pkce.py --skip-build
```

Sur des services déjà démarrés, fournir `OAUTH_USER1_PASSWORD` dans l'environnement,
puis lancer `node scripts/run-newman-pkce.cjs`. Le lanceur utilise `user1`, le client public
`portage-public` et le callback `OAUTH_REDIRECT_URI` (défaut : `http://127.0.0.1:8080/callback`).
`PKCE_AS_URL` et `PKCE_RS_URL` permettent de changer les adresses des services.
En HTTP local, l'AS doit être lancé avec `OAUTH_SECURE_COOKIE=false`.

Le dossier Automated PKCE vérifie : formulaire CSRF, connexion avec cookie, callback et state,
échange PKCE S256, accès API et identité, refus sans token, rotation du refresh token,
détection du rejeu et révocation de la famille, validité du JWT déjà émis,
usage unique du code, puis révocation explicite d'une nouvelle autorisation.
Le rejeu du code est testé après le cycle de renouvellement car il peut invalider l'autorisation.

Rapports : `.work/reports/newman-pkce.json` et `newman-pkce.xml`, avec compteurs assainis.
Aucun détail de requête/réponse ni token n'est exporté. Un échec produit un code de sortie non nul.
Ce parcours ne couvre pas le client confidentiel, l'expiration réelle ou la concurrence ;
les tests Java et les scénarios manuels restent complémentaires.

Dans Postman, sélectionner un seul dossier. Pour Automated PKCE, désactiver le suivi
automatique des redirections et renseigner `username` et `password` dans un environnement local.
Pour Manual code, renseigner `authorization_code`, `code_verifier` et le callback exact
obtenus via le navigateur. Ne pas exporter les valeurs sensibles.

### Services

Démarrer les trois services avec les secrets décrits dans `docs/KEY_SERVICE.md` :

| Service | Adresse |
| --- | --- |
| Authorization Server | `http://127.0.0.1:9000` |
| Resource Server | `http://127.0.0.1:9001` |
| Key Service | `http://127.0.0.1:9002` |

Ajouter ces variables à l'environnement du serveur d'autorisation :

| Variable | Valeur pour les essais |
| --- | --- |
| `OAUTH_STORAGE_KEY` | Base64 de 32 octets aléatoires ; conserver la même valeur aux redémarrages. |
| `OAUTH_STORAGE_DIRECTORY` | Un répertoire persistant réservé aux essais. |
| `OAUTH_REDIRECT_URI` | `http://127.0.0.1:8080/callback` |
| `OAUTH_SECURE_COOKIE` | `false`, uniquement pour ces essais HTTP locaux. |

Le callback n'a pas besoin d'un serveur sur le port 8080 pour la procédure ci-dessous :
Postman lit l'en-tête `Location` sans suivre la redirection.
Pour une séance manuelle persistante, lancer les JAR séparément : `demo-pkce.py` arrête ses services après les tests.

### Réglages Postman

- Utiliser l'application desktop, ou le Desktop Agent pour accéder aux services locaux.
- Désactiver **Automatically follow redirects** pour observer les réponses 302.
- Garder le cookie jar actif ; utiliser toujours `127.0.0.1`, sans alterner avec `localhost`.
- Mettre **Authorization → No Auth** sur les requêtes du client public et de connexion.
- Utiliser **Body → x-www-form-urlencoded** pour les POST OAuth et de connexion.
- Garder les secrets et tokens dans un environnement local ; ne pas les exporter ni les partager.

Références Postman : [redirections](https://learning.postman.com/docs/use/send-requests/create-requests/request-settings),
[cookies](https://learning.postman.com/v11/docs/use/send-requests/response-data/cookies).

### Variables de l'environnement Postman

| Nom | Valeur |
| --- | --- |
| `as` | `http://127.0.0.1:9000` |
| `rs` | `http://127.0.0.1:9001` |
| `client_id` | `portage-public` |
| `client_secret` | Valeur de `OAUTH_CLIENT_SECRET`, pour les essais confidentiels. |
| `username` | `user1` |
| `password` | Valeur de `OAUTH_USER1_PASSWORD`. |
| `redirect_uri` | `http://127.0.0.1:8080/callback` |
| `code_verifier` | `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` |
| `code_challenge` | `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM` |

La paire PKCE ci-dessus est le vecteur public de l'[annexe B de RFC 7636](https://www.rfc-editor.org/rfc/rfc7636.html#appendix-B).
Elle sert uniquement aux essais reproductibles. Un vrai client doit générer une nouvelle paire aléatoire par autorisation.
Les variables `csrf`, `state`, `authorization_code`, `access_token`, `refresh_token`,
`previous_refresh_token` et `concurrent_refresh_token` seront renseignées pendant les essais.

## 4. Parcours nominal, entièrement dans Postman

Alternative navigateur : ouvrir `/login`, se connecter puis cliquer sur
**Démarrer une autorisation PKCE** à l’accueil. Le callback de démonstration vérifie
le state et présente tous les champs à copier dans R4. Utiliser le vérificateur
généré affiché, et le callback `/demo/callback`, plutôt que la paire fixe ci-dessous.
Le parcours R1 à R7 reste disponible pour des tests entièrement dans Postman.

### R1 — Obtenir le formulaire de connexion

`GET {{as}}/login`, No Auth.

Attendu : **200**, formulaire HTML, cookie de session. Dans **Scripts → Post-response** :

```javascript
const match = pm.response.text().match(/name="_csrf"[^>]*value="([^"]+)"/);
pm.test('Formulaire et CSRF disponibles', () => {
  pm.response.to.have.status(200);
  pm.expect(match).not.to.eql(null);
});
if (match) pm.environment.set('csrf', match[1]);
```

### R2 — Authentifier l'utilisateur

`POST {{as}}/login`, No Auth, formulaire :

```text
username = {{username}}
password = {{password}}
_csrf    = {{csrf}}
```

Attendu : **302**, `Location` sans `?error`. Conserver le cookie mis à jour par Postman.
La redirection par défaut vers `/` affiche désormais l’accueil protégé. Pour continuer
entièrement dans Postman, garder le cookie et passer à R3.

### R3 — Demander un code avec PKCE

`GET {{as}}/oauth2/authorize`, No Auth, header `Accept: text/html`.
Dans **Params** :

```text
response_type         = code
client_id             = {{client_id}}
redirect_uri          = {{redirect_uri}}
scope                 = hello.read
state                 = {{state}}
code_challenge        = {{code_challenge}}
code_challenge_method = S256
```

Script **Pre-request** :

```javascript
pm.environment.set('state', pm.variables.replaceIn('{{$guid}}'));
pm.environment.unset('authorization_code');
```

Script **Post-response** :

```javascript
const location = pm.response.headers.get('Location') || '';
const code = location.match(/[?&]code=([^&]+)/);
const state = location.match(/[?&]state=([^&]+)/);
pm.test('Code retourné au callback avec le bon state', () => {
  pm.response.to.have.status(302);
  pm.expect(location.startsWith(pm.environment.get('redirect_uri') + '?')).to.eql(true);
  pm.expect(code).not.to.eql(null);
  pm.expect(state).not.to.eql(null);
  pm.expect(decodeURIComponent(state[1])).to.eql(pm.environment.get('state'));
});
if (code) pm.environment.set('authorization_code', decodeURIComponent(code[1]));
```

Attendu : **302** vers le callback, avec `code` et `state`. Échanger le code sous deux minutes.

### R4 — Échanger le code contre des tokens

`POST {{as}}/oauth2/token`, No Auth pour `portage-public`, formulaire :

```text
grant_type    = authorization_code
client_id     = {{client_id}}
code          = {{authorization_code}}
redirect_uri  = {{redirect_uri}}
code_verifier = {{code_verifier}}
```

Attendu : **200**, `access_token`, `refresh_token`, `token_type=Bearer`,
`scope=hello.read`, `expires_in` proche de 300, header `Cache-Control: no-store`.

Si le serveur ne reconnaît pas l’authentification client, il retourne **401 JSON
`invalid_client`**, sans page de connexion. Vérifier le Content-Type et les champs
du formulaire, notamment `code_verifier` pour le client public. Si le code est
expiré ou inconnu, l’échange reconnu doit retourner une erreur OAuth telle que
**400 `invalid_grant`**. Une session navigateur n’est pas nécessaire dans Postman.

Script de capture à utiliser sur R4 et R6, uniquement sur une réponse 200 :

```javascript
if (pm.response.code === 200) {
  const body = pm.response.json();
  pm.test('Tokens émis sans cache', () => {
    pm.expect(body.access_token).to.be.a('string').and.not.empty;
    pm.expect(body.refresh_token).to.be.a('string').and.not.empty;
    pm.expect(body.token_type.toLowerCase()).to.eql('bearer');
    pm.expect(body.scope).to.eql('hello.read');
    pm.expect(body.expires_in).to.be.within(298, 300);
    pm.expect(pm.response.headers.get('Cache-Control')).to.include('no-store');
  });
  pm.environment.set('access_token', body.access_token);
  pm.environment.set('refresh_token', body.refresh_token);
}
```

### R5 — Appeler la ressource protégée

`GET {{rs}}/hello`, Authorization **Bearer Token** : `{{access_token}}`.

Attendu : **200**, `{"message":"HelloWorld","subject":"subject-1"}` pour `user1`. Sans Bearer : **401**.
Décoder localement le JWT permet aussi de vérifier `sub=subject-1`, `aud=portage-api`,
`scope=hello.read`, `alg=ES256` et `exp-iat=300`. Le décodage seul ne vérifie pas la signature ;
l'appel accepté par le Resource Server vérifie l'intégration réelle.

### R6 — Renouveler et vérifier la rotation

`POST {{as}}/oauth2/token`, No Auth pour le client public, formulaire :

```text
grant_type    = refresh_token
client_id     = {{client_id}}
refresh_token = {{refresh_token}}
```

Script **Pre-request** :

```javascript
pm.environment.set('previous_refresh_token', pm.environment.get('refresh_token'));
```

Réutiliser le script de capture de R4, puis ajouter :

```javascript
if (pm.response.code === 200) {
  pm.test('Refresh token remplacé', () => {
    pm.expect(pm.response.json().refresh_token)
      .not.to.eql(pm.environment.get('previous_refresh_token'));
  });
}
```

Attendu : **200**, nouveau refresh token, nouveau JWT accepté par R5.
Pour les tests négatifs, dupliquer R6 **sans son Pre-request et sans son script de capture**
afin de préserver les variables de référence.

### R7 — Révoquer

`POST {{as}}/oauth2/revoke`, No Auth pour le client public, formulaire :

```text
client_id       = {{client_id}}
token           = {{refresh_token}}
token_type_hint = refresh_token
```

Attendu : **200**, corps généralement vide. R6 avec ce token doit ensuite retourner
**400 / invalid_grant**. Le JWT déjà émis peut encore être accepté par R5 jusqu'à son expiration.

### Refaire le parcours avec le client confidentiel

Définir `client_id=demo-client`. Sur R4, R6 et R7, sélectionner **Basic Auth** :
username `demo-client`, password `{{client_secret}}`. R1, R2 et R3 restent en No Auth.
PKCE demeure obligatoire. Ne pas ajouter `client_secret` au formulaire.

## 5. Matrice des scénarios manuels

Chaque ligne destructrice démarre avec un nouveau code R3/R4 et une nouvelle famille.
Pour les contrôles non déjà couverts par les tests automatisés, les résultats ci-dessous
constituent les critères de recette à confirmer, pas une déclaration d'exécution manuelle.

### Connexion et émission du code

| ID | Manipulation | Résultat attendu |
| --- | --- | --- |
| A01 | R1 → R2 avec les bons identifiants. | Connexion 302 sans `error`. |
| A02 | R2 avec mauvais mot de passe ou utilisateur inconnu, CSRF valide. | Redirection vers `/login?error`, aucune session authentifiée. |
| A03 | R2 sans `_csrf` ou avec valeur incorrecte. | 403, connexion refusée. |
| A04 | Effacer le cookie de session puis appeler R3 avec Accept HTML. | Redirection vers `/login`, aucun code. |
| A05 | R3 sans `code_challenge` et sans méthode. | Refus OAuth, aucun code. |
| A06 | R3 avec méthode `plain`, puis avec méthode inconnue. | Refus OAuth, aucun code. |
| A07 | R3 avec challenge trop court ou caractères hors Base64URL. | Refus OAuth, aucun code. |
| A08 | R3 avec scope `admin`. | `invalid_scope`, aucun code. |
| A09 | R3 avec `client_id` inconnu ou callback non enregistré. | Refus ; ne pas rediriger vers un callback non validé. |
| A10 | R3 nominal, comparer state envoyé et state reçu. | Valeurs identiques. La comparaison est à la charge du client. |
| A11 | R3 pour `user1`, puis nouvelle connexion distincte pour `user2`. | JWT avec `subject-1`, puis `subject-2` ; pas de mélange des sessions. |

Un refus sur `/oauth2/authorize` peut être une réponse 400 ou une redirection 302
contenant `error`, si le callback a déjà été validé. Vérifier surtout l'absence de `code`.

### Échange de code et ressource

| ID | Manipulation | Résultat attendu |
| --- | --- | --- |
| C01 | R4 nominal pour les deux clients. | 200 avec access et refresh tokens. |
| C02 | R4 avec mauvais `code_verifier`, puis sans vérificateur. | Refus OAuth 400/401, aucun token. |
| C03 | Mauvais vérificateur et paramètre `portage.public-refresh=true`. | Même refus : le paramètre ne contourne pas PKCE. |
| C04 | R4 avec un autre `redirect_uri`. | 400 / invalid_grant. |
| C05 | R4 avec un code aléatoire. | Refus OAuth, aucun token. |
| C06 | Obtenir un code, attendre plus de deux minutes, R4. | 400 / invalid_grant. |
| C07 | R4 nominal, puis rejouer exactement le même code. | 400 / invalid_grant ; le refresh token issu du premier échange est invalidé. |
| C08 | Utiliser le code avec un autre client authentifié. | Refus OAuth, aucun token ; reprendre ensuite avec une nouvelle autorisation. |
| C09 | Client confidentiel : secret faux, puis authentification Basic absente. | Refus d'authentification client, généralement 401 / invalid_client. |
| C10 | R5 sans token, avec token modifié, puis token expiré après cinq minutes. | 401 dans les trois cas. |
| C11 | R5 avec le JWT nominal avant expiration. | 200 / HelloWorld. |
| C12 | R4 avec grant inconnu ou paramètre obligatoire absent. | Erreur OAuth 4xx, aucun token, jamais 200. |

### Renouvellement et liaison au client

| ID | Manipulation | Résultat attendu |
| --- | --- | --- |
| F01 | R6 nominal pour les deux clients. | 200 et refresh token différent. |
| F02 | Plusieurs R6 successifs, en utilisant toujours le dernier token. | Toutes les demandes réussissent et tournent le token. |
| F03 | R6 avec `scope=hello.read`. | 200, mêmes droits. |
| F04 | R6 avec `scope=admin`. | 400 / invalid_scope ; le token initial reste utilisable avec le bon scope. |
| F05 | Token de `portage-public` présenté par `demo-client` avec son bon Basic. | 400 / invalid_grant ; le propriétaire peut encore utiliser son token. |
| F06 | Token de `demo-client` présenté par `portage-public`. | 400 / invalid_grant ; pas d'accès à l'autre utilisateur/client. |
| F07 | R6 avec token inventé ou tronqué. | 400 / invalid_grant. |
| F08 | Client public avec `client_id` absent, vide ou inconnu. | Refus client OAuth, aucun token. |
| F09 | Client confidentiel avec secret incorrect. | Refus client OAuth, généralement 401 / invalid_client. |
| F10 | Comparer les JWT avant et après R6. | Même sub, aud et scope ; nouvelles dates d'émission/expiration et nouveau jti. |

### Rejeu, révocation et isolation des familles

| ID | Manipulation | Résultat attendu |
| --- | --- | --- |
| S01 | R6 : A devient B ; rejouer A avec son client d'origine. | 400 / invalid_grant. |
| S02 | Après S01, utiliser B. | 400 / invalid_grant : toute la famille est invalidée. |
| S03 | Après S01, obtenir une nouvelle autorisation C et renouveler C. | 200 : une autre famille reste indépendante. |
| S04 | A devient B ; présenter A avec l'autre client, puis B avec le bon client. | Mauvais client refusé ; B fonctionne, sans révocation de sa famille. |
| S05 | R7 sur le refresh token actif, puis R6. | Révocation 200, renouvellement 400 / invalid_grant. |
| S06 | A devient B ; R7 sur A avec le bon client, puis R6 sur B. | Révocation 200 ; B refusé. |
| S07 | R7 avec token inconnu ou déjà révoqué. | 200, sans révéler l'existence du token. |
| S08 | Révoquer un token d'un autre client avec un client valide, puis utiliser le token avec son propriétaire. | Pas de révocation de l'autorisation du propriétaire. |
| S09 | R7 puis R5 avec le JWT déjà émis. | Encore accepté avant expiration ; 401 après expiration. |
| S10 | Deux autorisations indépendantes ; révoquer seulement la première. | Le renouvellement de la deuxième réussit. |

### Concurrence

Deux clics manuels ne garantissent pas un chevauchement. Pour lancer deux demandes
depuis Postman, obtenir une famille neuve, recopier son token dans `concurrent_refresh_token`,
puis créer `GET {{as}}/oauth2/jwks` et ajouter le script **Post-response** suivant.
Ce script concerne le client public. Ne pas cliquer plusieurs fois sur cette requête.

```javascript
const token = pm.environment.get('concurrent_refresh_token');
const responses = [];
for (let i = 0; i < 2; i++) {
  pm.sendRequest({
    url: pm.environment.get('as') + '/oauth2/token',
    method: 'POST',
    header: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: {
      mode: 'urlencoded',
      urlencoded: [
        {key: 'grant_type', value: 'refresh_token'},
        {key: 'client_id', value: 'portage-public'},
        {key: 'refresh_token', value: token}
      ]
    }
  }, (error, response) => {
    pm.test('Appel concurrent reçu', () => pm.expect(error).to.eql(null));
    if (error) return;
    responses.push(response);
    if (responses.length === 2) {
      pm.test('Une réussite et un refus', () => {
        pm.expect(responses.map(r => r.code).sort()).to.eql([200, 400]);
        pm.expect(responses.find(r => r.code === 400).json().error).to.eql('invalid_grant');
      });
      const winner = responses.find(r => r.code === 200);
      if (winner) pm.environment.set('refresh_after_race', winner.json().refresh_token);
    }
  });
}
```

Ensuite, envoyer une copie de R6 avec `refresh_token={{refresh_after_race}}` :
attendu **400 / invalid_grant**, car la seconde utilisation a révoqué la famille.
Le script lance deux appels asynchrones ; il ne garantit pas leur arrivée simultanée
au serveur. Le test Java avec barrière de départ constitue une vérification complémentaire.
Ne pas utiliser le Runner séquentiel comme preuve de concurrence.

Référence : [pm.sendRequest](https://learning.postman.com/latest-v-12/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-send-request).

### Persistance, expiration et configuration

| ID | Manipulation | Résultat attendu |
| --- | --- | --- |
| P01 | Obtenir un refresh token ; arrêter puis relancer l'AS avec le même répertoire, la même clé et la même configuration ; R6. | 200. |
| P02 | A devient B ; redémarrer ; rejouer A puis présenter B. | Deux refus invalid_grant : l'historique survit au redémarrage. |
| P03 | Révoquer, redémarrer, tenter le renouvellement. | Token toujours refusé. |
| P04 | Se connecter, redémarrer l'AS rapidement, conserver le cookie Postman ; appeler R3. | Code obtenu sans nouveau mot de passe, tant que la session n'a pas expiré. |
| P05 | Après connexion, ne plus utiliser la session pendant plus de 30 minutes ; appeler R3. | Nouvelle connexion nécessaire ; ne pas déduire une révocation des refresh tokens. |
| P06 | Obtenir un refresh token, attendre plus de huit heures sans renouveler, R6. | 400 / invalid_grant. |
| P07 | Maintenir une famille en la renouvelant avant chaque délai de huit heures, jusqu'à dépasser sept jours. | Plus de renouvellement après la limite absolue. |
| P08 | Arrêter l'AS ; tenter de rouvrir la même base avec une autre clé valide de 32 octets. | Démarrage refusé. Restaurer ensuite la bonne clé. |
| P09 | Clé absente, Base64 invalide, ou clé décodée de 16 octets. | Démarrage refusé. |
| P10 | Vérifier les cookies de R1/R2. | HttpOnly et SameSite=Lax ; Secure lorsque la configuration TLS l'active. |
| P11 | Appeler un grant non pris en charge avec un client authentifié. | 400 / unsupported_grant_type ; aucun token. |
| P12 | Appeler grant_type=client_credentials avec demo-client et Basic valide. | 400 / unauthorized_client ; aucun token. |

P01–P04 nécessitent de conserver le stockage ; ne pas supprimer le volume Docker.
P08/P09 sont des tests de démarrage, pas des appels Postman. Arrêter l'AS avant de changer sa clé.
Postman ne peut pas attester le chiffrement au repos par une réponse HTTP : le contrôle
du fichier chiffré et de sa réouverture relève du test de persistance et de l'inspection locale.

Les durées de deux minutes, cinq minutes et huit heures sont codées dans
`AuthorizationSecurityConfiguration.java` ; les sept jours dans le générateur et le service
de rotation. **Aucune variable d'environnement ne permet actuellement de les raccourcir.**
Pour une recette accélérée, utiliser une construction de test séparée avec des durées réduites,
en modifiant la limite de famille à ses deux emplacements, puis repartir d'une base de test neuve.
Les tests Java vérifient déjà l'expiration sans attendre ces délais.

## 6. Collection fournie et preuve de recette

Importer `postman/pkce.collection.json`. Choisir le dossier Automated PKCE pour le parcours Newman complet, ou Manual code pour échanger un code obtenu via le navigateur. Les tokens sont conservés dans des variables locales au run. Pour des clics Send indépendants, utiliser les requêtes et variables décrites plus haut.

Pour chaque scénario, noter : identifiant, client, utilisateur, statut, erreur OAuth,
réussite/échec et date. Ne pas joindre les tokens, mots de passe ni cookies au compte rendu.
Les critères sont : aucun token en cas de refus, aucune élévation de droits, aucune
utilisation par un autre client, rotation effective et révocation durable de la famille en cas de rejeu.

Les résultats courants sont ceux de `docs/evidence/coverage.json`, des rapports Maven et des rapports Newman datés.
