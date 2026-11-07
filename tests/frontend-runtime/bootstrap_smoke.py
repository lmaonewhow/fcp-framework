"""Production JS bootstrap smoke only. No fake native login/API response or native acceptance claim."""
from __future__ import annotations
import argparse,json,threading,time
from functools import partial
from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--dist',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--chromium',default='/usr/bin/chromium');args=parser.parse_args()
 if not (args.dist/'index.html').is_file():raise SystemExit('Actual production dist required')
 class StaticOnly(SimpleHTTPRequestHandler):
  def log_message(self,*_args):pass
  def do_GET(self):
   path=urlsplit(self.path).path
   if not path.startswith('/teacher/'):
    self.send_error(404);return
   suffix=path[len('/teacher/'):]
   self.path='/'+(suffix if '.' in suffix else 'index.html')
   super().do_GET()
 server=ThreadingHTTPServer(('127.0.0.1',0),partial(StaticOnly,directory=str(args.dist.resolve())));thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
 report={'scope':'actual production JS bootstrap and native login view only; no backend/authentication/database test','nativeBackend':'NOT_RUN','pageErrors':[],'consoleErrors':[],'status':'RUNNING'}
 try:
  from playwright.sync_api import sync_playwright
  with sync_playwright() as p:
   browser=p.chromium.launch(executable_path=args.chromium,headless=True)
   try:
    page=browser.new_page();page.on('pageerror',lambda error:report['pageErrors'].append(str(error)));page.on('console',lambda message:report['consoleErrors'].append(message.text) if message.type=='error' else None)
    page.goto(f'http://127.0.0.1:{server.server_port}/teacher/login',wait_until='domcontentloaded');page.wait_for_timeout(3000)
    report['loginTextboxes']=page.get_by_role('textbox').count();report['status']='PASS' if not report['pageErrors'] and report['loginTextboxes']>0 else 'FAIL'
   finally:browser.close()
 except Exception as error:
  report.update(status='BLOCKED',reason=type(error).__name__+' during browser bootstrap; no browser acceptance result')
 finally:
  server.shutdown();server.server_close();args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(report,ensure_ascii=False));return 0 if report['status']=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
