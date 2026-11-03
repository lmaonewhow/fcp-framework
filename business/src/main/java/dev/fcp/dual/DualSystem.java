package dev.fcp.dual;

import fcp.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static fcp.Schema.*;

/** Main-container composition. Storage and authorization implementations are injected. */
public final class DualSystem {
    private DualSystem() {}
    public static Engine create(Path root,RecordStore store,Engine.Guard authorization)throws IOException {
        Registry registry=Registry.load(root);
        Map<String,Object> launchers=map(Json.parse(Files.readString(root.resolve("apps/launchers.json"))));
        registry.factories.put("stdio",(m,scope)->new ProcessProvider(m,root,launchers));
        registry.factories.put("purchase-rules",(m,scope)->new PurchaseRules());
        registry.factories.put("purchase-workflow",(m,scope)->new Workflow("purchase",store));
        registry.factories.put("repair-workflow",(m,scope)->new Workflow("repair",store));
        return new Engine(registry,authorization,ui->{if(!Set.of("purchase-flow","repair-flow").contains(ui.get("component")))throw new FcpException("UI_CATALOG","Unsupported UI contribution");});
    }
}
