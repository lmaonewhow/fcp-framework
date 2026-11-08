package dev.fcp.generator;
import java.util.Map;
/** Build-time reviewed input/resource pins. Updating a profile requires a reviewed rebuild. */
final class EducationGeneratorSources {
    static final Map<String,String> SOURCE_SHA256=Map.ofEntries(
        Map.entry("contracts/education/catalog.json","60aff6c0a62037b7ca2ae02bd0ae67d84518d09303d3715e8005a243632ae0db"),
        Map.entry("contracts/education/sources.lock.json","aabfd2efdeb4182e595fdee2cbe17c63f03dbfeb385956a9667e7b8925fb11f0"),
        Map.entry("design/education-platform/v4.2.1/contracts/data-contracts.json","689e55dbc91190c87e28ed32afc95b74e0d883d0d2f567f83b9be1de2c79fae6"),
        Map.entry("design/education-platform/v4.2.1/database/model.json","490a9811225aa6d0624129485d81eb16ef01bd53d78b936a18c59a062f4d6622"),
        Map.entry("design/education-platform/v4.2.1/database/sql/P042__fresh_isolated_schema.sql","9f9743dff09d4bb303c53cc2103d5228127430fbf19bc8a636b9004538bbb111"),
        Map.entry("design/education-platform/v4.2.1/docs/02_数据库完整设计基线.md","a75ffe6133522dfdd59ae0f9df85f6c0dc8b69dd93c73e7fec65b4e31735d7e1"),
        Map.entry("design/education-platform/v4.2.1/docs/03_事务与版本协议.md","de453784deb64f6ea200abb77c5986aad7066be02a83370e6c87200393ece7f7"),
        Map.entry("design/education-platform/v4.2.1/docs/11_最终核查与4.2.1修订.md","40828aff39d4ae568601c2c27b88e9705110400f42ce46e9173753c89ff98c64"),
        Map.entry("design/education-platform/v4.2.1/tools/check_design.py","662d851ade16744ac64c3008822b1cf7b85ce1bd5445089be521c4b4a8596b69"),
        Map.entry("design/education-platform/v4.2.1/tools/common.py","c0355a60779288143219eec27d4a5b01509fcb49dd7eb884d95e47b2e2fc559b"),
        Map.entry("design/education-platform/v4.2.1/tools/generate_schema.py","b9909ef21fd7f0fab34008857bc74ee526b5f02fea12cd3a1bbfb9bca92d1a1c")
    );
    static final Map<String,String> RESOURCE_SHA256=Map.ofEntries(
        Map.entry("rules.json","e5b4af63d963740d26339ca3e5cf8300bcd665ae760bc4764d3bc22bd77b5f14"),
        Map.entry("templates/foundation/Workspace.vue.tpl","ed61a52271aaaa7b4437ab665eaf35b049da04f7cd3fe641583e025e20bb6202"),
        Map.entry("templates/foundation/api.ts.tpl","a97a7a353336cef13e61f71129518479faf0765b9e9b2a9b286bd60c2ea41342"),
        Map.entry("templates/foundation/schema.ts.tpl","82a302704140c5ed4e924f0916c71646d3b85518b5ef42335e9ea82e6a794712"),
        Map.entry("templates/roster/Workspace.vue.tpl","0801d44325234e91be585a88076f88f28a06651b70c17b52c9101a70ae3efeee"),
        Map.entry("templates/roster/api.ts.tpl","3b8db12c3c692ea9166d0f044dcc64f712d0a6fbd6fa1cb2e5dbdd5d35e1db9f"),
        Map.entry("templates/roster/schema.ts.tpl","82a302704140c5ed4e924f0916c71646d3b85518b5ef42335e9ea82e6a794712")
    );
    static final String RENDERER_SHA256="76dc8ef24d25fddf1e5a8012f844c7aa057515808c0c4d5b7ed680115e6393f9";
}
