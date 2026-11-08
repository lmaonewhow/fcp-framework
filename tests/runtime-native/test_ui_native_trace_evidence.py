import json
import os
from pathlib import Path
import tempfile
import unittest
from ui_native_trace_evidence import project_native_trace_evidence, bounded_lines

TRACE='1'*32
OTHER='2'*32
SECRET='private-value-must-not-leak'


def complete(trace=TRACE,route='/auth/tenant/list',code=500):
    return ('2026-11-08 02:31:53 [worker] INFO fcp.request [traceId='+trace+' spanId='+'3'*16+
            ' requestId='+'4'*32+'] - request_complete method=GET route='+route+
            ' status=200 durationMs=7 failed=false businessCode='+str(code)+
            ' businessFailed=true droppedTotal=0\n')


class NativeTraceProjectionTests(unittest.TestCase):
    def test_exact_throttle_trace_joins_only_completion_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'logs').mkdir()
            (root/'native-startup.log').write_text(SECRET+'\n\x1b[0;39m [traceId='+TRACE+' spanId='+'3'*16+' requestId='+'4'*32+'] - 访问过于频繁，请稍候再试\n')
            (root/'logs/fcp-request.log').write_text(complete()+complete(OTHER)+SECRET)
            result=project_native_trace_evidence(root,{})
            self.assertEqual(result['targetCount'],1)
            self.assertEqual(result['records'][0]['endpoint'],'/auth/tenant/list')
            self.assertEqual(result['records'][0]['businessCode'],500)
            self.assertEqual(result['missingTraceIds'],[])
            self.assertEqual(len(result['records']),1)
            self.assertNotIn(SECRET,json.dumps(result))

    def test_unknown_route_is_never_published(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'logs').mkdir()
            (root/'logs/fcp-request.log').write_text(complete(route='/'+SECRET))
            report={'uiNavigations':[{'events':[{'kind':'pageerror','traceId':TRACE}]}]}
            result=project_native_trace_evidence(root,report)
            self.assertEqual(result['records'][0]['endpoint'],'other-native-route')
            self.assertNotIn(SECRET,json.dumps(result))

    def test_missing_or_unparseable_log_does_not_infer_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            report={'uiNavigations':[{'events':[{'kind':'pageerror','traceId':TRACE}]}]}
            result=project_native_trace_evidence(root,report)
            self.assertFalse(result['available'])
            self.assertEqual(result['missingTraceIds'],[TRACE])
            (root/'logs').mkdir();(root/'logs/fcp-request.log').write_text(complete().replace('businessCode=500','businessCode='+SECRET))
            result=project_native_trace_evidence(root,report)
            self.assertTrue(result['available'])
            self.assertEqual(result['records'],[])
            self.assertEqual(result['missingTraceIds'],[TRACE])

    def test_non_exact_throttle_text_is_not_an_authorized_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'logs').mkdir()
            (root/'native-startup.log').write_text('[traceId='+TRACE+' spanId='+'3'*16+' requestId='+'4'*32+'] - 访问过于频繁，请稍候再试 '+SECRET+'\n')
            (root/'logs/fcp-request.log').write_text(complete())
            self.assertEqual(project_native_trace_evidence(root,{})['records'],[])

    def test_reader_rejects_file_and_directory_links_and_fifo(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);real=root/'real';real.mkdir();(real/'log').write_text('secret\n')
            (root/'file-link').symlink_to(real/'log')
            (root/'directory-link').symlink_to(real,target_is_directory=True)
            os.mkfifo(root/'fifo')
            for path in (root/'file-link',root/'directory-link/log',root/'fifo',root/'missing',real):
                self.assertEqual(list(bounded_lines(path)),[])

    def test_reader_ignores_growth_and_oversized_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'log';path.write_text('first\n'+'x'*40000+'\nlast\n')
            iterator=bounded_lines(path)
            self.assertEqual(next(iterator),'first')
            with path.open('a') as stream:stream.write('appended\n')
            self.assertEqual(list(iterator),['last'])
            self.assertEqual(list(bounded_lines(path,limit=10)),['appended'])

if __name__=='__main__':unittest.main()
