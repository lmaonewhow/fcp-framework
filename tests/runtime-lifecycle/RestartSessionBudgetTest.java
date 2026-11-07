import fcp.*;import fcp.runtime.*;import java.nio.file.*;import java.util.*;
/** Actual Python sessions; fixture authorization, durable file journal, no business-call replay. */
public class RestartSessionBudgetTest {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("fcp-session-budget-");var host=new RealRuntimeTest.Host();var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));
  var pkg=RealRuntimeTest.install(repo,root,"python","python",false);Path journal=root.resolve("calls");Map<String,String> executables=Map.of("python",args[0]);
  try(var runtime=new EngineRuntimePort(host,repo,executables,journal,3)){
   for(int i=0;i<5;i++) {runtime.prepare("python","1.0.0",pkg.sha256());RealRuntimeTest.check(runtime.ready("python","1.0.0",pkg.sha256()),"real ready");runtime.stop("python");Thread.sleep(260);}
   // Two failed sessions remain charged even when a subsequent clean session completes.
   for(int i=0;i<2;i++){runtime.prepare("python","1.0.0",pkg.sha256());long pid=Schema.number(runtime.health("python").get("pid"));ProcessHandle.of(pid).orElseThrow().destroyForcibly();ProcessHandle.of(pid).ifPresent(p->p.onExit().join());runtime.stop("python");Thread.sleep(260);}
   runtime.prepare("python","1.0.0",pkg.sha256());runtime.stop("python");Thread.sleep(260);
  }
  try(var runtime=new EngineRuntimePort(host,repo,executables,journal,3)){
   // Budget has room for exactly one attempt, not a reset to three.
   runtime.prepare("python","1.0.0",pkg.sha256());long pid=Schema.number(runtime.health("python").get("pid"));ProcessHandle.of(pid).orElseThrow().destroyForcibly();ProcessHandle.of(pid).ifPresent(p->p.onExit().join());runtime.stop("python");Thread.sleep(260);
   RealRuntimeTest.rejected(()->runtime.prepare("python","1.0.0",pkg.sha256()),"RESTART_BUDGET");
  }
  try(var runtime=new EngineRuntimePort(host,repo,executables,journal,3)){runtime.stop("python");RealRuntimeTest.rejected(()->runtime.prepare("python","1.0.0",pkg.sha256()),"RESTART_BUDGET");}
  RealRuntimeTest.check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"all children closed");
  System.out.println("PASS actual Python: 5 clean cycles, two crashes survive clean stop+journal reopen, third crash exhausts persistent budget, absent-runner stop cannot reset it");
 }
}
