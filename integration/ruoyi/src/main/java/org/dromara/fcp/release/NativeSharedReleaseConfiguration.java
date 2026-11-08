package org.dromara.fcp.release;
import fcp.release.routing.DurableReleaseRouter;
import fcp.storage.JdbcStateJournal;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
@Configuration
@ConditionalOnProperty(name="fcp.release.routing.enabled",havingValue="true")
public class NativeSharedReleaseConfiguration {
    @Bean @ConditionalOnMissingBean(DurableReleaseRouter.class)
    public DurableReleaseRouter fcpReleaseRouter(DataSource source,Environment env)throws Exception {
        Path file=Path.of(env.getRequiredProperty("FCP_RELEASE_ROUTING_KEY_FILE"));
        if(!file.isAbsolute()||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=32)throw new IllegalArgumentException("Release routing key must be a private 32-byte file");
        Path part=file.getRoot();for(Path segment:file){part=part.resolve(segment);if(Files.isSymbolicLink(part))throw new IllegalArgumentException("Routing key symlink");}
        Set<PosixFilePermission> forbidden=Set.of(PosixFilePermission.GROUP_READ,PosixFilePermission.GROUP_WRITE,PosixFilePermission.GROUP_EXECUTE,PosixFilePermission.OTHERS_READ,PosixFilePermission.OTHERS_WRITE,PosixFilePermission.OTHERS_EXECUTE);
        if(Files.getPosixFilePermissions(file).stream().anyMatch(forbidden::contains)||Files.getPosixFilePermissions(file.getParent()).stream().anyMatch(forbidden::contains))throw new IllegalArgumentException("Routing key requires owner-only file and directory");
        UserPrincipal owner=FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(!Files.getOwner(file,LinkOption.NOFOLLOW_LINKS).equals(owner))throw new IllegalArgumentException("Routing key owner mismatch");
        return new DurableReleaseRouter(new JdbcStateJournal(source,"system_release_policy"),Files.readAllBytes(file),Clock.systemUTC(),Duration.ofMinutes(10));
    }
}
