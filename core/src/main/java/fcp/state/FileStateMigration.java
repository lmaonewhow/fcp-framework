package fcp.state;

import java.nio.file.Path;

/** Explicit plan/apply conversion. Never prints state values. Back up the directory first. */
public final class FileStateMigration {
    public static void main(String[] args) throws Exception {
        if(args.length!=3 && args.length!=4)throw new IllegalArgumentException("plan|apply DIRECTORY CAPACITY [PLAN_SHA256]");
        Path directory=Path.of(args[1]);int capacity=Integer.parseInt(args[2]);
        if(args[0].equals("plan") && args.length==3) {
            System.out.println("legacySha256="+FilePluginStateStore.legacyPlan(directory,capacity));
        } else if(args[0].equals("apply") && args.length==4) {
            FilePluginStateStore.migrateLegacy(directory,capacity,args[3]);
            System.out.println("CONVERSION_VERIFIED; original and checked backup retained");
        } else throw new IllegalArgumentException("Invalid conversion command");
    }
}
