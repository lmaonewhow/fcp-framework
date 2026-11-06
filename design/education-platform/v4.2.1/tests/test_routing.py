import unittest
from routing_policy import bucket,choose,RouteRejected
class RoutingTests(unittest.TestCase):
    def test_repeat_stable(self):self.assertEqual(bucket(b's'*32,'r1','t1','a','actor'),bucket(b's'*32,'r1','t1','a','actor'))
    def test_tenant_unit_same_for_all_users(self):self.assertEqual(bucket(b's'*32,'r1','t1','a','tenant'),bucket(b's'*32,'r1','t1','b','tenant'))
    def test_monotonic_expansion(self):
        for i in range(100):
            a=choose(b's'*32,'r1','t1',str(i),'actor',1000);b=choose(b's'*32,'r1','t1',str(i),'actor',2000)
            if a=='candidate':self.assertEqual(b,'candidate')
    def test_emergency_stop_not_stable_fallback(self):self.assertEqual(choose(b's'*32,'r1','t1','a','actor',10000,emergency_stop=True),'BLOCKED')
    def test_no_eligibility_forces_stable(self):self.assertEqual(choose(b's'*32,'r1','t1','a','actor',10000,eligible=False),'stable')
    def test_zero_and_full(self):
        self.assertEqual(choose(b's'*32,'r1','t1','a','actor',0),'stable');self.assertEqual(choose(b's'*32,'r1','t1','a','actor',10000),'candidate')
    def test_booleans_not_percentages(self):
        with self.assertRaises(RouteRejected):choose(b's'*32,'r1','t1','a','actor',True)
    def test_length_delimited_prevents_join_collision(self):self.assertNotEqual(bucket(b's'*32,'ab','c','d','actor'),bucket(b's'*32,'a','bc','d','actor'))
