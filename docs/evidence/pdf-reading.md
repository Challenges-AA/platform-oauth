# Lecture documentaire du PDF

Source : `C:\Defi-cyberportage\Défi technique - Portage Cybertech.pdf`, 2 pages.
SHA-256 : `402437831725FC4A39DEDA1664143957F05778E15B8291BE01BBE281B066C3B8`.

## Méthode et preuve

Extraction locale avec Python standard, script `.work/pdf-extract.py`, sans accès réseau ni dépendance PDF. Les objets de page 3 et 56 utilisent les contenus 54 et 57 et les ressources 55 et 58. Les streams FlateDecode sont décompressés avec zlib. Les chaînes hexadécimales des blocs BT/ET sont décodées avec les CMap ToUnicode 10, 18, 28, 36, 44 et 52, en tenant compte du changement des noms de polices entre pages. Les fragments sont réunis par coordonnées Tm (y puis x). Le texte des DEUX pages a été lu et conservé dans `pdf-text.txt` avec séparateurs de page.

Les objets JPEG 20 et 21 ont été extraits dans `.work/pdf-image-20.jpg` et `.work/pdf-image-21.jpg` puis ouverts visuellement. Ils représentent respectivement le logo « PORTAGE cybertech » et son monogramme. Ils ne contiennent aucune exigence technique supplémentaire.

Limites : pas de moteur de rendu PDF complet disponible. L'ordre de lecture est reconstruit à partir des coordonnées; ligatures et caractère de puce privé sont conservés. Un glyphe absent de la CMap dans « su�it » (page 2) reste signalé U+FFFD; le contexte est clairement « suffit », sans portée sur les exigences. Aucune invention de texte masqué par image. Les deux images incorporées ont été contrôlées, mais les pages n'ont pas été rendues intégralement en bitmap.

## Exigences du document

| ID | Page | Exigence |
|---|---|---|
| PDF-01 | 1 | Développer deux API REST : serveur d'autorisation et serveur de ressources. |
| PDF-02 | 1 | Le serveur d'autorisation émet des JWT signés; une requête POST avec le sujet en paramètre permet d'obtenir un jeton. |
| PDF-03 | 1 | Le serveur de ressources expose un endpoint HelloWorld sécurisé. |
| PDF-04 | 1 | Le serveur de ressources vérifie et valide les jetons émis par le serveur d'autorisation. |
| PDF-05 | 1 | Pour simuler une rotation, il vérifie également un jeton signé par une nouvelle clé. Le mécanisme complet de rotation dans le serveur d'autorisation n'est pas nécessaire. |
| PDF-06 | 1 | Démonstration de bout en bout via Postman ou un autre outil. |
| PDF-07 | 1 | Java et Spring Boot. Aucune version imposée dans le PDF. |
| PDF-08 | 1–2 | Comprendre le code produit et expliquer les choix techniques; discuter sécurité API, OAuth 2.0/JWT, Spring et API de sécurité Java, qualité/lisibilité/testabilité, architecture, intégration IA. |
| PDF-09 | 2 | Préparer la discussion des prompts IA, portions générées et ajustements, choix d'implémentation. |

## Options et livraison

- Page 1 : outils IA au choix explicitement autorisés (exemples GitHub Copilot, Copilot Agent, ChatGPT).
- Page 2 : développement dans un dépôt GitHub possible; remettre le lien du dépôt une fois terminé. Le texte ne prescrit pas de visibilité publique.
- Page 2 : README recommandé « si nécessaire », avec construction, exécution, tests et informations pertinentes.

## Ambiguïtés et libertés à expliciter dans les décisions

- Page 1 : format/localisation du paramètre sujet, chemin du POST et chemin HelloWorld non précisés.
- Page 1 : algorithme de signature, découverte/publication des clés, JWKS, kid, cache et stratégie de renouvellement non imposés; choisir une solution démontrant PDF-05.
- Page 1 : claims, issuer, audience, durée de validité, permissions et réponse HelloWorld non précisés; documenter les validations retenues.
- Page 1 : l'intitulé OAuth 2.0 ne prescrit aucun grant standard, login utilisateur, client enregistré ni serveur OAuth complet. Le comportement minimal explicite est POST sujet vers JWT signé.
- Page 1 : deux API ne signifie pas explicitement deux processus, mais deux services séparés rendent la démonstration et la frontière de confiance claires.
- Pages 1–2 : pas d'exigence de base de données, UI, conteneurs, cloud, persistance ou déploiement public.
