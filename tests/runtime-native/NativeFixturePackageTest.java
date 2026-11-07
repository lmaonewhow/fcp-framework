import fcp.plugin.*;
import fcp.runtime.VerifiedPackageRepository;
import java.nio.file.*;
import java.util.*;

/** Real installer verification of acceptance inputs only, NOT a native host test. */
public class NativeFixturePackageTest {
    public static void main(String[] args)throws Exception {
        var installer=new PackageInstaller(Files.createTempDirectory("native-package-inputs-"),Set.of("test"));
        for(String language:List.of("python","node")) {
            Path archive=Path.of(args[0],"ci."+language+".zip");String digest=VerifiedPackageRepository.hash(Files.readAllBytes(archive));
            var installed=installer.install(archive,digest,"native-fixture-"+language);
            if(!installed.manifest().pluginId().equals("ci."+language)||installed.manifest().ui().isEmpty())throw new AssertionError("Invalid native fixture");
            System.out.println("PASS verified native acceptance package input: "+installed.manifest().pluginId()+" sha256="+digest);
        }
    }
}
