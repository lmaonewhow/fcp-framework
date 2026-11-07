package dev.fcp.host;

import fcp.*;
import fcp.host.*;
import fcp.runtime.VerifiedPackageRepository;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Verified archive staging only: no lifecycle registration, schema deployment or execution. */
public final class PackageStaging {
    private final HostCapabilities host;
    private final VerifiedPackageRepository repository;
    private final Path uploads;
    public PackageStaging(HostCapabilities host,VerifiedPackageRepository repository,Path uploads)throws IOException {
        this.host=Objects.requireNonNull(host);this.repository=Objects.requireNonNull(repository);this.uploads=uploads.toAbsolutePath().normalize();
        Files.createDirectories(this.uploads);if(Files.isSymbolicLink(this.uploads))throw new IOException("Unsafe staging directory");
    }
    private void require(TrustedContext c,String plugin){host.require(c,new HostCapabilities.Grant(plugin,"plugin.lifecycle","install","tenant:"+c.tenantId()));}
    public Map<String,Object> stage(TrustedContext context,InputStream body,String digest,String key)throws IOException {
        require(context,"control");if(key==null||key.isBlank()||key.length()>256)throw new FcpException("INVALID_ARGUMENT","Stage idempotency key required");
        Path upload=Files.createTempFile(uploads,"version-",".zip");
        try {
            try(OutputStream output=Files.newOutputStream(upload)){byte[] buffer=new byte[8192];long size=0;int n;while((n=body.read(buffer))!=-1){if((size+=n)>64L*1024*1024)throw new FcpException("PACKAGE_LIMIT","Package exceeds 64 MiB");output.write(buffer,0,n);}}
            String scoped=VerifiedPackageRepository.hash(Json.write(List.of("stage",owner(context),key)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var pkg=repository.install(upload,digest,scoped);require(context,pkg.manifest().pluginId());
            return Json.obj("pluginId",pkg.manifest().pluginId(),"version",pkg.manifest().version(),"digest",pkg.sha256(),"state","VERIFIED","replayed",pkg.replayed());
        }finally{Files.deleteIfExists(upload);}
    }
    private static String owner(TrustedContext c){return Json.write(List.of(c.principal().kind().name(),c.principal().id(),c.tenantId()));}
}
