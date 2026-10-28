"""Vérifie les seuils de couverture JaCoCo des services et écrit une preuve agrégée."""
import json, pathlib, sys, xml.etree.ElementTree as ET

def counters(path):
    """Lit les compteurs lignes/branches et refuse les rapports absents ou vides."""
    root=ET.parse(path).getroot()
    out={}
    for kind in ('LINE','BRANCH'):
        e=root.find(f"counter[@type='{kind}']")
        if e is None: raise ValueError(f'missing {kind} counter')
        missed=int(e.attrib['missed']); covered=int(e.attrib['covered'])
        if missed+covered<=0: raise ValueError(f'zero {kind} denominator')
        out[kind.lower()]={'missed':missed,'covered':covered,'ratio':covered/(missed+covered)}
    return out

def main():
    """Exige 80 % de lignes par service et sur le total pondéré."""
    root=pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else pathlib.Path(__file__).resolve().parents[1]
    modules=('key-service','authorization-server','resource-server')
    result={'threshold':0.8,'modules':{},'aggregate':{},'passed':False}
    for name in modules:
        p=root/name/'target/site/jacoco/jacoco.xml'
        if not p.is_file(): raise ValueError(f'missing JaCoCo report: {p}')
        result['modules'][name]=counters(p)
        if result['modules'][name]['line']['ratio']<.8: raise ValueError(f'{name} line coverage below 0.80')
    for kind in ('line','branch'):
        missed=sum(m[kind]['missed'] for m in result['modules'].values())
        covered=sum(m[kind]['covered'] for m in result['modules'].values())
        if missed+covered<=0: raise ValueError(f'zero aggregate {kind} denominator')
        result['aggregate'][kind]={'missed':missed,'covered':covered,'ratio':covered/(missed+covered)}
    if result['aggregate']['line']['ratio']<.8: raise ValueError('weighted aggregate line coverage below 0.80')
    result['passed']=True
    evidence=root/'docs/evidence/coverage.json'; evidence.parent.mkdir(parents=True,exist_ok=True)
    evidence.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))
    return 0

if __name__=='__main__':
    try: sys.exit(main())
    except Exception as e:
        print(f'Coverage check failed: {e}',file=sys.stderr); sys.exit(1)
