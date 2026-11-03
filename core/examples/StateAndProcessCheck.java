import fcp.*;
import fcp.state.*;
import java.nio.file.*;
import java.util.*;
import static fcp.Schema.*;

/** Actual standalone state persistence and Java-managed Python smoke. No Spring or test HTTP backend. */
public class StateAndProcessCheck {
    static int count;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);System.out.println("PASS "+(++count)+" "+name);}
    public static void main(String[] args)throws Exception {
        Path example=Path.of(args.length==0?"examples":args[0]).toAbsolutePath();
        Path directory=Files.createTempDirectory("fcp-core-state-");
        try {
            var key=new PluginStateStore.Key("tenant-a","stats");
            try(var state=new FilePluginStateStore(directory,4)){
                var initial=state.getOrCreate(key,false);check(!initial.enabled()&&initial.revision()==1,"secure initial state is disabled");
                var enabled=state.compareAndSet(key,1,true,"admin");check(enabled.enabled()&&enabled.revision()==2,"revisioned enable");
                try{state.compareAndSet(key,1,false,"admin");throw new AssertionError("stale update accepted");}catch(FcpException expected){check(expected.code.equals("STALE_COMPOSITION"),"stale update rejected");}
                var disabled=state.compareAndSet(key,2,false,"admin");check(!disabled.enabled()&&disabled.revision()==3,"revisioned disable");
            }
            try(var reopened=new FilePluginStateStore(directory,4)){
                var saved=reopened.getOrCreate(key,true);check(!saved.enabled()&&saved.revision()==3,"reopen preserves disabled state despite initial=true");
            }
            var document=Json.obj("runtime",Json.obj("launcher","python","timeout",5,"idleTimeoutMs",300));
            var launchers=Json.obj("python",Json.obj("argv",List.of("{python}","-I","-S","-u","{root}/worker.py"),"requires",List.of("{root}/worker.py")));
            try(var provider=new ProcessProvider(document,example,launchers)){
                check(number(ProcessProvider.metrics().get("activeProcesses"))==0,"Python starts lazily");
                var result=map(provider.invoke(null,"stats","sum",Json.obj("values",List.of(1,2,3))));
                long pid=number(result.get("pid"));check(number(result.get("sum"))==6&&pid!=ProcessHandle.current().pid(),"actual child computes sum");
                check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"reported Python PID is alive");
                var next=map(provider.invoke(null,"stats","sum",Json.obj("values",List.of(4,5))));
                check(number(next.get("sum"))==9&&number(next.get("pid"))==pid,"hot call reuses child");
                long deadline=System.nanoTime()+5_000_000_000L;
                while(number(ProcessProvider.metrics().get("activeProcesses"))!=0&&System.nanoTime()<deadline)Thread.sleep(25);
                check(number(ProcessProvider.metrics().get("activeProcesses"))==0,"idle child reclaimed");
                check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"idle PID exited");
                var resumed=map(provider.invoke(null,"stats","sum",Json.obj("values",List.of(7,8))));
                check(number(resumed.get("sum"))==15,"next call restarts real child");
            }
            check(number(ProcessProvider.metrics().get("activeProcesses"))==0,"close leaves no managed child");
            System.out.println("RESULT "+count+" framework-only checks PASS; native RuoYi/MySQL/UI not exercised");
        }finally{try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
