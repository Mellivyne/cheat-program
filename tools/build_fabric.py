"""Build one exact Fabric target without sharing Java outputs between Minecraft versions."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]

def mod_version():
    for line in (ROOT/'gradle.properties').read_text(encoding='utf-8').splitlines():
        if line.strip().startswith('mod_version='):
            return line.partition('=')[2].strip()
    raise ValueError('mod_version missing from gradle.properties')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('versions',nargs='*',help='Exact stable Minecraft versions; defaults to verified profiles.')
    parser.add_argument('--list',action='store_true',help='List verification status without building.')
    parser.add_argument('--probe',action='store_true',help='Compile unported sources for diagnostics; never releases a jar.')
    args=parser.parse_args()
    matrix=json.loads((ROOT/'compatibility/matrix.json').read_text(encoding='utf-8'))
    profiles={entry['minecraft']:entry for entry in matrix['targets']}
    if args.list:
        for version,profile in profiles.items():
            print(f"{version:8} Fabric Java {profile['java']} {profile['state']}")
        return 0
    selected=args.versions or [v for v,p in profiles.items() if p['state']!='pending']
    if not selected:parser.error('No verified version selected.')
    for version in selected:
        if version not in profiles:parser.error(f'Unknown/unpinned Minecraft version: {version}')
        profile=profiles[version]
        if profile['state']=='pending' and not args.probe:
            parser.error(f'{version} is still being ported. --probe compiles only; it cannot produce a supported jar.')
    report_dir=ROOT/'verification/build-reports'
    report_dir.mkdir(parents=True,exist_ok=True)
    results=[]
    for version in selected:
        log=report_dir/f'{version}.log'
        tasks=['compileJava','verifyMinecraftOutputLayout'] if args.probe else ['clean','build','verifyMinecraftArtifact']
        command=[str(ROOT/'gradlew.bat'),f'-Pminecraft_version={version}',*tasks,'--no-daemon','--console=plain']
        if args.probe:command.append('-Pcompatibility_probe=true')
        print(f'Fabric {version}: '+('API probe' if args.probe else 'build and verify'),flush=True)
        with log.open('w',encoding='utf-8') as output:
            completed=subprocess.run(command,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT)
        result={'minecraft':version,'loader':'fabric','exitCode':completed.returncode,'probe':args.probe,'log':str(log)}
        if completed.returncode==0 and not args.probe:
            libs=ROOT/('build/libs' if version==matrix['baseMinecraft'] else f'build/versions/{version}/libs')
            name=f"cheat-program-v{mod_version()}-fabric-mc{version}.jar"
            artifact=libs/name
            if not artifact.is_file():
                result['exitCode']=1
                result['error']='Expected versioned artifact missing'
            else:
                result['artifact']=str(artifact)
                result['sha256']=hashlib.sha256(artifact.read_bytes()).hexdigest()
        results.append(result)
        print(f"  {'PASS' if result['exitCode']==0 else 'FAIL'} — {log}",flush=True)
    # Keep still-valid reports for independent targets when only a subset is rebuilt.
    previous={}
    latest=report_dir/'latest.json'
    if latest.is_file():
        try:
            for old in json.loads(latest.read_text(encoding='utf-8')).get('results',[]):
                artifact=Path(old.get('artifact',''))
                if old.get('minecraft') not in selected and old.get('exitCode')==0 and artifact.is_file() and hashlib.sha256(artifact.read_bytes()).hexdigest()==old.get('sha256'):
                    previous[old['minecraft']]=old
        except (OSError,ValueError):
            pass
    previous.update({entry['minecraft']:entry for entry in results})
    report={'generatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'results':[previous[v] for v in profiles if v in previous]}
    (report_dir/'latest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return 1 if any(r['exitCode']!=0 for r in results) else 0

if __name__=='__main__':sys.exit(main())
