package com.bloodbank.dao;

import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.config.WebAdminPolicy;
import com.bloodbank.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Web review only. HTTP caller must first enforce session/CSRF/local policy. */
public final class AdminHospitalRegistrationDao {
 private static final String[] COLUMNS={"registration_request_id","hospital_name","address","district","pincode",
   "latitude","longitude","official_phone","official_email","registration_authority","jurisdiction_code",
   "registration_number","registration_key","representative_name","representative_title","representative_email",
   "representative_phone","status","submitted_at","reviewed_at","reviewed_by","verification_notes","rejection_reason","approved_hospital_id"};
 private static final String TIMESTAMP_FORMAT="'YYYY-MM-DD\"T\"HH24:MI:SS.FF6'";
 private static final String FULL_SELECT="SELECT registration_request_id,hospital_name,address,district,pincode,latitude,longitude,official_phone,official_email,registration_authority,jurisdiction_code,registration_number,registration_key,representative_name,representative_title,representative_email,representative_phone,status,"
   +"TO_CHAR(submitted_at,"+TIMESTAMP_FORMAT+") AS submitted_at,TO_CHAR(reviewed_at,"+TIMESTAMP_FORMAT+") AS reviewed_at,reviewed_by,verification_notes,rejection_reason,approved_hospital_id FROM SYSTEM.hospital_registration_request WHERE registration_request_id=?";
 private static final String LIST_SELECT="SELECT registration_request_id,hospital_name,district,status,TO_CHAR(submitted_at,"+TIMESTAMP_FORMAT+") AS submitted_at,approved_hospital_id FROM SYSTEM.hospital_registration_request WHERE status=? AND registration_request_id>? ORDER BY registration_request_id FETCH FIRST ? ROWS ONLY";
 private static final String APPROVAL_LOCK="BEGIN ? := SYS.DBMS_LOCK.REQUEST(id => 731908231, lockmode => 6, timeout => 5, release_on_commit => TRUE); END;";
 public static final String DEMO_APPROVAL_NOTES="Local academic demonstration approval; official government verification not performed.";
 private static final String DEMO_REJECTION_NOTES="Local academic demonstration rejection; official government verification not performed.";
 private static final Set<String> APPROVE_FIELDS=Set.of("review_token","confirmation");
 private static final Set<String> REJECT_FIELDS=Set.of("review_token","confirmation","rejection_reason");
 private final byte[] signingKey=new byte[32];
 public AdminHospitalRegistrationDao() {new SecureRandom().nextBytes(signingKey);}

 public Map<String,Object> list(long adminId,Map<String,String> query) throws SQLException {
  if(!Set.of("status","after_id","limit").containsAll(query.keySet()))throw invalid("query parameters");
  String status=query.getOrDefault("status","PENDING").trim().toUpperCase(Locale.ROOT);
  if(!Set.of("PENDING","APPROVED","REJECTED").contains(status))throw invalid("status filter");
  String cursor=query.getOrDefault("after_id","0"),size=query.getOrDefault("limit","20");
  if(!cursor.matches("0|[1-9][0-9]{0,9}")||!size.matches("[1-9][0-9]{0,2}"))throw invalid("pagination");
  long after=Long.parseLong(cursor);int limit=Integer.parseInt(size);
  if(limit>100)throw invalid("limit (maximum 100)");
  return operation(adminId,false,c->{
   List<Map<String,Object>> rows=new ArrayList<>();
   try(PreparedStatement s=c.prepareStatement(LIST_SELECT)) {
    s.setString(1,status);s.setLong(2,after);s.setInt(3,limit+1);
    try(ResultSet r=s.executeQuery()) {while(r.next()) {
     Map<String,Object> row=new LinkedHashMap<>();
     for(String col:List.of("registration_request_id","hospital_name","district","status","submitted_at","approved_hospital_id"))row.put(col,value(r,col));
     rows.add(row);
    }}
   }
   boolean more=rows.size()>limit;
   if(more)rows.remove(rows.size()-1);
   Object next=more?rows.get(rows.size()-1).get("registration_request_id"):null;
   return Data.map("applications",rows,"status",status,"limit",limit,"next_after_id",next);
  });
 }
 public Map<String,Object> detail(long id,long adminId,String sessionBinding) throws SQLException {
  validId(id);binding(sessionBinding);
  return operation(adminId,false,c->{
   Map<String,Object> row=application(c,id,false);
   return Data.map("application",row,"review_token",token(row,adminId,sessionBinding));
  });
 }
 public Map<String,Object> approve(long id,long adminId,String sessionBinding,Map<String,Object> body) throws SQLException {
  requireDemo();validId(id);binding(sessionBinding);fields(body,APPROVE_FIELDS);
  String supplied=reviewToken(body);confirmation(body,"APPROVE "+id);
  String notes=DEMO_APPROVAL_NOTES;
  return operation(adminId,true,c->{
   Map<String,Object> row=application(c,id,true);pending(row);fresh(row,adminId,sessionBinding,supplied);
   try(CallableStatement s=c.prepareCall(APPROVAL_LOCK)) {
    s.registerOutParameter(1,Types.INTEGER);s.execute();
    if(s.getInt(1)!=0)throw busy();
   }
   duplicates(c,id,row);
   long hospital;
   try(PreparedStatement s=c.prepareStatement("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual");ResultSet r=s.executeQuery()) {
    if(!r.next())throw new SQLException("Hospital sequence unavailable");hospital=r.getLong(1);
    if(hospital<1||hospital>9999999999L)throw new SQLException("Hospital sequence outside supported range");
   }
   try(PreparedStatement s=c.prepareStatement("INSERT INTO SYSTEM.hospital(hospital_id,name,address,district,pincode,latitude,longitude,phone,is_active) VALUES(?,?,?,?,?,?,?,?,'Y')")) {
    s.setLong(1,hospital);s.setString(2,(String)row.get("hospital_name"));s.setString(3,(String)row.get("address"));
    s.setString(4,(String)row.get("district"));s.setString(5,(String)row.get("pincode"));
    decimal(s,6,row.get("latitude"));decimal(s,7,row.get("longitude"));s.setString(8,(String)row.get("official_phone"));
    if(s.executeUpdate()!=1)throw new SQLException("Hospital insert did not affect one row");
   }
   try(PreparedStatement s=c.prepareStatement("UPDATE SYSTEM.hospital_registration_request SET status='APPROVED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,approved_hospital_id=? WHERE registration_request_id=? AND status='PENDING' AND approved_hospital_id IS NULL")) {
    s.setString(1,"WEB_ADMIN:"+adminId);s.setString(2,notes);s.setLong(3,hospital);s.setLong(4,id);
    if(s.executeUpdate()!=1)throw alreadyReviewed();
   }
   return Data.map("registration_request_id",id,"status","APPROVED","approved_hospital_id",hospital);
  });
 }
 public Map<String,Object> reject(long id,long adminId,String sessionBinding,Map<String,Object> body) throws SQLException {
  requireDemo();validId(id);binding(sessionBinding);fields(body,REJECT_FIELDS);
  String supplied=reviewToken(body);confirmation(body,"REJECT "+id);
  String notes=DEMO_REJECTION_NOTES,reason=text(body,"rejection_reason",1000);
  return operation(adminId,true,c->{
   Map<String,Object> row=application(c,id,true);pending(row);fresh(row,adminId,sessionBinding,supplied);
   try(PreparedStatement s=c.prepareStatement("UPDATE SYSTEM.hospital_registration_request SET status='REJECTED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,rejection_reason=? WHERE registration_request_id=? AND status='PENDING' AND approved_hospital_id IS NULL")) {
    s.setString(1,"WEB_ADMIN:"+adminId);s.setString(2,notes);s.setString(3,reason);s.setLong(4,id);
    if(s.executeUpdate()!=1)throw alreadyReviewed();
   }
   return Data.map("registration_request_id",id,"status","REJECTED");
  });
 }
 private static void duplicates(Connection c,long id,Map<String,Object> row) throws SQLException {
  String name=((String)row.get("hospital_name")).trim().toUpperCase(Locale.ROOT);
  String address=((String)row.get("address")).trim().toUpperCase(Locale.ROOT);
  String phone=((String)row.get("official_phone")).replaceAll("[^0-9]","");
  try(PreparedStatement s=c.prepareStatement("SELECT registration_request_id FROM SYSTEM.hospital_registration_request WHERE registration_request_id<>? AND status IN ('PENDING','APPROVED') AND ((registration_authority=? AND jurisdiction_code=? AND registration_key=?) OR UPPER(TRIM(hospital_name))=? OR (UPPER(TRIM(address))=? AND pincode=?) OR TRANSLATE(official_phone,'x +()-','x')=?)")) {
   s.setLong(1,id);s.setString(2,(String)row.get("registration_authority"));s.setString(3,(String)row.get("jurisdiction_code"));
   s.setString(4,(String)row.get("registration_key"));s.setString(5,name);s.setString(6,address);
   s.setString(7,(String)row.get("pincode"));s.setString(8,phone);
   try(ResultSet r=s.executeQuery()) {if(r.next())throw new ApiException(409,"APPLICATION_CONFLICT","A conflicting pending or approved application requires investigation");}
  }
  try(PreparedStatement s=c.prepareStatement("SELECT hospital_id FROM SYSTEM.hospital WHERE UPPER(TRIM(name))=? OR (UPPER(TRIM(address))=? AND pincode=?) OR TRANSLATE(phone,'x +()-','x')=?")) {
   s.setString(1,name);s.setString(2,address);s.setString(3,(String)row.get("pincode"));s.setString(4,phone);
   try(ResultSet r=s.executeQuery()) {if(r.next())throw new ApiException(409,"HOSPITAL_CONFLICT","A possible existing hospital match requires investigation");}
  }
 }
 private interface Work<T> {T run(Connection c) throws SQLException;}
 private <T>T operation(long adminId,boolean decision,Work<T> work) throws SQLException {
  validId(adminId);
  try(Connection c=DatabaseConnection.getConnection()) {
   c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
   try {
    // Lock the administrator during decisions so a concurrent role/status change
    // cannot occur between authorization and the decision's commit.
    try(PreparedStatement s=c.prepareStatement("SELECT user_role,account_status FROM SYSTEM.users WHERE user_id=?"+(decision?" FOR UPDATE WAIT 5":""))) {
     s.setLong(1,adminId);try(ResultSet r=s.executeQuery()) {
      if(!r.next()||!"ACTIVE".equals(r.getString("account_status")))throw new ApiException(401,"SESSION_INVALID","Sign in with an active administrator account");
      if(!"ADMIN".equals(r.getString("user_role")))throw new ApiException(403,"ADMIN_REQUIRED","Administrator access is required");
     }
    }
    T result=work.run(c);
    if(decision)c.commit();else c.rollback();
    return result;
   } catch(SQLException|RuntimeException e) {
    Data.rollback(c,e);
    if(e instanceof SQLException) {
     int code=((SQLException)e).getErrorCode();
     if(code==1)throw new ApiException(409,"REGISTRATION_CONFLICT","The decision conflicts with existing data; refresh and investigate before retrying");
     if(code==54||code==60||code==30006)throw busy();
    }
    throw e;
   }
  }
 }
 private static Map<String,Object> application(Connection c,long id,boolean lock) throws SQLException {
  try(PreparedStatement s=c.prepareStatement(FULL_SELECT+(lock?" FOR UPDATE WAIT 5":""))) {
   s.setLong(1,id);try(ResultSet r=s.executeQuery()) {
    if(!r.next())throw new ApiException(404,"APPLICATION_NOT_FOUND","Hospital application not found");
    Map<String,Object> row=new LinkedHashMap<>();for(String col:COLUMNS)row.put(col,value(r,col));return row;
   }
  }
 }
 private static Object value(ResultSet r,String col) throws SQLException {
  String raw=r.getString(col);if(raw==null)return null;
  if(col.equals("registration_request_id")||col.equals("approved_hospital_id"))return Long.parseLong(raw);
  if(col.equals("latitude")||col.equals("longitude"))return new BigDecimal(raw).stripTrailingZeros();
  return raw;
 }
 private String token(Map<String,Object> row,long adminId,String sessionBinding) {
  try {
   Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));
   return HexFormat.of().formatHex(mac.doFinal(JsonUtil.toJson(Data.map("admin_id",adminId,"session",sessionBinding,"application",row)).getBytes(StandardCharsets.UTF_8)));
  } catch(GeneralSecurityException e) {throw new IllegalStateException("Review token signing unavailable",e);}
 }
 private void fresh(Map<String,Object> row,long adminId,String sessionBinding,String supplied) {
  if(!MessageDigest.isEqual(token(row,adminId,sessionBinding).getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))
   throw new ApiException(409,"REVIEW_STALE","The review is stale or belongs to another session; reload the application");
 }
 private static void pending(Map<String,Object> row) {
  if(!"PENDING".equals(row.get("status"))||row.get("approved_hospital_id")!=null)throw alreadyReviewed();
 }
 private static ApiException alreadyReviewed() {return new ApiException(409,"ALREADY_REVIEWED","Only a pending, unapproved application can be reviewed");}
 private static ApiException busy() {return new ApiException(409,"REVIEW_BUSY","The review is busy; reload and try again later");}
 private static ApiException invalid(String field) {return new ApiException(400,"INVALID_FIELD","Invalid "+field);}
 private static void validId(long id) {if(id<1||id>9999999999L)throw invalid("ID");}
 private static void binding(String binding) {if(binding==null||binding.isEmpty()||binding.length()>128)throw new IllegalArgumentException("Server session binding required");}
 private static void fields(Map<String,Object> body,Set<String> fields) {if(body==null||!fields.equals(body.keySet()))throw invalid("review fields (missing or unsupported)");}
 private static String reviewToken(Map<String,Object> body) {
  Object value=body.get("review_token");if(!(value instanceof String)||!((String)value).matches("[0-9a-f]{64}"))throw invalid("review_token");return (String)value;
 }
 private static void confirmation(Map<String,Object> body,String expected) {if(!expected.equals(body.get("confirmation")))throw invalid("confirmation");}
 private static String text(Map<String,Object> body,String field,int maxBytes) {
  Object value=body.get(field);if(!(value instanceof String))throw invalid(field);return bounded((String)value,field,maxBytes);
 }
 private static String bounded(String value,String field,int maxBytes) {
  String text=value.strip();
  if(text.isEmpty()||text.codePoints().noneMatch(cp->!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))
    ||text.codePoints().anyMatch(cp->Character.isISOControl(cp)||(cp>=0xD800&&cp<=0xDFFF))
    ||text.getBytes(StandardCharsets.UTF_8).length>maxBytes)throw invalid(field);
  return text;
 }
 private static void requireDemo() {
  WebAdminPolicy policy=WebAdminPolicy.fromEnvironment();
  if(!policy.enabled())throw new ApiException(403,"ADMIN_UNAVAILABLE","Local administrator mode is required");
  try {policy.validateBinding(System.getenv().getOrDefault("BIND_ADDRESS","127.0.0.1"));}
  catch(IllegalStateException e) {throw new ApiException(403,"ADMIN_UNAVAILABLE","Local administrator mode requires a loopback binding");}
 }
 private static void decimal(PreparedStatement s,int index,Object value) throws SQLException {
  if(value==null)s.setNull(index,Types.NUMERIC);else s.setBigDecimal(index,(BigDecimal)value);
 }
}
