"""Verify actual compiled native advice with its own bundled dependencies. No Spring stubs."""
import json,os,subprocess,sys,tempfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def verify(jar):
 with tempfile.TemporaryDirectory(prefix='fcp-native-advice-') as work:
  work=Path(work)
  with zipfile.ZipFile(jar) as archive:
   for info in archive.infolist():
    path=Path(info.filename)
    if path.is_absolute() or '..' in path.parts:raise RuntimeError('Unsafe native JAR entry')
    if (info.filename.startswith('BOOT-INF/classes/') and info.filename.endswith('.class')) or (info.filename.startswith('BOOT-INF/lib/') and info.filename.endswith('.jar')):
     target=work/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(info))
  classpath=os.pathsep.join([str(work/'BOOT-INF/classes'),str(work/'BOOT-INF/lib/*'),str(work)])
  subprocess.run(['javac','--release','17','-cp',classpath,'-d',str(work),str(ROOT/'tests/runtime-native/advice/PluginAdviceContractProbe.java')],check=True,timeout=120)
  result=subprocess.run(['java','-cp',classpath,'PluginAdviceContractProbe'],check=True,capture_output=True,text=True,timeout=60)
  report=json.loads(result.stdout.strip().splitlines()[-1]);output=ROOT/'reports/native-release-composition';output.mkdir(parents=True,exist_ok=True)
  (output/'advice-contract.json').write_text(json.dumps(report,indent=2)+'\n')
  print('PASS actual compiled native advice contract; live response remains separately required')
if __name__=='__main__':verify(Path(sys.argv[1]))
