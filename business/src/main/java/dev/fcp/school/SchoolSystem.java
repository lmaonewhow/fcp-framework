package dev.fcp.school;

import fcp.*;
import dev.fcp.dual.RecordStore;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static fcp.Schema.*;

/** Educational demo composition; identity, authorization and persistence stay host-owned. */
public final class SchoolSystem {
    private SchoolSystem() {}
    public static Engine create(Path root, RecordStore store, Engine.Guard hostGuard) throws IOException {
        Registry registry=Registry.load(root);
        Map<String,Object> launchers=map(Json.parse(Files.readString(root.resolve("apps/launchers.json"))));
        registry.factories.put("stdio",(manifest,scope)->new ProcessProvider(manifest,root,launchers));
        registry.factories.put("evaluation-java",(manifest,scope)->new EvaluationModule(store));
        return new Engine(registry,(context,target,operation)->{
            String permission=target.equals("evaluation.stats/api")?"analyze":operation;
            if(!arr(context.attributes,"permissions").contains("fcp:eval:"+permission))
                throw new FcpException("FORBIDDEN","Current identity cannot perform this operation");
            if(hostGuard!=null)hostGuard.check(context,target,operation);
        }, contribution -> {
            if(!Set.of("evaluation-table","evaluation-form","score-preview").contains(contribution.get("component")))
                throw new FcpException("UI_CATALOG","UI component is not in the trusted host catalog");
        });
    }
}
