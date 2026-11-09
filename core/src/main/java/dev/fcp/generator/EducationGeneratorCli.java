package dev.fcp.generator;

import fcp.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local development/CI entry point for the identical Web renderer and safe apply engine.
 * Does not execute SQL, Python, a shell, generated code, or database writes.
 */
public final class EducationGeneratorCli {
    private EducationGeneratorCli() {}
    public static void main(String[] args)throws Exception {
        if(args.length<3||args.length>5||!Set.of("inspect","render","apply").contains(args[0]))
            throw new IllegalArgumentException("inspect <root> <report.json> | render <root> <output-dir> | apply <root> <report.json> [foundation] [roster]");
        Path root=Path.of(args[1]),destination=Path.of(args[2]);
        List<String> modules=args.length==3?List.of("foundation","roster"):Arrays.asList(args).subList(3,args.length);
        var rendered=EducationSqlRenderer.render(root,modules);
        Map<String,Object> report=new LinkedHashMap<>(rendered.profile());
        report.put("workspaceInputDigests",rendered.workspaceInputDigests());report.put("outputDigests",rendered.outputDigests());
        if(args[0].equals("render")) {
            Files.createDirectories(destination);
            for(var entry:rendered.zipBySource().entrySet())Files.write(destination.resolve(entry.getKey().substring("education:p042:".length())+".zip"),entry.getValue());
            destination=destination.resolve("rendered.json");
        } else if(args[0].equals("apply")) {
            var workspace=new GeneratedSourceWorkspace(root);
            var plan=workspace.plan("000000:1",rendered.zipBySource(),rendered.workspaceInputDigests());
            report.put("plan",plan);
            if(!Boolean.TRUE.equals(plan.get("applicable"))){write(destination,report);throw new IOException("Education generation conflicts; no files applied");}
            report.put("receipt",workspace.apply("000000:1",(String)plan.get("planId")));
        }
        write(destination,report);
        System.out.println("EDUCATION_GENERATOR_"+args[0].toUpperCase(Locale.ROOT)+" "+rendered.outputDigests().size()+" files");
    }
    private static void write(Path destination,Map<String,Object> report)throws IOException {
        Path parent=destination.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
        Files.writeString(destination,Json.write(report)+"\n",StandardCharsets.UTF_8);
    }
}
