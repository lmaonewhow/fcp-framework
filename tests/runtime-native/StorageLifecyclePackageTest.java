import fcp.*;
import fcp.plugin.*;
import fcp.runtime.VerifiedPackageRepository;
import fcp.storage.*;
import java.nio.file.*;
import java.util.*;

/** Actual immutable package/schema checks, NOT MySQL or native lifecycle acceptance. */
public final class StorageLifecyclePackageTest {
    public static void main(String[] args)throws Exception {
        var installer=new PackageInstaller(Files.createTempDirectory("a11-package-inputs-"),Set.of("test"));
        Map<String,PluginManifest.Capability> host=new HashMap<>();for(var c:StorageCapabilities.contracts())host.put(c.id(),c);
        List<StorageDeclaration> declarations=new ArrayList<>();
        for(String version:List.of("1.0.0","1.1.0","2.0.0")){
            Path archive=Path.of(args[0],"ci.storage-retention-"+version+".zip");
            var pkg=installer.install(archive,VerifiedPackageRepository.hash(Files.readAllBytes(archive)),"a11-"+version);
            if(!pkg.manifest().pluginId().equals("ci.storage-retention")||!pkg.manifest().version().equals(version))throw new AssertionError("Fixture identity");
            for(var r:pkg.manifest().requires()){
                var actual=host.get(r.id());if(actual==null||!r.permission().equals(actual.permission())||r.sideEffects()!=actual.sideEffects()||r.idempotent()!=actual.idempotent()||r.timeoutMillis()!=actual.timeoutMillis()||!r.inputSchema().equals(Json.parse(Json.write(actual.inputSchema())))||!r.outputSchema().equals(actual.outputSchema()))throw new AssertionError("Host storage contract mismatch: "+r.id());
            }
            declarations.add(StorageDeclaration.parse(pkg.manifest().storage()));
        }
        if(!declarations.get(0).digest().equals(declarations.get(1).digest())||declarations.get(0).digest().equals(declarations.get(2).digest()))throw new AssertionError("Compatibility fixture mismatch");
        System.out.println("PASS A11 immutable package contracts: compatible reinstall and incompatible schema inputs; native/MySQL NOT_RUN");
    }
}
