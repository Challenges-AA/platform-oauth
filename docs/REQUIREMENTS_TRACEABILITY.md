# Traçabilité

État : INCOMPLET. Sources et critères dans SPECIFICATION.md. Une implémentation présente n'est pas une validation réussie. Le diagnostic utilise des JAR du cache et JUnit 6.0.3 ; le POM final aligne JUnit 6.1.3 pour Cucumber 8, dont la résolution reste bloquée.

| ID / page | Obligation | Implémentation | Test ou preuve | État / écart |
|---|---|---|---|---|
| PDF-01 p.1 | Oui | Deux modules et deux SpringBootApplication | diagnostic-compilation.json ; diagnostic-e2e.json | Deux sources compilées et processus démarrés localement, packaging Maven non validé |
| PDF-02 p.1 | Oui | SubjectGrantAuthenticationConverter/Provider, client enregistré, UserRegistry | AuthorizationServerHttpTest ; diagnostic-tests-* ; diagnostic-e2e.json | Émission réelle diagnostiquée ; cycle final Maven/Cucumber bloqué |
| PDF-03 p.1 | Oui | HelloController + SecurityConfiguration | ResourceServerHttpTest, ResourceSteps, collection Postman | HTTP JUnit et diagnostic E2E réussis ; Newman non exécuté (module absent) |
| PDF-04 p.1 | Oui | Decoder Nimbus ES256, timestamps, issuer, AccessTokenClaimsValidator, scope | ResourceServerHttpTest négatifs et AccessTokenClaimsValidatorTest | Diagnostic JUnit réel ; couverture non mesurée |
| PDF-05 p.1 | Oui | JWKS actif/suivant, GenerateKeys/SignNextToken | JWKS mutable JUnit, diagnostic-e2e.json accepte active/next et refuse unknown | Simulation locale réussie sur classes ; démo packagée bloquée |
| PDF-06 p.1 | Oui | scripts/demo.py et postman/collection.json | demo-attempt.json, demo.json, newman-attempt.json | Démo finale Maven/Newman incomplète ; diagnostic E2E distinct réussi |
| PDF-07 p.1 | Oui | Java25 / Boot4.1.2 | toolchain.json, stack-verification.md, compilation et démarrage réels | Stack conservée |
| PDF-08 p.1-2 | Oui | ARCHITECTURE, README, AI_DEVELOPMENT_REPORT | Revue indépendante FINAL_REVIEW | Documents présents ; entretien humain hors exécution |
| PDF-09 p.2 | Option | Aucune publication | Aucun commit ni dépôt externe créé | Option non réalisée, hors autorisation |
| PDF-10 p.2 | Option PDF / skill obligatoire | README | Relecture et diagnostic commandes | Présent, commandes de reprise non toutes exécutées |
| SK-01 sans page | Oui | POM et modules Java25 Boot4.1.2 | compilation diagnostique ; build-*.json | Build Maven réel en échec de résolution |
| SK-02 sans page | Oui | Nimbus ES256, générateur JDK P-256, gitignore | diagnostic-e2e.json JWKS sans privé, scan local noms/contenus assaini | Cryptographie diagnostiquée, aucune paire de clés livrée |
| SK-03 sans page | Oui | 5 users placeholders, BCrypt, client Basic | UserRegistryTest, SubjectGrantAuthenticationProviderTest, AuthorizationServerHttpTest | Tests diagnostics positifs/négatifs ; suite finale indisponible |
| SK-04 sans page | Oui | Exceptions typées/advice ; handlers Security/OAuth | HTTP401/403 et refus OAuth | Vérifications partielles réelles ; pas d'audit exhaustif des erreurs |
| SK-05 sans page | Oui | JUnit ; Cucumber *IT+JSON/HTML ; Newman JSON/XML assainis | diagnostic-tests-*.json, newman-attempt.json, POM | JUnit diagnostic réussi ; Cucumber/Newman bloqués ; preuves TDD rouge/verte complètes absentes |
| SK-06 sans page | Oui | JaCoCo0.8.14 append, check80 chaque service, module coverage pondéré verify | check-coverage.py refuse rapport absent ; build logs | Couverture lignes/branches inconnue ; gate non satisfait |
| SK-07 sans page | Oui | scan-cves.ps1 deps/images | cve-scan-*.txt ; qa-scripts.json | INCOMPLET scanners/base absents, aucune absence CVE garantie |
| SK-08 sans page | Oui | Documents et evidence, revue agent distinct | FINAL_REVIEW.md | Revue effectuée ; constats validation restent ouverts |

Les contrôles PKCE, redirect URI, state et nonce ne sont pas applicables au grant privé. Le client enregistré n'autorise que ce grant et hello.read. La révocation immédiate et la rotation complète AS restent hors périmètre explicite/limites documentées. Pas de base, gateway ni SDK IA ajoutés.
