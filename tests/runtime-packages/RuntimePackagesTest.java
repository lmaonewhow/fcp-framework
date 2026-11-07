import fcp.Json;
import fcp.plugin.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import java.io.*;

/** Standalone real filesystem/ZIP contract regressions; no fake installation/execution. */
public final class RuntimePackagesTest {
    static int checks; static Path base; static int serial;
    interface Action {void run() throws Exception;}
    static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    static void reject(Action action,String label)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException expected){checks++;return;}throw new AssertionError("Accepted "+label);}
    static Map<String,Object> manifest(){return Json.obj("manifestVersion",1,"pluginId","sample.echo","version","1.0.0","publisher","trusted.example","contractVersion","1.0","runtime",Json.obj("type","local","entry","worker.py","argv",List.of("--mode","echo")),"dependencies",List.of(),"provides",List.of(),"requires",List.of());}
    static String json(Map<String,Object> m){return Json.write(m);}
    static Path zip(Map<String,byte[]> files)throws Exception{Path p=base.resolve("input-"+(serial++)+".zip");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(p))){for(var item:files.entrySet()){out.putNextEntry(new ZipEntry(item.getKey()));out.write(item.getValue());out.closeEntry();}}return p;}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static Map<String,byte[]> files(Map<String,Object> manifest){Map<String,byte[]> f=new LinkedHashMap<>();f.put("plugin.json",bytes(json(manifest)));f.put("worker.py",bytes("raise RuntimeError('INSTALL MUST NOT EXECUTE')\n"));return f;}
    static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static PackageInstaller installer(String name)throws Exception{return new PackageInstaller(base.resolve(name),Set.of("trusted.example"));}
    static void install(PackageInstaller installer,Path zip,String key)throws Exception{installer.install(zip,hash(zip),key);}
    static void mutate(Path p,java.util.function.Consumer<byte[]> change)throws Exception{byte[] b=Files.readAllBytes(p);change.accept(b);Files.write(p,b);}
    static int signature(byte[] b,int sig,int after){for(int i=after;i<b.length-4;i++)if((b[i]&255)==(sig&255)&&(b[i+1]&255)==((sig>>>8)&255)&&(b[i+2]&255)==((sig>>>16)&255)&&(b[i+3]&255)==((sig>>>24)&255))return i;throw new AssertionError("signature");}
    public static void main(String[] args)throws Exception{
        base=Files.createTempDirectory("fcp-packages-test-");
        var good=manifest();PluginManifest parsed=PluginManifest.parse(json(good));
        ok(parsed.pluginId().equals("sample.echo"),"parse identity");
        ok(parsed.runtime().argv().equals(List.of("--mode","echo")),"argv preserved");
        try{parsed.runtime().argv().add("bad");throw new AssertionError("mutable argv");}catch(UnsupportedOperationException expected){checks++;}
        for(String field:List.of("password","environment","sql","unknown")){var m=manifest();m.put(field,"secret");reject(()->PluginManifest.parse(json(m)),"unknown "+field);}
        reject(()->PluginManifest.parse(json(good).replace("\"manifestVersion\":1","\"manifestVersion\":1,\"manifestVersion\":1")),"duplicate JSON key");
        for(Object v:List.of(2,"1",1.5)){var m=manifest();m.put("manifestVersion",v);reject(()->PluginManifest.parse(json(m)),"manifest version");}
        for(String v:List.of("2.0","^1.0","1")){var m=manifest();m.put("contractVersion",v);reject(()->PluginManifest.parse(json(m)),"contract version");}
        for(String path:List.of("../escape","/absolute","a\\b","a//b","a/./b","C:drive","a/../b","")){var m=manifest();m.put("runtime",Json.obj("type","local","entry",path,"argv",List.of()));reject(()->PluginManifest.parse(json(m)),"entry path");}
        var remote=manifest();remote.put("runtime",Json.obj("type","remote","entry","worker.py","argv",List.of()));reject(()->PluginManifest.parse(json(remote)),"remote runtime");
        var shell=manifest();shell.put("runtime",Json.obj("type","local","entry","worker.py","argv",List.of(),"shell",true));reject(()->PluginManifest.parse(json(shell)),"shell field");
        var deps=manifest();deps.put("dependencies",List.of(Json.obj("pluginId","sample.echo","versionRange","1.0.0")));reject(()->PluginManifest.parse(json(deps)),"self dependency");
        deps.put("dependencies",List.of(Json.obj("pluginId","sample.other","versionRange",">=1")));reject(()->PluginManifest.parse(json(deps)),"unsupported range");
        ok(PluginManifest.matchesVersion("1.9.0","^1.2.0")&&!PluginManifest.matchesVersion("2.0.0","^1.2.0")&&!PluginManifest.matchesVersion("1.1.9","^1.2.0"),"caret version semantics");
        var secret=manifest();secret.put("secretRefs",List.of("api.token"));ok(PluginManifest.parse(json(secret)).secretRefs().equals(List.of("api.token")),"named secret references");
        secret.put("secretRefs",Map.of("api.token","plaintext"));reject(()->PluginManifest.parse(json(secret)),"secret value");
        var cap=Json.obj("id","echo.run","version","1.0.0","permission","echo.run","sideEffects",false,"idempotent",true,"timeoutMillis",1000,"inputSchema",Json.obj("type","object"),"outputSchema",Json.obj("type","object"));
        var caps=manifest();caps.put("provides",List.of(cap));ok(PluginManifest.parse(json(caps)).provides().size()==1,"capability declaration");caps.put("provides",List.of(cap,cap));reject(()->PluginManifest.parse(json(caps)),"duplicate capability");
        var storage=manifest();storage.put("storage",Json.obj("schemaVersion",1,"sql","DROP TABLE host"));reject(()->PluginManifest.parse(json(storage)),"SQL storage");
        var ui=manifest();ui.put("ui",Json.obj("contractVersion","1.0","pages",List.of(Json.obj("id","page","title","Echo","component","table","permission","echo.read","capability","echo.read","script","evil"))));reject(()->PluginManifest.parse(json(ui)),"script UI");
        var validUi=manifest();validUi.put("ui",Json.obj("contractVersion","1.0","pages",List.of(Json.obj("id","home","title","Echo","permissions",List.of("echo.read"),"components",List.of("text@1"),"capabilities",List.of(),"content",Json.obj("type","text","text","Plain literal text")))));
        var uiParsed=PluginManifest.parse(json(validUi));ok(!uiParsed.ui().isEmpty(),"shared declarative UI contract");
        try{((List<?>)uiParsed.ui().get("pages")).clear();throw new AssertionError("mutable UI");}catch(UnsupportedOperationException expected){checks++;}
        Object fixtures=Json.parse(Files.readString(Path.of("tests/runtime-packages/ui-boundaries.json")));
        for(Object item:(List<?>)fixtures) {
            @SuppressWarnings("unchecked") var fixture=(Map<String,Object>)item;
            var boundary=manifest(); boundary.put("ui",Json.obj("contractVersion","1.0","pages",List.of(Json.obj("id","home","title","Echo","permissions",List.of("echo.read"),"components",List.of("text@1","field@1","button@1","table@1","stack@1"),"capabilities",List.of("echo.run"),"content",fixture.get("content")))));
            if(Boolean.TRUE.equals(fixture.get("valid"))) {PluginManifest.parse(json(boundary));checks++;}
            else reject(()->PluginManifest.parse(json(boundary)),"shared UI boundary "+fixture.get("name"));
        }
        var invalidSchema=manifest();var badCap=new LinkedHashMap<>(cap);badCap.put("inputSchema",Json.obj("$ref","https://example.invalid/schema"));invalidSchema.put("provides",List.of(badCap));reject(()->PluginManifest.parse(json(invalidSchema)),"unsupported input schema");
        Path valid=zip(files(good));PackageInstaller installer=installer("store");
        var installed=installer.install(valid,hash(valid),"request-1");
        ok(Files.isRegularFile(installed.directory().resolve("worker.py")),"package published");ok(!installed.replayed(),"new request");
        ok(installer.install(valid,hash(valid),"request-1").replayed(),"same request replay");
        ok(installer("store").install(valid,hash(valid),"request-1").replayed(),"durable replay after reopen");
        ok(!installer.install(valid,hash(valid),"request-2").replayed(),"new key same package");
        ok(!Files.getPosixFilePermissions(installed.directory().resolve("plugin.json")).contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),"read-only published bytes");
        reject(()->installer.install(valid,"0".repeat(64),"bad-hash"),"digest mismatch");
        reject(()->new PackageInstaller(base.resolve("untrusted"),Set.of()).install(valid,hash(valid),"a"),"publisher allowlist");
        var changed=files(good);changed.put("worker.py",bytes("different"));Path different=zip(changed);
        reject(()->install(installer,different,"request-1"),"idempotency digest conflict");
        reject(()->install(installer,different,"request-new"),"same version different digest");
        for(String name:List.of("../escape","/tmp/escape","a\\b","a/../escape","PLUGIN.JSON")){var f=files(good);f.put(name,bytes("x"));Path p=zip(f);reject(()->install(installer,p,"bad-"+serial),"unsafe ZIP "+name);}
        var noEntry=files(good);noEntry.remove("worker.py");Path absent=zip(noEntry);reject(()->install(installer,absent,"absent"),"missing runtime entry");
        var conflict=files(good);conflict.put("worker.py/child",bytes("x"));Path collision=zip(conflict);reject(()->install(installer,collision,"collision"),"file directory conflict");
        Path link=zip(files(good));mutate(link,b->{int p=signature(b,0x02014b50,0);b[p+5]=3;b[p+38+2]=(byte)0xff;b[p+38+3]=(byte)0xa1;});reject(()->install(installer,link,"symlink"),"Unix symlink");
        Path duplicate=zip(files(good));mutate(duplicate,b->{byte[] original=bytes("worker.py"),replace=bytes("plugin.js");for(int i=0;i<=b.length-original.length;i++){boolean match=true;for(int j=0;j<original.length;j++)if(b[i+j]!=original[j])match=false;if(match)System.arraycopy(replace,0,b,i,replace.length);}}); // missing entry remains rejected
        reject(()->install(installer,duplicate,"renamed"),"missing declared entry after ZIP rename");
        Path mismatch=zip(files(good));mutate(mismatch,b->{int p=signature(b,0x04034b50,0);b[p+30]='x';});reject(()->install(installer,mismatch,"mismatch"),"local central name mismatch");
        Path encrypted=zip(files(good));mutate(encrypted,b->{int p=signature(b,0x02014b50,0);b[p+8]|=1;});reject(()->install(installer,encrypted,"encrypted"),"encrypted ZIP");
        Path badCrc=zip(files(good));mutate(badCrc,b->{int p=signature(b,0x02014b50,0);b[p+16]^=1;});reject(()->install(installer,badCrc,"crc"),"ZIP CRC mismatch");
        var aliasFiles=files(good);aliasFiles.put("Dir/a",bytes("a"));aliasFiles.put("dir/b",bytes("b"));Path aliases=zip(aliasFiles);reject(()->install(installer,aliases,"alias"),"implicit directory aliases");
        var bombFiles=files(good);bombFiles.put("bomb",new byte[100000]);Path bomb=zip(bombFiles);reject(()->install(installer,bomb,"bomb"),"compression ratio");
        var tiny=new PackageInstaller(base.resolve("tiny"),Set.of("trusted.example"),new PackageInstaller.Limits(10,1,10,10,2));reject(()->install(tiny,valid,"tiny"),"archive byte limit");
        var count=new PackageInstaller(base.resolve("count"),Set.of("trusted.example"),new PackageInstaller.Limits(100000,1,10000,10000,200));reject(()->install(count,valid,"count"),"entry count limit");
        var expanded=new PackageInstaller(base.resolve("expanded"),Set.of("trusted.example"),new PackageInstaller.Limits(100000,20,100,150,200));reject(()->install(expanded,valid,"expanded"),"per-entry/expanded limit");
        var dupFiles=files(good);dupFiles.put("same-a",bytes("a"));dupFiles.put("same-b",bytes("b"));Path dup=zip(dupFiles);
        mutate(dup,b->{byte[] from=bytes("same-b");for(int i=0;i<=b.length-from.length;i++){boolean match=true;for(int j=0;j<from.length;j++)if(b[i+j]!=from[j])match=false;if(match)b[i+5]='a';}});
        reject(()->install(installer,dup,"duplicate"),"duplicate ZIP filenames");
        var concurrent=installer("concurrent");java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->concurrent.install(valid,hash(valid),"same"));var second=pool.submit(()->installer("concurrent").install(valid,hash(valid),"same"));var a=first.get();var b=second.get();ok(a.directory().equals(b.directory()) && a.replayed()!=b.replayed(),"concurrent serialized idempotency");}finally{pool.shutdownNow();}
        Path symlinkRoot=base.resolve("symlink-store");Files.createSymbolicLink(symlinkRoot,base.resolve("store"));reject(()->new PackageInstaller(symlinkRoot,Set.of()),"symlink store");
        Path publishedFile=installed.directory().resolve("worker.py");Files.setPosixFilePermissions(publishedFile,Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));Files.writeString(publishedFile,"tampered");reject(()->install(installer,valid,"request-1"),"published bytes tamper on replay");
        try(var entries=Files.list(base.resolve("store/staging"))){ok(entries.findAny().isEmpty(),"failed installs clean staging");}
        ok(!Files.exists(base.resolve("escape")),"no traversal output");
        System.out.println("RuntimePackagesTest PASS checks="+checks+" evidence="+base);
    }
}
