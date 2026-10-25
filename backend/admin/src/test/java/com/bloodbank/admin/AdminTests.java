package com.bloodbank.admin;

import java.sql.*;
import java.util.*;
import oracle.jdbc.OracleDriver;
import oracle.jdbc.OracleDriver.Script;
import oracle.jdbc.OracleDriver.Step;

public final class AdminTests {
 private static int checks;
 private static final String REVIEWER="BB_REVIEW_TEST";
 private static final AdminStore STORE=new AdminStore(()->DriverManager.getConnection(OracleDriver.URL));
 private static final AdminStore.Review REVIEW=new AdminStore.Review("test_authority","in_tn","ab-123/4",
   "rep@example.test","Registry independently checked; reference R1","Authorization letter verified; reference A1","Legacy directory checked; reference L1");
 private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
 private static Map<String,Object> data(Object... entries) {
  Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)map.put((String)entries[i],entries[i+1]);return map;
 }
 private static Map<String,String> application() {
  Map<String,String> row=new LinkedHashMap<>();for(String col:AdminStore.COLUMNS)row.put(col,null);
  row.put("registration_request_id","100000");row.put("hospital_name","Test O'Neil Hospital");row.put("address","1 Test Road");
  row.put("district","Test District");row.put("pincode","600001");row.put("official_phone","+91 90000 00001");
  row.put("official_email","office@example.test");row.put("registration_authority","TEST_AUTHORITY");
  row.put("jurisdiction_code","IN_TN");row.put("registration_number","AB1234");row.put("registration_key","AB1234");
  row.put("representative_name","Representative");row.put("representative_title","Coordinator");
  row.put("representative_email","rep@example.test");row.put("representative_phone","9000000002");
  row.put("status","PENDING");row.put("submitted_at","2026-10-25 12:00:00");return row;
 }
 private static Map<String,Object> row(Map<String,String> application) {return new LinkedHashMap<>(application);}
 private static Map<String,Object> identity() {
  return data("reviewer",REVIEWER,"identity",REVIEWER,"proxy_user",null,"is_dba","FALSE","container_name","XEPDB1","schema_name",REVIEWER);
 }
 private static Script script() {Script s=new Script();OracleDriver.scripts.add(s);return s;}
 private static void auth(Script s) {
  s.query("SYS_CONTEXT('USERENV','SESSION_USER')",identity());
  s.query("SELECT role FROM session_roles",data("role","BB_HOSPITAL_REVIEWER"));
  s.query("SELECT privilege FROM session_privs",data("privilege","CREATE SESSION"));
 }
 private static Step locked(Script s,Map<String,String> application) {
  return s.query(AdminStore.SELECT+" FOR UPDATE WAIT 5",row(application)).bind(1,100000L);
 }
 private static Step approvalLock(Script s,int result) {return s.query(AdminStore.LOCK).output(1,Types.INTEGER,result);}
 private static void noIdentityDuplicate(Script s) {
  s.query("registration_request_id<>? AND status IN ('PENDING','APPROVED')").bind(1,100000L)
   .bind(2,"TEST_AUTHORITY").bind(3,"IN_TN").bind(4,"AB1234");
 }
 private static Step hospitalCheck(Script s,boolean duplicate) {
  Step step=duplicate?s.query("FROM SYSTEM.hospital WHERE UPPER(TRIM(name))",data("hospital_id",90001L))
    :s.query("FROM SYSTEM.hospital WHERE UPPER(TRIM(name))");
  return step.bind(1,"TEST O'NEIL HOSPITAL").bind(2,"1 TEST ROAD").bind(3,"600001").bind(4,"919000000001");
 }
 private static Step hospitalInsert(Script s) {
  return s.update("INSERT INTO SYSTEM.hospital(hospital_id,name,address,district,pincode,latitude,longitude,phone,is_active) VALUES(?,?,?,?,?,?,?,?,'Y')")
    .bind(1,100001L).bind(2,"Test O'Neil Hospital").bind(3,"1 Test Road").bind(4,"Test District")
    .bind(5,"600001").bind(6,null).bind(7,null).bind(8,"+91 90000 00001");
 }
 private static Step decision(Script s,boolean approve) {
  if(approve)return s.update("UPDATE SYSTEM.hospital_registration_request SET status='APPROVED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,approved_hospital_id=? WHERE registration_request_id=? AND status='PENDING'")
    .bind(1,REVIEWER).bind(2,REVIEW.notes()).bind(3,100001L).bind(4,100000L);
  return s.update("UPDATE SYSTEM.hospital_registration_request SET status='REJECTED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,rejection_reason=? WHERE registration_request_id=? AND status='PENDING'")
    .bind(1,REVIEWER).bind(2,"Evidence reviewed").bind(3,"Authority not established").bind(4,100000L);
 }
 private static Script throughHospitalInsert() {
  Script s=script();auth(s);locked(s,application());approvalLock(s,0);noIdentityDuplicate(s);hospitalCheck(s,false);
  s.query("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual",data("id",100001L));hospitalInsert(s);return s;
 }
 interface Action {void run() throws Exception;}
 private static void refused(Action action,String message) throws Exception {
  try {action.run();throw new AssertionError("Accepted: "+message);}
  catch(AdminStore.Refused e) {check(true,message);}
 }
 private static void sqlFailure(Action action,int code) throws Exception {
  try {action.run();throw new AssertionError("Swallowed SQL failure");}
  catch(SQLException e) {check(e.getErrorCode()==code,"SQL failure propagated and not reported as success");}
 }
 private static void authorizationTests() throws Exception {
  for(Map.Entry<String,Object> mutation:data("reviewer","SYSTEM","identity","OTHER","proxy_user","PROXY",
    "is_dba","TRUE","container_name","CDB$ROOT","schema_name","SYSTEM").entrySet()) {
   Script s=script();Map<String,Object> identity=identity();identity.put(mutation.getKey(),mutation.getValue());
   s.query("SYS_CONTEXT('USERENV','SESSION_USER')",identity);
   refused(()->STORE.view(100000),"Authorization rejects "+mutation.getKey());s.done(0,1);
  }
  Script s=script();s.query("SYS_CONTEXT('USERENV','SESSION_USER')",identity());s.query("SELECT role FROM session_roles");
  refused(()->STORE.view(100000),"Missing reviewer role");s.done(0,1);
  s=script();s.query("SYS_CONTEXT('USERENV','SESSION_USER')",identity());
  s.query("SELECT role FROM session_roles",data("role","DBA"));
  refused(()->STORE.view(100000),"Elevated role refused");s.done(0,1);
  s=script();s.query("SYS_CONTEXT('USERENV','SESSION_USER')",identity());
  s.query("SELECT role FROM session_roles",data("role","BB_HOSPITAL_REVIEWER"));
  s.query("SELECT privilege FROM session_privs",data("privilege","CREATE SESSION"),data("privilege","UPDATE ANY TABLE"));
  refused(()->STORE.view(100000),"Broad system privilege refused");s.done(0,1);
  for(boolean approve:List.of(true,false)) {
   s=script();Map<String,Object> publicIdentity=identity();publicIdentity.put("reviewer","SYSTEM");publicIdentity.put("identity","SYSTEM");
   s.query("SYS_CONTEXT('USERENV','SESSION_USER')",publicIdentity);
   if(approve)refused(()->STORE.approve(100000,application(),REVIEW),"Public database account cannot approve");
   else refused(()->STORE.reject(100000,application(),"notes","reason"),"Public database account cannot reject");
   s.done(0,1);
  }
 }
 private static void tests() throws Exception {
  authorizationTests();
  Script s=script();auth(s);s.query(AdminStore.SELECT,row(application())).bind(1,100000L);
  check(STORE.view(100000).equals(application()),"View full application including review metadata");s.done(0,1);
  s=script();auth(s);s.query("WHERE status='PENDING' AND registration_request_id>? ORDER BY registration_request_id FETCH FIRST 100 ROWS ONLY",row(application())).bind(1,99999L);
  check(STORE.list(99999).size()==1,"Pending keyset-paginated listing");s.done(0,1);
  s=script();auth(s);s.query("WHERE status='PENDING' AND registration_request_id>? ORDER BY registration_request_id FETCH FIRST 100 ROWS ONLY").bind(1,0L);
  check(STORE.list(0).isEmpty(),"Empty listing stays empty");s.done(0,1);

  s=throughHospitalInsert();decision(s,true);
  check(STORE.approve(100000,application(),REVIEW)==100001,"Atomic approval returns hospital ID after commit");s.done(1,0);
  s=script();auth(s);locked(s,application());decision(s,false);
  STORE.reject(100000,application()," Evidence reviewed "," Authority not established ");s.done(1,0);check(true,"Rejection touches only application");

  for(String status:List.of("APPROVED","REJECTED")) {
   Map<String,String> terminal=application();terminal.put("status",status);
   s=script();auth(s);locked(s,terminal);
   refused(()->STORE.approve(100000,terminal,REVIEW),"Repeated approval after "+status);s.done(0,1);
   s=script();auth(s);locked(s,terminal);
   refused(()->STORE.reject(100000,terminal,"Evidence reviewed","Authority not established"),"Repeated rejection after "+status);s.done(0,1);
  }
  Map<String,String> changed=application();changed.put("official_email","changed@example.test");
  s=script();auth(s);locked(s,changed);
  refused(()->STORE.approve(100000,application(),REVIEW),"Stale interactive review snapshot");s.done(0,1);
  for(AdminStore.Review wrong:List.of(
    new AdminStore.Review("other","in_tn","ab1234","rep@example.test","r","a","l"),
    new AdminStore.Review("test_authority","other","ab1234","rep@example.test","r","a","l"),
    new AdminStore.Review("test_authority","in_tn","other","rep@example.test","r","a","l"),
    new AdminStore.Review("test_authority","in_tn","ab1234","wrong@example.test","r","a","l"))) {
   s=script();auth(s);locked(s,application());
   refused(()->STORE.approve(100000,application(),wrong),"Independently verified identity/representative mismatch");s.done(0,1);
  }
  s=script();auth(s);locked(s,application());approvalLock(s,0);
  s.query("registration_request_id<>? AND status IN ('PENDING','APPROVED')",data("id",100002L));
  refused(()->STORE.approve(100000,application(),REVIEW),"Duplicate official identity refuses before insert");s.done(0,1);
  s=script();auth(s);locked(s,application());approvalLock(s,0);noIdentityDuplicate(s);hospitalCheck(s,true);
  refused(()->STORE.approve(100000,application(),REVIEW),"Potential legacy hospital duplicate refuses before sequence");s.done(0,1);
  for(int lockResult:List.of(1,2,3,4,5)) {
   s=script();auth(s);locked(s,application());approvalLock(s,lockResult);
   refused(()->STORE.approve(100000,application(),REVIEW),"Global approval lock failure "+lockResult);s.done(0,1);
  }
  s=script();auth(s);locked(s,application()).failure=new SQLException("Row lock timeout","72000",30006);
  sqlFailure(()->STORE.approve(100000,application(),REVIEW),30006);s.done(0,1);
  s=script();auth(s);s.query(AdminStore.SELECT+" FOR UPDATE WAIT 5");
  refused(()->STORE.approve(100000,application(),REVIEW),"Missing application");s.done(0,1);

  s=throughHospitalInsert();decision(s,true).failure=new SQLException("Decision failed","23000",1);
  sqlFailure(()->STORE.approve(100000,application(),REVIEW),1);s.done(0,1);
  s=throughHospitalInsert();decision(s,true).updatedRows=0;
  refused(()->STORE.approve(100000,application(),REVIEW),"Conditional decision count zero rolls back hospital insert");s.done(0,1);
  s=throughHospitalInsert();decision(s,true);s.commitFailure=new SQLException("Commit unavailable","08006",3113);
  sqlFailure(()->STORE.approve(100000,application(),REVIEW),3113);s.done(0,1);
  s=script();auth(s);locked(s,application());approvalLock(s,0);noIdentityDuplicate(s);hospitalCheck(s,false);
  s.query("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual",data("id",100001L));
  hospitalInsert(s).failure=new SQLException("Hospital insert failed","23000",1);
  sqlFailure(()->STORE.approve(100000,application(),REVIEW),1);s.done(0,1);
  s=script();auth(s);locked(s,application());decision(s,false).failure=new SQLException("Rejection failed","42000",942);
  sqlFailure(()->STORE.reject(100000,application(),"Evidence reviewed","Authority not established"),942);s.done(0,1);
  s=script();auth(s);locked(s,application());decision(s,false).updatedRows=0;
  refused(()->STORE.reject(100000,application(),"Evidence reviewed","Authority not established"),"Conditional rejection refuses zero updated rows");s.done(0,1);
  s=script();auth(s);locked(s,changed);
  refused(()->STORE.reject(100000,application(),"Evidence reviewed","Authority not established"),"Stale snapshot rejection refused");s.done(0,1);
  s=script();auth(s);locked(s,application());approvalLock(s,0);noIdentityDuplicate(s);hospitalCheck(s,false);
  s.query("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual").failure=new SQLException("Sequence failed","42000",2289);
  sqlFailure(()->STORE.approve(100000,application(),REVIEW),2289);s.done(0,1);

  refused(()->STORE.approve(100000,application(),new AdminStore.Review("a","b","c","d"," ","a","l")),"Registry evidence required");
  refused(()->STORE.approve(100000,application(),new AdminStore.Review("a","b","c","d","r"," ","l")),"Representative evidence required");
  refused(()->STORE.approve(100000,application(),new AdminStore.Review("a","b","c","d","r","a"," ")),"Legacy duplicate review evidence required");
  refused(()->STORE.reject(100000,application(),"notes"," "),"Rejection reason required");
  refused(()->STORE.reject(100000,application()," ","reason"),"Verification notes required");
  refused(()->AdminStore.text("é".repeat(1001),2000),"Byte bounds enforced");
  refused(()->AdminStore.text("bad\ntext",100),"Control characters refused");
  for(String id:List.of("0","-1","1 OR 1=1","10000000000"))refused(()->AdminStore.id(id),"Invalid ID rejected");
  check(OracleDriver.scripts.isEmpty(),"All exact SQL expectations consumed; no unrelated SQL");
 }
 public static void main(String[] args) {
  try {
   if(!OracleDriver.URL.equals(System.getenv("ORACLE_URL")))throw new IllegalStateException("TEST-ONLY URL required");
   Class.forName("oracle.jdbc.OracleDriver");tests();
   System.out.println("PASS: "+checks+" administrator checks; isolated JDBC double only, no live database writes.");
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
}
