package com.bloodbank.server;
import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.dao.*;
import com.bloodbank.util.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Java/JDBC is the only application backend. No inventory or authentication simulations. */
public final class BloodBankServer {
 private static final AuthDao AUTH=new AuthDao();
 private static final HospitalDao HOSPITALS=new HospitalDao();
 private static final InventoryDao INVENTORY=new InventoryDao();
 private static final DonorDao DONORS=new DonorDao();
 private static final EmergencyRequestDao REQUESTS=new EmergencyRequestDao();
 private static final Map<String,Session> SESSIONS=new ConcurrentHashMap<>();
 private static final SecureRandom RANDOM=new SecureRandom();
 private static final Set<String> ORIGINS=new HashSet<>(Arrays.asList(System.getenv().getOrDefault("CORS_ORIGINS","http://localhost:5173,http://127.0.0.1:5173").split(",")));
 private static final boolean SECURE=Boolean.parseBoolean(System.getenv().getOrDefault("COOKIE_SECURE","false"));
 private static final String COOKIE="blood_bank_session";
 private static final Map<String,Attempt> ATTEMPTS=new ConcurrentHashMap<>();
 private static final class Attempt {long start=System.currentTimeMillis();int count;}
 private static final class Session {
  final String id=token(),csrf=token();final Long userId;final long expires;
  Session(Long userId){this.userId=userId;expires=System.currentTimeMillis()+(userId==null?1800000L:28800000L);}
 }
 private static String token(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public static void main(String[] args) throws IOException {
  int port=Integer.parseInt(System.getenv().getOrDefault("PORT","8080"));
  HttpServer server=HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("BIND_ADDRESS","127.0.0.1"),port),64);
  ThreadPoolExecutor pool=new ThreadPoolExecutor(4,16,60,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(128),new ThreadPoolExecutor.CallerRunsPolicy());
  server.setExecutor(pool);server.createContext("/",BloodBankServer::handle);
  Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(1);pool.shutdownNow();}));
  server.start();System.out.println("Java blood bank API listening on port "+port+". Oracle connectivity must be checked at /api/health.");
 }
 private static void handle(HttpExchange x) throws IOException {
  try {
   String origin=x.getRequestHeaders().getFirst("Origin");
   x.getResponseHeaders().set("Vary","Origin");x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
   if(origin!=null) {
    if(!ORIGINS.contains(origin))throw new ApiException(403,"ORIGIN_DENIED","Origin is not allowed");
    x.getResponseHeaders().set("Access-Control-Allow-Origin",origin);x.getResponseHeaders().set("Access-Control-Allow-Credentials","true");
   }
   if("OPTIONS".equals(x.getRequestMethod())) {
    x.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, OPTIONS");
    x.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type, X-CSRF-Token");
    x.sendResponseHeaders(204,-1);return;
   }
   String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
   String allowed=methods(path);
   if(allowed==null)throw new ApiException(404,"NOT_FOUND","Endpoint not found");
   if(!Arrays.asList(allowed.split(", ")).contains(method)) {x.getResponseHeaders().set("Allow",allowed);throw new ApiException(405,"METHOD_NOT_ALLOWED","Method not allowed");}
   Session session=session(x);
   if(path.equals("/api/auth/me")) {
    if(session==null)session=createSession(x,null);
    Map<String,Object> user=session.userId==null?null:AUTH.current(session.userId);
    if(user!=null && !Arrays.asList("CITIZEN","HOSPITAL_STAFF").contains(user.get("role")))throw new ApiException(403,"ROLE_DENIED","This portal supports citizen and hospital staff accounts");
    send(x,user==null?401:200,Data.map("user",user,"csrf_token",session.csrf));return;
   }
   if(path.equals("/api/health")) {
    try(Connection c=DatabaseConnection.getConnection()) {
     if(!c.isValid(3))throw new SQLNonTransientConnectionException("Oracle connection validation failed","08001");
     send(x,200,Data.map("status","UP","database_connected",true));return;
    }
   }
   boolean protectedRoute=path.equals("/api/donors")||path.startsWith("/api/requests")||path.equals("/api/inventory/dashboard")||path.matches("/api/hospitals/[1-9][0-9]{0,9}/inventory");
   Map<String,Object> user=protectedRoute?requireUser(session):null;
   if(method.equals("POST"))csrf(x,session);
   if(path.equals("/api/auth/logout")) {
    if(session!=null)SESSIONS.remove(session.id);cookie(x,"",0);send(x,200,Data.map("status","LOGGED_OUT"));return;
   }
   if(path.equals("/api/auth/login")) {
    limitLogin(x);Map<String,Object> b=body(x);
    String email=Data.text(b,"email",254),password=password(b),role=Data.text(b,"role",20);
    if(!Arrays.asList("CITIZEN","HOSPITAL_STAFF").contains(role))throw new ApiException(400,"INVALID_ROLE","Unknown portal role");
    Map<String,Object> authenticatedUser=AUTH.authenticate(email,password,role);
    // Rotate the guest/session identifier after authentication.
    if(session!=null)SESSIONS.remove(session.id);Session authenticated=createSession(x,((Number)authenticatedUser.get("user_id")).longValue());
    send(x,200,Data.map("user",authenticatedUser,"csrf_token",authenticated.csrf));return;
   }
   if(path.startsWith("/api/auth/register/")) {
    limitLogin(x);boolean staff=path.endsWith("hospital");AUTH.register(body(x),staff);
    send(x,201,Data.map("status",staff?"PENDING_APPROVAL":"REGISTERED"));return;
   }
   if(path.equals("/api/hospitals")) {
    Long hospital=null;
    if(session!=null&&session.userId!=null) {Map<String,Object> u=AUTH.current(session.userId);if("HOSPITAL_STAFF".equals(u.get("role")))hospital=Data.staffHospital(u);}
    send(x,200,HOSPITALS.getAllHospitals(hospital));return;
   }
   if(path.equals("/api/hospitals/nearby")||path.equals("/api/blood/search")) {
    Map<String,String> q=query(x);String group=q.getOrDefault("blood_group",q.get("bloodGroup")),rh=q.getOrDefault("rh_factor",q.get("rhFactor"));
    String recipient=(group==null&&rh==null)?null:Data.group(group,rh);
    Double lat=decimal(q.get("lat")),lon=decimal(q.get("lon"));Double radius=decimal(q.get("radius"));
    List<Map<String,Object>> rows=HOSPITALS.nearby(q.get("pincode"),lat,lon,radius==null?50:radius,recipient);
    send(x,200,Data.map("hospitals",rows));return;
   }
   if(path.matches("/api/hospitals/[1-9][0-9]{0,9}/inventory")||path.equals("/api/inventory/dashboard")) {
    long hospital=Data.staffHospital(user); // Client hospital IDs never choose the scope.
    send(x,200,INVENTORY.inventory(hospital));return;
   }
   if(path.equals("/api/donors")) {
    if(!"CITIZEN".equals(user.get("role")))throw new ApiException(403,"CITIZEN_REQUIRED","Citizen access is required");
    send(x,201,DONORS.register(body(x)));return;
   }
   if(path.equals("/api/requests")||path.equals("/api/requests/emergency")) {
    Data.staffHospital(user);send(x,201,REQUESTS.create(body(x),session.userId));return;
   }
   if(path.matches("/api/requests/[1-9][0-9]{0,9}/allocations")) {
    if(method.equals("POST")) {Data.staffHospital(user);body(x);}
    long id=Data.id(path.split("/")[3]);send(x,200,REQUESTS.allocations(id,session.userId,method.equals("POST")));return;
   }
   throw new ApiException(404,"NOT_FOUND","Endpoint not found");
  } catch(ApiException e) {send(x,e.status,Data.map("error",Data.map("code",e.code,"message",e.getMessage())));}
  catch(SQLException e) {
   // Only server-side classification is logged; credentials, SQL text and personal data are not returned.
   System.err.println("Oracle failure: SQLState="+e.getSQLState()+", code="+e.getErrorCode());
   boolean unavailable=e instanceof SQLTransientConnectionException||e instanceof SQLNonTransientConnectionException||e instanceof SQLRecoverableException||(e.getSQLState()!=null&&e.getSQLState().startsWith("08"))||e.getErrorCode()==1017||e.getErrorCode()==28000;
   int status=unavailable?503:e.getErrorCode()==1?409:e.getErrorCode()==20999?500:500;
   send(x,status,Data.map("error",Data.map("code",unavailable?"ORACLE_UNAVAILABLE":status==409?"CONFLICT":"DATABASE_ERROR","message",unavailable?"Oracle is unavailable. Check server database configuration and connectivity.":status==409?"The record conflicts with existing data.":"Oracle could not complete the operation. Contact the administrator.")));
  } catch(Exception e) {
   System.err.println("API failure: "+e.getClass().getSimpleName());send(x,500,Data.map("error",Data.map("code","INTERNAL_ERROR","message","The server could not complete the operation.")));
  } finally {x.close();}
 }
 private static String methods(String path) {
  if(Arrays.asList("/api/auth/me","/api/health","/api/hospitals","/api/hospitals/nearby","/api/blood/search","/api/inventory/dashboard").contains(path)||path.matches("/api/hospitals/[1-9][0-9]{0,9}/inventory"))return "GET";
  if(Arrays.asList("/api/auth/login","/api/auth/logout","/api/auth/register/citizen","/api/auth/register/hospital","/api/donors","/api/requests","/api/requests/emergency").contains(path))return "POST";
  if(path.matches("/api/requests/[1-9][0-9]{0,9}/allocations"))return "GET, POST";return null;
 }
 private static Session session(HttpExchange x) {
  String header=x.getRequestHeaders().getFirst("Cookie");if(header==null)return null;
  for(String item:header.split(";")) {
   String[] p=item.trim().split("=",2);if(p.length==2&&p[0].equals(COOKIE)) {
    Session s=SESSIONS.get(p[1]);if(s!=null&&s.expires<=System.currentTimeMillis()){SESSIONS.remove(s.id);return null;}return s;
   }
  }return null;
 }
 private static synchronized Session createSession(HttpExchange x,Long user) {
  SESSIONS.values().removeIf(s->s.expires<=System.currentTimeMillis());
  if(SESSIONS.size()>=10000)throw new ApiException(503,"SESSION_CAPACITY","Session capacity reached; try again later");
  Session s=new Session(user);SESSIONS.put(s.id,s);cookie(x,s.id,user==null?1800:28800);return s;
 }
 private static void cookie(HttpExchange x,String value,int age) {x.getResponseHeaders().add("Set-Cookie",COOKIE+"="+value+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+age+(SECURE?"; Secure":""));}
 private static void csrf(HttpExchange x,Session s) {
  String supplied=x.getRequestHeaders().getFirst("X-CSRF-Token");
  if(s==null||supplied==null||!MessageDigest.isEqual(s.csrf.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ApiException(403,"CSRF_INVALID","Refresh the session before submitting this action");
 }
 private static Map<String,Object> requireUser(Session s) throws SQLException {
  if(s==null||s.userId==null)throw new ApiException(401,"AUTH_REQUIRED","Sign in to continue");return AUTH.current(s.userId);
 }
 private static String password(Map<String,Object> body) {
  Object p=body.get("password");if(!(p instanceof String)||((String)p).isEmpty()||((String)p).length()>128)throw new ApiException(400,"INVALID_PASSWORD","Invalid password");return (String)p;
 }
 private static Map<String,Object> body(HttpExchange x) throws IOException {
  String content=x.getRequestHeaders().getFirst("Content-Type");if(content==null||!content.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new ApiException(415,"JSON_REQUIRED","Use application/json");
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;
  try(InputStream in=x.getRequestBody()) {while((n=in.read(buf))!=-1){if(out.size()+n>16384)throw new ApiException(413,"BODY_TOO_LARGE","JSON body exceeds 16 KiB");out.write(buf,0,n);}}
  try {return JsonUtil.parseJsonObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString());}
  catch(CharacterCodingException e) {throw new ApiException(400,"INVALID_JSON","JSON must use UTF-8");}
 }
 private static Map<String,String> query(HttpExchange x) {
  Map<String,String> q=new LinkedHashMap<>();String raw=x.getRequestURI().getRawQuery();if(raw==null)return q;
  try {for(String part:raw.split("&")){String[] pair=part.split("=",2);String key=URLDecoder.decode(pair[0],"UTF-8");if(q.containsKey(key))throw new IllegalArgumentException();q.put(key,pair.length==2?URLDecoder.decode(pair[1],"UTF-8"):"");}}
  catch(Exception e){throw new ApiException(400,"INVALID_QUERY","Invalid or duplicate query parameters");}return q;
 }
 private static Double decimal(String value) {
  if(value==null)return null;try {double d=Double.parseDouble(value);if(!Double.isFinite(d))throw new NumberFormatException();return d;}
  catch(NumberFormatException e){throw new ApiException(400,"INVALID_NUMBER","Invalid coordinate or radius");}
 }
 private static synchronized void limitLogin(HttpExchange x) {
  long now=System.currentTimeMillis();ATTEMPTS.values().removeIf(a->now-a.start>=60000);
  String address=x.getRemoteAddress().getAddress().getHostAddress();Attempt a=ATTEMPTS.get(address);
  if(a==null){if(ATTEMPTS.size()>=10000)throw new ApiException(429,"RATE_LIMITED","Try again later");a=new Attempt();ATTEMPTS.put(address,a);}
  if(++a.count>20)throw new ApiException(429,"RATE_LIMITED","Too many authentication attempts; try again in one minute");
 }
 private static void send(HttpExchange x,int status,Object body) throws IOException {
  byte[] bytes=JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");x.sendResponseHeaders(status,bytes.length);try(OutputStream out=x.getResponseBody()){out.write(bytes);}
 }
}
