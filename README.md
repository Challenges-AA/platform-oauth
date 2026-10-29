# Platform OAuth

Plateforme Java 25 / Spring Boot 4.1.2 démontrant **Authorization Code avec PKCE S256**,
des refresh tokens rotatifs et la signature JWT par un service de clés séparé.

## Comprendre le projet

| Emplacement | Responsabilité |
| --- | --- |
| `authorization-server/` — port 9000 | Connexion, PKCE, émission, renouvellement, révocation et sessions JDBC |
| `resource-server/` — port 9001 | `GET /hello`, validation des JWT et du scope `hello.read` |
| `key-service/` — port 9002 | Signature ES256, JWKS public, stockage et rotation des clés P-256 |
| `coverage/` | Rapport JaCoCo agrégé et seuils de couverture |
| `postman/pkce.collection.json` | Collection du flux courant |
| `scripts/` | Vérification, contrôle de couverture, scan CVE et génération du PDF |

Commencer par [l’architecture](docs/ARCHITECTURE.md), puis suivre
[le guide PKCE](docs/AUTHORIZATION_CODE_PKCE.md).
Le [plan de test Postman](docs/PLAN_TEST_MANUEL_POSTMAN_PKCE.md) détaille les requêtes et résultats attendus.
Le [dossier PDF](docs/PLATFORM_OAUTH_ARCHITECTURE_ET_SCENARIOS.pdf) regroupe les schémas et 56 scénarios.

## Construire et tester

Prérequis : JDK 25, Maven 3.9+ et Python 3. Définir `JAVA_HOME` sur le JDK installé.

```powershell
mvn clean verify
```

Ce cycle exécute JUnit, les scénarios Cucumber, JaCoCo et le contrôle des seuils :
au moins 80 % des lignes par service et sur l’ensemble pondéré.
Les rapports sont dans les répertoires `target/` des modules ; le résumé est dans
`docs/evidence/coverage.json`.

Pour conserver une exécution datée avec logs assainis :

```powershell
python scripts/verify.py
```

Ce script utilise le cache Maven `.work/m2` ; `--offline` réutilise uniquement ce cache.
Les rapports détaillés sont générés dans `.work/reports/`, ignoré par Git.
Il n’est pas nécessaire de les versionner.

## Démarrer localement

Provisionner les variables suivantes dans les terminaux de lancement :

| Variable | Usage |
| --- | --- |
| `OAUTH_USER1_PASSWORD` … `OAUTH_USER5_PASSWORD` | Cinq mots de passe distincts ; comptes `user1` … `user5` |
| `OAUTH_CLIENT_SECRET` | Secret du client confidentiel `demo-client` |
| `OAUTH_STORAGE_KEY` | Base64 de 32 octets aléatoires, pour la base chiffrée de l’AS |
| `KEY_SERVICE_MASTER_KEY` | Autre Base64 de 32 octets aléatoires, pour le stockage des clés |
| `KEY_SERVICE_SIGNING_SECRET` | Secret partagé entre l’AS et le Key Service, au moins 32 caractères |
| `KEY_SERVICE_ADMIN_SECRET` | Secret distinct pour la rotation, au moins 32 caractères |
| `OAUTH_SECURE_COOKIE` | `false` uniquement pour les essais HTTP locaux ; `true` par défaut |

Les fichiers `.env` ne sont pas chargés automatiquement par `java -jar` : les variables
doivent être présentes dans l’environnement du processus.
Le compte admin et la clé maîtresse du Key Service sont destinés uniquement à ce service.

Lancer les commandes dans trois terminaux distincts :

```powershell
java -jar key-service/target/key-service-1.0.0-SNAPSHOT-exec.jar
java -jar authorization-server/target/authorization-server-1.0.0-SNAPSHOT.jar
java -jar resource-server/target/resource-server-1.0.0-SNAPSHOT.jar
```

Valeurs facultatives : `OAUTH_REDIRECT_URI` (défaut `http://127.0.0.1:8080/callback`),
`OAUTH_STORAGE_DIRECTORY` (défaut `runtime-auth`), `KEY_SERVICE_DIRECTORY`, `KEY_SERVICE_URL`
et `RESOURCE_SERVER_JWKS_URI`.
Conserver les répertoires persistants et les mêmes clés de chiffrement aux redémarrages.

## Tester le flux courant

Pour le parcours guidé dans le navigateur, ouvrir `http://127.0.0.1:9000/login`,
se connecter puis cliquer sur **Démarrer une autorisation PKCE** à l’accueil.
La page génère un vérificateur et un state aléatoires, puis revient sur
`/demo/callback`. Après vérification du state, elle affiche les valeurs exactes
à utiliser dans Postman, dont `redirect_uri=http://127.0.0.1:9000/demo/callback`.
Le vérificateur est gardé dans le stockage de session de l’onglet pendant le trajet,
puis supprimé au retour. Aucun secret client n’est exposé et aucun token n’est échangé automatiquement.

Si le serveur est accessible sur une autre adresse, définir `OAUTH_DEMO_REDIRECT_URI`
avec la même origine et le chemin `/demo/callback`. Utiliser le même onglet pour tout
le parcours. Une connexion commencée depuis `/oauth2/authorize` reprend sa demande
initiale au lieu d’être forcée vers l’accueil.

Importer `postman/pkce.collection.json` et suivre le [parcours R1 à R7](docs/PLAN_TEST_MANUEL_POSTMAN_PKCE.md).
Clients disponibles : `portage-public` sans secret et `demo-client` avec HTTP Basic.
PKCE S256 est obligatoire pour les deux clients.

Les tests JavaScript du générateur et de la validation du callback se lancent avec Node.js :

```powershell
node --test authorization-server/src/test/js/demo.test.mjs
```

| Élément | Durée |
| --- | --- |
| Code d’autorisation | 2 minutes, usage unique |
| Access token JWT | 5 minutes |
| Refresh token | 8 heures depuis son émission ; remplacé à chaque renouvellement |
| Famille de refresh tokens | 7 jours au maximum |
| Session de connexion | 30 minutes d’inactivité |

Un ancien refresh token rejoué par son client d’origine révoque sa famille.
La révocation n’invalide pas immédiatement les JWT déjà délivrés : le Resource Server
les accepte jusqu’à leur expiration.

## Docker et stockage

```powershell
docker compose up --build
```

Compose utilise les variables d’environnement ou son fichier `.env` et conserve les volumes
`authorization-data` et `signing-keys`. Pour une recette HTTP locale avec cookies, définir
`OAUTH_SECURE_COOKIE=false` dans l’environnement de l’AS via une surcharge Compose locale.
Les ports publiés sont limités à loopback. H2 et le Key Service sont prévus pour une instance unique.
Voir [le guide du Key Service](docs/KEY_SERVICE.md) pour les sauvegardes et la rotation.

## Outils complémentaires

- `python scripts/check-coverage.py` : relire les rapports JaCoCo existants.
- `scripts/scan-cves.ps1` : scanner avec Trivy/Grype lorsqu’ils sont installés.
- `python scripts/build-architecture-pdf.py` : régénérer le PDF ; dépendances Python `reportlab` et `pypdf`, polices Windows Segoe UI/Consolas.
- [Spécification](docs/SPECIFICATION.md) et [traçabilité](docs/REQUIREMENTS_TRACEABILITY.md) : périmètre et correspondance avec les tests.

## Compatibilité historique facultative

Le grant pédagogique avec mot de passe reste **désactivé par défaut**.
Les outils dont le nom contient `legacy` servent uniquement à cette compatibilité :

- `python scripts/demo-legacy.py` : démo temporaire des trois services, avec le grant historique explicitement activé.
- `node scripts/run-newman-legacy.cjs` : collection `postman/legacy.collection.json`, nécessitant Newman, les services démarrés et le grant activé.

La démo historique accepte `--skip-build` et `--skip-newman`, puis arrête ses processus.
Elle ne sert pas à héberger une séance manuelle PKCE.
