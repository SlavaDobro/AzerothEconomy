from pathlib import Path
import shutil,re
project=Path(__file__).resolve().parents[1];root=project.parent; src=root/'server'; dst=project/'test-server';dst.mkdir(exist_ok=True)
for name in ['libraries','versions','cache']:
 if not (dst/name).exists():shutil.copytree(src/name,dst/name)
shutil.copy2(src/'paper-26.3-244.jar',dst/'paper-26.3-244.jar')
(dst/'eula.txt').write_text('eula=true\n')
(dst/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\nenable-rcon=false\nenable-query=false\nlevel-name=economy-test-world\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=3\n')
(dst/'plugins').mkdir(exist_ok=True)
for jar in (src/'plugins').glob('*.jar'):shutil.copy2(jar,dst/'plugins'/jar.name)
def ignore(path,names):
 return [n for n in names if n.lower() in ['userdata','playerdata','players','logs','cache','.paper-remapped','pack','.cache'] or n.endswith(('.db','.db-wal','.db-shm','.sqlite','.sqlite-wal','.sqlite-shm','.log','.zip')) or re.fullmatch('[0-9a-f-]{36}.*',n,re.I)]
for folder in (src/'plugins').iterdir():
 if folder.is_dir() and folder.name not in ['.paper-remapped','bStats','spark']:
  shutil.copytree(folder,dst/'plugins'/folder.name,dirs_exist_ok=True,ignore=ignore)
shutil.copy2(project/'target/TerraWoWmain-1.1.0.jar',dst/'plugins/TerraWoWmain-1.1.0.jar')
(dst/'plugins/TerraWoWmain').mkdir(exist_ok=True)
config=(project/'src/main/resources/config.yml').read_text(encoding='utf-8').replace('self-test: false','self-test: true').replace('stop-after-tests: false','stop-after-tests: true')
(dst/'plugins/TerraWoWmain/config.yml').write_text(config,encoding='utf-8')
print('Isolated test server prepared:',dst)
