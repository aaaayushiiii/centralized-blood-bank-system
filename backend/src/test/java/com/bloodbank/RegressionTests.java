package com.bloodbank;
import com.bloodbank.dao.*;
import com.bloodbank.util.*;
import oracle.jdbc.OracleDriver;
import oracle.jdbc.OracleDriver.Script;
import java.sql.*;
import java.util.*;
public final class RegressionTests {
 private static int checks;
 private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 private static void error(int status,Runnable action){try{action.run();throw new AssertionError("Expected "+status);}catch(ApiException e){check(e.status==status,"Wrong status "+e.status);}}
 private static Map<String,Object> user(){return Data.map("user_id",7L,"full_name","Staff","email","staff@example.test","phone",null,"user_role","HOSPITAL_STAFF","account_status","ACTIVE","hospital_id",11L,"hospital_name","Hospital");}
 private static Map<String,Object> request(String status){return Data.map("request_id",101L,"hospital_id",11L,"blood_group","AB+","units_required",2,"units_allocated",status.equals("PENDING")?0:2,"status",status,"priority","HIGH");}
 private static Script script(){Script s=new Script();OracleDriver.scripts.add(s);return s;}
 private static void identity(Script s){s.query("WHERE u.user_id=?",user()).bind(1,7L);}
 private static void request(Script s,String status){s.query("FROM request WHERE request_id=? AND hospital_id=?",request(status)).bind(1,101L).bind(2,11L);}
 public static void main(String[] args) throws Exception {
  if(!OracleDriver.URL.equals(System.getenv("ORACLE_URL")))throw new IllegalStateException("Run run-tests.ps1; tests must use TEST-ONLY JDBC URL");
  jsonAndValidation();passwords();creation();dispatch("SUCCESS");dispatch("PARTIAL");dispatch("INSUFFICIENT_STOCK");refused();sqlFailure();ownership();inventory();nearby();registration();loginFailure();donorWrites();allocationRead();
  check(OracleDriver.scripts.isEmpty(),"Unused connection scripts");
  System.out.println("PASS: "+checks+" regression checks; JDBC doubles only, no live Oracle verification.");
 }
 private static void jsonAndValidation() {
  String original="  Password \\\"\n\t\u263a  ";
  check(original.equals(JsonUtil.parseJsonObject(JsonUtil.toJson(Data.map("value",original))).get("value")),"JSON string round trip");
  check(new java.math.BigDecimal("2").equals(JsonUtil.parseJsonObject("{\"n\":2}").get("n")),"JSON numeric type");
  for(String invalid:Arrays.asList("{} garbage","{\"x\":1,\"x\":2}","{\"x\":01}","{\"x\":1,}","[]","{\"x\":NaN}","{\"x\":1e999999999}"))error(400,()->JsonUtil.parseJsonObject(invalid));
  check(Data.group("ab","+").equals("AB+"),"Combined group");error(400,()->Data.group("AB","x"));
  check(Data.priority("Urgent").equals("HIGH") && Data.priority("Routine").equals("NORMAL"),"React priorities");
  error(400,()->Data.integer(1.5,1,999,"units"));error(400,()->Data.integer(1000,1,999,"units"));error(400,()->Data.id("1 OR 1=1"));
  error(403,()->Data.staffHospital(Data.map("role","CITIZEN","hospital_id",11L)));
  check(EmergencyRequestDao.outcomeStatus("BUSY")==409 && EmergencyRequestDao.outcomeStatus("NOT_AUTHORIZED")==403,"Dispatch errors");
 }
 private static void passwords() {
  String password=" leading and trailing spaces ";String a=Passwords.hash(password),b=Passwords.hash(password);
  check(!a.equals(b),"Random password salts");check(Passwords.verify(password,a),"Password verifies");check(!Passwords.verify(password.trim(),a),"Password whitespace preserved");
  check(!Passwords.verify("wrong",a),"Wrong password rejected");check(!Passwords.verify(password,"TEST-ONLY-NOT-A-REAL-HASH"),"Seed placeholder rejected");
  check(!Passwords.verify(password,"ef92b778bafe771e89245b89ecbc08a44"),"Legacy hash rejected");
 }
 private static void creation() throws Exception {
  Script s=script();identity(s);s.query("seq_request.NEXTVAL",Data.map("id",101L));
  s.update("INSERT INTO request(").bind(1,101L).bind(2,7L).bind(3,"Hospital stock request").bind(5,"AB+").bind(6,2).bind(7,"HIGH").bind(8,11L);
  request(s,"PENDING");Map<String,Object> response=new EmergencyRequestDao().create(Data.map("hospital_id",999L,"blood_group","AB","rh_factor","+","units_requested",2,"priority","Urgent"),7L);
  check(((Map<?,?>)response.get("request")).get("request_status").equals("PENDING"),"Creation never dispatches");s.done(1,0);checks++;
 }
 private static void dispatch(String outcome) throws Exception {
  Script s=script();identity(s);request(s,"PENDING");
  s.update("{call PRC_EMERGENCY_DISPATCH(?,?,?,?,?,?,?,?)}").bind(1,101L).bind(2,"USER:7").bind(3,11L).bind(4,"Y")
   .output(5,Types.VARCHAR,outcome).output(6,Types.NUMERIC,2).output(7,Types.NUMERIC,outcome.equals("INSUFFICIENT_STOCK")?0:2).output(8,Types.VARCHAR,"Procedure result");
  s.query("FROM v_request_allocation_detail WHERE request_id=?").bind(1,101L);
  request(s,outcome.equals("SUCCESS")?"FULFILLED":outcome.equals("PARTIAL")?"PARTIALLY_FULFILLED":"REJECTED");
  Map<String,Object> response=new EmergencyRequestDao().allocations(101,7,true);
  check(outcome.equals(response.get("outcome")),"Dispatch result "+outcome);s.done(1,0);checks++;
 }
 private static void refused() throws Exception {
  for(String outcome:Arrays.asList("NOT_ELIGIBLE","NOT_AUTHORIZED","BUSY","NOT_FOUND","INVALID_HOSPITAL")) {
   Script s=script();identity(s);request(s,"PENDING");s.update("PRC_EMERGENCY_DISPATCH").output(5,Types.VARCHAR,outcome).output(6,Types.NUMERIC,2).output(7,Types.NUMERIC,0).output(8,Types.VARCHAR,"Refused");
   try {new EmergencyRequestDao().allocations(101,7,true);throw new AssertionError("Expected refusal");}catch(ApiException e){check(e.status==EmergencyRequestDao.outcomeStatus(outcome),"Refusal HTTP mapping");}
   s.done(0,1);checks++;
  }
 }
 private static void sqlFailure() throws Exception {
  Script s=script();identity(s);request(s,"PENDING");
  OracleDriver.Step step=s.update("PRC_EMERGENCY_DISPATCH").output(5,Types.VARCHAR,null).output(6,Types.NUMERIC,null).output(7,Types.NUMERIC,null).output(8,Types.VARCHAR,null);
  step.failure=new SQLException("Synthetic stored procedure failure","99999",20999);
  try {new EmergencyRequestDao().allocations(101,7,true);throw new AssertionError("Expected SQL exception");}catch(SQLException e){check(e.getErrorCode()==20999,"SQL exception propagated");}
  s.done(0,1);checks++;
 }
 private static void ownership() throws Exception {
  Script s=script();identity(s);s.query("FROM request WHERE request_id=? AND hospital_id=?").bind(1,101L).bind(2,11L);
  try{new EmergencyRequestDao().allocations(101,7,true);throw new AssertionError("Expected inaccessible request");}catch(ApiException e){check(e.status==404,"Cross-hospital request hidden");}
  s.done(0,1);checks++;
  s=script();Map<String,Object> locked=user();locked.put("account_status","LOCKED");s.query("WHERE u.user_id=?",locked);
  try{new AuthDao().current(7);throw new AssertionError("Expected locked account");}catch(ApiException e){check(e.status==401,"Account status rechecked");}s.done(0,0);checks++;
 }
 private static void inventory() throws Exception {
  Script s=script();s.query("TRUNC(SYSDATE)",Data.map("date","2026-10-15"));
  s.query("expiry_date<=TRUNC(SYSDATE)",Data.map("bag_id",21L,"blood_group","O-","expiry_date",java.sql.Date.valueOf("2026-10-15"),"effective_status","EXPIRED")).bind(1,11L);
  Map<String,Object> response=new InventoryDao().inventory(11);
  Map<?,?> bag=(Map<?,?>)((List<?>)response.get("bags")).get(0);
  check(bag.get("bag_status").equals("Expired") && bag.get("rh_factor").equals("-"),"Expiry and React labels");s.done(0,0);checks++;
  s=script();s.query("TRUNC(SYSDATE)",Data.map("date","2026-10-15"));s.query("FROM blood_bags");
  check(((List<?>)new InventoryDao().inventory(11).get("bags")).isEmpty(),"Empty inventory stays empty");s.done(0,0);checks++;
 }
 private static void nearby() throws Exception {
  Script s=script();OracleDriver.Step step=s.update("PRC_NEARBY_HOSPITALS").bind(1,12.9).bind(2,77.5).bind(3,50.0).bind(4,"AB+").output(5,-10,null);
  step.cursor=Arrays.asList(Data.map("hospital_id",11L,"hospital_name","Hospital","address","Address","latitude",12.9,"longitude",77.5,"distance_km",0.1,"available_units",2),Data.map("hospital_id",11L,"available_units",1));
  s.query("SELECT phone FROM hospital",Data.map("phone","1234567890")).bind(1,11L);
  List<Map<String,Object>> rows=new HospitalDao().nearby(null,12.9,77.5,50,"AB+");
  check(rows.size()==1&&rows.get(0).get("available_units").equals(3),"Compatible group rows aggregated");s.done(0,0);checks++;
  s=script();s.query("AVG(latitude)",Data.map("lat",null,"lon",null)).bind(1,"560001");
  try{new HospitalDao().nearby("560001",null,null,50,"O+");throw new AssertionError("Unknown coordinates");}catch(ApiException e){check(e.status==422,"Unknown pincode is explicit error");}s.done(0,0);checks++;
 }
 private static void registration() throws Exception {
  Script s=script();s.query("FROM hospital WHERE hospital_id=?",Data.map("id",11L)).bind(1,11L);s.query("seq_users.NEXTVAL",Data.map("id",22L));
  s.update("INSERT INTO users(").bind(1,22L).bind(2,"Staff").bind(3,"new@example.test").bind(6,"HOSPITAL_STAFF").bind(7,"PENDING_VERIFICATION");
  s.update("INSERT INTO hospital_staff(").bind(1,22L).bind(2,11L);
  new AuthDao().register(Data.map("full_name","Staff","email","NEW@example.test","password","test registration password","hospital_id",11L),true);s.done(1,0);checks++;
 }
 private static void loginFailure() throws Exception {
  Script s=script();s.query("FROM users WHERE email=? FOR UPDATE",Data.map("id",7L,"hash",Passwords.hash("correct test password"),"status","ACTIVE","role","HOSPITAL_STAFF")).bind(1,"staff@example.test");
  s.update("failed_login_count=LEAST").bind(1,7L);
  try{new AuthDao().authenticate("STAFF@example.test","incorrect test password","HOSPITAL_STAFF");throw new AssertionError("Bad credentials");}catch(ApiException e){check(e.status==401,"Bad credentials rejected");}
  // The failed-login counter is deliberately committed, then the error path rolls back an empty transaction.
  s.done(1,1);checks++;
 }
 private static void donorWrites() throws Exception {
  Map<String,Object> body=Data.map("full_name","Donor","blood_group","O","rh_factor","-","contact_number","1234567890","age",30,"gender","Female","last_donation_date",null);
  Script s=script();s.query("seq_donor.NEXTVAL",Data.map("id",44L));s.update("INSERT INTO donor(").bind(1,44L).bind(2,"DONOR-44").bind(4,"O-");
  Map<?,?> donor=(Map<?,?>)new DonorDao().register(body).get("donor");check(donor.get("donor_id").equals(44L),"Database donor ID returned");s.done(1,0);checks++;
  s=script();s.query("seq_donor.NEXTVAL",Data.map("id",45L));s.update("INSERT INTO donor(").failure=new SQLException("Synthetic insert failure");
  try{new DonorDao().register(body);throw new AssertionError("Insert failure must not succeed");}catch(SQLException expected){checks++;}s.done(0,1);checks++;
 }
 private static void allocationRead() throws Exception {
  Script s=script();Map<String,Object> citizen=user();citizen.put("user_role","CITIZEN");citizen.put("hospital_id",null);s.query("WHERE u.user_id=?",citizen).bind(1,7L);
  s.query("FROM request WHERE request_id=? AND requester_user_id=?",request("FULFILLED")).bind(1,101L).bind(2,7L);
  s.query("FROM v_request_allocation_detail WHERE request_id=?",Data.map("allocation_id",55L,"bag_id",66L,"bag_code","BAG-66","blood_group","O-","expiry_date",java.sql.Date.valueOf("2026-12-01"),"allocation_status","RESERVED","bag_status","RESERVED")).bind(1,101L);
  s.query("FROM request WHERE request_id=? AND requester_user_id=?",request("FULFILLED")).bind(2,7L);
  Map<String,Object> response=new EmergencyRequestDao().allocations(101,7,false);
  check(((Map<?,?>)((List<?>)response.get("allocations")).get(0)).get("bag_id").equals(66L),"Retrieval returns stored allocation");s.done(1,0);checks++;
 }

}
