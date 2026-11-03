import dev.fcp.school.*;
import javax.sql.DataSource;
import java.io.*;import java.nio.file.*;import java.sql.*;import java.util.*;import java.util.logging.Logger;
/** Actual MySQL authorization adapter; not a substitute for native HTTP session checks. */
public final class NativeAuthorizationCheck {
 static int checks;
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 public static void main(String[]args)throws Exception {
  Properties p=new Properties();try(var input=Files.newInputStream(Path.of(args[0]))){p.load(input);}
  String url=p.getProperty("jdbc.url");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:23306/fcp_ci_native_[a-f0-9]{8}\\?.*"))throw new IllegalArgumentException("Dedicated native test endpoint required");
  DataSource source=new DataSource(){
   public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,p.getProperty("jdbc.user"),p.getProperty("jdbc.password"));}
   public Connection getConnection(String user,String pass)throws SQLException{throw new UnsupportedOperationException();}
   public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter value){}public int getLoginTimeout(){return 0;}public void setLoginTimeout(int value){}
   public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Unsupported");}public boolean isWrapperFor(Class<?> type){return false;}
  };
  try(Connection c=source.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT @@port,@@collation_connection,(SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_tenant_package' AND column_name='menu_ids')")){
   r.next();check(r.getInt(1)==23306,"actual dedicated MySQL port");System.out.println("MEASURE connectionCollation="+r.getString(2)+" packageColumnCollation="+r.getString(3));
  }
  var auth=new JdbcHostAuthorization(source);var admin=auth.resolve("000000","942201");var teacher=auth.resolve("000000","942202");var viewer=auth.resolve("000000","942203");var other=auth.resolve("100001","942204");
  check(admin.adminShell()&&admin.can("manage")&&admin.can("allClasses"),"explicit native FCP admin grants");
  check(teacher.can("create")&&teacher.can("analyze")&&!teacher.can("manage")&&!teacher.adminShell()&&teacher.classes().equals(Set.of("class-a")),"teacher role and exact class grant");
  check(viewer.can("list")&&!viewer.can("create")&&!viewer.can("manage"),"viewer remains read-only");
  check(other.scope().equals("tenant-100001")&&other.can("create")&&!other.adminShell()&&other.classes().equals(Set.of("class-a")),"second tenant uses its own active package/role");
  String menus;
  try(Connection c=source.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT menu_ids FROM sys_tenant_package WHERE package_id=942500")){if(!r.next())throw new AssertionError("fixture package missing");menus=r.getString(1);}
  String reduced=String.join(",",Arrays.stream(menus.split(",")).filter(x->!x.equals("942004")).toList());
  try {
   try(Connection c=source.getConnection();PreparedStatement s=c.prepareStatement("UPDATE sys_tenant_package SET menu_ids=? WHERE package_id=942500")){s.setString(1,reduced);check(s.executeUpdate()==1,"revoke only own synthetic package create grant");}
   check(!auth.resolve("100001","942204").can("create"),"package revocation takes effect on next fresh resolve");
   check(auth.resolve("000000","942202").can("create"),"other-tenant package mutation cannot revoke default tenant");
  } finally {
   try(Connection c=source.getConnection();PreparedStatement s=c.prepareStatement("UPDATE sys_tenant_package SET menu_ids=? WHERE package_id=942500")){s.setString(1,menus);if(s.executeUpdate()!=1)throw new AssertionError("fixture restore failed");}
  }
  check(auth.resolve("100001","942204").can("create"),"exact package grant restored");
  System.out.println("RESULT "+checks+" actual MySQL authorization checks PASS; native HTTP login must be tested separately");
 }
}
