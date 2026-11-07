import fcp.FcpException;
import fcp.Json;
import fcp.plugin.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.security.*;
import java.io.*;

/** Actual build and PackageInstaller rejection, no executing fixtures and no fabricated sandbox. */
public final class PluginDependencyPolicyTest {
    static int checks,serial;static Path base;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    static Map<String,String> files(String entry){
        var manifest=Json.obj("manifestVersion",1,"pluginId","policy.test","version","1.0.0","publisher","test","contractVersion","1.0",
            "runtime",Json.obj("type","local","entry",entry,"argv",List.of()),"provides",List.of());
        var files=new LinkedHashMap<String,String>();files.put("plugin.json",Json.write(manifest));
        files.put(entry,entry.endsWith(".py")?"raise RuntimeError('INSTALL MUST NOT EXECUTE')\n":"throw Error('INSTALL MUST NOT EXECUTE');\n");return files;
    }
    static Path directory(Map<String,String> files)throws Exception {
        Path path=Files.createDirectory(base.resolve("input-"+(serial++)));
        for(var e:files.entrySet()){Path file=path.resolve(e.getKey());Files.createDirectories(file.getParent());Files.writeString(file,e.getValue());}return path;
    }
    static Path zip(Path input)throws Exception {
        Path zip=base.resolve("archive-"+(serial++)+".zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip));var paths=Files.walk(input)) {
            for(Path path:paths.filter(Files::isRegularFile).sorted().toList()){out.putNextEntry(new ZipEntry(input.relativize(path).toString()));out.write(Files.readAllBytes(path));out.closeEntry();}
        }return zip;
    }
    static PackageInstaller.InstalledPackage install(Path input,Path store)throws Exception {
        Path zip=zip(input);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        return new PackageInstaller(store,Set.of("test")).install(zip,hash,"gate-test");
    }
    static void rejected(String name,String entry,String filename,String content,String code)throws Exception {
        var files=files(entry);files.put(filename,content);Path input=directory(files),store=base.resolve("store-"+(serial++));
        try{PluginDependencyPolicy.verify(input);throw new AssertionError("Build accepted "+name);}catch(FcpException expected){check(expected.code.equals(code),name+" build error code");}
        try{install(input,store);throw new AssertionError("Installer accepted "+name);}catch(FcpException expected){check(expected.code.equals(code),name+" install error code");}
        for(String folder:List.of("packages","versions","requests","staging"))try(var paths=Files.list(store.resolve(folder))){check(paths.findAny().isEmpty(),name+" rejected before publication/receipt "+folder);}
    }
    public static void main(String[] args)throws Exception {
        base=Files.createTempDirectory("fcp-policy-test-");
        for(String entry:List.of("worker.py","worker.mjs")) {
            var good=files(entry);good.put("requirements.txt","fcp-contract-sdk==1.0.0\nfcp_capabilities_sdk>=1.0\nrequests>=2\n");
            good.put("package.json",Json.write(Json.obj("dependencies",Json.obj("@fcp/contracts","^1.0.0","@fcp/capabilities-sdk","1.0.0","lodash","4.17.21"))));
            good.put("helpers.py","import json, os\nfrom fcp_contract_sdk import Client\n");
            good.put("helpers.mjs","import readline from 'node:readline';\nimport {Client} from '@fcp/contracts';\n");
            Path input=directory(good);PluginDependencyPolicy.verify(input);checks++;
            var installed=install(input,base.resolve("valid-"+(serial++)));check(Files.exists(installed.directory().resolve(entry)),"portable dependencies and contract SDK are allowed");
        }
        rejected("Python RuoYi requirement","worker.py","requirements.txt","ruoyi-system==5.6.2\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Python normalized host requirement","worker.py","requirements.txt","fcp_host==1.0\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Python RuoYi type import","worker.py","worker.py","from org.dromara.system import SysUser\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Python host business import","worker.py","worker.py","import json, dev.fcp.school as school\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Python semicolon direct import","worker.py","worker.py","import json; import fcp.host\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Python from import host module","worker.py","worker.py","from dev.fcp import host\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Python multiline from import host module","worker.py","worker.py","from dev.fcp import (\n    host,\n)\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node host dependency","worker.mjs","package.json","{\"dependencies\":{\"@fcp/host\":\"1.0.0\"}}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node optional host dependency","worker.mjs","package.json","{\"optionalDependencies\":{\"ruoyi-common\":\"1.0.0\"}}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node peer host dependency","worker.mjs","package.json","{\"peerDependencies\":{\"@fcp/business\":\"1.0.0\"}}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node dev host dependency","worker.mjs","package.json","{\"devDependencies\":{\"@ruoyi/system\":\"1.0.0\"}}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node aliased host dependency","worker.mjs","package.json","{\"dependencies\":{\"safe-name\":\"npm:@fcp/host@1.0.0\"}}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node bundled host dependency","worker.mjs","package.json","{\"bundledDependencies\":[\"@fcp/host\"]}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Vendored implementation package metadata","worker.mjs","node_modules/alias/package.json","{\"name\":\"@fcp/business\",\"version\":\"1.0.0\"}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Direct relative host source import","worker.mjs","worker.mjs","import service from '../../host/src/main/service.js';\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node lock nested host dependency","worker.mjs","package-lock.json","{\"packages\":{\"node_modules/safe/node_modules/@fcp/business\":{\"version\":\"1.0.0\"}}}","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node ESM direct host import","worker.mjs","worker.mjs","import {service} from '@ruoyi/system';\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node multiline direct host import","worker.mjs","worker.mjs","import {\n service\n} from '@fcp/host';\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node CommonJS direct host import","worker.cjs","worker.cjs","const service = require('@fcp/host');\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Node literal dynamic host import","worker.mjs","worker.mjs","const service = await import('@fcp/business');\n","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("Bundled JVM host implementation","worker.py","ruoyi-common.jar","not executed","HOST_IMPLEMENTATION_DEPENDENCY");
        rejected("pip include cannot hide declarations","worker.py","requirements.txt","-r ../host/requirements.txt\n","PLUGIN_DEPENDENCY_METADATA");
        rejected("pip editable host dependency","worker.py","requirements.txt","-e ../host\n","PLUGIN_DEPENDENCY_METADATA");
        rejected("npm filesystem host dependency","worker.mjs","package.json","{\"dependencies\":{\"safe\":\"file:../host\"}}","PLUGIN_DEPENDENCY_METADATA");
        rejected("unsupported dynamic Python metadata","worker.py","setup.py","raise RuntimeError('MUST NOT EXECUTE')\n","PLUGIN_DEPENDENCY_METADATA");
        System.out.println("PluginDependencyPolicyTest PASS checks="+checks+" (trusted-code dependency gate, NOT OS isolation)");
    }
}
