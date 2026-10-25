package com.bloodbank.tools;

import com.bloodbank.config.WebAdminPolicy;
import com.bloodbank.util.Data;
import com.bloodbank.util.Passwords;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Offline bootstrap only; not part of the public backend source tree/artifact. */
public final class CreateWebAdmin {
 private static final class Refused extends IllegalArgumentException { Refused(String message) {super(message);} }
 private static final Pattern LOCAL_URL=Pattern.compile("jdbc:oracle:thin:@//(localhost|127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})/XEPDB1");
 static void localConfiguration(boolean enabled,String url,String user) {
  Matcher match=LOCAL_URL.matcher(url==null?"":url);
  if(!enabled||!"SYSTEM".equals(user)||!match.matches())
   throw new Refused("Setup requires explicit WEB_ADMIN_ENABLED=true and a local SYSTEM/XEPDB1 connection");
  int port=Integer.parseInt(match.group(2));
  if(port<1||port>65535)throw new Refused("Invalid local database port");
 }
 static long create(Connection c,String fullName,String email,char[] password,char[] confirmation) throws SQLException {
  try {
   String name=text(fullName,100);
   String address=text(email,254).toLowerCase(Locale.ROOT);
   if(!address.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||address.getBytes(StandardCharsets.UTF_8).length>254
     ||address.codePoints().anyMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)))
    throw new Refused("Invalid administrator email");
   if(password==null||confirmation==null||password.length<12||password.length>128
      || !Arrays.equals(password,confirmation)||new String(password).isBlank())
    throw new Refused("Use matching nonblank passwords between 12 and 128 characters");
   c.setAutoCommit(false);
   try {
    try(PreparedStatement s=c.prepareStatement("SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS session_user, SYS_CONTEXT('USERENV','AUTHENTICATED_IDENTITY') AS identity, SYS_CONTEXT('USERENV','CON_NAME') AS container_name, SYS_CONTEXT('USERENV','ISDBA') AS is_dba, SYS_CONTEXT('USERENV','PROXY_USER') AS proxy_user FROM dual");ResultSet r=s.executeQuery()) {
     if(!r.next()||!"SYSTEM".equals(r.getString("session_user"))||!"SYSTEM".equals(r.getString("identity"))
       ||!"XEPDB1".equals(r.getString("container_name"))||!"FALSE".equals(r.getString("is_dba"))||r.getString("proxy_user")!=null)
      throw new Refused("Setup requires a direct normal SYSTEM connection to XEPDB1, not SYSDBA/proxy");
    }
    try(PreparedStatement s=c.prepareStatement("SELECT user_id FROM SYSTEM.users WHERE email=?")) {
     s.setString(1,address);try(ResultSet r=s.executeQuery()) {
      if(r.next())throw new Refused("Email already exists; setup never promotes or modifies an existing account");
     }
    }
    String hash=Passwords.hash(new String(password));
    long id;
    try(PreparedStatement s=c.prepareStatement("SELECT SYSTEM.seq_users.NEXTVAL FROM dual");ResultSet r=s.executeQuery()) {
     if(!r.next())throw new SQLException("User sequence unavailable");id=r.getLong(1);
     if(id<1||id>9999999999L)throw new SQLException("User sequence outside supported range");
    }
    try(PreparedStatement s=c.prepareStatement("INSERT INTO SYSTEM.users(user_id,full_name,email,password_hash,user_role,account_status) VALUES(?,?,?,?,'ADMIN','ACTIVE')")) {
     s.setLong(1,id);s.setString(2,name);s.setString(3,address);s.setString(4,hash);
     if(s.executeUpdate()!=1)throw new SQLException("Administrator insert did not affect one row");
    }
    c.commit();return id;
   } catch(SQLException|RuntimeException e) {Data.rollback(c,e);throw e;}
  } finally {
   if(password!=null)Arrays.fill(password,'\0');
   if(confirmation!=null)Arrays.fill(confirmation,'\0');
  }
 }
 private static String text(String value,int maxBytes) {
  if(value==null)throw new Refused("Required account details missing");
  String text=value.strip();
  if(text.isEmpty()||text.codePoints().noneMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c))
    || text.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0xD800&&c<=0xDFFF))
    || text.getBytes(StandardCharsets.UTF_8).length>maxBytes)
   throw new Refused("Account details are invalid or exceed the byte limit");
  return text;
 }
 public static void main(String[] args) {
  char[] dbPassword=null,password=null,confirmation=null;int exit=0;
  try {
   if(args.length!=1||!"--create-local-web-admin".equals(args[0]))
    throw new Refused("Explicit --create-local-web-admin operation required");
   Console console=System.console();
   if(console==null)throw new Refused("Interactive console required; redirected setup is disabled");
   String url=System.getenv("ORACLE_URL"),user=System.getenv("ORACLE_USER");
   localConfiguration(WebAdminPolicy.fromEnvironment().enabled(),url,user);
   String name=console.readLine("New administrator full name: ");
   String email=console.readLine("New administrator email (must not already exist): ");
   password=console.readPassword("New web administrator password (12-128 characters): ");
   confirmation=console.readPassword("Confirm new web administrator password: ");
   if(!"CREATE LOCAL WEB ADMIN".equals(console.readLine("Type CREATE LOCAL WEB ADMIN to insert one new application account: ")))
    throw new Refused("Setup cancelled");
   dbPassword=console.readPassword("Existing local SYSTEM database password: ");
   if(dbPassword==null||dbPassword.length==0)throw new Refused("Database password required");
   Class.forName("oracle.jdbc.OracleDriver");
   Properties props=new Properties();props.setProperty("user",user);props.setProperty("password",new String(dbPassword));
   props.setProperty("oracle.net.CONNECT_TIMEOUT","10000");props.setProperty("oracle.jdbc.ReadTimeout","12000");
   try {
    try(Connection c=DriverManager.getConnection(url,props)) {
     Arrays.fill(dbPassword,'\0');props.clear();
     long id=create(c,name,email,password,confirmation);
     console.printf("Created new application ADMIN account ID %d. No Oracle user/role or hospital/staff account was created.%n",id);
    }
   } finally {props.clear();}
  } catch(Refused e) {System.err.println("REFUSED: "+e.getMessage());exit=1;}
  catch(SQLException e) {System.err.println("Setup failed; no success confirmed. Oracle code="+e.getErrorCode()+". Check account existence before retrying.");exit=1;}
  catch(Exception e) {System.err.println("Setup failed; check local configuration. No credentials are printed.");exit=1;}
  finally {
   if(dbPassword!=null)Arrays.fill(dbPassword,'\0');
   if(password!=null)Arrays.fill(password,'\0');
   if(confirmation!=null)Arrays.fill(confirmation,'\0');
  }
  if(exit!=0)System.exit(exit);
 }
}
