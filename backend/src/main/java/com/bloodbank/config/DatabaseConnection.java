package com.bloodbank.config;
import java.sql.*;
public final class DatabaseConnection {
 private DatabaseConnection() {}
 private static String required(String key) throws SQLException {
  String v=System.getenv(key);
  if(v==null || v.trim().isEmpty()) throw new SQLNonTransientConnectionException("Missing database configuration: "+key,"08001");
  return v;
 }
 public static Connection getConnection() throws SQLException {
  String url=required("ORACLE_URL"), user=required("ORACLE_USER"), password=required("ORACLE_PASSWORD");
  if(!url.startsWith("jdbc:oracle:thin:@")) throw new SQLNonTransientConnectionException("Expected Oracle thin JDBC URL","08001");
  try { Class.forName("oracle.jdbc.OracleDriver"); }
  catch(ClassNotFoundException e) { throw new SQLNonTransientConnectionException("Oracle JDBC driver is missing","08001",e); }
  DriverManager.setLoginTimeout(10);
  java.util.Properties props=new java.util.Properties();
  props.setProperty("user",user); props.setProperty("password",password);
  props.setProperty("oracle.net.CONNECT_TIMEOUT","10000"); props.setProperty("oracle.jdbc.ReadTimeout","12000");
  return DriverManager.getConnection(url,props);
 }
 public static boolean isConnected() {
  try(Connection c=getConnection()) { return c.isValid(3); } catch(SQLException e) { return false; }
 }
}
