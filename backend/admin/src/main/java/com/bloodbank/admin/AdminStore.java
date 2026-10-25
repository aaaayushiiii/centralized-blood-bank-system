package com.bloodbank.admin;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Isolated CLI persistence. Not compiled or packaged with the public backend. */
final class AdminStore {
 interface Connections { Connection open() throws SQLException; }
 static final class Refused extends RuntimeException { Refused(String message) {super(message);} }
 record Review(String authority,String jurisdiction,String number,String representativeEmail,
               String registryEvidence,String representativeEvidence,String legacyEvidence) {
  String notes() {
   return text("Registry: "+text(registryEvidence,600)+" | Representative: "+text(representativeEvidence,600)
     +" | Legacy hospital review: "+text(legacyEvidence,600),2000);
  }
 }
 static final String[] COLUMNS={"registration_request_id","hospital_name","address","district","pincode",
   "latitude","longitude","official_phone","official_email","registration_authority","jurisdiction_code",
   "registration_number","registration_key","representative_name","representative_title","representative_email",
   "representative_phone","status","submitted_at","reviewed_at","reviewed_by","verification_notes",
   "rejection_reason","approved_hospital_id"};
 static final String SELECT="SELECT "+String.join(",",COLUMNS)+" FROM SYSTEM.hospital_registration_request WHERE registration_request_id=?";
 // Reserve this numeric lock ID exclusively for this tool in this database.
 // REQUEST does not allocate a named lock or commit; rollback/commit releases it.
 static final String LOCK="BEGIN ? := SYS.DBMS_LOCK.REQUEST(id => 731908231, lockmode => 6, timeout => 5, release_on_commit => TRUE); END;";
 private final Connections connections;
 AdminStore(Connections connections) {this.connections=connections;}

 private static String authorize(Connection c) throws SQLException {
  String reviewer;
  try(PreparedStatement s=c.prepareStatement("SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS reviewer, SYS_CONTEXT('USERENV','AUTHENTICATED_IDENTITY') AS identity, SYS_CONTEXT('USERENV','PROXY_USER') AS proxy_user, SYS_CONTEXT('USERENV','ISDBA') AS is_dba, SYS_CONTEXT('USERENV','CON_NAME') AS container_name, SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS schema_name FROM dual");ResultSet r=s.executeQuery()) {
   if(!r.next())throw new Refused("Reviewer authorization unavailable");
   reviewer=r.getString("reviewer");
   if(reviewer==null || !reviewer.matches("BB_REVIEW_[A-Z0-9_]{1,20}")
     || !reviewer.equals(r.getString("identity")) || !reviewer.equals(r.getString("schema_name"))
     || r.getString("proxy_user")!=null || !"FALSE".equals(r.getString("is_dba"))
     || !"XEPDB1".equals(r.getString("container_name")))throw new Refused("A dedicated, direct reviewer login in XEPDB1 is required");
  }
  boolean role=false;
  try(PreparedStatement s=c.prepareStatement("SELECT role FROM session_roles");ResultSet r=s.executeQuery()) {
   while(r.next()) {
    if(!"BB_HOSPITAL_REVIEWER".equals(r.getString(1)))throw new Refused("Unexpected enabled role; least-privilege review required");
    role=true;
   }
  }
  if(!role)throw new Refused("Required reviewer role is not enabled");
  try(PreparedStatement s=c.prepareStatement("SELECT privilege FROM session_privs");ResultSet r=s.executeQuery()) {
   while(r.next())if(!"CREATE SESSION".equals(r.getString(1)))throw new Refused("Excessive system privileges; reviewer login refused");
  }
  return reviewer;
 }

 List<Map<String,String>> list(long afterId) throws SQLException {
  if(afterId<0 || afterId>9999999999L)throw new Refused("Invalid pagination ID");
  try(Connection c=connections.open()) {
   c.setAutoCommit(false);
   try {
    authorize(c);List<Map<String,String>> rows=new ArrayList<>();
    try(PreparedStatement s=c.prepareStatement("SELECT registration_request_id,hospital_name,registration_authority,jurisdiction_code,registration_key,submitted_at FROM SYSTEM.hospital_registration_request WHERE status='PENDING' AND registration_request_id>? ORDER BY registration_request_id FETCH FIRST 100 ROWS ONLY")) {
     s.setLong(1,afterId);try(ResultSet r=s.executeQuery()) {while(r.next()) {
      Map<String,String> row=new LinkedHashMap<>();
      for(String col:List.of("registration_request_id","hospital_name","registration_authority","jurisdiction_code","registration_key","submitted_at"))row.put(col,r.getString(col));
      rows.add(row);
     }}
    }
    c.rollback();return rows;
   } catch(SQLException|RuntimeException e) {rollback(c,e);throw e;}
  }
 }
 Map<String,String> view(long id) throws SQLException {
  validId(id);
  try(Connection c=connections.open()) {
   c.setAutoCommit(false);
   try {authorize(c);Map<String,String> row=application(c,id,false);c.rollback();return row;}
   catch(SQLException|RuntimeException e) {rollback(c,e);throw e;}
  }
 }
 long approve(long id,Map<String,String> viewed,Review review) throws SQLException {
  validId(id);String notes=review.notes();
  String authority=code(review.authority,80),jurisdiction=code(review.jurisdiction,30),number=number(review.number);
  String email=text(review.representativeEmail,254).toLowerCase(Locale.ROOT);
  try(Connection c=connections.open()) {
   c.setAutoCommit(false);
   try {
    String reviewer=authorize(c);Map<String,String> row=application(c,id,true);
    pendingAndUnchanged(row,viewed);
    if(!authority.equals(row.get("registration_authority")) || !jurisdiction.equals(row.get("jurisdiction_code"))
      || !number.equals(row.get("registration_key")) || !email.equals(row.get("representative_email")))
     throw new Refused("Independently verified identity or representative does not match the locked application");
    try(CallableStatement s=c.prepareCall(LOCK)) {
     s.registerOutParameter(1,Types.INTEGER);s.execute();
     if(s.getInt(1)!=0)throw new Refused("Approval lock unavailable; no decision made");
    }
    // All tool approvals hold the same lock through commit, including different application IDs.
    try(PreparedStatement s=c.prepareStatement("SELECT registration_request_id FROM SYSTEM.hospital_registration_request WHERE registration_request_id<>? AND status IN ('PENDING','APPROVED') AND registration_authority=? AND jurisdiction_code=? AND registration_key=?")) {
     s.setLong(1,id);s.setString(2,authority);s.setString(3,jurisdiction);s.setString(4,number);
     try(ResultSet r=s.executeQuery()) {if(r.next())throw new Refused("Conflicting official identity; approval refused");}
    }
    // Deliberately includes inactive hospitals and refuses possible legacy matches. No override.
    try(PreparedStatement s=c.prepareStatement("SELECT hospital_id FROM SYSTEM.hospital WHERE UPPER(TRIM(name))=? OR (UPPER(TRIM(address))=? AND pincode=?) OR TRANSLATE(phone,'x +()-','x')=?")) {
     s.setString(1,row.get("hospital_name").strip().toUpperCase(Locale.ROOT));
     s.setString(2,row.get("address").strip().toUpperCase(Locale.ROOT));s.setString(3,row.get("pincode"));
     s.setString(4,row.get("official_phone").replaceAll("[^0-9]",""));
     try(ResultSet r=s.executeQuery()) {if(r.next())throw new Refused("Possible existing hospital match; investigate independently before any new application decision");}
    }
    long hospitalId;
    try(PreparedStatement s=c.prepareStatement("SELECT SYSTEM.seq_hospital.NEXTVAL FROM dual");ResultSet r=s.executeQuery()) {
     if(!r.next())throw new SQLException("Hospital sequence unavailable");hospitalId=r.getLong(1);validId(hospitalId);
    }
    try(PreparedStatement s=c.prepareStatement("INSERT INTO SYSTEM.hospital(hospital_id,name,address,district,pincode,latitude,longitude,phone,is_active) VALUES(?,?,?,?,?,?,?,?,'Y')")) {
     s.setLong(1,hospitalId);s.setString(2,row.get("hospital_name"));s.setString(3,row.get("address"));s.setString(4,row.get("district"));s.setString(5,row.get("pincode"));
     decimal(s,6,row.get("latitude"));decimal(s,7,row.get("longitude"));s.setString(8,row.get("official_phone"));
     if(s.executeUpdate()!=1)throw new SQLException("Hospital insert failed");
    }
    try(PreparedStatement s=c.prepareStatement("UPDATE SYSTEM.hospital_registration_request SET status='APPROVED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,approved_hospital_id=? WHERE registration_request_id=? AND status='PENDING'")) {
     s.setString(1,reviewer);s.setString(2,notes);s.setLong(3,hospitalId);s.setLong(4,id);
     if(s.executeUpdate()!=1)throw new Refused("Application already decided; approval rolled back");
    }
    c.commit();return hospitalId;
   } catch(SQLException|RuntimeException e) {rollback(c,e);throw e;}
  }
 }
 void reject(long id,Map<String,String> viewed,String notes,String reason) throws SQLException {
  validId(id);notes=text(notes,2000);reason=text(reason,1000);
  try(Connection c=connections.open()) {
   c.setAutoCommit(false);
   try {
    String reviewer=authorize(c);Map<String,String> row=application(c,id,true);pendingAndUnchanged(row,viewed);
    try(PreparedStatement s=c.prepareStatement("UPDATE SYSTEM.hospital_registration_request SET status='REJECTED',reviewed_at=SYSTIMESTAMP,reviewed_by=?,verification_notes=?,rejection_reason=? WHERE registration_request_id=? AND status='PENDING'")) {
     s.setString(1,reviewer);s.setString(2,notes);s.setString(3,reason);s.setLong(4,id);
     if(s.executeUpdate()!=1)throw new Refused("Application already decided; rejection rolled back");
    }
    c.commit();
   } catch(SQLException|RuntimeException e) {rollback(c,e);throw e;}
  }
 }
 private static Map<String,String> application(Connection c,long id,boolean lock) throws SQLException {
  try(PreparedStatement s=c.prepareStatement(SELECT+(lock?" FOR UPDATE WAIT 5":""))) {
   s.setLong(1,id);try(ResultSet r=s.executeQuery()) {
    if(!r.next())throw new Refused("Application not found");
    Map<String,String> row=new LinkedHashMap<>();for(String col:COLUMNS)row.put(col,r.getString(col));
    return Collections.unmodifiableMap(row);
   }
  }
 }
 private static void pendingAndUnchanged(Map<String,String> row,Map<String,String> viewed) {
  if(!"PENDING".equals(row.get("status")))throw new Refused("Application is no longer pending");
  if(!row.equals(viewed))throw new Refused("Application changed since viewing; view and verify it again");
 }
 static String text(String value,int bytes) {
  if(value==null)throw new Refused("Required review input missing");
  String s=value.strip();
  if(s.isEmpty() || s.codePoints().noneMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c))
    || s.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0xD800&&c<=0xDFFF))
    || s.getBytes(StandardCharsets.UTF_8).length>bytes)throw new Refused("Review input is blank, invalid or exceeds its byte limit");
  return s;
 }
 private static String code(String value,int bytes) {
  String s=text(value,bytes);if(!s.matches("[A-Za-z0-9_]+"))throw new Refused("Invalid verified authority/jurisdiction code");return s.toUpperCase(Locale.ROOT);
 }
 private static String number(String value) {
  String s=text(value,100);if(!s.matches("[A-Za-z0-9 ./-]+"))throw new Refused("Invalid verified registration number");
  s=s.toUpperCase(Locale.ROOT).replace(" ","").replace("-","").replace("/","").replace(".","");
  if(s.isEmpty())throw new Refused("Verified registration number missing");return s;
 }
 static long id(String value) {
  if(value==null || !value.matches("[1-9][0-9]{0,9}"))throw new Refused("Expected a positive numeric ID");return Long.parseLong(value);
 }
 private static void validId(long id) {if(id<1 || id>9999999999L)throw new Refused("ID outside allowed range");}
 private static void decimal(PreparedStatement s,int index,String value) throws SQLException {
  if(value==null)s.setNull(index,Types.NUMERIC);else s.setBigDecimal(index,new java.math.BigDecimal(value));
 }
 private static void rollback(Connection c,Throwable e) {try {c.rollback();}catch(SQLException failure) {e.addSuppressed(failure);}}
}
