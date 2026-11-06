import unittest
from reference.relational_model import open_db,insert,seed

class RelationTests(unittest.TestCase):
    def setUp(self):self.db=open_db();seed(self.db)
    def tearDown(self):self.db.rollback();self.db.close()
    def violation(self,name,**row):
        try:insert(self.db,name,**row)
        except self.db.integrity_errors as e:
            if self.db.kind=='mysql':self.assertIn(e.args[0],(1062,1451,1452,3819),str(e))
        else:self.fail('invalid relation was accepted')
    def test_valid_class(self):insert(self.db,'ed_education_class',id=113,school_id=100,term_id=110,class_code='02')
    def test_wrong_school_term_rejected(self):self.violation('ed_education_class',id=113,school_id=100,term_id=210,class_code='02')
    def test_valid_membership(self):insert(self.db,'ed_education_membership',id=1,school_id=100,enrollment_id=112,class_id=111)
    def test_cross_school_membership_rejected(self):self.violation('ed_education_membership',id=1,school_id=100,enrollment_id=112,class_id=211)
    def test_cross_school_enrollment_rejected(self):self.violation('ed_education_membership',id=1,school_id=200,enrollment_id=112,class_id=211)
    def test_cross_tenant_class_rejected(self):self.violation('ed_education_class',tenant_id='t2',id=113,school_id=100,term_id=110,class_code='02')
    def test_task_wrong_case(self):self.violation('ed_workflow_action',id=1,case_id=500,task_id=610,actor_kind='USER')
    def test_system_action_without_task(self):insert(self.db,'ed_workflow_action',id=1,case_id=500,task_id=None,actor_kind='SYSTEM')
    def test_user_action_needs_task(self):self.violation('ed_workflow_action',id=1,case_id=500,task_id=None,actor_kind='USER')
    def test_valid_task_case(self):insert(self.db,'ed_workflow_action',id=1,case_id=500,task_id=510,actor_kind='USER')
    def test_wrong_reversal_account(self):self.violation('ed_reward_entry',id=703,account_id=800,business_key='reverse',entry_type='REVERSAL',amount='-1',reverses_entry_id=701)
    def test_valid_reversal_account(self):insert(self.db,'ed_reward_entry',id=703,account_id=700,business_key='reverse',entry_type='REVERSAL',amount='-1',reverses_entry_id=701)
    def test_award_cannot_negative(self):self.violation('ed_reward_entry',id=703,account_id=700,business_key='x',entry_type='AWARD',amount='-1')
    def test_reversal_needs_origin(self):self.violation('ed_reward_entry',id=703,account_id=700,business_key='x',entry_type='REVERSAL',amount='-1',reverses_entry_id=None)
    def test_judgement_wrong_observation(self):self.violation('ed_eval_judgement',id=441,series_id=430,observation_id=421,indicator_id=410,revision_no=2,supersedes_id=440)
    def test_duplicate_root_for_series(self):self.violation('ed_eval_judgement',id=441,series_id=430,observation_id=420,indicator_id=410,revision_no=1,supersedes_id=None)
    def test_judgement_correction(self):insert(self.db,'ed_eval_judgement',id=441,series_id=430,observation_id=420,indicator_id=410,revision_no=2,supersedes_id=440)
    def test_judgement_branch_rejected(self):
        insert(self.db,'ed_eval_judgement',id=441,series_id=430,observation_id=420,indicator_id=410,revision_no=2,supersedes_id=440)
        self.violation('ed_eval_judgement',id=442,series_id=430,observation_id=420,indicator_id=410,revision_no=3,supersedes_id=440)
    def test_wrong_series_head(self):
        insert(self.db,'ed_eval_judgement_series',id=431,observation_id=421,indicator_id=410,judge_key='teacher1',purpose_key='formal',current_judgement_id=None)
        insert(self.db,'ed_eval_judgement',id=450,series_id=431,observation_id=421,indicator_id=410,revision_no=1,supersedes_id=None)
        with self.assertRaises(self.db.integrity_errors):self.db.execute('UPDATE ed_eval_judgement_series SET current_judgement_id=450 WHERE tenant_id=? AND id=430',('t1',))
    def timeline(self,id,purpose,source_type='observation'):
        return dict(id=id,person_id=100,class_id=111,source_domain='evaluation',source_type=source_type,source_id='420',purpose_key=purpose)
    def test_multiple_purposes(self):
        insert(self.db,'ed_reporting_timeline',**self.timeline(1,'teacher.timeline'));insert(self.db,'ed_reporting_timeline',**self.timeline(2,'parent.timeline'))
    def test_projection_replay_unique(self):
        insert(self.db,'ed_reporting_timeline',**self.timeline(1,'teacher.timeline'));self.violation('ed_reporting_timeline',**self.timeline(2,'teacher.timeline'))
    def test_source_type_distinguishes_ids(self):
        insert(self.db,'ed_reporting_timeline',**self.timeline(1,'teacher.timeline'));insert(self.db,'ed_reporting_timeline',**self.timeline(2,'teacher.timeline','judgement'))
    def test_current_release_unique(self):
        insert(self.db,'ed_ops_release_current',deployment_key='d1',scope_key='platform',stable_release_id=900,desired_generation=1,observed_generation=1,state='READY')
        self.violation('ed_ops_release_current',deployment_key='d1',scope_key='platform',stable_release_id=901,desired_generation=1,observed_generation=1,state='READY')
    def test_current_release_same_deployment(self):self.violation('ed_ops_release_current',deployment_key='d1',scope_key='platform',stable_release_id=902,desired_generation=1,observed_generation=1,state='READY')
    def test_historical_releases_multiple_allowed(self):insert(self.db,'ed_ops_release_binding',id=903,deployment_key='d1',status='RETIRED');insert(self.db,'ed_ops_release_binding',id=904,deployment_key='d1',status='RETIRED')
    def test_command_dedup_across_contract(self):
        insert(self.db,'ed_core_command_request',id=1,actor_key='a',effect_namespace='evaluation.record',intent_id='intent1',contract_version='v1')
        self.violation('ed_core_command_request',id=2,actor_key='a',effect_namespace='evaluation.record',intent_id='intent1',contract_version='v2')
    def test_effect_dedup_across_consumers(self):
        insert(self.db,'ed_core_inbox',id=1,effect_namespace='points.award',effect_key='reward1',implementation_version='v1')
        self.violation('ed_core_inbox',id=2,effect_namespace='points.award',effect_key='reward1',implementation_version='v2')
    def test_extensions_two_schools(self):
        insert(self.db,'ed_config_extension_schema',id=1,scope_kind='SCHOOL',scope_key='100')
        insert(self.db,'ed_config_extension_schema',id=2,scope_kind='SCHOOL',scope_key='200')
    def test_failed_outcome_not_success_audit(self):self.violation('ed_core_audit_event',id=1,result='FAILED')
    def test_denial_saved_separately(self):insert(self.db,'ed_core_security_event',id=1,outcome='DENIED')
    def test_extensions_need_schema(self):self.violation('ed_eval_observation',id=422,subject_kind='PERSON',subject_id=100,class_id=111,extensions='{}',extensions_schema_id=None)
