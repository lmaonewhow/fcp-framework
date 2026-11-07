package dev.fcp.dev;

import javax.sql.DataSource;
import java.sql.*;
import java.io.PrintWriter;
import java.util.logging.Logger;

/** Private deployment configuration only. No DDL, pooling, or request-supplied connections. */
final class DevelopmentDataSource implements DataSource {
    private final String url,user,password;
    DevelopmentDataSource(String url,String user,String password) {
        // This dev host is intentionally local-only, including its database connection.
        if(!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/[A-Za-z0-9_]+(?:\\?[^#]*)?"))
            throw new IllegalArgumentException("Development storage requires an explicit loopback MySQL URL");
        this.url=url;this.user=user;this.password=password;
    }
    public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}
    public Connection getConnection(String user,String password)throws SQLException{throw new SQLFeatureNotSupportedException();}
    public PrintWriter getLogWriter(){return null;}
    public void setLogWriter(PrintWriter writer)throws SQLException{throw new SQLFeatureNotSupportedException();}
    public void setLoginTimeout(int seconds)throws SQLException{throw new SQLFeatureNotSupportedException();}
    public int getLoginTimeout(){return 0;}
    public Logger getParentLogger()throws SQLFeatureNotSupportedException{throw new SQLFeatureNotSupportedException();}
    public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Not a wrapper");}
    public boolean isWrapperFor(Class<?> type){return false;}
}
