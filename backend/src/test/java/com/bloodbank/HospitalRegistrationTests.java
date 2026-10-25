package com.bloodbank;

import com.bloodbank.dao.HospitalRegistrationDao;
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

/** New onboarding only. No live driver, old regression suites or real Oracle writes. */
public final class HospitalRegistrationTests {
 private static int checks;
 private static final HospitalRegistrationDao DAO=new HospitalRegistrationDao();
 private static final String INSERT="INSERT INTO hospital_registration_request "
   +"(registration_request_id,hospital_name,address,district,pincode,latitude,longitude,official_phone,official_email,"
   +"registration_authority,jurisdiction_code,registration_number,representative_name,representative_title,"
   +"representative_email,representative_phone,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')";
 private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private static String base;
 private static void check(boolean ok,String label) {checks++;if(!ok)throw new AssertionError(label);}
 private static Map<String,Object> application() {
  return Data.map("hospital_name"," Test O'Neil Hospital ","address"," 1 Test Road ","district"," Test District ",
    "pincode","600001","official_phone","+91 90000 00001","official_email"," OFFICE@EXAMPLE.TEST ",
    "registration_authority"," test_authority ","jurisdiction_code"," in_tn ","registration_number"," ab-12/3 ",
    "representative_name"," Test Representative ","representative_title"," Coordinator ",
    "representative_email"," REP@EXAMPLE.TEST ","representative_phone","9000000002");
 }
 private static Script script() {Script s=new Script();OracleDriver.scripts.add(s);return s;}
 private static Step insert(Script s,long id,BigDecimal latitude,BigDecimal longitude) {
  return s.update(INSERT).bind(1,id).bind(2,"Test O'Neil Hospital").bind(3,"1 Test Road").bind(4,"Test District")
   .bind(5,"600001").bind(6,latitude).bind(7,longitude).bind(8,"+91 90000 00001").bind(9,"office@example.test")
   .bind(10,"TEST_AUTHORITY").bind(11,"IN_TN").bind(12,"AB123").bind(13,"Test Representative")
   .bind(14,"Coordinator").bind(15,"rep@example.test").bind(16,"9000000002");
 }
 private static Script successScript(long id) {
  Script s=script();s.query("SELECT seq_hosp_reg_request.NEXTVAL FROM dual",Data.map("id",id));
  insert(s,id,null,null);return s;
 }
 private static void invalid(String field,Object value) throws Exception {
  Map<String,Object> body=application();body.put(field,value);
  try {DAO.submit(body);throw new AssertionError("Accepted invalid "+field);}
  catch(ApiException e) {check(e.status==400,"400 for "+field);}
  check(OracleDriver.scripts.isEmpty(),"Validation needs no JDBC for "+field);
 }
 private static void daoTests() throws Exception {
  Script s=successScript(100000);
  Map<String,Object> result=DAO.submit(application());
  check(result.equals(Data.map("registration_request_id",100000L,"status","PENDING")),"Minimal pending response");
  s.done(1,0);
  // Every allowed presentation normalizes to the same bound identity.
  for(String number:List.of("AB123","Ab 12.3","ab-12/3")) {
   Map<String,Object> body=application();body.put("registration_number",number);
   s=successScript(100001);DAO.submit(body);s.done(1,0);
  }
  Map<String,Object> body=application();body.put("latitude",new BigDecimal("-90.000000"));body.put("longitude",new BigDecimal("180"));
  s=script();s.query("SELECT seq_hosp_reg_request.NEXTVAL FROM dual",Data.map("id",100002L));
  insert(s,100002,new BigDecimal("-90").stripTrailingZeros(),new BigDecimal("180").stripTrailingZeros());
  DAO.submit(body);s.done(1,0);
  body=application();body.put("latitude",null);body.put("longitude",null);
  s=successScript(100003);DAO.submit(body);s.done(1,0);

  Map<String,Integer> lengths=new LinkedHashMap<>();
  lengths.put("hospital_name",150);lengths.put("address",300);lengths.put("district",60);lengths.put("pincode",6);
  lengths.put("official_phone",20);lengths.put("official_email",254);lengths.put("registration_authority",80);
  lengths.put("jurisdiction_code",30);lengths.put("registration_number",100);lengths.put("representative_name",100);
  lengths.put("representative_title",80);lengths.put("representative_email",254);lengths.put("representative_phone",20);
  for(Map.Entry<String,Integer> field:lengths.entrySet()) {
   invalid(field.getKey(),null);invalid(field.getKey()," ");invalid(field.getKey(),42);
   invalid(field.getKey(),"a".repeat(field.getValue()+1));
   body=application();body.remove(field.getKey());
   try {DAO.submit(body);throw new AssertionError("Accepted missing "+field.getKey());}
   catch(ApiException e) {check(e.status==400,"Required "+field.getKey());}
  }
  for(String field:List.of("status","reviewed_by","reviewed_at","approved_hospital_id","registration_request_id",
    "registration_key","submitted_at","verification_notes","rejection_reason","user_role","password","unexpected")) {
   invalid(field,null);invalid(field,"ACTIVE");
  }
  invalid("hospital_name","é".repeat(76));invalid("address","\u2003\u00a0");
  invalid("hospital_name","bad\nname");invalid("hospital_name","\uD800");
  invalid("pincode","000001");invalid("pincode","12345A");
  invalid("official_email","not-an-email");invalid("representative_email","bad @example.test");
  invalid("official_email","test\u00a0@example.test");
  invalid("official_phone","-------");invalid("representative_phone","abc1234567");
  invalid("official_phone","1234567890123456");
  invalid("registration_authority","Test Authority");invalid("jurisdiction_code","IN-TN");
  invalid("registration_authority","teſt");invalid("registration_number"," /.- ");
  invalid("registration_number","ab'123");invalid("registration_number","ＡＢ123");
  invalid("latitude",12);invalid("longitude",77);invalid("latitude","12.1");invalid("latitude",Double.NaN);
  for(Object lat:List.of(new BigDecimal("90.000001"),new BigDecimal("12.1234567"),Boolean.TRUE)) {
   body=application();body.put("latitude",lat);body.put("longitude",77);
   try {DAO.submit(body);throw new AssertionError("Accepted invalid coordinate");}
   catch(ApiException e) {check(e.status==400,"Coordinate range/type/precision");}
  }
  s=script();s.query("SELECT seq_hosp_reg_request.NEXTVAL FROM dual",Data.map("id",100004L));
  insert(s,100004,null,null).failure=new SQLException("private applicant@example.test UQ_HRR_OPEN_ID","23000",1);
  try {DAO.submit(application());throw new AssertionError("Accepted duplicate");}
  catch(ApiException e) {check(e.status==409&&!e.getMessage().contains("private")&&!e.getMessage().contains("@"),"Safe duplicate conflict");}
  s.done(0,1);
  s=script();s.query("SELECT seq_hosp_reg_request.NEXTVAL FROM dual").failure=new SQLException("Sequence failed","42000",2289);
  try {DAO.submit(application());throw new AssertionError("Sequence failure swallowed");}
  catch(SQLException e) {check(e.getErrorCode()==2289,"Sequence failure propagated");}
  s.done(0,1);
  s=script();s.query("SELECT seq_hosp_reg_request.NEXTVAL FROM dual",Data.map("id",100005L));
  insert(s,100005,null,null).failure=new SQLException("Insert failed","42000",942);
  try {DAO.submit(application());throw new AssertionError("Insert failure swallowed");}
  catch(SQLException e) {check(e.getErrorCode()==942,"Insert failure propagated");}
  s.done(0,1);
  check(OracleDriver.scripts.isEmpty(),"DAO SQL limited to sequence plus new-table insert");
 }
 private static HttpResponse<String> send(String method,String path,String cookie,String csrf,String origin,String contentType,String body) throws Exception {
  HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
  if(cookie!=null)b.header("Cookie",cookie);
  if(csrf!=null)b.header("X-CSRF-Token",csrf);
  if(origin!=null)b.header("Origin",origin);
  if(contentType!=null)b.header("Content-Type",contentType);
  return CLIENT.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 private static void httpTests() throws Exception {
  final String path="/hospital-registration-requests",origin="http://127.0.0.1:5173";
  HttpResponse<String> r=send("GET","/auth/me",null,null,origin,null,null);
  check(r.statusCode()==401,"Guest CSRF handshake");
  String cookie=r.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
  String csrf=(String)JsonUtil.parseJsonObject(r.body()).get("csrf_token");
  String payload=JsonUtil.toJson(application());
  check(send("POST",path,null,csrf,origin,"application/json",payload).statusCode()==403,"Cookie required");
  check(send("POST",path,cookie,null,origin,"application/json",payload).statusCode()==403,"CSRF required");
  check(send("POST",path,cookie,"wrong",origin,"application/json",payload).statusCode()==403,"Wrong CSRF rejected");
  r=send("POST",path,cookie,csrf,"https://untrusted.example","application/json",payload);
  check(r.statusCode()==403&&r.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),"Existing CORS enforced");
  r=send("OPTIONS",path,null,null,origin,null,null);
  check(r.statusCode()==204&&r.headers().firstValue("Access-Control-Allow-Origin").orElse("").equals(origin),"Existing CORS preflight");
  r=send("GET",path,cookie,null,origin,null,null);
  check(r.statusCode()==405&&r.headers().firstValue("Allow").orElse("").equals("POST"),"POST-only route");
  check(send("POST",path+"/approve",cookie,csrf,origin,"application/json","{}").statusCode()==404,"No approval route");
  check(send("POST",path+"/",cookie,csrf,origin,"application/json",payload).statusCode()==404,"Exact route only");

  Script s=successScript(100100);
  r=send("POST",path,cookie,csrf,origin,"application/json",payload);s.done(1,0);
  check(r.statusCode()==201,"Created response");
  check(JsonUtil.parseJsonObject(r.body()).equals(JsonUtil.parseJsonObject("{\"registration_request_id\":100100,\"status\":\"PENDING\"}")),"Only request ID and pending status returned");
  check(r.headers().firstValue("Set-Cookie").isEmpty(),"Submission does not create authenticated session");
  check(r.headers().firstValue("Cache-Control").orElse("").equals("no-store"),"No cached applicant response");
  check(send("GET","/auth/me",cookie,null,origin,null,null).statusCode()==401,"Applicant remains a guest");
  s=script();s.query("SELECT seq_hosp_reg_request.NEXTVAL FROM dual",Data.map("id",100101L));
  insert(s,100101,null,null).failure=new SQLException("Secret name and secret@example.test UQ_HRR_OPEN_ID","23000",1);
  r=send("POST",path,cookie,csrf,origin,"application/json",payload);s.done(0,1);
  check(r.statusCode()==409&&r.body().contains("REGISTRATION_CONFLICT")&&!r.body().contains("Secret")&&!r.body().contains("UQ_HRR"),"HTTP duplicate privacy");
  check(send("POST",path,cookie,csrf,origin,"application/json",payload).statusCode()==503,"Database unavailability never reports success");
  s=script();s.query("SELECT seq_hosp_reg_request.NEXTVAL FROM dual",Data.map("id",100102L));
  insert(s,100102,null,null).failure=new SQLException("Private SQL detail","42000",942);
  r=send("POST",path,cookie,csrf,origin,"application/json",payload);s.done(0,1);
  check(r.statusCode()==500&&!r.body().contains("Private"),"Database error redacted");
  check(send("POST",path,cookie,csrf,origin,"text/plain",payload).statusCode()==415,"JSON content type required");
  check(send("POST",path,cookie,csrf,origin,"application/json","{broken").statusCode()==400,"Malformed JSON rejected");
  check(send("POST",path,cookie,csrf,origin,"application/json","{\"status\":1,\"status\":2}").statusCode()==400,"Duplicate JSON keys rejected");
  check(send("POST",path,cookie,csrf,origin,"application/json"," ".repeat(17000)).statusCode()==413,"Existing body limit enforced");
  Map<String,Object> forged=application();forged.put("status","APPROVED");
  check(send("POST",path,cookie,csrf,origin,"application/json",JsonUtil.toJson(forged)).statusCode()==400,"Privileged field rejected over HTTP");
  check(send("POST",path,cookie,csrf,origin,"application/json","{}").statusCode()==400,"Missing fields rejected over HTTP");
  // Use invalid submissions: throttle testing cannot allocate sequence IDs or write anything.
  int attempts=0;
  do {r=send("POST",path,cookie,csrf,origin,"application/json","{}");attempts++;}
  while(r.statusCode()==400&&attempts<25);
  check(r.statusCode()==429,"Per-peer submission throttle");
  check(Integer.parseInt(r.headers().firstValue("Retry-After").orElse("0"))>0,"Retry-After provided");
  r=send("GET","/auth/me",null,null,origin,null,null);
  String otherCookie=r.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
  String otherCsrf=(String)JsonUtil.parseJsonObject(r.body()).get("csrf_token");
  check(send("POST",path,otherCookie,otherCsrf,origin,"application/json","{}").statusCode()==429,"New guest session cannot evade peer throttle");
  check(send("GET","/auth/me",cookie,null,origin,null,null).statusCode()==401,"Onboarding throttle does not block guest handshake");
  check(OracleDriver.scripts.isEmpty(),"No unexpected writes/SQL from HTTP rejection cases");
 }
 public static void main(String[] args) {
  try {
   if(!OracleDriver.URL.equals(System.getenv("ORACLE_URL")))throw new IllegalStateException("TEST-ONLY URL required");
   daoTests();
   BloodBankServer.main(new String[0]);
   base="http://127.0.0.1:"+System.getenv("PORT")+"/api";
   httpTests();
   System.out.println("PASS: "+checks+" onboarding checks; JDBC double only, no live Oracle writes or old regression suites.");
   System.exit(0);
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
}
