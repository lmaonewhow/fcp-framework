package fcp.state;

import fcp.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.sql.*;
import java.util.*;
import static fcp.state.PluginStateStore.*;

/** Transactional Windows file-state backend. FilePluginStateStore owns the host lock. */
final class SqliteStateBackend implements AutoCloseable {
    private final Connection connection;
    private boolean poisoned;

    SqliteStateBackend(Path directory, boolean allowLegacy) throws IOException {
        Path database=directory.resolve("plugin-state.sqlite");
        if (Files.isSymbolicLink(database) || Files.isSymbolicLink(directory.resolve("plugin-state.sqlite-journal")))
            throw new IOException("Symlink SQLite file rejected");
        if (Files.exists(directory.resolve("plugin-state.json")) && !Files.exists(database) && !allowLegacy)
            throw new IOException("Legacy JSON requires explicit checked migration");
        Connection opened=null;
        try {
            validateNativeDirectory();
            verifyDriverArtifact();
            Class.forName("org.sqlite.JDBC");
            opened=DriverManager.getConnection("jdbc:sqlite:"+database.toUri().toASCIIString()+"?mode=rwc");
            connection=opened;
            if (!connection.getMetaData().getDriverVersion().equals("3.54.1.0"))
                throw new IOException("Unexpected SQLite driver version");
            execute("PRAGMA busy_timeout=5000");
            if (!scalar("PRAGMA journal_mode=DELETE").equalsIgnoreCase("delete"))
                throw new IOException("SQLite rollback journal unavailable");
            execute("PRAGMA synchronous=EXTRA");
            if (!scalar("PRAGMA synchronous").equals("3")) throw new IOException("SQLite durable sync unavailable");
            if (!scalar("PRAGMA quick_check").equals("ok")) throw new IOException("SQLite integrity check failed");
            String version=scalar("PRAGMA user_version");
            if (version.equals("0")) {
                if (!scalar("SELECT count(*) FROM sqlite_master WHERE type='table'").equals("0"))
                    throw new IOException("Unrecognized SQLite database");
                connection.setAutoCommit(false);
                execute("CREATE TABLE fcp_file_state(scope TEXT NOT NULL,plugin TEXT NOT NULL,enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),revision INTEGER NOT NULL CHECK(revision>=1),PRIMARY KEY(scope,plugin)) WITHOUT ROWID");
                execute("CREATE TABLE fcp_file_meta(name TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID");
                execute("PRAGMA user_version=1");
                connection.commit();connection.setAutoCommit(true);
            } else if (!version.equals("1")) throw new IOException("Unsupported SQLite state format");
        } catch (Exception | LinkageError failure) {
            if (opened!=null) try { opened.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw new IOException("SQLite state initialization failed",failure);
        }
    }

    private static void verifyDriverArtifact() throws Exception {
        if(System.getProperty("org.sqlite.lib.path")!=null || System.getProperty("org.sqlite.lib.name")!=null)
            throw new IOException("SQLite native library overrides rejected");
        java.net.URL resource=SqliteStateBackend.class.getClassLoader().getResource("org/sqlite/JDBC.class");
        if(resource==null || !resource.getProtocol().equals("jar"))throw new IOException("Pinned SQLite JAR missing");
        java.net.URL jar=((java.net.JarURLConnection)resource.openConnection()).getJarFileURL();
        if(!jar.getProtocol().equals("file"))throw new IOException("SQLite JAR must be local");
        Path path=Path.of(jar.toURI());
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid SQLite JAR");
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)) {byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;)digest.update(buffer,0,n);}
        if(!java.util.HexFormat.of().formatHex(digest.digest()).equals("f8dce420a6016278497f4f0560f65602b9db858de141b541cf91a4644ebe4639"))
            throw new IOException("SQLite JAR checksum mismatch");
    }

    private static void validateNativeDirectory() throws IOException {
        String configured=System.getProperty("org.sqlite.tmpdir");
        if (configured==null || configured.isBlank()) throw new IOException("Set org.sqlite.tmpdir to a private native extraction directory");
        Path directory=Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Native extraction directory missing");
        for (Path part=directory;part!=null;part=part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Native extraction symlink rejected");
        AclFileAttributeView acl=Files.getFileAttributeView(directory,AclFileAttributeView.class);
        if (acl!=null) {
            String owner=acl.getOwner().getName();
            for (AclEntry entry:acl.getAcl()) {
                String principal=entry.principal().getName();
                if (entry.type()==AclEntryType.ALLOW && !principal.equals(owner) && !principal.equals("NT AUTHORITY\\SYSTEM"))
                    throw new IOException("Native extraction directory must allow only its owner and SYSTEM");
            }
        } else if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            for (PosixFilePermission permission:Files.getPosixFilePermissions(directory))
                if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))
                    throw new IOException("Native extraction directory must be owner-only");
        } else throw new IOException("Native directory ownership cannot be verified");
    }

    private void execute(String sql) throws SQLException { try (Statement statement=connection.createStatement()) { statement.execute(sql); } }
    private String scalar(String sql) throws SQLException {
        try (Statement statement=connection.createStatement();ResultSet result=statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Missing SQLite result");
            String value=result.getString(1);
            if (result.next()) throw new SQLException("Ambiguous SQLite result");
            return value;
        }
    }
    Map<Key,State> load(int capacity) throws IOException {
        Map<Key,State> states=new LinkedHashMap<>();
        try (Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT scope,plugin,enabled,revision,typeof(enabled),typeof(revision) FROM fcp_file_state ORDER BY scope,plugin")) {
            while(rows.next()) {
                int flag=rows.getInt(3);long revision=rows.getLong(4);
                if (flag<0 || flag>1 || revision<1 || !rows.getString(5).equals("integer") || !rows.getString(6).equals("integer"))
                    throw new IOException("Invalid SQLite state");
                states.put(new Key(rows.getString(1),rows.getString(2)),new State(flag==1,revision));
                if (states.size()>capacity) throw new IOException("SQLite state exceeds capacity");
            }
            return states;
        } catch (SQLException | RuntimeException failure) { throw new IOException("Cannot load SQLite state",failure); }
    }
    String legacyDigest() throws IOException {
        try (PreparedStatement query=connection.prepareStatement("SELECT value FROM fcp_file_meta WHERE name='legacy_sha256'");ResultSet result=query.executeQuery()) {
            return result.next()?result.getString(1):null;
        } catch(SQLException failure) { throw new IOException("Cannot read SQLite conversion metadata",failure); }
    }
    void persist(Map<Key,State> states,String legacyDigest) {
        if (poisoned) throw new FcpException("STATE_UNAVAILABLE","SQLite store requires recovery");
        try {
            connection.setAutoCommit(false);
            execute("DELETE FROM fcp_file_state");
            try (PreparedStatement insert=connection.prepareStatement("INSERT INTO fcp_file_state VALUES(?,?,?,?)")) {
                for (var row:states.entrySet()) {
                    insert.setString(1,row.getKey().scope());insert.setString(2,row.getKey().plugin());
                    insert.setInt(3,row.getValue().enabled()?1:0);insert.setLong(4,row.getValue().revision());insert.addBatch();
                }
                insert.executeBatch();
            }
            if (legacyDigest!=null) try (PreparedStatement insert=connection.prepareStatement("INSERT INTO fcp_file_meta VALUES('legacy_sha256',?)")) {
                insert.setString(1,legacyDigest);insert.executeUpdate();
            }
            connection.commit();connection.setAutoCommit(true);
        } catch (SQLException failure) {
            poisoned=true; // The commit outcome may be uncertain. Only a verified reopen can recover.
            try { connection.rollback(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw new FcpException("STATE_UNAVAILABLE","Durable SQLite write failed; reopen and inspect storage",failure);
        }
    }
    public void close() throws IOException {
        try { connection.close(); } catch (SQLException failure) { throw new IOException("SQLite close failed",failure); }
    }
}
