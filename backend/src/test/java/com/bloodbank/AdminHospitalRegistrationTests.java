package com.bloodbank;

import com.bloodbank.dao.AdminHospitalRegistrationDao;
import com.bloodbank.server.BloodBankServer;
import com.bloodbank.util.*;
import oracle.jdbc.OracleDriver;
import oracle.jdbc.OracleDriver.Script;
import oracle.jdbc.OracleDriver.Step;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** Synthetic JDBC/HTTP cases only: no live Oracle driver or records. */
public final class AdminHospitalRegistrationTests {
 private static int checks;
 private static final long ID=700001L,HOSPITAL=800001L;
 private static final String BINDING="synthetic-session-one";
 private static final AdminHospitalRegistrationDao DAO=new AdminHospitalRegistrationDao();
 private static final String PASSWORD="synthetic-"+UUID.randomUUID(),HASH=Passwords.hash(PASSWORD);
 private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private static final String PATH="/admin/hospital-registration-requests";
 private static String base;
 private record Session(String cookie,String csrf) {}
 interface Action {void run() throws Exception;}
 private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
 private static Map<String,Object> app() {
  return Data.map("registration_request_id",ID,"hospital_name","Synthetic O'Neil Hospital","address","1 Synthetic Road",
   "district","Synthetic District","pincode","600001","latitude",null,"longitude",null,"official_phone","+91 90000 00001",
   "official_email","office@example.test","registration_authority","TEST_AUTHORITY","jurisdiction_code","TEST_ONLY",
   "registration_number","AB123","registration_key","AB123","representative_name","Synthetic Representative",
   "representative_title","Coordinator","representative_email","rep@example.test","representative_phone","9000000002",
   "status","PENDING","submitted_at","2026-10-26T12:00:00.000000","reviewed_at",null,"reviewed_by",null,
   "verification_notes",null,"rejection_reason",null,"approved_hospital_id",null);
 }
 private static Map<String,Object> approveBody(String token) {
  return Data.map("review_token",token,"confirmation","APPROVE "+ID);
 }
 private static Map<String,Object> rejectBody(String token) {
  return Data.map("review_token",token,"confirmation","REJECT "+ID,"rejection_reason","Fictional identity not established");
 }
 private static Script script() {Script s=new Script();OracleDriver.scripts.add(s);return s;}
 private static Step authorize(Script s,boolean decision) {return authorize(s,decision,"ADMIN","ACTIVE",7);}
 private static Step authorize(Script s,boolean decision,String role,String status,long actor) {
  return s.query("SELECT user_role,account_status FROM SYSTEM.users WHERE user_id=?"+(decision?" FOR UPDATE WAIT 5":""),
    Data.map("user_role",role,"account_status",status)).bind(1,actor);
 }
 private static Step application(Script s,boolean lock,Map<String,Object> row) {
  return s.query("FROM SYSTEM.hospital_registration_request WHERE registration_request_id=?"+(lock?" FOR UPDATE WAIT 5":""),row).bind(1,ID);
 }
 private static String token() throws Exception {
  Script s=script();authorize(s,false);application(s,false,app());
  Map<String,Object> result=DAO.detail(ID,7,BINDING);s.done(0,1);
  check(result.get("application").equals(app()),"Detail returns the exact necessary application fields");
  String token=(String)result.get("review_token");check(token.matches("[0-9a-f]{64}"),"Opaque signed review token");return token;
 }
 private static Step globalLock(Script s,int result) {
  return s.query("BEGIN ? := SYS.DBMS_LOCK.REQUEST(id => 731908231, lockmode => 6, timeout => 5, release_on_commit => TRUE); END;")
    .output(1,Types.INTEGER,result);
 }
 private static Step applicationsCheck(Script s,boolean duplicate) {
  String sql="registration_request_id<>? AND status IN ('PENDING','APPROVED') AND ((registration_authority=? AND jurisdiction_code=? AND registration_key=?) OR UPPER(TRIM(hospital_name))=? OR (UPPER(TRIM(address))=? AND pincode=?) OR TRANSLATE(official_phone,'x +()-','x')=?)";
  Step step=duplicate?s.query(sql,Data.map("id",ID+1)):s.query(sql);
  return step.bind(1,ID).bind(2,"TEST_AUTHORITY").bind(3,"TEST_ONLY").bind(4,"AB123")
   .bind(5,"SYNTHETIC O'NEIL HOSPITAL").bind(6,"1 SYNTHETIC ROAD").bind(7,"600001").bind(8,"919000000001");
 }
 private static Step hospitalsCheck(Script s,boolean duplicate) {
  String sql="SELECT hospital_id FROM SYSTEM.hospital WHERE UPPER(TRIM(name))=? OR (UPPER(TRIM(address))=? AND pincode=?) OR TRANSLATE(phone,'x +()-','x')=?";
  Step step=duplicate?s.query(sql,Data.map("id",HOSPITAL)):s.query(sql);
  return step.bind(1,"SYNTHETIC O'NEIL HOSPITAL").bind(2,"1 SYNTHETIC ROAD").bind(3,"600001").bind(4,"919000000001");
 }
 private static Step insert(Script s) {
  return s.update("INSERT INTO SYSTEM.hospital(hospital_id,name,address,district,pincode,latitude,longitude,phone,is_active) VALUES(?,?,?,?,?,?,?,?,'Y')")
   .bind(1,HOSPITAL).bind(2,"Synthetic O'Neil Hospital").bind(3,"1 Synthetic Road").bind(4,"Synthetic District")
   .bind(5,"600001").bind(6,null).bind(7,null).bind(8,"+91 90000 00001");
 }
 private static Step decision(Script s,boolean approve) {
  if(approve)return s.update("UPDATE SYSTEM.hospital_registration_request SET status='APPROVED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,approved_hospital_id=? WHERE registration_request_id=? AND status='PENDING' AND approved_hospital_id IS NULL")
   .bind(1,"WEB_ADMIN:7").bind(3,HOSPITAL).bind(4,ID);
  return s.update("UPDATE SYSTEM.hospital_registration_request SET status='REJECTED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,rejection_reason=? WHERE registration_request_id=? AND status='PENDING' AND approved_hospital_id IS NULL")
   .bind(1,"WEB_ADMIN:7").bind(2,"Local academic demonstration rejection; official government verification not performed.").bind(3,"Fictional identity not established").bind(4,ID);
 }
 private static Script throughDuplicates() {
  Script s=script();authorize(s,true);application(s,true,app());globalLock(s,0);applicationsCheck(s,false);hospitalsCheck(s,false);return s;
 }
 private static Script throughInsert() {
  Script s=throughDuplicates();s.query("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual",Data.map("id",HOSPITAL));insert(s);return s;
 }
 private static void apiFailure(Action action,int status,String code) throws Exception {
  try {action.run();throw new AssertionError("Accepted forbidden operation: "+code);}
  catch(ApiException e) {check(e.status==status&&e.code.equals(code),"Safe "+status+" / "+code);}
 }
 private static void sqlFailure(Action action,int code) throws Exception {
  try {action.run();throw new AssertionError("SQL failure swallowed");}
  catch(SQLException e) {check(e.getErrorCode()==code,"SQL failure reaches existing safe HTTP error handler");}
 }
 private static void daoTests() throws Exception {
  String token=token();
  Script s=script();authorize(s,false);s.query("FETCH FIRST ? ROWS ONLY").bind(1,"PENDING").bind(2,0L).bind(3,21);
  Map<String,Object> page=DAO.list(7,Map.of());s.done(0,1);
  check(page.get("applications").equals(List.of())&&page.get("next_after_id")==null,"Empty default pending page");
  s=script();authorize(s,false);Map<String,Object> two=app();two.put("registration_request_id",ID+1);Map<String,Object> three=app();three.put("registration_request_id",ID+2);
  s.query("FETCH FIRST ? ROWS ONLY",app(),two,three).bind(1,"PENDING").bind(2,0L).bind(3,3);
  page=DAO.list(7,Map.of("limit","2"));s.done(0,1);
  @SuppressWarnings("unchecked") List<Map<String,Object>> summaries=(List<Map<String,Object>>)page.get("applications");
  check(summaries.size()==2&&page.get("next_after_id").equals(ID+1),"Bounded keyset pagination with lookahead");
  check(summaries.get(0).keySet().equals(Set.of("registration_request_id","hospital_name","district","status","submitted_at","approved_hospital_id")),"List excludes contact, evidence, representative and token data");
  for(String status:List.of("APPROVED","REJECTED")) {
   s=script();authorize(s,false);s.query("FETCH FIRST ? ROWS ONLY").bind(1,status).bind(2,ID+1).bind(3,101);
   check(DAO.list(7,Map.of("status",status,"after_id",Long.toString(ID+1),"limit","100")).get("status").equals(status),"Approved/rejected filtering");s.done(0,1);
  }
  for(Map<String,String> query:List.of(Map.of("status","ALL"),Map.of("limit","101"),Map.of("limit","0"),Map.of("limit","1.5"),Map.of("after_id","-1"),Map.of("after_id","1 OR 1=1"),Map.of("unknown","value")))
   apiFailure(()->DAO.list(7,query),400,"INVALID_FIELD");

  s=throughInsert();Step approved=decision(s,true);
  check(DAO.approve(ID,7,BINDING,approveBody(token)).equals(Data.map("registration_request_id",ID,"status","APPROVED","approved_hospital_id",HOSPITAL)),"Atomic approval response");s.done(1,0);
  String notes=(String)approved.actual.get(2);
  check(notes.equals("Local academic demonstration approval; official government verification not performed."),"Exact server-owned demo disclaimer persisted without evidence requirements");
  s=script();authorize(s,true);application(s,true,app());decision(s,false);
  check(DAO.reject(ID,7,BINDING,rejectBody(token)).equals(Data.map("registration_request_id",ID,"status","REJECTED")),"Rejection creates no hospital or sequence request");s.done(1,0);

  for(String status:List.of("APPROVED","REJECTED")) {
   Map<String,Object> terminal=app();terminal.put("status",status);if(status.equals("APPROVED"))terminal.put("approved_hospital_id",HOSPITAL);
   s=script();authorize(s,true);application(s,true,terminal);apiFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),409,"ALREADY_REVIEWED");s.done(0,1);
   s=script();authorize(s,true);application(s,true,terminal);apiFailure(()->DAO.reject(ID,7,BINDING,rejectBody(token)),409,"ALREADY_REVIEWED");s.done(0,1);
  }
  for(boolean approve:List.of(true,false)) {
   Map<String,Object> changed=app();changed.put("address","Changed after review");
   s=script();authorize(s,true);application(s,true,changed);
   apiFailure(()->{if(approve)DAO.approve(ID,7,BINDING,approveBody(token));else DAO.reject(ID,7,BINDING,rejectBody(token));},409,"REVIEW_STALE");s.done(0,1);
  }
  s=script();authorize(s,true);application(s,true,app());
  apiFailure(()->DAO.approve(ID,7,"different-session",approveBody(token)),409,"REVIEW_STALE");s.done(0,1);
  s=script();authorize(s,true,"ADMIN","ACTIVE",8);application(s,true,app());
  apiFailure(()->DAO.approve(ID,8,BINDING,approveBody(token)),409,"REVIEW_STALE");s.done(0,1);
  s=script();authorize(s,true);application(s,true,app());
  apiFailure(()->new AdminHospitalRegistrationDao().approve(ID,7,BINDING,approveBody(token)),409,"REVIEW_STALE");s.done(0,1);
  Map<String,Object> forgedToken=approveBody("0".repeat(64));s=script();authorize(s,true);application(s,true,app());
  apiFailure(()->DAO.approve(ID,7,BINDING,forgedToken),409,"REVIEW_STALE");s.done(0,1);
  s=script();authorize(s,true);application(s,true,app());globalLock(s,0);applicationsCheck(s,true);
  apiFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),409,"APPLICATION_CONFLICT");s.done(0,1);
  s=script();authorize(s,true);application(s,true,app());globalLock(s,0);applicationsCheck(s,false);hospitalsCheck(s,true);
  apiFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),409,"HOSPITAL_CONFLICT");s.done(0,1);
  for(int result:List.of(1,2,3,4,5)) {
   s=script();authorize(s,true);application(s,true,app());globalLock(s,result);
   apiFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),409,"REVIEW_BUSY");s.done(0,1);
  }
  for(int code:List.of(54,60,30006)) {
   s=script();authorize(s,true);application(s,true,app()).failure=new SQLException("private lock info","72000",code);
   apiFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),409,"REVIEW_BUSY");s.done(0,1);
  }
  s=throughDuplicates();s.query("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual").failure=new SQLException("sequence unavailable","42000",2289);
  sqlFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),2289);s.done(0,1);
  s=throughDuplicates();s.query("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual",Data.map("id",HOSPITAL));insert(s).failure=new SQLException("private unique constraint","23000",1);
  apiFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),409,"REGISTRATION_CONFLICT");s.done(0,1);
  s=throughInsert();decision(s,true).failure=new SQLException("update failed","42000",942);
  sqlFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),942);s.done(0,1);
  s=throughInsert();decision(s,true).updatedRows=0;
  apiFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),409,"ALREADY_REVIEWED");s.done(0,1);
  s=throughInsert();decision(s,true);s.commitFailure=new SQLException("commit uncertain","08006",3113);
  sqlFailure(()->DAO.approve(ID,7,BINDING,approveBody(token)),3113);s.done(0,1);
  s=script();authorize(s,true);application(s,true,app());decision(s,false).failure=new SQLException("rejection failed","42000",942);
  sqlFailure(()->DAO.reject(ID,7,BINDING,rejectBody(token)),942);s.done(0,1);
  s=script();authorize(s,true);application(s,true,app());decision(s,false).updatedRows=0;
  apiFailure(()->DAO.reject(ID,7,BINDING,rejectBody(token)),409,"ALREADY_REVIEWED");s.done(0,1);
  s=script();authorize(s,false);s.query("FROM SYSTEM.hospital_registration_request WHERE registration_request_id=?");
  apiFailure(()->DAO.detail(ID,7,BINDING),404,"APPLICATION_NOT_FOUND");s.done(0,1);
  for(String status:List.of("DISABLED","LOCKED","PENDING_VERIFICATION")) {
   s=script();authorize(s,true,"ADMIN",status,7);apiFailure(()->DAO.reject(ID,7,BINDING,rejectBody(token)),401,"SESSION_INVALID");s.done(0,1);
  }
  for(String role:List.of("CITIZEN","HOSPITAL_STAFF")) {
   s=script();authorize(s,false,role,"ACTIVE",7);apiFailure(()->DAO.list(7,Map.of()),403,"ADMIN_REQUIRED");s.done(0,1);
  }
  for(String field:List.of("reviewed_by","status","approved_hospital_id","user_id","role","hospital_id")) {
   Map<String,Object> forged=approveBody(token);forged.put(field,null);
   apiFailure(()->DAO.approve(ID,7,BINDING,forged),400,"INVALID_FIELD");
  }
  for(String field:approveBody(token).keySet()) {
   Map<String,Object> missing=approveBody(token);missing.remove(field);apiFailure(()->DAO.approve(ID,7,BINDING,missing),400,"INVALID_FIELD");
  }
  for(String field:List.of("registration_identity_checked","verified_registration_number","registration_evidence","verification_notes","review_context")) {
   Map<String,Object> extra=approveBody(token);extra.put(field,"untrusted");apiFailure(()->DAO.approve(ID,7,BINDING,extra),400,"INVALID_FIELD");
  }
  for(Map.Entry<String,Object> entry:Data.map("confirmation","APPROVE 999999","review_token","bad").entrySet()) {
   Map<String,Object> bad=approveBody(token);bad.put(entry.getKey(),entry.getValue());apiFailure(()->DAO.approve(ID,7,BINDING,bad),400,"INVALID_FIELD");
  }
  for(String field:List.of("rejection_reason","review_token","confirmation")) {
   Map<String,Object> missing=rejectBody(token);missing.put(field," ");apiFailure(()->DAO.reject(ID,7,BINDING,missing),400,"INVALID_FIELD");
  }
  Map<String,Object> longReason=rejectBody(token);longReason.put("rejection_reason","a".repeat(1001));apiFailure(()->DAO.reject(ID,7,BINDING,longReason),400,"INVALID_FIELD");
  check(OracleDriver.scripts.isEmpty(),"DAO validation never issues unexpected or unrelated SQL");
 }

 private static HttpResponse<String> send(String method,String path,Session session,String csrf,String origin,String body) throws Exception {
  HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
  if(session!=null)b.header("Cookie",session.cookie);if(csrf!=null)b.header("X-CSRF-Token",csrf);if(origin!=null)b.header("Origin",origin);
  if(body!=null)b.header("Content-Type","application/json");
  return CLIENT.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 private static Session session(HttpResponse<String> r) {return new Session(r.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0],(String)JsonUtil.parseJsonObject(r.body()).get("csrf_token"));}
 private static Session guest() throws Exception {return session(send("GET","/auth/me",null,null,null,null));}
 private static Map<String,Object> user(String role,String status) {
  return Data.map("user_id",7L,"full_name","Synthetic User","email","user@example.test","phone",null,"user_role",role,"account_status",status,
   "hospital_id",role.equals("HOSPITAL_STAFF")?90001L:null,"hospital_name",role.equals("HOSPITAL_STAFF")?"Synthetic Assignment":null);
 }
 private static Session login(String role) throws Exception {
  Session guest=guest();Script s=script();s.query("FROM users WHERE email=? FOR UPDATE",Data.map("id",7L,"hash",HASH,"status","ACTIVE","role",role));
  s.query("WHERE u.user_id=?",user(role,"ACTIVE"));s.update("last_login_at=SYSTIMESTAMP");
  HttpResponse<String> r=send("POST","/auth/login",guest,guest.csrf,null,JsonUtil.toJson(Data.map("email","user@example.test","password",PASSWORD,"role",role)));
  s.done(1,0);check(r.statusCode()==200,"Authenticated synthetic "+role);return session(r);
 }
 private static Script httpGuard() {Script s=script();s.query("WHERE u.user_id=?",user("ADMIN","ACTIVE")).bind(1,7L);return s;}
 private static void httpTests(boolean enabled) throws Exception {
  Session guest=guest();
  for(String suffix:List.of("","/"+ID,"/"+ID+"/approve","/"+ID+"/reject")) {
   String method=suffix.endsWith("approve")||suffix.endsWith("reject")?"POST":"GET";
   HttpResponse<String> r=send(method,PATH+suffix,guest,guest.csrf,null,method.equals("POST")?"{}":null);
   check(r.statusCode()==(enabled?401:403)&&!r.body().contains("Synthetic O'Neil"),"All review operations deny guests/default-disabled feature");
  }
  if(!enabled) {check(OracleDriver.scripts.isEmpty(),"Disabled feature issues no review SQL");return;}
  for(String role:List.of("CITIZEN","HOSPITAL_STAFF")) {
   Session ordinary=login(role);
   for(String suffix:List.of("","/"+ID,"/"+ID+"/approve","/"+ID+"/reject")) {
    boolean post=suffix.endsWith("approve")||suffix.endsWith("reject");
    check(send(post?"POST":"GET",PATH+suffix,ordinary,ordinary.csrf,null,post?"{}":null).statusCode()==403,"Every operation enforces administrator role");
   }
  }
  Session admin=login("ADMIN");
  check(send("GET",PATH,admin,null,"https://untrusted.invalid",null).statusCode()==403,"CORS rejects external origin");
  check(send("GET",PATH,admin,null,"http://example.test",null).statusCode()==403,"Local policy rejects nonlocal origin even if global CORS permits it");
  check(send("POST","/hospital-registration-requests/"+ID+"/approve",admin,admin.csrf,null,"{}").statusCode()==404,"No public approval route");
  check(send("GET",PATH+"/"+ID+"/approve",admin,null,null,null).statusCode()==405,"Approval cannot be invoked by GET");
  Script guard=httpGuard();Script s=script();authorize(s,false);s.query("FETCH FIRST ? ROWS ONLY",app());
  HttpResponse<String> r=send("GET",PATH,admin,null,null,null);guard.done(0,0);s.done(0,1);
  check(r.statusCode()==200&&!r.body().contains("rep@example.test")&&!r.body().contains("review_token"),"Real DAO list path excludes detail-only data");
  guard=httpGuard();s=script();authorize(s,false);application(s,false,app());
  r=send("GET",PATH+"/"+ID,admin,null,null,null);guard.done(0,0);s.done(0,1);
  check(r.statusCode()==200&&r.body().contains("rep@example.test"),"Authorized detail path returns representative details");
  String token=(String)JsonUtil.parseJsonObject(r.body()).get("review_token");
  for(String csrf:Arrays.asList(null,"stale-csrf")) {
   guard=httpGuard();r=send("POST",PATH+"/"+ID+"/approve",admin,csrf,null,JsonUtil.toJson(approveBody(token)));guard.done(0,0);
   check(r.statusCode()==403&&r.body().contains("CSRF_INVALID"),"Missing/stale CSRF denied before decision DAO");
  }
  for(String field:List.of("review_token","confirmation")) {
   Map<String,Object> missing=approveBody(token);missing.remove(field);guard=httpGuard();
   r=send("POST",PATH+"/"+ID+"/approve",admin,admin.csrf,null,JsonUtil.toJson(missing));guard.done(0,0);
   check(r.statusCode()==400,"HTTP requires review token and explicit confirmation");
  }
  Map<String,Object> forged=approveBody(token);forged.put("reviewed_by","SYSTEM");guard=httpGuard();
  r=send("POST",PATH+"/"+ID+"/approve",admin,admin.csrf,null,JsonUtil.toJson(forged));guard.done(0,0);
  check(r.statusCode()==400,"Browser cannot choose reviewer identity");
  guard=httpGuard();s=throughInsert();decision(s,true);
  r=send("POST",PATH+"/"+ID+"/approve",admin,admin.csrf,null,JsonUtil.toJson(approveBody(token)));guard.done(0,0);s.done(1,0);
  check(r.statusCode()==200&&JsonUtil.parseJsonObject(r.body()).equals(JsonUtil.parseJsonObject(JsonUtil.toJson(Data.map("registration_request_id",ID,"status","APPROVED","approved_hospital_id",HOSPITAL)))),"HTTP atomic approval contract");
  Map<String,Object> terminal=app();terminal.put("status","APPROVED");terminal.put("approved_hospital_id",HOSPITAL);
  guard=httpGuard();s=script();authorize(s,true);application(s,true,terminal);
  r=send("POST",PATH+"/"+ID+"/approve",admin,admin.csrf,null,JsonUtil.toJson(approveBody(token)));guard.done(0,0);s.done(0,1);
  check(r.statusCode()==409&&r.body().contains("ALREADY_REVIEWED"),"Repeated HTTP approval refused");
  // A fresh synthetic pending case, not a reset of any real application.
  guard=httpGuard();s=script();authorize(s,true);application(s,true,app());decision(s,false);
  r=send("POST",PATH+"/"+ID+"/reject",admin,admin.csrf,null,JsonUtil.toJson(rejectBody(token)));guard.done(0,0);s.done(1,0);
  check(r.statusCode()==200&&r.body().contains("REJECTED")&&!r.body().contains("approved_hospital_id"),"HTTP rejection contract without hospital creation");
  Session otherLogin=login("ADMIN");guard=httpGuard();s=script();authorize(s,true);application(s,true,app());
  r=send("POST",PATH+"/"+ID+"/reject",otherLogin,otherLogin.csrf,null,JsonUtil.toJson(rejectBody(token)));guard.done(0,0);s.done(0,1);
  check(r.statusCode()==409&&r.body().contains("REVIEW_STALE"),"Review token bound to original login session");
  guard=httpGuard();s=script();authorize(s,false,"CITIZEN","ACTIVE",7);
  r=send("GET",PATH,admin,null,null,null);guard.done(0,0);s.done(0,1);
  check(r.statusCode()==403,"DAO rechecks role after initial HTTP guard");
  guard=httpGuard();s=script();authorize(s,true);application(s,true,app()).failure=new SQLException("private rep@example.test constraint secret","42000",942);
  r=send("POST",PATH+"/"+ID+"/reject",admin,admin.csrf,null,JsonUtil.toJson(rejectBody(token)));guard.done(0,0);s.done(0,1);
  check(r.statusCode()==500&&!r.body().contains("private")&&!r.body().contains("constraint")&&!r.body().contains("@"),"SQL internals/applicant details redacted on errors");
  guard=httpGuard();r=send("GET",PATH,admin,null,null,null);guard.done(0,0);
  check(r.statusCode()==503,"Database unavailable never returns synthetic records");
  for(String query:List.of("?limit=101","?status=ALL","?limit=2&limit=3","?after_id=-1")) {
   guard=httpGuard();r=send("GET",PATH+query,admin,null,null,null);guard.done(0,0);check(r.statusCode()==400,"HTTP pagination/filter query validation");
  }
  for(String payload:List.of("{broken","{\"review_token\":1,\"review_token\":2}"," ".repeat(17000))) {
   guard=httpGuard();r=send("POST",PATH+"/"+ID+"/reject",admin,admin.csrf,null,payload);guard.done(0,0);
   check(r.statusCode()==(payload.length()>16384?413:400),"Existing strict JSON/body limit reused");
  }
  check(send("POST","/auth/logout",admin,admin.csrf,null,"{}").statusCode()==200,"Existing logout used");
  check(send("GET",PATH,admin,null,null,null).statusCode()==401,"Logged-out cookie cannot access records");
  check(OracleDriver.scripts.isEmpty(),"HTTP route SQL expectations consumed; no unrelated writes");
 }
 public static void main(String[] args) {
  try {
   if(!OracleDriver.URL.equals(System.getenv("ORACLE_URL")))throw new IllegalStateException("TEST-ONLY URL required");
   boolean enabled=args.length==1&&args[0].equals("enabled");
   if(enabled)daoTests();
   else {
    apiFailure(()->DAO.approve(ID,7,BINDING,approveBody("a".repeat(64))),403,"ADMIN_UNAVAILABLE");
    apiFailure(()->DAO.reject(ID,7,BINDING,rejectBody("a".repeat(64))),403,"ADMIN_UNAVAILABLE");
    check(OracleDriver.scripts.isEmpty(),"Disabled demo refuses direct DAO decisions before SQL");
   }
   BloodBankServer.main(new String[0]);base="http://127.0.0.1:"+System.getenv("PORT")+"/api";httpTests(enabled);
   System.out.println("PASS: "+checks+" administrator review checks (feature "+(enabled?"enabled":"default-disabled")+"); non-writing JDBC double only.");System.exit(0);
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
}
