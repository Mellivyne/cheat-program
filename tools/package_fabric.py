"""Copy successfully verified per-Minecraft artifacts into a reviewable release folder."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]

def main():
    matrix=json.loads((ROOT/'compatibility/matrix.json').read_text(encoding='utf-8'))
    version=next(line.partition('=')[2].strip() for line in (ROOT/'gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
    output=ROOT/'outputs/fabric'
    output.mkdir(parents=True,exist_ok=True)
    records=[]
    for target in matrix['targets']:
        if target['state']=='pending':continue
        mc=target['minecraft']
        libs=ROOT/('build/libs' if mc==matrix['baseMinecraft'] else f'build/versions/{mc}/libs')
        name=f'cheat-program-v{version}-fabric-mc{mc}.jar'
        artifact=libs/name
        with zipfile.ZipFile(artifact) as jar:
            mod=json.loads(jar.read('fabric.mod.json'))
            assert mod['name']=='Cheat Program' and mod['license']=='PolyForm-Noncommercial-1.0.0'
            for notice in ['LICENSE','LICENSING.md','LICENSE-LEGACY-MIT','THIRD_PARTY_NOTICES.md']:
                assert jar.read(notice)==(ROOT/notice).read_bytes(),f'Missing or stale license notice: {mc} / {notice}'
            assert not any(path.startswith('com/cheatprogram/dev/') and path.endswith('.class') and not path.endswith('/DevTools.class') for path in jar.namelist()), 'Development fixtures must not ship'
            assert not any('AiImageProtocolCheck' in path for path in jar.namelist())
            assert mod['id']=='cheatprogram' and mod['version']==version and mod['depends']['minecraft']==mc,mc
            assert mod['depends']['java']==f">={target['java']}",mc
            assert not any('smoke' in path.lower() for path in jar.namelist()),'Private test code must not ship'
            scope=json.loads((ROOT/'compatibility/features.json').read_text(encoding='utf-8'))
            expected={name.replace('.','/')+'.class' for name in scope['modules']}
            actual={name for name in jar.namelist() if name.startswith('com/cheatprogram/module/') and name.endswith('.class') and '$' not in name}
            assert actual==expected, f'Module contents do not match the declared feature scope: {mc}'
            config=json.loads(jar.read('cheatprogram.mixins.json'))
            for mixin in config['client']+config.get('mixins',[]):
                assert config['package'].replace('.','/')+'/'+mixin+'.class' in jar.namelist(),mixin
        destination=output/mc/name
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(artifact,destination)
        digest=hashlib.sha256(destination.read_bytes()).hexdigest()
        records.append({'minecraft':mc,'projectVersion':version,'loader':'fabric','java':target['java'],
                        'file':destination.relative_to(output).as_posix(),'sha256':digest,'verification':target.get('verification',{})})
    (output/'manifest.json').write_text(json.dumps({'projectVersion':version,'artifacts':records,'pending':[t['minecraft'] for t in matrix['targets'] if t['state']=='pending']},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'SHA256SUMS.txt').write_text(''.join(f"{r['sha256']}  {r['file']}\n" for r in records),encoding='utf-8')
    print('\n'.join(f"Packaged {r['file']}" for r in records))

if __name__=='__main__':main()
