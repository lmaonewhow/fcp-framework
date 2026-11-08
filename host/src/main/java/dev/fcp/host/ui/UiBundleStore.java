package dev.fcp.host.ui;

import fcp.*;
import fcp.release.version.VersionCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only trusted precompiled UI store. No caller-supplied paths, uploads or cleanup. */
public final class UiBundleStore {
    public record Bundle(String digest,String shell,String backendContract,long sizeBytes,Map<String,Object> files) {}
    public record Asset(byte[] bytes,String contentType,String digest) {}
    private final Path root;
    public UiBundleStore(Path root)throws IOException {
        if(!root.isAbsolute())throw new IOException("UI bundle root must be absolute");
        this.root=root.normalize();safe(this.root);
    }
    public static String shell(String shell){if(!Set.of("admin","teacher").contains(shell))throw new IllegalArgumentException("Unknown shell");return shell;}
    public static String digest(String digest){if(digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid bundle digest");return digest;}
    private static void safe(Path path)throws IOException {
        Path p=path.getRoot();for(Path segment:path){p=p.resolve(segment);if(Files.isSymbolicLink(p))throw new IOException("UI bundle symlink");}
    }
    private Path directory(String digest,String shell)throws IOException {Path dir=root.resolve(digest(digest)).resolve(shell(shell));safe(dir);if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new FileNotFoundException("UI bundle unavailable");return dir;}
    private static String path(String path){if(path==null||path.length()>512||!path.matches("[A-Za-z0-9_./-]+")||path.startsWith("/")||Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))throw new IllegalArgumentException("Unsafe UI asset path");return path;}
    private byte[] read(Path path,long max)throws IOException{safe(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)throw new IOException("UI asset file limit/type");try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(Math.toIntExact(max+1));if(bytes.length>max)throw new IOException("UI asset limit");return bytes;}}
    public Bundle bundle(String digest,String shell)throws IOException {
        Path dir=directory(digest,shell);
        Map<String,Object> manifest=Schema.map(Json.parse(new String(read(dir.resolve("manifest.json"),2*1024*1024),StandardCharsets.UTF_8)));
        if(!manifest.keySet().equals(Set.of("format","shell","backendContract","files","digest"))||Schema.number(manifest.get("format"))!=1||!shell.equals(manifest.get("shell"))||!digest.equals(manifest.get("digest")))throw new IOException("UI bundle manifest identity");
        Map<String,Object> content=new TreeMap<>(manifest);content.remove("digest");
        if(!VersionCodec.sha256(VersionCodec.canonical(content)).equals(digest))throw new IOException("UI bundle manifest digest");
        String contract=Schema.str(manifest.get("backendContract"));if(!contract.matches("[A-Za-z0-9._/-]{1,128}"))throw new IOException("UI backend contract");
        Map<String,Object> files=Schema.map(manifest.get("files"));if(files.isEmpty()||files.size()>10000||!files.containsKey("index.html")||files.containsKey("manifest.json"))throw new IOException("UI bundle inventory");
        long total=0;for(var entry:files.entrySet()){
            path(entry.getKey());if(Set.of("index.html.gz","index.html.br").contains(entry.getKey()))throw new IOException("Precompressed index not allowed");Map<String,Object> item=Schema.map(entry.getValue());
            if(!item.keySet().equals(Set.of("sha256","sizeBytes")))throw new IOException("UI file schema");digest(Schema.str(item.get("sha256")));long size=Schema.number(item.get("sizeBytes"));
            if(size<0||size>32L*1024*1024)throw new IOException("UI file limit");total=Math.addExact(total,size);if(total>256L*1024*1024)throw new IOException("UI bundle limit");
        }
        return new Bundle(digest,shell,contract,total,Collections.unmodifiableMap(files));
    }
    public void verify(Bundle bundle)throws IOException {for(String path:bundle.files().keySet())asset(bundle,path);}
    public Asset asset(Bundle bundle,String requested)throws IOException {
        String path=path(requested);Object raw=bundle.files().get(path);if(raw==null)throw new FileNotFoundException("UI asset not in manifest");var item=Schema.map(raw);
        byte[] bytes=read(directory(bundle.digest(),bundle.shell()).resolve(path),32L*1024*1024);
        if(bytes.length!=Schema.number(item.get("sizeBytes"))||!VersionCodec.sha256(bytes).equals(item.get("sha256")))throw new IOException("UI asset integrity failure");
        return new Asset(bytes,mime(path),VersionCodec.sha256(bytes));
    }
    public Asset index(Bundle bundle)throws IOException {
        byte[] original=asset(bundle,"index.html").bytes();if(original.length>4*1024*1024)throw new IOException("UI index limit");
        String html=new String(original,StandardCharsets.UTF_8);
        if(!html.contains("<head>")||html.toLowerCase(Locale.ROOT).contains("<base"))throw new IOException("UI index must have head and no base element");
        String prefix="/fcp/ui/assets/"+bundle.digest()+"/"+bundle.shell()+"/";
        String bootstrap="<base href=\""+prefix+"\"><meta name=\"fcp-ui-bundle\" content=\""+bundle.digest()+"\">";
        byte[] bytes=html.replaceFirst("<head>","<head>"+bootstrap).getBytes(StandardCharsets.UTF_8);
        return new Asset(bytes,"text/html; charset=utf-8",VersionCodec.sha256(bytes));
    }
    private static String mime(String p){String ext=p.substring(p.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);return switch(ext){case "html"->"text/html; charset=utf-8";case "js","mjs"->"application/javascript; charset=utf-8";case "css"->"text/css; charset=utf-8";case "json"->"application/json";case "svg"->"image/svg+xml";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "ico"->"image/x-icon";case "woff"->"font/woff";case "woff2"->"font/woff2";default->"application/octet-stream";};}
}
