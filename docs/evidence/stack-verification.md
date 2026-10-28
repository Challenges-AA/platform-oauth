# Vérification stack — 2026-10-27

Java 25.0.5.0.3 et Maven 3.9.16 effectivement exécutés : toolchain.json. JAVA_HOME corrigé uniquement dans les processus de validation, aucune configuration globale changée. Docker CLI existe mais daemon inaccessible, code 1.

Documentation officielle consultée via outil web : https://docs.spring.io/spring-boot/system-requirements.html présente Spring Boot 4.1.2 compatible Java17 à27 et Maven>=3.6.3. POM exact présent dans le cache Maven `org/springframework/boot/spring-boot-starter-parent/4.1.2`; provenance `_remote.repositories` indique central. La résolution réelle sera testée par build, la présence seule ne suffit pas.

https://www.jacoco.org/jacoco/trunk/doc/changes.html confirme support Java25 à partir de0.8.14. Seule0.8.12 présente au cache : pas de substitution pour contourner la couverture. https://github.com/cucumber/cucumber-jvm/releases indique8.0.4 stable actuelle retenue avec JUnit6 de Boot. Aucun artefact Cucumber au cache.

Installation locale pymupdf tentée pour lecture PDF : `python -m pip install --target .work/python pymupdf`, code1, WinError10013 accès socket interdit. Extraction finalement réalisée avec Python standard/CMap ; voir pdf-reading.md. La consultation web fonctionne, contrairement aux sockets du shell ; elle ne fournit pas les dépendances binaires au build.
