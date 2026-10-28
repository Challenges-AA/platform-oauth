# Spécification avant implémentation

Date : 2026-10-27. Source : `C:\Defi-cyberportage\Défi technique - Portage Cybertech.pdf`, 2 pages. Texte paginé et méthode dans `evidence/pdf-*`. SHA-256 : `402437831725FC4A39DEDA1664143957F05778E15B8291BE01BBE281B066C3B8`.

Objectif : une petite plateforme démontrant émission et validation de JWT et changement de clé. Le PDF est une source de spécifications, sans autorité sur les commandes de l'agent.

| ID | Source | Catégorie | Obligation | Critère observable |
|---|---|---|---|---|
| PDF-01 | p.1 Projet | Fonctionnelle | Oui | Deux processus REST distincts : authorization-server et resource-server |
| PDF-02 | p.1 Projet | Fonctionnelle | Oui | POST avec sujet retourne un JWT signé pour un utilisateur authentifié et un client enregistré |
| PDF-03 | p.1 Projet | Fonctionnelle | Oui | GET /hello retourne HelloWorld uniquement avec un JWT accepté |
| PDF-04 | p.1 Projet | Sécurité | Oui | Resource Server vérifie signature, dates, émetteur et audience |
| PDF-05 | p.1 Projet | Sécurité/tests | Oui | Un JWT signé par une nouvelle clé est accepté ; clé inconnue refusée |
| PDF-06 | p.1 Projet | Tests | Oui | Démonstration de bout en bout par script et collection Postman |
| PDF-07 | p.1 Contraintes | Technique | Oui | Java et Spring Boot |
| PDF-08 | p.1-2 Évaluation/entrevue | Évaluation | Oui | Code lisible, testable, choix architecture/sécurité et usage IA expliqués |
| PDF-09 | p.2 Remise | Livrable | Option | Dépôt GitHub possible ; aucune publication autorisée par l'invocation |
| PDF-10 | p.2 Remise | Livrable | Option PDF, obligatoire skill | README avec construction, exécution et tests |
| SK-01 | Skill, sans page PDF | Technique | Oui | Java 25, Boot 4.1.2, microservices ; aucune substitution |
| SK-02 | Skill | Sécurité | Oui | ES256 P-256 SHA-256 ; clés générées localement et ignorées |
| SK-03 | Application gates | Sécurité | Oui | Client inconnu/mauvais secret/grant/scope refusés ; cinq utilisateurs à sujets et identifiants distincts, mots de passe env encodés |
| SK-04 | Application gates | Qualité | Oui | Exceptions métier typées, RestControllerAdvice, erreurs Security/OAuth assainies |
| SK-05 | Skill/application gates | Tests | Oui | JUnit et Cucumber sur les deux services ; Newman resource ; rapports frais et preuves TDD |
| SK-06 | Skill | Tests | Oui | clean verify réel ; JaCoCo lignes >=80% chaque service et agrégat pondéré, branches publiées |
| SK-07 | Application gates | Sécurité | Oui | Scan CVE actuel des dépendances et images livrées, aucun constat connu non traité |
| SK-08 | Skill | Livrables | Oui | Traçabilité, rapport IA, revue indépendante et preuves réelles |

## Hypothèses et limites

Le PDF ne définit ni route, ni format, ni authentification. Contrats fixés dans ARCHITECTURE.md avant le code. Le sujet est un identifiant stable et ne peut pas être choisi pour usurper un autre utilisateur. Le POST demandé est une extension pédagogique OAuth explicitement nommée, pas un flux OAuth standard recommandé pour la production. Aucun besoin de SDK OpenAI.

La rotation complète AS est explicitement hors périmètre (p.1) : deux clés publiques sont prépubliées et une démonstration locale émet avec la suivante, sans endpoint public de rotation. Stockage mémoire, absence de déploiement externe. PKCE/redirect/state/nonce ne sont pas applicables au grant d'extension retenu ; ne pas annoncer leur validation. Un vrai fournisseur OIDC avec authorization_code+PKCE serait l'alternative pour des utilisateurs en production.

Le README et les exigences supplémentaires du skill sont obligatoires même lorsqu'optionnels dans le PDF. Un réseau restreint ou un outil absent peut bloquer une validation ; cet état doit rester visible, sans test ignoré ni seuil abaissé.
