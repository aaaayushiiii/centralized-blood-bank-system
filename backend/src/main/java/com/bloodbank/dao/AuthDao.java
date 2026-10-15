package com.bloodbank.dao;
import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.*;
import java.sql.*;
import java.util.*;
public final class AuthDao {
 private static final String DUMMY_HASH=Passwords.hash("Non-account timing equalization "+UUID.randomUUID());
 public static Map<String,Object> current(Connection c,long userId) throws SQLException {
  try(PreparedStatement s=c.prepareStatement("SELECT u.user_id,u.full_name,u.email,u.phone,u.user_role,u.account_status,hs.hospital_id,h.name AS hospital_name FROM users u LEFT JOIN hospital_staff hs ON hs.user_id=u.user_id LEFT JOIN hospital h ON h.hospital_id=hs.hospital_id AND h.is_active='Y' WHERE u.user_id=?")) {
   s.setLong(1,userId);try(ResultSet r=s.executeQuery()) {
    if(!r.next() || !"ACTIVE".equals(r.getString("account_status"))) throw new ApiException(401,"SESSION_INVALID","Sign in with an active account");
    Object hospital=r.getObject("hospital_id");
    if("HOSPITAL_STAFF".equals(r.getString("user_role")) && (hospital==null || r.getString("hospital_name")==null)) throw new ApiException(403,"ASSIGNMENT_REQUIRED","An active hospital assignment is required");
    return Data.map("user_id",r.getLong("user_id"),"full_name",r.getString("full_name"),"email",r.getString("email"),"phone",r.getString("phone"),"role",r.getString("user_role"),"status",r.getString("account_status"),"hospital_id",hospital==null?null:r.getLong("hospital_id"),"hospital_name",r.getString("hospital_name"));
   }
  }
 }
 public Map<String,Object> current(long id) throws SQLException {
  try(Connection c=DatabaseConnection.getConnection()) {return current(c,id);}
 }
 public Map<String,Object> authenticate(String email,String password,String role) throws SQLException {
  try(Connection c=DatabaseConnection.getConnection()) {
   c.setAutoCommit(false);
   try {
    long id=0;String hash=DUMMY_HASH,status="",actualRole="";
    try(PreparedStatement s=c.prepareStatement("SELECT user_id,password_hash,account_status,user_role FROM users WHERE email=? FOR UPDATE")) {
     s.setString(1,email.toLowerCase(Locale.ROOT).trim());try(ResultSet r=s.executeQuery()) {if(r.next()) {id=r.getLong(1);hash=r.getString(2);status=r.getString(3);actualRole=r.getString(4);}}
    }
    boolean verified=Passwords.verify(password,hash);
    if(!verified || !"ACTIVE".equals(status) || !actualRole.equals(role)) {
     if(id!=0) try(PreparedStatement s=c.prepareStatement("UPDATE users SET failed_login_count=LEAST(failed_login_count+1,999),account_status=CASE WHEN account_status='ACTIVE' AND failed_login_count>=4 THEN 'LOCKED' ELSE account_status END WHERE user_id=?")) {s.setLong(1,id);s.executeUpdate();}
     c.commit();throw new ApiException(401,"INVALID_CREDENTIALS","Invalid credentials or account unavailable");
    }
    Map<String,Object> user=current(c,id);
    try(PreparedStatement s=c.prepareStatement("UPDATE users SET failed_login_count=0,last_login_at=SYSTIMESTAMP WHERE user_id=?")) {s.setLong(1,id);s.executeUpdate();}
    c.commit();return user;
   } catch(SQLException|RuntimeException e) {Data.rollback(c,e);throw e;}
  }
 }
 public void register(Map<String,Object> body,boolean staff) throws SQLException {
  String name=Data.text(body,"full_name",100), email=Data.text(body,"email",254).toLowerCase(Locale.ROOT);
  Object pw=body.get("password");
  if(!(pw instanceof String)||((String)pw).length()<12||((String)pw).length()>128) throw new ApiException(400,"INVALID_PASSWORD","Use a password between 12 and 128 characters");
  String password=(String)pw;
  if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new ApiException(400,"INVALID_EMAIL","Invalid email address");
  String phone=body.get("phone")==null?null:Data.text(body,"phone",15);
  if(phone!=null && !phone.matches("[+]?[0-9 ()-]{7,15}")) throw new ApiException(400,"INVALID_PHONE","Invalid phone number");
  String hash=Passwords.hash(password);
  try(Connection c=DatabaseConnection.getConnection()) {
   c.setAutoCommit(false);
   try {
    Long hospital=staff?Data.id(body.get("hospital_id")):null;
    if(staff) try(PreparedStatement s=c.prepareStatement("SELECT hospital_id FROM hospital WHERE hospital_id=? AND is_active='Y'")) {s.setLong(1,hospital);try(ResultSet r=s.executeQuery()) {if(!r.next())throw new ApiException(422,"INVALID_HOSPITAL","Select an active hospital");}}
    long id=Data.next(c,"seq_users");
    try(PreparedStatement s=c.prepareStatement("INSERT INTO users(user_id,full_name,email,phone,password_hash,user_role,account_status) VALUES(?,?,?,?,?,?,?)")) {
     s.setLong(1,id);s.setString(2,name);s.setString(3,email);s.setString(4,phone);s.setString(5,hash);s.setString(6,staff?"HOSPITAL_STAFF":"CITIZEN");s.setString(7,staff?"PENDING_VERIFICATION":"ACTIVE");s.executeUpdate();
    }
    if(staff) try(PreparedStatement s=c.prepareStatement("INSERT INTO hospital_staff(staff_id,user_id,hospital_id,designation) VALUES(seq_hospital_staff.NEXTVAL,?,?,?)")) {s.setLong(1,id);s.setLong(2,hospital);s.setString(3,"Pending staff access request");s.executeUpdate();}
    c.commit();
   } catch(SQLException|RuntimeException e) {Data.rollback(c,e);throw e;}
  }
 }
}
