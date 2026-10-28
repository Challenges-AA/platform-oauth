# Diagnostic de configuration — 27 octobre 2026

Dernière consigne utilisateur : investigation et interventions Docker suspendues. Les commandes Docker/WSL ci-dessous sont conservées à titre documentaire pour une validation ultérieure ; elles ne seront plus exécutées dans cette session. Conteneurs : **non exécutés — Docker indisponible**. Aucun test actuel JUnit/Cucumber ne dépend de Docker : les tests HTTP utilisent Boot RANDOM_PORT et le HttpServer du JDK. Aucun profil Maven spécial ni désactivation de tests n'est nécessaire.

Java 25, Spring Boot 4.1.2 et les versions imposées des plugins sont conservés. Les échecs Maven observés sont antérieurs à la compilation : ils ne prouvent aucun échec de test métier.

| Contrôle | Résultat | Preuve / limite |
|---|---|---|
| Versions déclarées | Passé | parent Boot 4.1.2 ; release 25 ; compiler 3.14.0 ; Surefire/Failsafe 3.5.6 ; Cucumber 8.0.4 ; JaCoCo 0.8.14 ; exec 3.6.3 |
| Compatibilité Java/Boot | Passé documentaire | Boot 4.1.2 supporte Java 17 à 27 ; Maven >= 3.6.3. Maven installé 3.9.16 |
| JaCoCo / Java 25 | Passé documentaire | 0.8.14 ajoute le support officiel Java 25 |
| Cucumber / JUnit | Aligne dans le POM, non execute | Cucumber8.0.4 et BOM JUnit6.1.3 ; Boot fournissait6.0.3. Voir sources ci-dessous |
| Dépôt Maven par défaut | Échoué | build-01.json : création impossible de C:\.m2\repository (user.home de cette session) |
| Cache utilisateur hors ligne | Échoué | build-02.json : Failsafe 3.5.6 absent ; modules sautés |
| Dépôt local au projet, en ligne | Échoué | build-03.json : parent inaccessible, Permission denied: getsockopt |
| DNS Maven Central | Passé | configuration-environment.json : résolution IPv4 réussie |
| HTTPS Maven Central | Échoué avant TLS | WinError 10013 ; aucune erreur de certificat observée, validation TLS non atteinte |
| Docker context / WSL | Échoué, accès refusé | configuration-environment.json : fichiers Docker inaccessibles ; Wsl/EnumerateDistros/Service/E_ACCESSDENIED |
| clean verify complet, Failsafe, couverture agrégée | Non exécuté jusqu'au bout | résolution Maven bloquante ; ne pas annoncer une couverture vérifiée |
| Tests / démonstration JVM et Docker | Indépendants | tests locaux HttpServer JDK et Boot RANDOM_PORT ; aucun Testcontainers trouvé |

Les settings globaux ne contiennent aucun serveur/proxy déclaré ni offline explicite ; seul miroir actif : maven-default-http-blocker vers 0.0.0.0 pour external:http:*. Ce miroir HTTP ne bloque pas Central HTTPS. Aucun settings utilisateur trouvé à C:\Users\nakoe\.m2\settings.xml. Aucun secret n'a été consigné. Aucun changement global, installation ou démarrage Docker n'a été effectué.

Le BOM Boot 4.1.2 declare JUnit 6.0.3. Le POM final aligne maintenant junit-jupiter.version sur 6.1.3 pour toute la famille JUnit. Le changelog Cucumber mentionne 6.1.2 mais ses notes de version 8 et le POM core 8.0.4 utilisent 6.1.3 : cette derniere version est retenue. Source exacte : https://raw.githubusercontent.com/cucumber/cucumber-jvm/v8.0.4/release-notes/v8.0.0.md . La resolution et execution du graphe final restent bloquees par le reseau ; les diagnostics locaux utilisent JUnit 6.0.3 en cache.

## Reproduction Git Bash Windows

Depuis une session utilisateur disposant des accès réseau normaux, appliquer uniquement au processus courant :

```bash
cd /c/Defi-cyberportage/platform-oauth
export JAVA_HOME='C:\Program Files\Java\jdk-25.0.5.0.3'
export PATH="$(cygpath -u "$JAVA_HOME")/bin:/c/apache-maven-3.9.16/bin:$PATH"
hash -r
java -version
mvn -version
python --version
mkdir -p .work/m2 docs/evidence
REPO_WIN="$(cygpath -w "$PWD/.work/m2")"
set -o pipefail
mvn -B -ntp -U "-Dmaven.repo.local=$REPO_WIN" dependency:go-offline 2>&1 | tee docs/evidence/configuration-prewarm.log
prewarm_rc=${PIPESTATUS[0]}
printf 'Maven prewarm exit: %s\n' "$prewarm_rc"
mvn -B -ntp -U "-Dmaven.repo.local=$REPO_WIN" clean verify 2>&1 | tee docs/evidence/configuration-clean-verify.log
build_rc=${PIPESTATUS[0]}
printf 'Maven clean verify exit: %s\n' "$build_rc"
```

`dependency:go-offline` prépare dépendances et plugins, mais `clean verify` reste nécessaire : certaines résolutions dynamiques peuvent encore arriver pendant les tests. Ne pas ajouter `-o` tant que ce dépôt n'est pas complet. Ne pas ajouter de propriété de saut des tests. Le gate agrégé utilise également Python. En cas de socket interdit, faire autoriser Maven/Java vers repo.maven.apache.org:443 dans l'environnement réseau utilisé ; si un proxy d'organisation est requis, utiliser sa configuration approuvée et ne pas publier ses identifiants. Ne pas désactiver SSL et ne pas remplacer HTTPS par HTTP.

## Diagnostic Docker facultatif

À lancer dans une session Windows utilisateur normale (commandes de lecture seule) :

```bash
docker --version
docker context ls
docker context show
docker version
docker info
wsl.exe --status
wsl.exe --list --verbose
```

Le CLI installé ne prouve pas que le moteur fonctionne. L'absence du pipe docker_engine dans l'essai initial signifie que cet endpoint n'était pas disponible dans cette session ; les contextes étant inaccessibles ici, le contexte Desktop réel n'a pas été validé. Les refus WSL ne prouvent pas une absence d'installation.

Interventions proposées, non exécutées : ouvrir Docker Desktop dans la session de l'utilisateur et attendre le moteur ; vérifier les prérequis WSL 2 et la virtualisation dans les paramètres Windows/Desktop ; sélectionner le contexte Desktop Linux seulement s'il existe ; relancer `docker version` et `docker info`. Ne pas lancer de réinitialisation, installation ou migration de contexte sans validation. Les tests et la démonstration JVM ne nécessitent aucune de ces interventions ; Compose reste une option de déploiement.

## Références officielles consultées

- [Exigences Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
- [Cucumber JVM : journal des versions, 8.0.0 et 8.0.4](https://github.com/cucumber/cucumber-jvm/blob/main/CHANGELOG.md) : baseline Java 17 et Platform 6.1.2.
- [JaCoCo : historique, 0.8.14](https://www.jacoco.org/jacoco/trunk/doc/changes.html)
- [Maven Surefire et JUnit Platform](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html) : la page courante décrit également Surefire 3.6.x ; elle ne remplace pas une exécution du plugin 3.5.6 imposé ici.
