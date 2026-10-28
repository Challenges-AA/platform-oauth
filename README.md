# Mini plateforme OAuth

Trois applications Java 25 / Spring Boot 4.1.2 séparent l'autorisation, la cryptographie et l'accès aux ressources.

| Service | Port | Responsabilité |
| --- | --- | --- |
| Authorization Server | 9000 | Authentifier le client et l'utilisateur, vérifier le sujet et les scopes, demander un JWT signé |
| Resource Server | 9001 | Vérifier le JWT ES256 via le JWKS public, protéger `GET /hello` |
| Key Service | 9002 | Générer les clés P-256, stocker l'état chiffré, choisir la clé active, signer et effectuer les rotations |

Le serveur d'autorisation ne détient plus de clé privée. Le Key Service conserve son état AES-256-GCM dans un répertoire persistant, effectue une rotation quotidienne et permet une rotation manuelle avec un compte administrateur distinct du compte de signature. Les anciennes clés publiques restent disponibles quinze minutes afin de vérifier les JWT encore valables.

Voir [le guide du service de clés](docs/KEY_SERVICE.md) pour l'architecture, les contrats, les secrets requis, le démarrage et les limites. Cette évolution remplace le mécanisme historique `active.jwk` / `next.jwk` décrit dans les anciens rapports.

## Build et tests

Prérequis : JDK 25, Maven 3.9+, Python 3 ; Node.js/Newman pour la collection Postman. Définir `JAVA_HOME` sur le JDK installé.

```powershell
mvn clean verify
python scripts/check-coverage.py
```

Le cycle exécute JUnit, Cucumber et les contrôles JaCoCo à 80 % sur chacun des trois services, ainsi que la couverture pondérée. Pour conserver des logs assainis et les rapports frais : `python scripts/verify.py` (dépôt Maven local `.work/m2`).

## Démonstration

```powershell
python scripts/demo.py
```

La démo crée des secrets et un stockage temporaires, construit les JAR, démarre les trois services, vérifie l'émission, les droits de signature et de rotation, les JWT avant/après rotation, les JWKS publics, une panne de signature (503) et un redémarrage sans changement de clé active. Elle lance ensuite Newman. Les processus sont arrêtés et les fichiers temporaires supprimés à la fin.

`--skip-build` réutilise les JAR existants ; `--skip-newman` exécute uniquement les assertions HTTP Python. Aucun secret, JWT ou contenu de clé n'est affiché. Le résultat est enregistré dans `docs/evidence/demo.json`.

## Exécution persistante et Docker

Suivre [les instructions de configuration](docs/KEY_SERVICE.md#démarrage-local). Le Key Service exige une clé maîtresse Base64 de 32 octets et deux secrets distincts d'au moins 32 caractères. L'AS exige son secret client, les cinq mots de passe et le secret de signature du Key Service.

Après le build et le provisionnement des variables :

```powershell
docker compose up --build
```

Compose conserve les clés chiffrées dans le volume nommé `signing-keys`, accessible seulement au Key Service. Garder la même clé maîtresse entre les redémarrages. Les ports publiés sont limités à loopback. La construction et l'exécution Docker nécessitent une validation dans un environnement disposant de Docker.

## Contrats

- AS : `POST http://127.0.0.1:9000/oauth2/token` ; proxy public `GET /oauth2/jwks`.
- RS : `GET http://127.0.0.1:9001/hello`, scope `hello.read`.
- Clés : `GET http://127.0.0.1:9002/oauth2/jwks`, `POST /internal/sign` (signer), `POST /internal/rotate` (admin).
- Issuer : `http://127.0.0.1:9000` ; audience : `portage-api` ; JWT valable 300 secondes.
- Utilisateurs : `user1` à `user5`, associés à `subject-1` à `subject-5` ; secrets fournis par environnement.

Le grant `urn:portage:params:oauth:grant-type:subject` est pédagogique et transporte un identifiant et un mot de passe. Les utilisateurs et clients restent en mémoire. Le service de clés est à instance unique ; TLS, HSM/KMS, haute disponibilité et révocation immédiate ne sont pas implémentés. Les anciens fichiers JWK ne sont pas importés automatiquement.

La collection Postman reste disponible avec `node scripts/run-newman.cjs` après démarrage des services et définition de `OAUTH_CLIENT_SECRET` et `OAUTH_USER1_PASSWORD`. Les rapports sont assainis. Le scan CVE reste disponible dans `scripts/scan-cves.ps1` ; il nécessite ses outils et un accès aux bases de vulnérabilités.
