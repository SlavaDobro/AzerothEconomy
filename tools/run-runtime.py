from pathlib import Path
import subprocess
project=Path(__file__).resolve().parents[1];root=project.parent;test=project/'test-server';java=root/'server/runtime/bin/java.exe'
with (project/'runtime-run.log').open('w',encoding='utf-8') as f:
 r=subprocess.run([str(java),'-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-Dterminal.jline=false','-Dterminal.ansi=false','-Doshi.os.windows.hkeyperfdata=false','-jar','paper-26.3-244.jar','--nogui'],cwd=test,stdin=subprocess.DEVNULL,stdout=f,stderr=subprocess.STDOUT,timeout=150)
print('Exit:',r.returncode)
print((test/'plugins/TerraWoWmain/runtime-tests.txt').read_text(encoding='utf-8'))
