"""Create the Open source release from an explicit allowlist; never include runtime data."""
from pathlib import Path
import zipfile,hashlib,json
root=Path(__file__).resolve().parents[1]
out=root/'outputs';out.mkdir(exist_ok=True)
archive=out/'cheat-program-v1.0-source.zip'
folders=['docs','src','versions','gradle','compatibility','tools','verification/java']
files=[root/n for n in ['LICENSE','README.md','THIRD_PARTY_NOTICES.md','verification/README.md','.gitignore','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat']]
for folder in folders:
 files.extend(p for p in (root/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix not in ['.pyc','.log'])
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(set(files)):
  rel=p.relative_to(root)
  assert not any(part in ['run','config','logs','.gradle','build','backups'] for part in rel.parts),rel
  z.write(p,'CheatProgram/'+rel.as_posix())
record={'file':archive.name,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'entries':len(set(files)),'runtimeDataIncluded':False}
(out/'source-manifest.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record))
