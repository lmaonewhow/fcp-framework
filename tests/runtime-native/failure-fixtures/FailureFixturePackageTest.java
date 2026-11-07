import fcp.plugin.*;
import fcp.runtime.VerifiedPackageRepository;
import java.nio.file.*;
import java.util.*;

/** Actual ZIP verification only. It does not establish native HTTP/MySQL acceptance. */
public final class FailureFixturePackageTest {
    public static void main(String[] args)throws Exception {
        Path archive=Path.of(args[0]);String digest=VerifiedPackageRepository.hash(Files.readAllBytes(archive));
        var installer=new PackageInstaller(Files.createTempDirectory("failure-package-input-"),Set.of("test"));
        var installed=installer.install(archive,digest,"boundary-fixture");
        if(!installed.manifest().pluginId().equals("ci.boundary")||installed.manifest().provides().size()!=1||!installed.manifest().requires().isEmpty())throw new AssertionError("Unexpected boundary manifest");
        System.out.println("PASS failure-boundary ZIP verified by production PackageInstaller; native HTTP/MySQL NOT_RUN");
    }
}
