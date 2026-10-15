package com.bloodbank;
import com.bloodbank.server.BloodBankServer;
import com.bloodbank.util.*;
import oracle.jdbc.OracleDriver;
import oracle.jdbc.OracleDriver.Script;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
public final class HttpRegressionTests {
 private static int checks;
 private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private static String base;
 private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 private static HttpResponse<String> send(String method,String path,String cookie,String csrf,String origin,String body) throws Exception {
  HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20));
  if(cookie!=null)b.header("Cookie",cookie);if(csrf!=null)b.header("X-CSRF-Token",csrf);if(origin!=null)b.header("Origin",origin);
  if(body!=null)b.header("Content-Type","application/json");
  return CLIENT.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 private static String cookie(HttpResponse<String> response){return response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];}
 private static Map<String,Object> json(HttpResponse<String> response){return JsonUtil.parseJsonObject(response.body());}
 private static Script script(){Script s=new Script();OracleDriver.scripts.add(s);return s;}
 private static Map<String,Object> user(){return Data.map("user_id",7L,"full_name","Staff","email","staff@example.test","phone",null,"user_role","HOSPITAL_STAFF","account_status","ACTIVE","hospital_id",11L,"hospital_name","Hospital");}
 public static void main(String[] args) {
  try {
   if(!OracleDriver.URL.equals(System.getenv("ORACLE_URL")))throw new IllegalStateException("TEST-ONLY URL required");
   BloodBankServer.main(new String[0]);base="http://127.0.0.1:"+System.getenv().getOrDefault("PORT","8080")+"/api";
   tests();System.out.println("PASS: "+checks+" HTTP checks; test-only JDBC, no live Oracle verification.");System.exit(0);
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
 private static void tests() throws Exception {
  HttpResponse<String> r=send("GET","/health",null,null,null,null);check(r.statusCode()==503,"Disconnected health must fail");check(r.body().contains("ORACLE_UNAVAILABLE"),"Clear Oracle error");
  r=send("GET","/hospitals",null,null,"https://untrusted.example",null);check(r.statusCode()==403,"Untrusted origin denied");check(r.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),"No reflected origin");
  r=send("OPTIONS","/requests",null,null,"http://127.0.0.1:5173",null);check(r.statusCode()==204,"Preflight");check(r.headers().firstValue("Access-Control-Allow-Credentials").orElse("").equals("true"),"Credentialed trusted CORS");
  r=send("GET","/auth/me",null,null,null,null);check(r.statusCode()==401,"Guest session status");String guestCookie=cookie(r),guestCsrf=(String)json(r).get("csrf_token");
  check(guestCsrf!=null && guestCsrf.length()>32,"Random CSRF");
  HttpResponse<String> another=send("GET","/auth/me",null,null,null,null);check(!guestCsrf.equals(json(another).get("csrf_token")),"CSRF differs by session");
  check(send("POST","/auth/login",guestCookie,null,null,"{}").statusCode()==403,"Missing CSRF denied");
  check(send("POST","/auth/login",guestCookie,"wrong",null,"{}").statusCode()==403,"Wrong CSRF denied");
  check(send("GET","/auth/login",null,null,null,null).statusCode()==405,"Login method enforced");
  check(send("GET","/requests/emergency",null,null,null,null).statusCode()==405,"Emergency method enforced");
  check(send("GET","/requests/999/allocations",null,null,null,null).statusCode()==401,"Anonymous allocation retrieval denied");
  check(send("POST","/requests/999/allocations",guestCookie,guestCsrf,null,"{}").statusCode()==401,"Anonymous dispatch denied");
  check(send("POST","/auth/login",guestCookie,guestCsrf,null,"{broken").statusCode()==400,"Malformed JSON rejected");
  check(send("POST","/auth/login",guestCookie,guestCsrf,null," ".repeat(17000)).statusCode()==413,"Body limit enforced");
  r=send("POST","/auth/login",guestCookie,guestCsrf,null,"{\"email\":\"staff@example.test\",\"password\":\"synthetic password\",\"role\":\"HOSPITAL_STAFF\"}");check(r.statusCode()==503,"No fallback login");
  Script s=script();s.query("FROM hospital WHERE is_active='Y'");
  r=send("GET","/hospitals",null,null,null,null);check(r.statusCode()==200&&r.body().equals("[]"),"Empty hospital list stays empty");s.done(0,0);
  String pw="synthetic staff password";s=script();
  s.query("FROM users WHERE email=? FOR UPDATE",Data.map("id",7L,"hash",Passwords.hash(pw),"status","ACTIVE","role","HOSPITAL_STAFF"));
  s.query("WHERE u.user_id=?",user());s.update("last_login_at=SYSTIMESTAMP");
  r=send("POST","/auth/login",guestCookie,guestCsrf,null,JsonUtil.toJson(Data.map("email","staff@example.test","password",pw,"role","HOSPITAL_STAFF")));
  check(r.statusCode()==200,"Login envelope");s.done(1,0);String staffCookie=cookie(r),csrf=(String)json(r).get("csrf_token");
  check(!staffCookie.equals(guestCookie)&&!csrf.equals(guestCsrf),"Session rotated on login");check(!r.body().contains("password"),"No password response");
  s=script();s.query("WHERE u.user_id=?",user());r=send("GET","/auth/me",staffCookie,null,null,null);check(r.statusCode()==200&&r.body().contains("ACTIVE"),"Cookie-backed React session");s.done(0,0);
  s=script();s.query("WHERE u.user_id=?",user());
  check(send("POST","/requests",staffCookie,guestCsrf,null,"{}").statusCode()==403,"Old CSRF invalid after login");s.done(0,0);
  s=script();s.query("WHERE u.user_id=?",user());Script inventory=script();inventory.query("TRUNC(SYSDATE)",Data.map("date","2026-10-15"));inventory.query("FROM blood_bags WHERE hospital_id=?").bind(1,11L);
  r=send("GET","/hospitals/999/inventory",staffCookie,null,null,null);check(r.statusCode()==200&&r.body().contains("\"bags\":[]"),"Inventory uses assigned hospital despite URL");s.done(0,0);inventory.done(0,0);
  r=send("POST","/auth/logout",staffCookie,csrf,null,"{}");check(r.statusCode()==200,"Logout succeeds");
  check(send("GET","/hospitals/11/inventory",staffCookie,null,null,null).statusCode()==401,"Logged-out session unusable");
  check(OracleDriver.scripts.isEmpty(),"All JDBC expectations consumed");
 }
}
