package com.bloodbank;

import com.bloodbank.config.WebAdminPolicy;
import com.bloodbank.dao.AuthDao;
import com.bloodbank.server.BloodBankServer;
import com.bloodbank.util.*;
import oracle.jdbc.OracleDriver;
import oracle.jdbc.OracleDriver.Script;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** Focused authentication boundaries; TEST-ONLY JDBC, never the live driver. */
public final class WebAdminAuthenticationTests {
 private static int checks;
 private static final String PASSWORD="synthetic-"+UUID.randomUUID();
 private static final String HASH=Passwords.hash(PASSWORD);
 private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private static String base;
 private record Guest(String cookie,String csrf) {}
 private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
 private static Map<String,Object> current(String role,String status) {
  return Data.map("user_id",7L,"full_name","Synthetic User","email","user@example.test","phone",null,
    "user_role",role,"account_status",status,"hospital_id",role.equals("HOSPITAL_STAFF")?90001L:null,
    "hospital_name",role.equals("HOSPITAL_STAFF")?"Synthetic Hospital":null);
 }
 private static Script script() {Script s=new Script();OracleDriver.scripts.add(s);return s;}
 private static void currentScript(String role,String status) {
  Script s=script();s.query("WHERE u.user_id=?",current(role,status)).bind(1,7L);
 }
 private static HttpResponse<String> request(String method,String path,Guest session,String csrf,String origin,String body) throws Exception {
  HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
  if(session!=null)b.header("Cookie",session.cookie);
  if(csrf!=null)b.header("X-CSRF-Token",csrf);
  if(origin!=null)b.header("Origin",origin);
  if(body!=null)b.header("Content-Type","application/json");
  return CLIENT.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 private static Map<String,Object> json(HttpResponse<String> r) {return JsonUtil.parseJsonObject(r.body());}
 private static Guest session(HttpResponse<String> r) {
  return new Guest(r.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0],(String)json(r).get("csrf_token"));
 }
 private static Guest guest() throws Exception {
  HttpResponse<String> r=request("GET","/auth/me",null,null,null,null);
  check(r.statusCode()==401,"Guest session remains unauthenticated");return session(r);
 }
 private static HttpResponse<String> login(Guest guest,String role,String password) throws Exception {
  return request("POST","/auth/login",guest,guest.csrf,null,JsonUtil.toJson(Data.map("email","user@example.test","password",password,"role",role,
    "user_id",99999,"account_status","ACTIVE")));
 }
 private static Guest authenticated(Guest guest,String role,int lifetime) throws Exception {
  Script s=script();
  s.query("FROM users WHERE email=? FOR UPDATE",Data.map("id",7L,"hash",HASH,"status","ACTIVE","role",role)).bind(1,"user@example.test");
  s.query("WHERE u.user_id=?",current(role,"ACTIVE")).bind(1,7L);
  s.update("UPDATE users SET failed_login_count=0,last_login_at=SYSTIMESTAMP WHERE user_id=?").bind(1,7L);
  HttpResponse<String> r=login(guest,role,PASSWORD);s.done(1,0);
  check(r.statusCode()==200,"Stored active "+role+" authenticates");
  Guest result=session(r);
  check(!result.cookie.equals(guest.cookie)&&!result.csrf.equals(guest.csrf),"Login rotates session ID and CSRF");
  String cookie=r.headers().firstValue("Set-Cookie").orElseThrow();
  check(cookie.contains("HttpOnly")&&cookie.contains("SameSite=Lax")&&cookie.contains("Max-Age="+lifetime),"Role-specific safe cookie lifetime");
  @SuppressWarnings("unchecked") Map<String,Object> user=(Map<String,Object>)json(r).get("user");
  check(role.equals(user.get("role"))&&((Number)user.get("user_id")).longValue()==7,"Identity/role come from Oracle, not client ID/status");
  check(!r.body().contains("password")&&!r.body().contains("pbkdf2")&&!r.body().contains(PASSWORD),"No password/hash in login response");
  return result;
 }
 private static void rejectedLogin(Guest guest,String requestedRole,String storedRole,String status,String password) throws Exception {
  Script s=script();s.query("FROM users WHERE email=? FOR UPDATE",Data.map("id",7L,"hash",HASH,"status",status,"role",storedRole));
  s.update("account_status=CASE WHEN account_status='ACTIVE' AND failed_login_count>=4 THEN 'LOCKED' ELSE account_status END WHERE user_id=?").bind(1,7L);
  // Existing AuthDao commits its failure counter before throwing, then attempts
  // rollback in its common exception path; preserve that existing behavior.
  HttpResponse<String> r=login(guest,requestedRole,password);s.done(1,1);
  check(r.statusCode()==401&&r.body().contains("INVALID_CREDENTIALS"),"Invalid/inactive/wrong-role login refused generically");
  check(r.headers().firstValue("Set-Cookie").isEmpty(),"Failed login creates no authenticated cookie");
 }
 private static void policyTests() throws Exception {
  WebAdminPolicy enabled=new WebAdminPolicy(true),disabled=new WebAdminPolicy(false);
  for(String bind:List.of("127.0.0.1","localhost","::1")) {enabled.validateBinding(bind);check(true,"Loopback bind accepted");}
  for(String bind:List.of("0.0.0.0","::","192.0.2.1","example.test")) {
   try {enabled.validateBinding(bind);throw new AssertionError("Public bind accepted");}
   catch(IllegalStateException e) {check(true,"Admin public bind refused");}
  }
  disabled.validateBinding("0.0.0.0");check(true,"Disabled admin preserves existing bind policy");
  InetAddress local=InetAddress.getByAddress(new byte[]{127,0,0,1});
  InetAddress remote=InetAddress.getByAddress(new byte[]{(byte)203,0,113,1});
  for(String origin:Arrays.asList(null,"http://localhost:5173","https://127.0.0.1:5173","http://[::1]:5173")) {
   enabled.requireLocal(local,local,origin);check(true,"Local origin accepted");
  }
  for(String origin:List.of("https://example.test","http://localhost.evil.test","http://user@localhost","null")) {
   try {enabled.requireLocal(local,local,origin);throw new AssertionError("Unsafe origin accepted");}
   catch(ApiException e) {check(e.status==403,"Nonlocal/opaque/userinfo origin refused");}
  }
  for(InetAddress[] pair:List.of(new InetAddress[]{local,remote},new InetAddress[]{remote,local})) {
   try {enabled.requireLocal(pair[0],pair[1],null);throw new AssertionError("Nonlocal socket accepted");}
   catch(ApiException e) {check(e.status==403,"Peer/local socket enforcement");}
  }
  try {disabled.requireLocal(local,local,null);throw new AssertionError("Disabled flag accepted");}
  catch(ApiException e) {check(e.status==403,"Feature flag denies by default");}
 }
 private static void expire(Guest session) throws Exception {
  Field field=BloodBankServer.class.getDeclaredField("SESSIONS");field.setAccessible(true);
  Object value=((Map<?,?>)field.get(null)).get(session.cookie.split("=",2)[1]);
  Field expires=value.getClass().getDeclaredField("expires");expires.setAccessible(true);
  check(expires.getLong(value)>System.currentTimeMillis()&&expires.getLong(value)<=System.currentTimeMillis()+1800000L,"Admin server-side expiration is 30 minutes");
  expires.setLong(value,0); // Test-only expiry simulation; no sleeps or production clock configuration.
 }
 private static void publicRegistrationCannotPromote() throws Exception {
  Map<String,Object> forged=Data.map("full_name","Synthetic User","email","new@example.test","password",PASSWORD,
    "role","ADMIN","user_role","ADMIN","account_status","ACTIVE");
  Script s=script();s.query("SELECT seq_users.NEXTVAL FROM dual",Data.map("id",101L));
  s.update("INSERT INTO users(").bind(1,101L).bind(6,"CITIZEN").bind(7,"ACTIVE");
  new AuthDao().register(forged,false);s.done(1,0);check(true,"Public citizen signup cannot self-assign ADMIN");
  forged.put("hospital_id",90001L);
  s=script();s.query("SELECT hospital_id FROM hospital WHERE hospital_id=? AND is_active='Y'",Data.map("id",90001L)).bind(1,90001L);
  s.query("SELECT seq_users.NEXTVAL FROM dual",Data.map("id",102L));
  s.update("INSERT INTO users(").bind(1,102L).bind(6,"HOSPITAL_STAFF").bind(7,"PENDING_VERIFICATION");
  s.update("INSERT INTO hospital_staff(").bind(1,102L).bind(2,90001L);
  new AuthDao().register(forged,true);s.done(1,0);check(true,"Public staff signup cannot self-assign ADMIN or active status");
 }
 private static void tests(boolean enabled) throws Exception {
  policyTests();Guest g=guest();
  check(request("POST","/auth/login",g,null,null,"{}").statusCode()==403,"Login requires CSRF");
  check(request("POST","/auth/login",g,"wrong",null,"{}").statusCode()==403,"Wrong CSRF rejected");
  check(request("POST","/auth/login",g,g.csrf,"https://evil.example","{}").statusCode()==403,"Existing CORS protects login");
  check(request("POST","/auth/register/admin",g,g.csrf,null,"{}").statusCode()==404,"No public admin signup route");
  check(request("GET","/admin/hospital-registration-requests",g,null,null,null).statusCode()==(enabled?401:403),"Review API remains protected after Task 3 routing");
  if(!enabled) {
   check(login(g,"ADMIN",PASSWORD).statusCode()==403,"ADMIN login disabled without explicit feature flag");
   check(request("GET","/admin/auth/me",g,null,null,null).statusCode()==403,"Admin-only operation disabled");
   Guest citizen=authenticated(g,"CITIZEN",28800);
   currentScript("CITIZEN","ACTIVE");HttpResponse<String> me=request("GET","/auth/me",citizen,null,null,null);
   check(me.statusCode()==200,"Default citizen session still validates");
   Guest staff=authenticated(guest(),"HOSPITAL_STAFF",28800);
   currentScript("HOSPITAL_STAFF","ACTIVE");
   check(request("GET","/auth/me",staff,null,null,null).statusCode()==200,"Default staff assignment/session still validates");
   check(OracleDriver.scripts.isEmpty(),"Disabled feature causes no unexpected SQL");return;
  }
  check(request("GET","/admin/auth/me",g,null,null,null).statusCode()==401,"Admin operation requires authentication");
  publicRegistrationCannotPromote();
  Guest citizen=authenticated(g,"CITIZEN",28800);
  check(request("GET","/admin/auth/me",citizen,null,null,null).statusCode()==403,"Citizen cannot access admin operation");
  currentScript("ADMIN","ACTIVE");
  HttpResponse<String> changed=request("GET","/auth/me",citizen,null,null,null);
  check(changed.statusCode()==401&&json(changed).get("user")==null,"Database promotion cannot elevate an existing citizen session");
  Guest staff=authenticated(guest(),"HOSPITAL_STAFF",28800);
  check(request("GET","/admin/auth/me",staff,null,null,null).statusCode()==403,"Staff cannot access admin operation");
  g=guest();rejectedLogin(g,"ADMIN","CITIZEN","ACTIVE",PASSWORD);
  rejectedLogin(g,"ADMIN","HOSPITAL_STAFF","ACTIVE",PASSWORD);
  for(String status:List.of("PENDING_VERIFICATION","DISABLED","LOCKED"))rejectedLogin(g,"ADMIN","ADMIN",status,PASSWORD);
  rejectedLogin(g,"ADMIN","ADMIN","ACTIVE",PASSWORD+"-wrong");
  rejectedLogin(g,"CITIZEN","ADMIN","ACTIVE",PASSWORD);
  Guest admin=authenticated(g,"ADMIN",1800);
  currentScript("ADMIN","ACTIVE");check(request("GET","/auth/me",admin,null,null,null).statusCode()==200,"Common session endpoint supports verified ADMIN");
  currentScript("ADMIN","ACTIVE");check(request("GET","/admin/auth/me",admin,null,null,null).statusCode()==200,"Admin operation rechecks stored role/status");
  check(request("POST","/auth/logout",admin,g.csrf,null,"{}").statusCode()==403,"Pre-login CSRF cannot log out rotated admin session");
  currentScript("ADMIN","DISABLED");check(request("GET","/admin/auth/me",admin,null,null,null).statusCode()==401,"Disabling admin revokes access immediately");
  check(request("GET","/admin/auth/me",admin,null,null,null).statusCode()==401,"Revoked cookie cannot be reused");
  admin=authenticated(guest(),"ADMIN",1800);
  currentScript("CITIZEN","ACTIVE");check(request("GET","/admin/auth/me",admin,null,null,null).statusCode()==401,"Admin demotion invalidates session");
  admin=authenticated(guest(),"ADMIN",1800);expire(admin);
  check(request("GET","/admin/auth/me",admin,null,null,null).statusCode()==401,"Expired admin cannot access protected operation");
  admin=authenticated(guest(),"ADMIN",1800);
  HttpResponse<String> logout=request("POST","/auth/logout",admin,admin.csrf,null,"{}");
  check(logout.statusCode()==200&&logout.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"),"Logout expires cookie");
  check(request("GET","/admin/auth/me",admin,null,null,null).statusCode()==401,"Logout destroys server-side session");
  g=guest();int attempts=0;HttpResponse<String> limited;
  do {limited=login(g,"UNKNOWN",PASSWORD);attempts++;}while(limited.statusCode()==400&&attempts<25);
  check(limited.statusCode()==429,"Existing per-peer login throttle still applies");
  check(OracleDriver.scripts.isEmpty(),"No unexpected identity/approval SQL");
 }
 public static void main(String[] args) {
  try {
   if(!OracleDriver.URL.equals(System.getenv("ORACLE_URL")))throw new IllegalStateException("TEST-ONLY URL required");
   boolean enabled=args.length==1&&args[0].equals("enabled");
   BloodBankServer.main(new String[0]);base="http://127.0.0.1:"+System.getenv("PORT")+"/api";
   tests(enabled);
   System.out.println("PASS: "+checks+" focused web administrator authentication checks (feature "+(enabled?"enabled":"default-disabled")+"); JDBC double only.");System.exit(0);
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
}
