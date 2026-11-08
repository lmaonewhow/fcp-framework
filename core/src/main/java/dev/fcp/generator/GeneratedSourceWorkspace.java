package dev.fcp.generator;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Restricted generated-source overlay. Never executes generated code or SQL.
 * Cooperating processes are serialized; the repository must not be concurrently
 * modified by an untrusted local process (filesystem path checks are not a sandbox).
 */
public final class GeneratedSourceWorkspace {
    private static final Object JVM_LOCK = new Object();
    private static final int MAX_ZIP = 16 * 1024 * 1024, MAX_FILE = 2 * 1024 * 1024;
    private static final long TTL = 10 * 60 * 1000;
    private final Path root, state;
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private record Candidate(byte[] bytes, String origin) {}
    private record Entry(String path, byte[] candidate, String current, String baseline, String origin, String oldOrigin, String status, String reason) {}
    private record Plan(String id, String owner, List<String> tables, long expires, List<Entry> entries) {}
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }

    public GeneratedSourceWorkspace(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.state = this.root.resolve(".fcp-generator");
        check(this.root);
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Repository root missing");
    }
    private <T> T locked(Work<T> work) throws IOException {
        synchronized (JVM_LOCK) {
            mkdir(state); check(state.resolve("lock"));
            try (FileChannel channel = FileChannel.open(state.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) { privatePermissions(state.resolve("lock"), false); return work.run(); }
        }
    }
    /** Compatibility entry point: a combined ZIP is conservatively owned by the entire table set. */
    public Map<String,Object> plan(String owner, List<String> tableIds, byte[] zip) throws IOException {
        validateOwner(owner); validateTables(tableIds);
        Map<String,Candidate> sources = new TreeMap<>();
        String origin = origin(owner, tableIds);
        for (var entry : unzip(zip).entrySet()) sources.put(entry.getKey(), new Candidate(entry.getValue(), origin));
        return planSources(owner, tableIds, sources);
    }
    /** Per-table ZIPs keep ownership stable when callers split or combine batches. */
    public Map<String,Object> plan(String owner, Map<String,byte[]> zipByTable) throws IOException {
        validateOwner(owner);
        if (zipByTable == null) throw new IOException("Table ZIPs required");
        List<String> tables = new ArrayList<>(zipByTable.keySet()); validateTables(tables);
        long compressed = 0, expanded = 0;
        Map<String,Candidate> sources = new TreeMap<>();
        for (String table : tables) {
            byte[] zip = zipByTable.get(table);
            if (zip == null || (compressed += zip.length) > MAX_ZIP) throw new IOException("Combined ZIP size limit");
            String origin = origin(owner, List.of(table));
            for (var entry : unzip(zip).entrySet()) {
                expanded += entry.getValue().length;
                if (expanded > 32L * 1024 * 1024 || sources.size() >= 512) throw new IOException("Combined generated-source limit");
                if (sources.putIfAbsent(entry.getKey(), new Candidate(entry.getValue(), origin)) != null)
                    throw new IOException("Cross-table target collision: " + entry.getKey());
            }
        }
        return planSources(owner, tables, sources);
    }
    private static void validateTables(List<String> tables) throws IOException {
        if (tables == null || tables.isEmpty() || tables.size() > 100 || tables.stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 200) || new HashSet<>(tables).size() != tables.size()) throw new IOException("Invalid table IDs");
    }
    private static String origin(String owner, List<String> tables) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(owner);
            for (String table : new TreeSet<>(tables)) out.writeUTF(table);
        }
        return digest(bytes.toByteArray());
    }
    private Map<String,Object> planSources(String owner, List<String> tableIds, Map<String,Candidate> sources) throws IOException {
        // Reject ancestor collisions across independently produced table archives too.
        for (String path : sources.keySet()) {
            for (Path parent = Path.of(path).getParent(); parent != null; parent = parent.getParent())
                if (sources.containsKey(parent.toString().replace('\\', '/'))) throw new IOException("File/directory target collision");
        }
        return locked(() -> {
            ensureClean();
            List<Entry> entries = new ArrayList<>();
            for (var source : sources.entrySet()) {
                String path = source.getKey();
                byte[] baselineBytes = read(base(path));
                byte[] candidateBytes = RuoYiGeneratedMetadata.stabilize(path, source.getValue().bytes, baselineBytes);
                String current = digest(read(target(path))), baseline = digest(baselineBytes), candidate = digest(candidateBytes);
                String oldOrigin = text(read(ownership(path))), origin = source.getValue().origin;
                String status, reason = "";
                if ((baseline != null || oldOrigin != null) && !Objects.equals(origin, oldOrigin)) { status = "conflict"; reason = "Target belongs to a different owner/table source, or lacks ownership metadata"; }
                else if (Objects.equals(current, candidate)) status = "unchanged";
                else if (baseline == null) status = current == null ? "create" : "conflict";
                else if (Objects.equals(current, baseline)) status = "update";
                else if (Objects.equals(candidate, baseline)) status = "preserve_manual";
                else status = "conflict";
                entries.add(new Entry(path, candidateBytes, current, baseline, origin, oldOrigin, status, reason));
            }
            long now = System.currentTimeMillis();
            plans.values().removeIf(p -> p.expires < now);
            long incoming = entries.stream().mapToLong(e -> e.candidate.length).sum();
            while (!plans.isEmpty() && (plans.size() >= 32 || cachedBytes() + incoming > 64L * 1024 * 1024)) plans.remove(plans.keySet().iterator().next());
            Plan p = new Plan(UUID.randomUUID().toString(), owner, List.copyOf(tableIds), now + TTL, entries);
            plans.put(p.id, p);
            List<Map<String,Object>> files = new ArrayList<>(); List<String> conflicts = new ArrayList<>();
            for (Entry e : entries) {
                Map<String,Object> f = new LinkedHashMap<>(); f.put("path", e.path); f.put("status", e.status); f.put("reason", e.reason);
                f.put("currentSha256", e.current); f.put("candidateSha256", digest(e.candidate));
                f.put("currentPreview", preview(read(target(e.path)))); f.put("candidatePreview", preview(e.candidate)); files.add(f);
                if (e.status.equals("conflict")) conflicts.add(e.path);
            }
            return map("planId",p.id,"expiresAt",Instant.ofEpochMilli(p.expires).toString(),"applicable",conflicts.isEmpty(),"files",files,"conflicts",conflicts);
        });
    }
    private long cachedBytes() { return plans.values().stream().flatMap(p -> p.entries.stream()).mapToLong(e -> e.candidate.length).sum(); }
    public Map<String,Object> apply(String owner, String planId) throws IOException {
        validateOwner(owner); validId(planId);
        return locked(() -> {
            ensureClean();
            Properties[] previous = {null};
            visitOperations(old -> { if (planId.equals(old.getProperty("planId"))) previous[0] = old; });
            if (previous[0] != null) { requireOwner(previous[0], owner); return result(previous[0]); }
            Plan p = plans.get(planId);
            if (p == null || p.expires < System.currentTimeMillis()) throw new IOException("Plan missing or expired; preview again");
            if (!owner.equals(p.owner)) throw new IOException("Plan owner mismatch");
            for (Entry e : p.entries) {
                if (e.status.equals("conflict")) throw new IOException("Plan has conflicts: " + e.path);
                if (!Objects.equals(e.current, digest(read(target(e.path)))) || !Objects.equals(e.baseline, digest(read(base(e.path)))) || !Objects.equals(e.oldOrigin, text(read(ownership(e.path))))) throw new IOException("Stale plan: " + e.path);
            }
            enforceStorageBudget(p);
            String id = UUID.randomUUID().toString(); Path dir = state.resolve("operations").resolve(id); mkdir(dir);
            Properties op = new Properties(); op.setProperty("operationId",id); op.setProperty("planId",p.id); op.setProperty("owner",owner);
            op.setProperty("createdAt",Instant.now().toString()); op.setProperty("status","pending"); op.setProperty("tables",String.join(",",p.tables));
            int n = 0;
            for (Entry e : p.entries) {
                if (!e.status.equals("create") && !e.status.equals("update")) continue;
                String k = "file." + n + "."; op.setProperty(k+"path",e.path);
                backup(op,k+"before",dir.resolve(n+".before"),read(target(e.path)));
                backup(op,k+"base",dir.resolve(n+".base"),read(base(e.path)));
                backup(op,k+"originBefore",dir.resolve(n+".origin"),read(ownership(e.path)));
                op.setProperty(k+"originAfter", e.origin);
                atomic(dir.resolve(n+".after"),e.candidate); op.setProperty(k+"after",digest(e.candidate)); n++;
            }
            op.setProperty("count",Integer.toString(n)); save(dir.resolve("manifest.properties"),op);
            atomic(state.resolve("active"),id.getBytes(StandardCharsets.UTF_8));
            // Journal remains until all source and baseline replacements have completed.
            for (int i=0;i<n;i++) {
                String path = op.getProperty("file."+i+".path"); byte[] after = read(dir.resolve(i+".after"));
                atomic(target(path),after); atomic(base(path),after); atomic(ownership(path),op.getProperty("file."+i+".originAfter").getBytes(StandardCharsets.UTF_8));
            }
            op.setProperty("status",n == 0 ? "noop" : "applied"); save(dir.resolve("manifest.properties"),op);
            Files.delete(state.resolve("active")); return result(op);
        });
    }
    public Map<String,Object> history(String owner) throws IOException {
        validateOwner(owner);
        return locked(() -> {
            List<Map<String,Object>> out = new ArrayList<>();
            visitOperations(p -> {
                if (owner.equals(p.getProperty("owner"))) {
                    out.add(result(p));
                    out.sort(Comparator.comparing((Map<String,Object> m) -> Instant.parse(m.get("createdAt").toString())).reversed());
                    if (out.size() > 256) out.remove(out.size()-1);
                }
            });
            String active = active();
            return map("operations",out,"recoveryRequired",active != null || hasPending());
        });
    }
    public Map<String,Object> rollback(String owner, String operationId) throws IOException {
        validateOwner(owner); validId(operationId);
        return locked(() -> {
            String active = active();
            if (active != null && !active.equals(operationId)) throw new IOException("Another operation requires recovery");
            visitOperations(p -> { if (pending(p) && !operationId.equals(p.getProperty("operationId"))) throw new IOException("Another operation requires recovery: " + p.getProperty("operationId")); });
            Path dir = state.resolve("operations").resolve(operationId); Properties op = properties(dir.resolve("manifest.properties")); requireOwner(op,owner);
            if ("rolled_back".equals(op.getProperty("status"))) { if (operationId.equals(active)) Files.delete(state.resolve("active")); return result(op); }
            boolean recovering = operationId.equals(active) || "pending".equals(op.getProperty("status")) || "rolling_back".equals(op.getProperty("status"));
            int n = Integer.parseInt(op.getProperty("count"));
            for (int i=0;i<n;i++) {
                String k="file."+i+".", path=op.getProperty(k+"path");
                String current=digest(read(target(path))), baseline=digest(read(base(path)));
                if (!matches(current,op.getProperty(k+"after")) && !(recovering && matches(current,op.getProperty(k+"before")))) throw new IOException("Rollback would overwrite later changes: "+path);
                if (!matches(baseline,op.getProperty(k+"after")) && !(recovering && matches(baseline,op.getProperty(k+"base")))) throw new IOException("Rollback baseline changed: "+path);
                String source = text(read(ownership(path)));
                if (!Objects.equals(source,op.getProperty(k+"originAfter")) && !(recovering && matches(digest(read(ownership(path))),op.getProperty(k+"originBefore")))) throw new IOException("Rollback source ownership changed: "+path);
                verifyBackup(dir.resolve(i+".origin"),op.getProperty(k+"originBefore"));
                verifyBackup(dir.resolve(i+".before"),op.getProperty(k+"before")); verifyBackup(dir.resolve(i+".base"),op.getProperty(k+"base"));
            }
            atomic(state.resolve("active"),operationId.getBytes(StandardCharsets.UTF_8));
            op.setProperty("status","rolling_back"); save(dir.resolve("manifest.properties"),op);
            for (int i=0;i<n;i++) {
                String path=op.getProperty("file."+i+".path"); restore(target(path),read(dir.resolve(i+".before"))); restore(base(path),read(dir.resolve(i+".base"))); restore(ownership(path),read(dir.resolve(i+".origin")));
            }
            op.setProperty("status","rolled_back"); save(dir.resolve("manifest.properties"),op); Files.delete(state.resolve("active")); return result(op);
        });
    }
    private static boolean matches(String a,String b) { return Objects.equals(a, "absent".equals(b)?null:b); }
    private void verifyBackup(Path p,String hash) throws IOException { if (!matches(digest(read(p)),hash)) throw new IOException("Corrupt backup"); }
    private void backup(Properties p,String key,Path path,byte[] bytes) throws IOException { p.setProperty(key,bytes==null?"absent":digest(bytes)); if(bytes!=null) atomic(path,bytes); }
    private Map<String,Object> result(Properties p) {
        List<String> files=new ArrayList<>(); for(int i=0;i<Integer.parseInt(p.getProperty("count"));i++) files.add(p.getProperty("file."+i+".path"));
        return map("operationId",p.getProperty("operationId"),"planId",p.getProperty("planId"),"status",p.getProperty("status"),"createdAt",p.getProperty("createdAt"),"files",files);
    }
    @FunctionalInterface private interface OperationVisitor { void visit(Properties p) throws IOException; }
    private void visitOperations(OperationVisitor visitor) throws IOException {
        Path dir=state.resolve("operations"); check(dir); if(!Files.exists(dir)) return;
        try(var stream=Files.list(dir)) {
            var paths=stream.iterator();
            while(paths.hasNext()) {
                Path p=paths.next(); check(p); Path manifest=p.resolve("manifest.properties");
                if(Files.exists(manifest,LinkOption.NOFOLLOW_LINKS)) visitor.visit(properties(manifest));
            }
        }
    }
    private String active() throws IOException { byte[] b=read(state.resolve("active")); if(b==null)return null; String id=new String(b,StandardCharsets.UTF_8); validId(id);return id; }
    private void ensureClean() throws IOException {
        String id=active(); if(id!=null) throw new IOException("Recovery required; rollback operation "+id);
        visitOperations(p -> { if (pending(p)) throw new IOException("Recovery required; rollback operation "+p.getProperty("operationId")); });
    }
    private static boolean pending(Properties p) { return Set.of("pending","rolling_back").contains(p.getProperty("status", "")); }
    private boolean hasPending() throws IOException { boolean[] found={false};visitOperations(p -> { if(pending(p))found[0]=true; });return found[0]; }
    private Path ownership(String p) throws IOException { if(!allowedTarget(p))throw new IOException("Invalid ownership target");Path path=state.resolve("ownership").resolve(p);check(path);return path; }
    private static String text(byte[] bytes) { return bytes == null ? null : new String(bytes,StandardCharsets.UTF_8); }
    private void enforceStorageBudget(Plan p) throws IOException {
        Path operations = state.resolve("operations"); check(operations);
        if (Files.exists(operations)) try (var paths = Files.list(operations)) { if (paths.count() >= 256) throw new IOException("Generator operation limit (256) reached; archive state with an administrator before new applies. History and rollback remain available"); }
        long used = 0;
        try (var paths = Files.walk(state)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next(); check(path);
                if (Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) used += Files.size(path);
            }
        }
        // Conservative reservation covers backups, candidates, new baselines, manifests,
        // and atomic-write temporary files. Never delete audit history to make space.
        long reservation = 4L * 1024 * 1024;
        for (Entry e : p.entries) if (e.status.equals("create") || e.status.equals("update")) {
            byte[] before = read(target(e.path)), baseline = read(base(e.path));
            reservation += (before == null ? 0 : before.length) + (baseline == null ? 0 : baseline.length) + 3L * e.candidate.length + 2048;
        }
        if (used + reservation > 256L * 1024 * 1024) throw new IOException("Generator state storage limit (256 MiB) reached; history and rollback remain available");
    }
    private static void requireOwner(Properties p,String owner) throws IOException { if(!owner.equals(p.getProperty("owner")))throw new IOException("Operation owner mismatch"); }
    private static void validateOwner(String owner) throws IOException { if(owner==null||owner.isBlank()||owner.length()>256)throw new IOException("Invalid owner"); }
    private static void validId(String id) throws IOException { if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("Invalid ID"); }
    private Path target(String p) throws IOException { if(!allowedTarget(p))throw new IOException("Invalid target"); Path t=root.resolve(p);check(t);return t; }
    private Path base(String p) throws IOException { if(!allowedTarget(p))throw new IOException("Invalid baseline target"); Path t=state.resolve("baseline").resolve(p);check(t);return t; }
    private static boolean allowedTarget(String p) { return p!=null && safeName(p) && (p.startsWith("integration/ruoyi/src/main/java/")||p.startsWith("integration/ruoyi/src/main/resources/mapper/")||p.startsWith("integration/plus-ui/src/api/")||p.startsWith("integration/plus-ui/src/views/")||p.startsWith("generated/sql/")); }
    private static boolean safeName(String p) { if(p.isEmpty()||p.length()>1024||p.startsWith("/")||p.contains("\\")||p.contains(":")||p.chars().anyMatch(c->c<32))return false; for(String s:p.split("/",-1))if(s.equals(".")||s.equals("..")||s.isEmpty())return false;return true; }
    private void check(Path p) throws IOException { Path abs=p.toAbsolutePath().normalize(); for(Path q=abs;q!=null;q=q.getParent()) {
            if(Files.isSymbolicLink(q))throw new IOException("Symbolic link forbidden: "+q);
            if(!q.equals(abs) && Files.exists(q,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(q,LinkOption.NOFOLLOW_LINKS))throw new IOException("Non-directory ancestor: "+q);
        } }
    private void mkdir(Path p) throws IOException {
        check(p);
        if (p.startsWith(state) && Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView.class) != null)
            Files.createDirectories(p,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(p);
        check(p);
        if (p.startsWith(state)) for (Path q=p; q!=null && q.startsWith(state); q=q.getParent()) privatePermissions(q,true);
    }
    private void privatePermissions(Path p, boolean directory) throws IOException {
        if (Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null)
            Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    private byte[] read(Path p) throws IOException { check(p);if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return null;if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>MAX_FILE)throw new IOException("Unsafe or oversized file: "+p);return Files.readAllBytes(p); }
    private void atomic(Path p,byte[] b) throws IOException {
        check(p); mkdir(p.getParent()); Path tmp=Files.createTempFile(p.getParent(),".fcp-",".tmp");
        try { try(FileChannel c=FileChannel.open(tmp,StandardOpenOption.WRITE)) { ByteBuffer buf=ByteBuffer.wrap(b);while(buf.hasRemaining())c.write(buf);c.force(true); } check(p);if(p.startsWith(state))privatePermissions(tmp,false);Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(tmp); }
    }
    private void restore(Path p,byte[] b) throws IOException { if(b==null){check(p);Files.deleteIfExists(p);}else atomic(p,b); }
    private void save(Path p,Properties props) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream(); props.store(out,"FCP generator journal");if(out.size()>MAX_FILE)throw new IOException("Journal metadata limit exceeded");atomic(p,out.toByteArray()); }
    private Properties properties(Path p) throws IOException { byte[] b=read(p);if(b==null)throw new IOException("Operation not found");Properties props=new Properties();props.load(new ByteArrayInputStream(b));return props; }
    private static String digest(byte[] b) { if(b==null)return null;try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);} }
    private static String preview(byte[] b) { return b==null?"":new String(b,0,Math.min(b.length,4096),StandardCharsets.UTF_8); }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m; }

    private static Map<String,byte[]> unzip(byte[] zip) throws IOException {
        if(zip==null||zip.length>MAX_ZIP||zip.length<22)throw new IOException("Invalid ZIP size");
        // Inspect central metadata because ZipInputStream does not expose Unix symlink modes.
        ByteBuffer b=ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN); int end=-1;
        for(int i=zip.length-22;i>=Math.max(0,zip.length-65557);i--)if(b.getInt(i)==0x06054b50 && i+22+u16(b,i+20)==zip.length){end=i;break;}
        if(end<0||u16(b,end+4)!=0||u16(b,end+6)!=0||u16(b,end+8)!=u16(b,end+10))throw new IOException("Invalid/multipart ZIP");
        int count=u16(b,end+10),offset=b.getInt(end+16),size=b.getInt(end+12);
        if(count<1||count>512||offset<0||size<0||(long)offset+size!=end)throw new IOException("ZIP limits or ZIP64 unsupported");
        int at=offset;Set<String> central=new HashSet<>();
        for(int i=0;i<count;i++) {
            if(at<0||at+46>end||b.getInt(at)!=0x02014b50)throw new IOException("Invalid ZIP directory");
            int len=u16(b,at+28),extra=u16(b,at+30),comment=u16(b,at+32); long next=(long)at+46+len+extra+comment;
            if(next>end||(u16(b,at+8)&1)!=0||u16(b,at+34)!=0)throw new IOException("Unsupported ZIP entry");
            int mode=(b.getInt(at+38)>>>16)&0170000;
            if(mode!=0&&mode!=0100000&&mode!=0040000)throw new IOException("ZIP links/special files forbidden");
            String name=new String(zip,at+46,len,StandardCharsets.UTF_8);validateZipName(name);
            if(!central.add(name))throw new IOException("Duplicate ZIP entry");at=(int)next;
        }
        if(at!=end)throw new IOException("Invalid ZIP directory length");
        Map<String,byte[]> result=new TreeMap<>();Set<String> local=new HashSet<>();long total=0;
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip),StandardCharsets.UTF_8)) {
            ZipEntry e;while((e=in.getNextEntry())!=null) {
                String name=e.getName();validateZipName(name);if(!central.contains(name)||!local.add(name))throw new IOException("ZIP directory mismatch");
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){total+=n;if(out.size()+n>MAX_FILE||total>32L*1024*1024)throw new IOException("ZIP expansion limit");out.write(buf,0,n);}
                if(!e.isDirectory()){String target=mapZip(name);if(result.put(target,out.toByteArray())!=null)throw new IOException("Duplicate target");}
                else if(out.size()!=0)throw new IOException("Directory with payload");
            }
        } catch(IllegalArgumentException ex){throw new IOException("Invalid ZIP encoding",ex);}
        if(!local.equals(central)||result.isEmpty())throw new IOException("Incomplete/empty ZIP");
        for(String path:result.keySet()) { int slash=path.lastIndexOf('/'); while(slash>0) { if(result.containsKey(path.substring(0,slash)))throw new IOException("File/directory target collision");slash=path.lastIndexOf('/',slash-1); } }
        return result;
    }
    private static int u16(ByteBuffer b,int i){return Short.toUnsignedInt(b.getShort(i));}
    private static void validateZipName(String name) throws IOException {
        String trimmed=name.endsWith("/")?name.substring(0,name.length()-1):name;
        if(!safeName(trimmed))throw new IOException("Unsafe ZIP path");
        if(name.endsWith("/")) {
            boolean ok=false;for(String prefix:List.of("main/java/","main/resources/mapper/","vue/api/","vue/views/"))if(prefix.startsWith(name)||name.startsWith(prefix))ok=true;
            if(!ok)throw new IOException("Unknown ZIP directory: "+name);
        }else mapZip(name);
    }
    private static String mapZip(String name) throws IOException {
        if(name.startsWith("main/java/"))return "integration/ruoyi/src/"+name;
        if(name.startsWith("main/resources/mapper/"))return "integration/ruoyi/src/"+name;
        if(name.startsWith("vue/api/")||name.startsWith("vue/views/"))return "integration/plus-ui/src/"+name.substring(4);
        if(!name.contains("/")&&name.endsWith("Menu.sql"))return "generated/sql/"+name;
        throw new IOException("Unknown generated entry: "+name);
    }
}
