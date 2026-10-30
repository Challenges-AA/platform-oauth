"""Build a French architecture and manual acceptance dossier from repository sources.

Usage: python scripts/build-architecture-pdf.py
Dependencies: reportlab, pypdf (optionally pymupdf for visual review).
The isolated .work/pdf-deps directory is supported; no runtime application changes.
"""
from pathlib import Path
import sys, re, math, textwrap, json
from html import escape

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.work/pdf-deps'))
from reportlab.pdfgen import canvas
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, PageBreak,
                               Table, TableStyle, KeepTogether, Preformatted)
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.graphics.shapes import Drawing, Rect, Line, String, Polygon
from pypdf import PdfReader

OUT = ROOT / 'docs/PLATFORM_OAUTH_ARCHITECTURE_ET_SCENARIOS.pdf'
FONT_DIR = Path('C:/Windows/Fonts')
for name, filename in [('Body','segoeui.ttf'), ('BodyBold','segoeuib.ttf'),
                       ('BodyItalic','segoeuii.ttf'), ('Mono','consola.ttf')]:
    pdfmetrics.registerFont(TTFont(name, str(FONT_DIR / filename)))
pdfmetrics.registerFontFamily('Body',normal='Body',bold='BodyBold',italic='BodyItalic',boldItalic='BodyBold')
NAVY = colors.HexColor('#132d46')
BLUE = colors.HexColor('#176b91')
TEAL = colors.HexColor('#07847b')
GRAY = colors.HexColor('#556575')
PALE = colors.HexColor('#eff5f8')
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='TitleFR',fontName='BodyBold',fontSize=29,leading=35,textColor=NAVY,spaceAfter=18))
styles.add(ParagraphStyle(name='H1FR',fontName='BodyBold',fontSize=20,leading=25,textColor=NAVY,spaceAfter=15))
styles.add(ParagraphStyle(name='H2FR',fontName='BodyBold',fontSize=13,leading=17,textColor=BLUE,spaceBefore=10,spaceAfter=8))
styles.add(ParagraphStyle(name='TextFR',fontName='Body',fontSize=9.5,leading=14,textColor=NAVY,spaceAfter=8))
styles.add(ParagraphStyle(name='SmallFR',fontName='Body',fontSize=8.1,leading=11.5,textColor=NAVY,spaceAfter=5))
styles.add(ParagraphStyle(name='MutedFR',fontName='Body',fontSize=8,leading=11,textColor=GRAY,spaceAfter=7))
styles.add(ParagraphStyle(name='CodeFR',fontName='Mono',fontSize=7.5,leading=10,textColor=NAVY,
                          backColor=PALE,borderPadding=7,spaceBefore=3,spaceAfter=9))
styles.add(ParagraphStyle(name='CaseFR',fontName='BodyBold',fontSize=11.5,leading=15,textColor=BLUE,spaceBefore=3,spaceAfter=7))

story = []
bookmarks = []
def p(text, style='TextFR'):
    # Text is repository-authored prose; protect all literal HTTP / script syntax.
    return Paragraph(escape(text).replace('\n','<br/>'), styles[style])
def add(text,style='TextFR'): story.append(p(text,style))
def code(text):
    wrapped=[]
    for line in text.strip('\n').splitlines():
        wrapped.extend(textwrap.wrap(line,width=93,replace_whitespace=False,drop_whitespace=False) or [''])
    return Preformatted('\n'.join(wrapped),styles['CodeFR'])
def table(rows, widths=None):
    cells=[[p(str(v),'SmallFR') for v in row] for row in rows]
    t=Table(cells,colWidths=widths,repeatRows=1,hAlign='LEFT')
    t.setStyle(TableStyle([
        ('BACKGROUND',(0,0),(-1,0),colors.HexColor('#dceaf2')),
        ('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),8),
        ('RIGHTPADDING',(0,0),(-1,-1),8),('TOPPADDING',(0,0),(-1,-1),7),
        ('BOTTOMPADDING',(0,0),(-1,-1),7),('LINEBELOW',(0,0),(-1,0),.6,BLUE),
        ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,PALE]),
        ('LINEBELOW',(0,1),(-1,-1),.25,colors.HexColor('#d5e0e6'))]))
    story.append(t);story.append(Spacer(1,10))
def section(title):
    story.append(PageBreak());add(title,'H1FR')

class DossierCanvas(canvas.Canvas):
    def showPage(self):
        self.setStrokeColor(colors.HexColor('#c6d9e3'));self.line(48,43,A4[0]-48,43)
        self.setFont('Body',7.5);self.setFillColor(GRAY)
        self.drawString(48,30,'PLATFORM-OAUTH  |  Architecture et recette manuelle  |  28 octobre 2026')
        self.drawRightString(A4[0]-48,30,str(self._pageNumber))
        super().showPage()

class Dossier(SimpleDocTemplate):
    def afterFlowable(self,f):
        if isinstance(f,Paragraph) and f.style.name in ('H1FR','CaseFR'):
            title=f.getPlainText();key='section-'+str(len(bookmarks));bookmarks.append((title,self.page))
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(title,key,0 if f.style.name=='H1FR' else 1,False)

def label(d,x,y,text,size=9,color=NAVY):
    d.add(String(x,y,text,fontName='Body',fontSize=size,fillColor=color,textAnchor='middle'))
def box(d,x,y,w,h,lines,fill=PALE):
    d.add(Rect(x,y,w,h,rx=7,ry=7,fillColor=fill,strokeColor=BLUE,strokeWidth=.8))
    for i,line in enumerate(lines): label(d,x+w/2,y+h-18-i*13,line,9)
def arrow(d,x1,y1,x2,y2,color=BLUE):
    d.add(Line(x1,y1,x2,y2,strokeColor=color,strokeWidth=1.2))
    theta=math.atan2(y2-y1,x2-x1);size=6
    pts=[x2,y2,x2-size*math.cos(theta-.5),y2-size*math.sin(theta-.5),
         x2-size*math.cos(theta+.5),y2-size*math.sin(theta+.5)]
    d.add(Polygon(pts,fillColor=color,strokeColor=color))

def architecture():
    d=Drawing(495,410)
    box(d,164,342,168,51,['Utilisateur / Postman','Client public ou confidentiel'])
    box(d,5,229,210,65,['Authorization Server :9000','Connexion • PKCE • tokens','Révocation • sessions Spring'])
    box(d,290,229,200,65,['Resource Server :9001','GET /hello','Validation JWT et scope'])
    box(d,5,88,210,66,['Base H2 chiffrée AES','Autorisations • sessions','Historique SHA-256 • consentements'])
    box(d,290,88,200,66,['Key Service :9002','Signature ES256 • JWKS','Rotation des clés EC P-256'])
    box(d,290,6,200,45,['keys.enc','Chiffrement AES-256-GCM'])
    arrow(d,192,342,112,294);label(d,83,320,'1. Login / authorize / token',8)
    arrow(d,308,342,388,294);label(d,406,320,'2. Bearer /hello',8)
    arrow(d,108,229,108,154);label(d,108,194,'Lecture / écriture JDBC',8)
    arrow(d,390,229,390,154);label(d,432,193,'JWKS public',8)
    arrow(d,215,246,259,246);arrow(d,259,246,259,121);arrow(d,259,121,290,121)
    label(d,253,216,'signer',7.5);label(d,246,201,'POST',7.5);label(d,244,187,'/internal/sign',7.5)
    arrow(d,390,88,390,51)
    label(d,111,56,'OAUTH_STORAGE_KEY',8,TEAL)
    label(d,111,42,'clé séparée du fichier H2',8,GRAY)
    return d

def sequence(title,actors,events):
    d=Drawing(495,430)
    xs=[40+i*(415/(len(actors)-1)) for i in range(len(actors))]
    for x,a in zip(xs,actors):
        box(d,x-38,389,76,30,[a])
        d.add(Line(x,20,x,389,strokeColor=colors.HexColor('#b3c8d4'),strokeDashArray=[3,3]))
    y=361
    for a,b,msg in events:
        if a==b:
            d.add(Line(xs[a],y,xs[a]+20,y,strokeColor=TEAL));d.add(Line(xs[a]+20,y,xs[a]+20,y-12,strokeColor=TEAL))
            arrow(d,xs[a]+20,y-12,xs[a],y-12,TEAL)
            label(d,247,y+8,msg,8,TEAL)
        else:
            arrow(d,xs[a],y,xs[b],y)
            label(d,(xs[a]+xs[b])/2,y+9,msg,8)
        y-=36
    return d

# Cover and architecture.
story.append(Spacer(1,60))
add('PLATFORM-OAUTH','TitleFR')
add('Architecture de fonctionnement\net dossier de recette Postman','H1FR')
add('Authorization Code avec PKCE S256\nRefresh tokens, rotation et persistance','H2FR')
story.append(Spacer(1,30))
add('Version documentaire : 28 octobre 2026\nBranche : feature/authorization-code-pkce-refresh-token-lifecycle')
add('Document fondé sur le code courant et le plan manuel du dépôt. Les sorties des scénarios sont des résultats attendus : leur présence dans ce PDF ne signifie pas que la recette manuelle a été exécutée.')
add('Les exemples utilisent uniquement des variables et des valeurs fictives. Aucune clé, aucun mot de passe ni token réel ne figure dans ce document.','MutedFR')
story.append(Spacer(1,24))
table([['Lecture','Contenu'],['Partie 1','Architecture, responsabilités, stockage et séquences'],
       ['Partie 2','Préparation et requêtes Postman R1 à R7'],
       ['Partie 3','56 fiches de scénario : entrées, sorties, explications'],
       ['Annexes','Scripts Postman, limites de recette et traçabilité']], [95,400])
add('Navigation : les signets du lecteur PDF permettent d’ouvrir directement une section ou une fiche de scénario.','MutedFR')

section('1. Architecture applicative')
story.append(architecture())
add('Le client obtient une autorisation auprès de l’AS, puis présente son access token au Resource Server. Le Key Service détient les clés privées et signe à la demande de l’AS. Le RS télécharge les clés publiques et vérifie les JWT localement.')
add('Le RS consulte par défaut le JWKS du Key Service (:9002). L’AS expose également un proxy public GET /oauth2/jwks. Le RS ne consulte pas la base des refresh tokens à chaque appel.')
add('Les flèches représentent les demandes ; les réponses reviennent sur les mêmes connexions. HTTP loopback correspond à la configuration locale. TLS et des URL d’issuer cohérentes doivent être configurés pour un déploiement.','MutedFR')

section('2. Responsabilités et données')
table([['Composant','Responsabilité / accès'],
       ['Authorization Server :9000','Authentifie les utilisateurs ; contrôle PKCE, client et scopes ; émet les tokens ; gère la révocation. Ne possède aucune clé privée de signature.'],
       ['Resource Server :9001','GET /hello. Vérifie ES256, kid, signature, issuer, audience, exp, nbf, iat, sub, jti et scope hello.read.'],
       ['Key Service :9002','Génère P-256 ; signe avec le compte signer ; rotation réservée au compte admin ; publie seulement les clés publiques.'],
       ['H2 / authorization-data','Autorisations, codes et tokens courants, sessions Spring, consentements et historique des refresh tokens. Fichier chiffré AES.'],
       ['keys.enc / signing-keys','Clé privée active et anciennes clés publiques retenues ; chiffrement AES-256-GCM avec une autre clé maîtresse.']], [135,360])
add('Deux stockages et deux clés indépendantes','H2FR')
add('OAUTH_STORAGE_KEY protège la base de l’AS. KEY_SERVICE_MASTER_KEY protège keys.enc. Le secret de signature sert à authentifier l’AS auprès du Key Service : il n’est pas la clé privée ES256. Les clés doivent rester séparées des fichiers sauvegardés.')
add('Les utilisateurs et les clients de démonstration restent configurés en mémoire au démarrage, avec des identifiants stables. Les autorisations et sessions sont persistantes. Les deux clients sont internes et ne déclenchent pas d’écran de consentement ; le service JDBC de consentement est néanmoins disponible.')
table([['Objet','Durée / règle'],['Code d’autorisation','2 minutes ; usage unique'],['Access token JWT','5 minutes ; scope hello.read ; audience portage-api'],['Refresh token','8 heures depuis son émission ; remplacé à chaque renouvellement'],['Famille','7 jours au maximum depuis la première émission'],['Session navigateur','30 minutes d’inactivité ; distincte de l’autorisation OAuth']], [150,345])

section('3. Séquence : connexion et émission')
story.append(sequence('PKCE',['Client','AS','Key Service','H2'],[
    (0,1,'1. GET /login puis POST /login + CSRF'),
    (1,3,'2. Session authentifiée persistée'),
    (0,1,'3. GET /oauth2/authorize + challenge S256'),
    (1,3,'4. Code lié au client, callback et droits'),
    (1,0,'5. 302 callback ?code=... & state=...'),
    (0,1,'6. POST /oauth2/token + code_verifier'),
    (1,1,'7. Vérification client, code et PKCE'),
    (1,2,'8. POST /internal/sign — claims validés'),
    (1,3,'9. Tokens et empreinte enregistrés ; commit'),
    (1,0,'10. 200 — access_token + refresh_token')]))
add('Le code_verifier n’est pas transmis à /authorize : seul son SHA-256 encodé en Base64URL sans padding est envoyé. Le client garde le vérificateur et le présente au token endpoint.')
add('Le client vérifie que state reçu est identique à state envoyé. Spring protège le code et PKCE ; la vérification de state au callback reste une responsabilité du client.')

section('4. Séquence : renouvellement et rejeu')
story.append(sequence('Rotation',['Client','AS','Key Service','H2'],[
    (0,1,'1. POST /oauth2/token — refresh token A'),
    (1,3,'2. Transaction et verrou de cycle de vie'),
    (1,3,'3. Lire le grant ; vérifier client et expiration'),
    (1,2,'4. Signer un nouvel access token'),
    (1,3,'5. Remplacer A par B ; conserver SHA-256(A)'),
    (1,3,'6. Sauvegarder et commit'),
    (1,0,'7. 200 — nouveau JWT et refresh token B'),
    (0,1,'8. Rejeu de A par le client d’origine'),
    (1,3,'9. Trouver la famille ; invalider B et le grant'),
    (1,0,'10. 400 invalid_grant ; B est aussi refusé')]))
add('Un autre client présentant A est refusé sans révoquer la famille du propriétaire. Les demandes de token et de révocation sont sérialisées par un verrou JDBC global. La réponse portant B est envoyée après commit.')
add('Deux usages concurrents du même refresh token conduisent à une réussite et un refus. La seconde utilisation est un rejeu : le refresh token issu de la première est alors lui aussi invalidé. Le client doit sérialiser ses renouvellements.')

section('5. Révocation, ressources et limites')
add('Révocation explicite','H2FR')
story.append(code('POST /oauth2/revoke\nclient_id=portage-public\ntoken={{refresh_token}}\ntoken_type_hint=refresh_token\n\nHTTP 200 ; puis renouvellement : HTTP 400 {"error":"invalid_grant"}'))
add('La révocation d’un token inconnu retourne aussi 200. Révoquer un ancien refresh token de sa propre famille invalide la famille active. Les JWT déjà signés restent acceptables jusqu’à expiration : le RS ne fait pas d’introspection à chaque appel.')
add('Signature et rotation des clés','H2FR')
add('La rotation des clés ES256 est distincte de la rotation des refresh tokens. Le Key Service effectue une rotation quotidienne et une rotation manuelle par POST /internal/rotate avec le compte admin. Les anciennes clés publiques restent publiées 900 secondes par défaut pour vérifier les JWT encore valables.')
add('Limites actuelles','H2FR')
for txt in [
    'H2 embarqué et le Key Service sont prévus pour une instance locale. Ne pas partager un fichier H2 entre conteneurs ; une architecture distribuée demanderait une base partagée et des verrous adaptés.',
    'Les empreintes des refresh tokens sont conservées ; la purge des familles expirées n’est pas automatisée. La rotation des clés maîtresses de chiffrement exige une procédure de migration.',
    'La déconnexion de la session navigateur ne révoque pas implicitement les autorisations OAuth. Une nouvelle connexion et une nouvelle autorisation créent une famille indépendante.',
    'Les durées des tokens sont codées dans les classes ; aucune variable d’environnement ne permet actuellement de les raccourcir.',
    'La gestion 503 temporarily_unavailable est explicitement câblée pour les erreurs OAuth de ce type. Un JwtEncodingException sur le nouveau flux Spring ne doit pas être supposé produire automatiquement 503 : le comportement de panne du signataire nécessite sa propre vérification.'
]: add('• '+txt)

section('6. Classes et points d’extension')
table([['Classe / fichier','Rôle'],
 ['AuthorizationSecurityConfiguration','Chaînes de sécurité, utilisateurs de connexion, deux clients, S256, générateur JWT et endpoints. Classe existante modifiée.'],
 ['AuthorizationPersistenceConfiguration','Source H2 chiffrée, schémas JDBC Spring, sessions et cookies.'],
 ['PublicRefreshClientAuthentication','Identification NONE pour refresh et revoke ; laisse l’échange de code et PKCE aux composants Spring.'],
 ['RotatingAuthorizationService','Délègue la persistance à JdbcOAuth2AuthorizationService ; conserve les empreintes et invalide les familles rejouées.'],
 ['RotatingRefreshTokenGenerator','Refresh token aléatoire ; plafond de famille de sept jours.'],
 ['TokenTransactionFilter','Verrou et transaction englobant token/revoke ; tampon de réponse avant commit.'],
 ['authorization-lifecycle.sql','Tables oauth_lifecycle_lock et oauth_refresh_history.'],
 ['RemoteSigningConfiguration','JwtEncoder HTTP vers le Key Service. Aucun stockage de clé privée dans l’AS.'],
 ['Tests maintenus','AuthorizationCodePkceHttpTest, AuthorizationPersistenceTest, Cucumber PKCE et Newman.']], [180,315])
add('Les données Spring d’autorisation associent registeredClientId, principalName, authorizedScopes, attributs, code, access token et refresh token courant. L’historique ajoute token_hash, authorization_id, client_id et expires_at. Les anciennes valeurs brutes des refresh tokens ne sont pas conservées dans cette table d’historique.')

section('7. Préparer la recette Postman')
add('Démarrage','H2FR')
add('Construire les JAR avec mvn clean verify, puis lancer les trois services dans trois terminaux héritant des variables requises. Pour une séance persistante, lancer les JAR séparément ; scripts/demo-pkce.py lance puis arrête ses services après Newman.')
story.append(code('java -jar key-service/target/key-service-1.0.0-SNAPSHOT-exec.jar\njava -jar authorization-server/target/authorization-server-1.0.0-SNAPSHOT.jar\njava -jar resource-server/target/resource-server-1.0.0-SNAPSHOT.jar'))
table([['Variables serveur','Usage'],['OAUTH_USER1_PASSWORD … OAUTH_USER5_PASSWORD','Cinq mots de passe distincts ; user1 à user5.'],['OAUTH_CLIENT_SECRET','Secret de demo-client.'],['KEY_SERVICE_SIGNING_SECRET / ADMIN_SECRET','Secrets distincts, au moins 32 caractères ; noms complets préfixés KEY_SERVICE_.'],['KEY_SERVICE_MASTER_KEY','Base64 de 32 octets aléatoires ; Key Service uniquement.'],['OAUTH_STORAGE_KEY','Autre Base64 de 32 octets aléatoires ; AS uniquement.'],['OAUTH_STORAGE_DIRECTORY','Répertoire persistant de test.'],['OAUTH_REDIRECT_URI','http://127.0.0.1:8080/callback'],['OAUTH_SECURE_COOKIE','false pour la recette HTTP locale ; true par défaut.']], [225,270])
add('Avec Docker Compose, conserver le volume authorization-data et le volume signing-keys. Le compose courant ne transmet pas OAUTH_SECURE_COOKIE : pour une recette HTTP avec Docker, ajouter explicitement ce réglage dans une surcharge locale, ou utiliser HTTPS. Ne pas effacer les volumes pour un scénario de redémarrage.','MutedFR')

section('8. Variables et conventions de test')
table([['Variable Postman','Valeur'],['as / rs','http://127.0.0.1:9000 / http://127.0.0.1:9001'],['client_id','portage-public ; refaire les cas nominaux avec demo-client'],['client_secret','Valeur locale de OAUTH_CLIENT_SECRET'],['username / password','user1 / valeur locale de OAUTH_USER1_PASSWORD'],['redirect_uri','http://127.0.0.1:8080/callback'],['code_verifier','dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'],['code_challenge','E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM'],['Variables capturées','csrf, state, authorization_code, access_token, refresh_token, previous_refresh_token']], [145,350])
add('Cette paire PKCE est un vecteur public RFC 7636, utilisable uniquement pour une recette reproductible. Un vrai client génère un nouveau vérificateur aléatoire par demande d’autorisation.')
add('Réglages','H2FR')
for txt in ['Désactiver Automatically follow redirects pour voir les 302 et leur Location.',
            'Garder le cookie jar actif. Utiliser 127.0.0.1 partout, sans alterner avec localhost.',
            'POST : Body > x-www-form-urlencoded. Client public : No Auth. Client confidentiel : Basic Auth uniquement sur token/revoke.',
            'Le callback n’a pas besoin de serveur : Postman ne suit pas la redirection et lit Location.',
            'Chaque test destructeur repart d’une nouvelle autorisation. A, B et C désignent des refresh tokens fictifs, jamais leurs valeurs réelles.',
            'Une sortie « refus OAuth » signifie 4xx ou redirection OAuth avec error sur /authorize, sans aucun token/code de succès. Ne pas exiger un corps JSON sur une redirection.']:
    add('• '+txt)

# Baseline request recipes, used to make every scenario input explicit.
BASE = {
 'R1':'GET {{as}}/login\nAuthorization: No Auth',
 'R2':'POST {{as}}/login\nAuthorization: No Auth ; cookie de R1\nusername={{username}}\npassword={{password}}\n_csrf={{csrf}}',
 'R3':'GET {{as}}/oauth2/authorize\nAccept: text/html ; cookie authentifié\nresponse_type=code\nclient_id=portage-public\nredirect_uri={{redirect_uri}}\nscope=hello.read\nstate={{state}}\ncode_challenge={{code_challenge}}\ncode_challenge_method=S256',
 'R4':'POST {{as}}/oauth2/token\nAuthorization: No Auth\ngrant_type=authorization_code\nclient_id=portage-public\ncode={{authorization_code}}\nredirect_uri={{redirect_uri}}\ncode_verifier={{code_verifier}}',
 'R5':'GET {{rs}}/hello\nAuthorization: Bearer {{access_token}}',
 'R6':'POST {{as}}/oauth2/token\nAuthorization: No Auth\ngrant_type=refresh_token\nclient_id=portage-public\nrefresh_token={{refresh_token}}',
 'R7':'POST {{as}}/oauth2/revoke\nAuthorization: No Auth\nclient_id=portage-public\ntoken={{refresh_token}}\ntoken_type_hint=refresh_token'
}
outputs={
 'R1':'200 text/html ; formulaire avec champ _csrf ; cookie SESSION.',
 'R2':'302 ; Location sans ?error ; session authentifiée. Sans demande OAuth sauvegardée, la redirection mène à l’accueil protégé /.',
 'R3':'302 ; Location: {{redirect_uri}}?code=<CODE>&state=<STATE>. Vérifier state puis capturer code.',
 'R4':'200 JSON : {"access_token":"<JWT>","refresh_token":"<RT_A>","token_type":"Bearer","expires_in":299,"scope":"hello.read"}. expires_in proche de 300 ; Cache-Control: no-store.',
 'R5':'200 JSON : {"message":"HelloWorld","subject":"subject-1"}.',
 'R6':'200 JSON : nouveau access_token et refresh_token B différent de A ; scope hello.read. Même sub et aud.',
 'R7':'200, corps normalement vide. Une demande R6 avec le token révoqué retourne 400 {"error":"invalid_grant"}.'}
why={
 'R1':'Le cookie lie la requête de connexion à sa session. Le champ CSRF est capturé depuis le formulaire, sans valeur inventée.',
 'R2':'Le mot de passe est vérifié par Spring avec le hash configuré. Le principal authentifié utilise le sujet stable subject-N.',
 'R3':'Le code est lié au client, au callback, aux droits et au challenge. Le code n’est pas un access token ; il ne permet pas directement /hello.',
 'R4':'Spring vérifie le code, son expiration, le callback, le client et la preuve PKCE. Le JWT est signé à distance puis les tokens sont persistés.',
 'R5':'Le RS vérifie les clés publiques, la signature, les claims et le scope. Cette étape prouve plus qu’un simple décodage Base64 du JWT.',
 'R6':'La rotation rend A inutilisable ; le client doit enregistrer B. Ne jamais déclencher plusieurs renouvellements avec la même valeur.',
 'R7':'La révocation concerne l’autorisation serveur. Le JWT autonome déjà remis au client reste utilisable jusqu’à son expiration.'}
for r in BASE:
    section('9.'+r[1:]+' Requête '+r+' — '+{'R1':'Formulaire','R2':'Connexion','R3':'Code PKCE','R4':'Échange de code','R5':'Ressource','R6':'Renouvellement','R7':'Révocation'}[r])
    add('Entrée','H2FR');story.append(code(BASE[r]))
    add('Sortie attendue','H2FR');add(outputs[r])
    add('Explication','H2FR');add(why[r])
    if r in ('R4','R6','R7'):
        add('Variante confidentielle : remplacer client_id par demo-client et choisir Basic Auth, username demo-client, password {{client_secret}}. Ne pas ajouter de client_secret dans le formulaire. PKCE reste obligatoire à R4.')
    if r=='R3': add('Préparer state avec {{$guid}} et vérifier sa correspondance au retour. Le script complet de capture figure en annexe. Un code non échangé expire au bout de deux minutes.')
    if r=='R6': add('Avant Send, copier refresh_token dans previous_refresh_token. Après 200 seulement, mettre à jour access_token et refresh_token. Ne pas utiliser ce script de capture sur les variantes négatives.')

# Inputs and rationale for all rows in the existing manual acceptance matrix.
DETAILS={
'A01':('R2','Exécuter R1 puis ce POST avec les valeurs valides.','Le cookie et le CSRF associent la connexion à sa session ; les identifiants valides créent le principal authentifié.'),
'A02':('R2','Essai 1 : password=incorrect ; essai 2 : username=inconnu. Conserver un CSRF valide.','La connexion doit refuser les identités invalides sans émettre de code ou de token. Reprendre R1 si le CSRF change.'),
'A03':('R2','Essai 1 : retirer _csrf ; essai 2 : _csrf=incorrect.','Le refus CSRF intervient avant la création d’une session authentifiée, même avec un mot de passe correct.'),
'A04':('R3','Supprimer le cookie SESSION du domaine 127.0.0.1 avant Send.','Un client OAuth n’authentifie pas l’utilisateur : sans session, l’AS exige la connexion.'),
'A05':('R3','Retirer code_challenge et code_challenge_method.','La configuration requireProofKey et la validation S256 empêchent un échange de code sans preuve PKCE.'),
'A06':('R3','Essai 1 : code_challenge_method=plain ; essai 2 : code_challenge_method=inconnue.','Seule S256 est autorisée. La méthode plain ne protège pas le secret de vérification sur la demande d’autorisation.'),
'A07':('R3','Essai 1 : code_challenge=abc ; essai 2 : challenge avec caractères + ou /.','Le challenge S256 attendu comporte 43 caractères Base64URL sans padding. Un challenge invalide ne doit pas produire de code.'),
'A08':('R3','Remplacer scope par admin.','Le client n’est enregistré que pour hello.read ; un scope supplémentaire ne peut pas être auto-accordé.'),
'A09':('R3','Essai 1 : client_id=inconnu ; essai 2 : redirect_uri=http://127.0.0.1:8080/autre.','Le serveur doit d’abord valider le client et le callback. Il ne doit pas envoyer une erreur contenant des données d’autorisation vers une URI non enregistrée.'),
'A10':('R3','Envoyer state=<GUID_FRAIS> et comparer au state dans Location.','L’AS renvoie state ; le client doit effectuer la comparaison. Un state modifié doit être rejeté par le client, pas supposé vérifié par l’AS au callback.'),
'A11':('R4','R1/R2 avec user1, puis R3/R4. Effacer les cookies ; refaire avec user2 et son mot de passe.','Le sujet vient du compte authentifié, jamais d’un paramètre subject libre dans le flux PKCE. Comparer les deux JWT et les réponses de /hello.'),
'C01':('R4','Exécuter une fois pour portage-public ; nouvelle autorisation pour demo-client + Basic valide.','Les deux types de clients doivent produire un access et un refresh token, avec S256 obligatoire dans les deux cas.'),
'C02':('R4','Code frais par essai : code_verifier='+'b'*43+' ; puis retirer code_verifier.','Le vérificateur doit correspondre au challenge enregistré. Selon la phase de rejet Spring, le statut est 400 ou 401 ; aucun token ne doit être retourné.'),
'C03':('R4','code_verifier='+'b'*43+' ; ajouter portage.public-refresh=true.','Une chaîne HTTP ne peut pas forger le marqueur booléen interne du provider réservé au refresh/revoke. Le mauvais PKCE doit rester refusé.'),
'C04':('R4','redirect_uri=http://127.0.0.1:8080/autre ; garder le code et le vérificateur corrects.','Le callback de l’échange doit correspondre à celui de la demande initiale : un code intercepté ne doit pas être échangé avec une autre URI.'),
'C05':('R4','code=code-invente ; garder un vérificateur conforme.','Aucune autorisation persistée ne correspond à cette valeur ; le serveur ne doit jamais générer de tokens à partir d’un code arbitraire.'),
'C06':('R4','Créer le code par R3 ; attendre au moins 2 min 10 s sans l’échanger ; envoyer R4.','Le code est volontairement très court. L’attente commence à sa création, pas à l’ouverture de Postman.'),
'C07':('R4','Envoyer une première fois R4, conserver le refresh A ; renvoyer exactement le même code ; tenter R6 avec A.','Un code à usage unique rejoué invalide également les tokens associés via le provider Spring. Le premier JWT peut cependant rester accepté jusqu’à expiration.'),
'C08':('R4','Obtenir un code pour portage-public ; échanger avec client_id=demo-client et son Basic valide.','Le code appartient à son client initial. Utiliser des identifiants valides d’un autre client ne transfère pas cette autorisation.'),
'C09':('R4','Code pour demo-client ; essai Basic avec secret faux ; autre essai No Auth avec client_id=demo-client.','Un client confidentiel ne peut pas se présenter comme un client public. L’authentification du client doit échouer avant émission.'),
'C10':('R5','Essai 1 : supprimer Authorization ; essai 2 : modifier le JWT ; essai 3 : attendre plus de 5 min après iat.','Le RS distingue les tokens absents ou invalides des tokens vérifiables. Il ne fait pas confiance à une simple chaîne ayant une forme JWT.'),
'C11':('R5','Utiliser le JWT nominal, non modifié et encore valable.','L’acceptation établit que la signature distante, les clés publiques, l’audience et le scope sont compatibles entre les services.'),
'C12':('R4','Essai 1 : grant_type=inconnu ; autres essais : retirer code ou redirect_uri.','Les champs nécessaires au grant sont contrôlés. Le code exact de l’erreur dépend du champ et de la phase de validation ; consigner la réponse réellement obtenue.'),
'F01':('R6','Refaire avec chaque client et sa propre famille. Pour demo-client, ajouter son Basic valide.','Le nouveau refresh token doit différer du précédent. Le JWT renouvelé doit être accepté par le RS.'),
'F02':('R6','Enchaîner trois renouvellements A -> B -> C -> D, en remplaçant la variable après chaque 200.','Une rotation normale ne doit pas révoquer la famille. Seule la valeur la plus récente est présentée à chaque fois.'),
'F03':('R6','Ajouter scope=hello.read.','Le scope explicite est compris dans les droits d’origine. Il ne doit pas modifier le sujet ni l’audience de l’autorisation.'),
'F04':('R6','Ajouter scope=admin ; après le refus, retirer ce champ et réutiliser le token initial.','L’élévation de privilèges est refusée sans consommer le refresh token valide. La deuxième demande avec les droits permis doit réussir.'),
'F05':('R6','Token émis pour portage-public ; client_id=demo-client ; Basic de demo-client. Puis réessayer avec le propriétaire.','Le token est lié au registeredClientId stocké. Une tentative d’un autre client ne doit ni obtenir les droits ni détruire la famille du propriétaire.'),
'F06':('R6','Token émis pour demo-client ; No Auth et client_id=portage-public.','Le client public ne peut pas utiliser le grant d’un client confidentiel, même en possédant sa valeur de refresh token.'),
'F07':('R6','Essai refresh_token=invente ; essai avec token réel tronqué.','Le refresh token est une valeur opaque aléatoire recherchée côté serveur. Il ne peut pas être reconstruit à partir du sujet utilisateur.'),
'F08':('R6','Retirer client_id ; puis essayer client_id vide ; puis client_id=inconnu.','Le renouvellement public nécessite une identification explicite d’un client enregistré. La possession seule d’un token sans client ne suffit pas.'),
'F09':('R6','Famille de demo-client ; Basic demo-client avec secret incorrect.','L’authentification du client confidentiel reste obligatoire lors de chaque renouvellement, et pas uniquement au premier échange.'),
'F10':('R6','Conserver localement le JWT avant et après renouvellement ; décoder les deux sans les publier.','sub, aud et scope sont conservés ; les dates et jti identifient une nouvelle émission. Le décodage seul ne vérifie pas la signature.'),
'S01':('R6','A -> B par un renouvellement valide ; envoyer ensuite refresh_token=A avec le client d’origine.','L’historique conserve l’empreinte de A. Ce rejeu est interprété comme une compromission possible et révoque la famille.'),
'S02':('R6','Après S01, envoyer refresh_token=B.','Refuser seulement A serait insuffisant : un attaquant pourrait avoir obtenu B. Le dernier token de la famille doit aussi être invalidé.'),
'S03':('R6','Après S01, refaire R3/R4 pour créer une famille C ; envoyer refresh_token=C.','Une nouvelle autorisation crée une autre famille. La détection d’un rejeu ne doit pas bannir toutes les futures autorisations du client.'),
'S04':('R6','A -> B pour portage-public ; présenter A avec demo-client + Basic valide ; présenter B avec portage-public.','La détection de rejeu n’invalide une famille que si la demande identifie son client d’origine. Cette règle limite la révocation malveillante par un autre client.'),
'S05':('R7','Révoquer le refresh actif ; puis R6 avec exactement la valeur révoquée.','La révocation doit persister dans l’autorisation Spring et être appliquée dès la tentative suivante de renouvellement.'),
'S06':('R7','A -> B ; token=A dans la révocation avec le bon client ; ensuite R6 avec B.','Un ancien token permet de retrouver la famille via l’historique d’empreintes ; la révocation doit atteindre son token courant.'),
'S07':('R7','token=inconnu ; puis répéter une révocation déjà effectuée.','Le protocole de révocation retourne 200 sans révéler si une valeur existe. Le critère d’efficacité est le refus d’un renouvellement ultérieur.'),
'S08':('R7','Token actif de portage-public ; révoquer avec client_id=demo-client et son Basic ; renouveler avec portage-public.','Un client ne peut pas révoquer les tokens d’un autre. Observer le refus ou l’absence d’effet, puis vérifier que le propriétaire reste opérationnel.'),
'S09':('R5','Conserver le JWT ; R7 sur le refresh ; appeler /hello avant puis après exp.','Le RS valide localement un JWT autonome. La révocation des refresh tokens empêche une nouvelle émission, mais ne fournit pas de révocation immédiate du JWT déjà signé.'),
'S10':('R6','Créer deux familles A et X par deux R3/R4 ; révoquer A ; envoyer refresh_token=X.','L’état de révocation doit être limité au grant visé, même lorsque deux autorisations appartiennent au même client et au même utilisateur.'),
'P01':('R6','Conserver le token ; arrêter et relancer seulement l’AS, avec même clé, répertoire et configuration ; renouveler.','Les autorisations sont en base et non dans une collection mémoire. Ne pas effacer le volume ou changer d’identité de client pendant le test.'),
'P02':('R6','A -> B ; redémarrer l’AS ; envoyer A, puis B, avec le bon client.','La persistance du token courant seule ne suffit pas. L’historique de rejeu doit lui aussi survivre au redémarrage et permettre d’invalider B.'),
'P03':('R6','Révoquer le token ; redémarrer l’AS ; réutiliser la même valeur.','Une révocation ne doit pas disparaître avec la JVM. Le token doit rester inutilisable après rechargement.'),
'P04':('R3','Se connecter ; conserver le cookie ; redémarrer rapidement l’AS ; demander un nouveau code.','Spring Session JDBC conserve le contexte de sécurité. La même session encore valide doit éviter une nouvelle saisie du mot de passe.'),
'P05':('R3','Se connecter ; ne plus utiliser la session pendant au moins 30 min 10 s ; demander un code.','L’expiration de la session HTTP exige une nouvelle connexion. Elle est indépendante de la durée de vie des refresh tokens déjà délivrés.'),
'P06':('R6','Obtenir A ; attendre au moins 8 h et une marge, sans jamais renouveler A ; envoyer A.','Chaque refresh token a sa propre échéance. Un token expiré doit être rejeté même si sa famille n’a pas encore atteint sept jours.'),
'P07':('R6','Maintenir une famille par des renouvellements espacés de moins de 8 h ; essayer après 7 jours depuis la création.','La limite absolue empêche une session OAuth renouvelable indéfiniment. Les dernières échéances de tokens sont plafonnées à cette limite.'),
'P08':('SYS','AS arrêté ; même répertoire ; remplacer OAUTH_STORAGE_KEY par une autre clé Base64 valide de 32 octets ; démarrer.','Le fichier chiffré ne doit pas être lu avec une autre clé. Le service doit refuser le démarrage ; restaurer ensuite la clé d’origine.'),
'P09':('SYS','Démarrer avec OAUTH_STORAGE_KEY absent ; puis Base64 invalide ; puis Base64 de 16 octets.','La configuration exige une clé encodant 32 octets. Le service ne doit ni utiliser une clé de repli ni créer silencieusement un stockage non protégé.'),
'P10':('R1','Inspecter Set-Cookie après R1/R2 ; refaire sur une configuration HTTPS avec Secure activé.','HttpOnly limite l’accès JavaScript ; SameSite=Lax limite certains envois intersites ; Secure impose le transport HTTPS pour le cookie.'),
'P11':('UNSUPPORTED','Client demo-client avec Basic valide ; envoyer un grant supprimé.','Seul Authorization Code avec PKCE et le renouvellement sont maintenus.'),
'P12':('UNSUPPORTED','Client demo-client avec Basic valide ; envoyer grant_type=client_credentials.','Ce client ne possède pas le grant client_credentials ; aucun token ne doit être émis.')
}

source=(ROOT/'docs/PLAN_TEST_MANUEL_POSTMAN_PKCE.md').read_text(encoding='utf-8')
rows=[]
for line in source.splitlines():
    m=re.match(r'^\| ([ACFSP]\d{2}) \| (.*?) \| (.*?) \|$',line)
    if m: rows.append(m.groups())
assert len(rows)==55 and set(i for i,_,_ in rows)==set(DETAILS)

section('10. Catalogue des scénarios')
add('Chaque fiche décrit des entrées de test, des sorties attendues et le mécanisme vérifié. Les identifiants A/C/F/S/P correspondent au plan Markdown du dépôt ; CON01 complète le cas de concurrence.')
table([['Famille','Identifiants','Nombre'],['Connexion / autorisation','A01 à A11','11'],['Code / ressource','C01 à C12','12'],['Refresh et liaison','F01 à F10','10'],['Rejeu et révocation','S01 à S10','10'],['Concurrence','CON01','1'],['Persistance et configuration','P01 à P12','12']], [235,170,90])
add('Préconditions communes','H2FR')
add('Services démarrés, variables locales préparées, cookie de connexion valide quand requis. Toutes les demandes token/revoke utilisent x-www-form-urlencoded. Les tests nominaux et ceux d’authentification du client sont à refaire pour le client confidentiel avec HTTP Basic.')
add('Les tests de rejeu, de code réutilisé et de révocation modifient durablement leur famille. Créer une famille neuve pour chaque scénario indépendant. Ne pas laisser les scripts de capture écraser le token de référence après une erreur.')
add('Différence entre preuve et résultat attendu','H2FR')
add('Les rapports Maven datés et le résumé de couverture donnent les résultats automatisés courants. Le catalogue manuel élargit les contrôles et décrit des attentes à confirmer. Aucun résultat manuel n’est inventé dans ce document ; noter le statut réellement obtenu pour chaque essai.')

def request_for(ident,template,changes):
    if template=='SYS': return 'Hors HTTP / arrêt-démarrage de l’Authorization Server\n'+changes
    if template=='UNSUPPORTED':
        grant = 'unsupported' if ident == 'P11' else 'client_credentials'
        return 'POST {{as}}/oauth2/token\nAuthorization: Basic demo-client / {{client_secret}}\ngrant_type='+grant+'\n'+changes
    return BASE[template]+'\n\nManipulation : '+changes

for n,(ident,action,expected) in enumerate(rows):
    if n%2==0: story.append(PageBreak())
    template,changes,explanation=DETAILS[ident]
    card=[p(ident+' — '+action,'CaseFR'),p('INPUT / PRÉPARATION','MutedFR'),
          code(request_for(ident,template,changes)),
          p('OUTPUT ATTENDU','MutedFR'),p(expected,'SmallFR'),
          p('EXPLICATION','MutedFR'),p(explanation,'SmallFR'),Spacer(1,13)]
    story.append(KeepTogether(card))

section('CON01 — Deux renouvellements concurrents')
add('Préparation / input','H2FR')
add('Créer une famille neuve pour portage-public. Copier son refresh token A dans concurrent_refresh_token. Envoyer une requête GET {{as}}/oauth2/jwks dont le Post-response lance les deux POST ci-dessous de manière asynchrone. Le script complet figure en annexe.')
story.append(code('Deux requêtes indépendantes, avec la MÊME valeur A :\nPOST {{as}}/oauth2/token\nAuthorization: No Auth\ngrant_type=refresh_token\nclient_id=portage-public\nrefresh_token={{concurrent_refresh_token}}'))
add('Output attendu','H2FR')
story.append(code('Réponse 1 (ordre non garanti) : 200 + JWT + refresh token B\nRéponse 2 : 400 {"error":"invalid_grant"}\nPuis POST refresh_token=B : 400 {"error":"invalid_grant"}'))
add('Explication','H2FR')
add('Le verrou JDBC sérialise lecture, validation et sauvegarde. Une seule demande consomme A avec succès. La suivante retrouve l’empreinte d’un ancien token et invalide sa famille : B devient donc lui aussi inutilisable.')
add('Deux clics ou deux appels asynchrones Postman ne garantissent pas une arrivée simultanée au serveur. Ils permettent de contrôler le résultat fonctionnel ; le test Java avec barrière de départ complète la preuve de concurrence. Un Runner exécutant deux requêtes successives ne prouve pas le chevauchement.')

section('11. Scripts Postman : capture et rotation')
add('R1 — Capturer le CSRF (Post-response)','H2FR')
story.append(code('const match = pm.response.text().match(/name="_csrf"[^>]*value="([^\"]+)"/);\npm.test("CSRF disponible", () => {\n  pm.response.to.have.status(200);\n  pm.expect(match).not.to.eql(null);\n});\nif (match) pm.environment.set("csrf", match[1]);'))
add('R3 — Préparer state (Pre-request)','H2FR')
story.append(code('pm.environment.set("state", pm.variables.replaceIn("{{$guid}}"));\npm.environment.unset("authorization_code");'))
add('R3 — Capturer le code et vérifier state (Post-response)','H2FR')
story.append(code('const location = pm.response.headers.get("Location") || "";\nconst code = location.match(/[?&]code=([^&]+)/);\nconst state = location.match(/[?&]state=([^&]+)/);\npm.test("Code et state valides", () => {\n  pm.response.to.have.status(302);\n  pm.expect(code).not.to.eql(null);\n  pm.expect(state).not.to.eql(null);\n  pm.expect(decodeURIComponent(state[1]))\n    .to.eql(pm.environment.get("state"));\n});\nif (code) pm.environment.set("authorization_code", decodeURIComponent(code[1]));'))

section('12. Scripts Postman : tokens')
add('R4 et R6 — Capturer les tokens après réussite seulement','H2FR')
story.append(code('if (pm.response.code === 200) {\n  const body = pm.response.json();\n  pm.test("Réponse token nominale", () => {\n    pm.expect(body.access_token).to.be.a("string").and.not.empty;\n    pm.expect(body.refresh_token).to.be.a("string").and.not.empty;\n    pm.expect(body.token_type.toLowerCase()).to.eql("bearer");\n    pm.expect(body.scope).to.eql("hello.read");\n    pm.expect(body.expires_in).to.be.within(298, 300);\n    pm.expect(pm.response.headers.get("Cache-Control")).to.include("no-store");\n  });\n  pm.environment.set("access_token", body.access_token);\n  pm.environment.set("refresh_token", body.refresh_token);\n}'))
add('R6 — Avant le renouvellement (Pre-request)','H2FR')
story.append(code('pm.environment.set("previous_refresh_token", pm.environment.get("refresh_token"));'))
add('R6 — Vérifier la rotation (Post-response)','H2FR')
story.append(code('if (pm.response.code === 200) {\n  pm.test("Refresh token remplacé", () => {\n    pm.expect(pm.response.json().refresh_token)\n      .not.to.eql(pm.environment.get("previous_refresh_token"));\n  });\n}'))
add('Pour tester un rejeu, utiliser une copie sans script de capture et choisir explicitement previous_refresh_token. Ne pas remplacer accidentellement la valeur active par une ancienne valeur.')

section('13. Script Postman : concurrence')
concurrency=re.search(r'```javascript\n(const token = pm.environment.get\(\x27concurrent_refresh_token\x27\);.*?)\n```',source,re.S).group(1)
story.append(code(concurrency))
add('Après les deux réponses, envoyer R6 avec refresh_token={{refresh_after_race}}. Attendu : 400 invalid_grant. Aucun token ni cookie ne doit être imprimé dans la console ou exporté dans un compte rendu.','SmallFR')

section('14. Recette longue et compte rendu')
add('Expirations et redémarrages','H2FR')
add('Les scénarios P06 et P07 demandent des délais réels de huit heures et sept jours. Pour une recette accélérée, utiliser une construction de test séparée : modifier les TTL dans AuthorizationSecurityConfiguration et la limite de famille à ses deux emplacements dans RotatingRefreshTokenGenerator et RotatingAuthorizationService ; repartir d’une base de test neuve. Cette adaptation n’est pas fournie sous forme d’un paramètre d’environnement.')
add('P08/P09 sont des tests de démarrage. Arrêter l’AS avant de changer sa clé, conserver le fichier existant et restaurer la bonne clé après le refus. Postman seul ne permet pas de prouver le chiffrement au repos : l’inspection du fichier et le test de réouverture avec mauvaise clé apportent une preuve distincte.')
add('Compte rendu sans secrets','H2FR')
table([['Champ','À renseigner'],['Scénario / date','Identifiant, horodatage et version du code'],['Contexte','Client, utilisateur, famille désignée par un nom fictif'],['Input','Requête et modifications, sans valeur réelle de token ou mot de passe'],['Output observé','Statut HTTP, error OAuth, présence/absence de champs'],['Verdict','Conforme / non conforme / non exécuté ; justification'],['Preuve','Capture assainie ou assertion Postman ; jamais un export de secrets']], [150,345])
add('La collection postman/pkce.collection.json couvre seulement échange, rotation, rejeu et refus après rejeu. Ses variables _first_refresh et _latest_refresh sont locales à un même run. Pour des clics Send indépendants, employer les variables d’environnement du présent dossier.')

section('15. Sources et traçabilité')
for name in ['docs/AUTHORIZATION_CODE_PKCE.md','docs/PLAN_TEST_MANUEL_POSTMAN_PKCE.md',
             'docs/KEY_SERVICE.md','docs/evidence/coverage.json',
             'authorization-server/src/main/java/org/portage/authorizationserver/config/AuthorizationSecurityConfiguration.java',
             'authorization-server/src/main/java/org/portage/authorizationserver/config/AuthorizationPersistenceConfiguration.java',
             'authorization-server/src/main/java/org/portage/authorizationserver/security/RotatingAuthorizationService.java',
             'authorization-server/src/main/java/org/portage/authorizationserver/security/RotatingRefreshTokenGenerator.java',
             'authorization-server/src/main/java/org/portage/authorizationserver/security/PublicRefreshClientAuthentication.java',
             'resource-server/src/main/java/org/portage/resourceserver/web/HelloController.java']:
    add(name,'SmallFR')
add('Références externes','H2FR')
for url in ['https://www.rfc-editor.org/rfc/rfc7636.html#appendix-B',
            'https://learning.postman.com/docs/use/send-requests/create-requests/request-settings',
            'https://learning.postman.com/v11/docs/use/send-requests/response-data/cookies',
            'https://learning.postman.com/latest-v-12/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-send-request']:
    add(url,'SmallFR')
add('ARCHITECTURE.md présente la vue synthétique du fonctionnement courant. Les rapports détaillés sont générés dans les dossiers target ; le dépôt conserve le résumé de couverture. Le PDF est généré par scripts/build-architecture-pdf.py.','MutedFR')

OUT.parent.mkdir(parents=True,exist_ok=True)
doc=Dossier(str(OUT),pagesize=A4,rightMargin=50,leftMargin=50,topMargin=48,bottomMargin=58,
            title='Platform OAuth — Architecture et scénarios de test',author='Platform OAuth',
            subject='Architecture PKCE et recette Postman avec inputs, outputs et explications')
doc.build(story,canvasmaker=DossierCanvas)
reader=PdfReader(str(OUT))
texts=[page.extract_text() or '' for page in reader.pages]
all_text='\n'.join(texts)
for ident,_,_ in rows:
    assert ident in all_text, f'Missing scenario {ident}'
assert 'CON01' in all_text and len(reader.pages)>20
print(json.dumps({'file':str(OUT),'pages':len(reader.pages),'scenarios':len(rows)+1,
                  'bytes':OUT.stat().st_size,'bookmarks':len(bookmarks)},ensure_ascii=False))
