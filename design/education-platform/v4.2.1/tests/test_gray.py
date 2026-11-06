import copy
import unittest
from gray_gate import decide,DEFAULT_POLICY

def valid_input():
    group=dict(window_start='2026-11-06T00:00:00Z',window_end='2026-11-06T00:20:00Z',observed_at='2026-11-06T00:20:05Z',edge_requests=2000,executed_requests=2000,failed_requests=0,assigned_operations=1000,committed_operations=1000,failed_operations=0,denied_operations=0,pending_operations=0,telemetry_operations=1000,p95_ms=100,p95_source='trusted_histogram',metric_schema='http-v1',histogram_schema='latency-1',stratum='teacher-write')
    return dict(confirmed_invariant_breach=False,aa_verified=True,real_user_path_verified=True,observed_seconds=1800,expected_stable_manifest='a'*64,expected_candidate_manifest='b'*64,stable=dict(group,actual_manifest_digest='a'*64),candidate=dict(group,actual_manifest_digest='b'*64))

class GrayTests(unittest.TestCase):
    def setUp(self):self.data=valid_input()
    def outcome(self):return decide(self.data,now='2026-11-06T00:20:10Z')['outcome']
    def test_valid_continue(self):self.assertEqual(self.outcome(),'CONTINUE')
    def test_nan_pause(self):self.data['candidate']['p95_ms']=float('nan');self.assertEqual(self.outcome(),'PAUSE')
    def test_infinity_pause(self):self.data['candidate']['p95_ms']=float('inf');self.assertEqual(self.outcome(),'PAUSE')
    def test_zero_denominator(self):self.data['candidate']['edge_requests']=0;self.assertEqual(self.outcome(),'PAUSE')
    def test_missing_group(self):del self.data['candidate'];self.assertEqual(self.outcome(),'PAUSE')
    def test_wrong_version(self):self.data['candidate']['actual_manifest_digest']='a'*64;self.assertEqual(self.outcome(),'PAUSE')
    def test_stale(self):self.data['candidate']['window_end']='2026-11-06T00:15:00Z';self.assertEqual(self.outcome(),'PAUSE')
    def test_suppressed_errors_count_mismatch(self):self.data['candidate']['executed_requests']=1990;self.assertEqual(self.outcome(),'PAUSE')
    def test_suppressed_operations(self):self.data['candidate']['telemetry_operations']=980;self.assertEqual(self.outcome(),'PAUSE')
    def test_duplicate_telemetry(self):self.data['candidate']['telemetry_operations']=1001;self.assertEqual(self.outcome(),'PAUSE')
    def test_latency_abort(self):self.data['candidate']['p95_ms']=200;self.assertEqual(self.outcome(),'ABORT')
    def test_error_abort(self):self.data['candidate']['failed_requests']=20;self.assertEqual(self.outcome(),'ABORT')
    def test_hard_breach_overrides_bad_metrics(self):self.data['confirmed_invariant_breach']=True;del self.data['candidate'];self.assertEqual(self.outcome(),'ABORT')
    def test_pending_operation_pause(self):self.data['candidate']['pending_operations']=1;self.data['candidate']['committed_operations']=999;self.assertEqual(self.outcome(),'PAUSE')
    def test_receipt_counts_mismatch(self):self.data['candidate']['committed_operations']=999;self.assertEqual(self.outcome(),'PAUSE')
    def test_bad_p95_source(self):self.data['candidate']['p95_source']='average_of_p95';self.assertEqual(self.outcome(),'PAUSE')
    def test_low_sample(self):self.data['candidate']['edge_requests']=20;self.assertEqual(self.outcome(),'PAUSE')
    def test_aa_missing(self):self.data['aa_verified']=False;self.assertEqual(self.outcome(),'PAUSE')
    def test_browser_path_missing(self):self.data['real_user_path_verified']=False;self.assertEqual(self.outcome(),'PAUSE')
    def test_bool_counts_rejected(self):self.data['candidate']['failed_requests']=False;self.assertEqual(self.outcome(),'PAUSE')
    def test_metric_schema_missing_on_both(self):
        del self.data['stable']['metric_schema'];del self.data['candidate']['metric_schema'];self.assertEqual(self.outcome(),'PAUSE')
    def test_incomparable_strata(self):self.data['candidate']['stratum']='parent-read';self.assertEqual(self.outcome(),'PAUSE')
    def test_short_total_observation(self):self.data['observed_seconds']=60;self.assertEqual(self.outcome(),'PAUSE')
    def test_baseline_outage_pauses(self):self.data['stable']['failed_requests']=20;self.data['candidate']['failed_requests']=20;self.assertEqual(self.outcome(),'PAUSE')
    def test_invalid_policy_pauses(self):
        p=copy.deepcopy(DEFAULT_POLICY);p['minimum_coverage']=2;self.assertEqual(decide(self.data,p)['outcome'],'PAUSE')
    def test_naive_time_pauses(self):self.data['candidate']['window_start']='2026-11-06T00:00:00';self.assertEqual(self.outcome(),'PAUSE')
