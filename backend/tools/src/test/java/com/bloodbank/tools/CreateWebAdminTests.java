package com.bloodbank.tools;

import com.bloodbank.util.Passwords;
import oracle.jdbc.OracleDriver;
import oracle.jdbc.OracleDriver.Script;
import oracle.jdbc.OracleDriver.Step;
import java.sql.*;
import java.util.*;

public final class CreateWebAdminTests {
 private static int checks;
 private static final String PASSWORD="synthetic-"+UUID.randomUUID();
 private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
 private static Map<String,Object> row(Object... values) {
  Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row;
 }
 private static Map<String,Object> identity() {return row("session_user","SYSTEM","identity","SYSTEM","container_name","XEPDB1","is_dba","FALSE","proxy_user",null);}
 private static Script script() {Script s=new Script();OracleDriver.scripts.add(s);return s;}
 private static Step identity(Script s) {return s.query("SYS_CONTEXT('USERENV','SESSION_USER')",identity());}
 private static Step existing(Script s) {return s.query("SELECT user_id FROM SYSTEM.users WHERE email=?").bind(1,"new@example.test");}
 private static Step insert(Script s) {
  return s.update("INSERT INTO SYSTEM.users(user_id,full_name,email,password_hash,user_role,account_status) VALUES(?,?,?,?,'ADMIN','ACTIVE')")
    .bind(1,100100L).bind(2,"New Administrator").bind(3,"new@example.test");
 }
 private static long create(String name,String email,char[] password,char[] confirmation) throws Exception {
  try(Connection c=DriverManager.getConnection(OracleDriver.URL)) {return CreateWebAdmin.create(c,name,email,password,confirmation);}
 }
 private static long create() throws Exception {return create(" New Administrator "," NEW@EXAMPLE.TEST ",PASSWORD.toCharArray(),PASSWORD.toCharArray());}
 interface Action {void run() throws Exception;}
 private static void refuses(Action a,String label) throws Exception {
  try {a.run();throw new AssertionError("Accepted "+label);}catch(IllegalArgumentException e) {check(true,label);}
 }
 private static void sqlFailure(Action a,int code) throws Exception {
  try {a.run();throw new AssertionError("Swallowed failure");}catch(SQLException e) {check(e.getErrorCode()==code,"SQL failure propagated");}
 }
 private static void tests() throws Exception {
  CreateWebAdmin.localConfiguration(true,"jdbc:oracle:thin:@//localhost:1521/XEPDB1","SYSTEM");check(true,"Intentional local setup configuration accepted");
  for(String url:List.of("jdbc:oracle:thin:@//example.test:1521/XEPDB1","jdbc:oracle:thin:@//localhost:1521/XE","jdbc:oracle:thin:@//localhost:99999/XEPDB1","jdbc:oracle:thin:@TEST-ONLY"))
   refuses(()->CreateWebAdmin.localConfiguration(true,url,"SYSTEM"),"Remote/wrong-service/invalid URL refused");
  refuses(()->CreateWebAdmin.localConfiguration(false,"jdbc:oracle:thin:@//localhost:1521/XEPDB1","SYSTEM"),"Explicit flag required");
  refuses(()->CreateWebAdmin.localConfiguration(true,"jdbc:oracle:thin:@//localhost:1521/XEPDB1","OTHER"),"Unexpected setup database identity refused");

  Script s=script();identity(s);existing(s);s.query("SELECT SYSTEM.seq_users.NEXTVAL FROM dual",row("id",100100L));Step inserted=insert(s);
  char[] password=PASSWORD.toCharArray(),confirmation=PASSWORD.toCharArray();
  check(create(" New Administrator "," NEW@EXAMPLE.TEST ",password,confirmation)==100100,"New application administrator ID returned after commit");s.done(1,0);
  String hash=(String)inserted.actual.get(4);
  check(Passwords.verify(PASSWORD,hash)&&!hash.equals(PASSWORD),"Stored password uses existing salted PBKDF2 format");
  check(inserted.actual.keySet().equals(Set.of(1,2,3,4)),"Only expected values bound; role/status are SQL constants");
  check(new String(password).chars().allMatch(c->c==0)&&new String(confirmation).chars().allMatch(c->c==0),"Password buffers wiped");

  for(Map.Entry<String,Object> entry:row("session_user","SYS","identity","OTHER","container_name","CDB$ROOT","is_dba","TRUE","proxy_user","PROXY").entrySet()) {
   s=script();Map<String,Object> wrong=identity();wrong.put(entry.getKey(),entry.getValue());s.query("SYS_CONTEXT('USERENV','SESSION_USER')",wrong);
   refuses(CreateWebAdminTests::create,"Wrong/proxy/SYSDBA session refused before account insert");s.done(0,1);
  }
  s=script();identity(s);s.query("SELECT user_id FROM SYSTEM.users WHERE email=?",row("id",91000L)).bind(1,"new@example.test");
  refuses(CreateWebAdminTests::create,"Existing email never promoted or modified");s.done(0,1);

  s=script();identity(s);existing(s);s.query("SELECT SYSTEM.seq_users.NEXTVAL FROM dual",row("id",100100L));insert(s).failure=new SQLException("Duplicate","23000",1);
  sqlFailure(CreateWebAdminTests::create,1);s.done(0,1);
  s=script();identity(s);existing(s);s.query("SELECT SYSTEM.seq_users.NEXTVAL FROM dual").failure=new SQLException("Sequence error","42000",2289);
  sqlFailure(CreateWebAdminTests::create,2289);s.done(0,1);
  s=script();identity(s);existing(s);s.query("SELECT SYSTEM.seq_users.NEXTVAL FROM dual",row("id",100100L));insert(s).updatedRows=0;
  sqlFailure(CreateWebAdminTests::create,0);s.done(0,1);
  s=script();identity(s);existing(s);s.query("SELECT SYSTEM.seq_users.NEXTVAL FROM dual",row("id",100100L));insert(s);s.commitFailure=new SQLException("Commit uncertain","08006",3113);
  sqlFailure(CreateWebAdminTests::create,3113);s.done(0,1);

  for(String[] values:List.of(new String[]{" ","new@example.test",PASSWORD,PASSWORD},new String[]{"Name","bad-email",PASSWORD,PASSWORD},
    new String[]{"Name","new@example.test","short","short"},new String[]{"Name","new@example.test",PASSWORD,PASSWORD+"different"},
    new String[]{"Name","new@example.test"," ".repeat(12)," ".repeat(12)},new String[]{"é".repeat(51),"new@example.test",PASSWORD,PASSWORD})) {
   s=script();char[] first=values[2].toCharArray(),second=values[3].toCharArray();
   refuses(()->create(values[0],values[1],first,second),"Invalid identity/password refused before SQL");s.done(0,0);
   check(new String(first).chars().allMatch(c->c==0)&&new String(second).chars().allMatch(c->c==0),"Failure wipes password buffers");
  }
  check(OracleDriver.scripts.isEmpty(),"All setup SQL consumed; no hospital/staff/DDL operations");
 }
 public static void main(String[] args) {
  try {
   if(!OracleDriver.URL.equals(System.getenv("ORACLE_URL")))throw new IllegalStateException("TEST-ONLY URL required");
   Class.forName("oracle.jdbc.OracleDriver");tests();
   System.out.println("PASS: "+checks+" offline administrator setup checks; test-double transactions only.");
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
}
