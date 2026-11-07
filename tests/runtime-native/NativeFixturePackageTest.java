import fcp.plugin.*;
import fcp.runtime.VerifiedPackageRepository;
import java.nio.file.*;
import java.util.*;

/** Real installer verification of acceptance inputs only, NOT a native host test. */
public class NativeFixturePackageTest {
    public static void main(String[] args)throws Exception {
        var installer=new PackageInstaller(Files.createTempDirectory("native-package-inputs-"),Set.of("test"));
        for(String fixture:List.of("python","node","python-1.1.0","python-1.2.0")) {
            String language=fixture.startsWith("python")?"python":"node";
            Path archive=Path.of(args[0],"ci."+fixture+".zip");String digest=VerifiedPackageRepository.hash(Files.readAllBytes(archive));
            var installed=installer.install(archive,digest,"native-fixture-"+fixture);
            if(!installed.manifest().pluginId().equals("ci."+language)||installed.manifest().ui().isEmpty())throw new AssertionError("Invalid native fixture");
            String expectedVersion=fixture.contains("-")?fixture.substring(fixture.indexOf("-")+1):"1.0.0";
            if(!installed.manifest().version().equals(expectedVersion))throw new AssertionError("Wrong fixture version");
            if(installed.manifest().storage().isEmpty()==expectedVersion.equals("1.2.0"))throw new AssertionError("Wrong storage fixture");
            System.out.println("PASS verified native acceptance package input: "+installed.manifest().pluginId()+" sha256="+digest);
        }
    }
}
