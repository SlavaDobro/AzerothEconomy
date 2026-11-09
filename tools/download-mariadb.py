from pathlib import Path
import urllib.request,hashlib,zipfile
p=Path(__file__).resolve().parent;url='https://archive.mariadb.org/mariadb-11.4.5/winx64-packages/';name='mariadb-11.4.5-winx64.zip'
f=p/name
urllib.request.urlretrieve(url+name,f)
sums=urllib.request.urlopen(url+'sha256sums.txt').read().decode();line=next(x for x in sums.splitlines() if x.rstrip().endswith(name));assert hashlib.sha256(f.read_bytes()).hexdigest()==line.split()[0]
with zipfile.ZipFile(f) as z:z.extractall(p)
print('MariaDB isolated test binary downloaded and SHA256 verified')
