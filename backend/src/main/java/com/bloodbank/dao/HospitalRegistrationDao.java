package com.bloodbank.dao;

import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Submission only: never creates a hospital, staff assignment, account or session. */
public final class HospitalRegistrationDao {
 private static final Set<String> FIELDS=Set.of(
   "hospital_name","address","district","pincode","latitude","longitude",
   "official_phone","official_email","registration_authority","jurisdiction_code","registration_number",
   "representative_name","representative_title","representative_email","representative_phone");
 private static final String INSERT="INSERT INTO hospital_registration_request "
   +"(registration_request_id,hospital_name,address,district,pincode,latitude,longitude,official_phone,official_email,"
   +"registration_authority,jurisdiction_code,registration_number,representative_name,representative_title,"
   +"representative_email,representative_phone,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')";

 public Map<String,Object> submit(Map<String,Object> body) throws SQLException {
  // An allowlist rejects privileged fields (including explicit null values) and typos.
  if(!FIELDS.containsAll(body.keySet()))
   throw new ApiException(400,"INVALID_FIELD","Only hospital application submission fields are accepted");
  String name=text(body,"hospital_name",150),address=text(body,"address",300),district=text(body,"district",60);
  String pincode=text(body,"pincode",6);
  if(!pincode.matches("[1-9][0-9]{5}"))throw invalid("pincode");
  BigDecimal latitude=coordinate(body,"latitude",90),longitude=coordinate(body,"longitude",180);
  if((latitude==null)!=(longitude==null))throw invalid("latitude and longitude");
  String phone=phone(body,"official_phone"),email=email(body,"official_email");
  String authority=code(body,"registration_authority",80),jurisdiction=code(body,"jurisdiction_code",30);
  String number=text(body,"registration_number",100);
  if(!number.matches("[A-Za-z0-9 ./-]+"))throw invalid("registration_number");
  // Exactly the migration's UPPER(TRANSLATE(number, 'x -/.', 'x')) for allowed ASCII input.
  number=number.toUpperCase(Locale.ROOT).replace(" ","").replace("-","").replace("/","").replace(".","");
  if(number.isEmpty())throw invalid("registration_number");
  String repName=text(body,"representative_name",100),title=text(body,"representative_title",80);
  String repEmail=email(body,"representative_email"),repPhone=phone(body,"representative_phone");

  try(Connection c=DatabaseConnection.getConnection()) {
   c.setAutoCommit(false);
   try {
    long id;
    try(PreparedStatement s=c.prepareStatement("SELECT seq_hosp_reg_request.NEXTVAL FROM dual");ResultSet r=s.executeQuery()) {
     if(!r.next())throw new SQLException("Application sequence returned no value");
     id=r.getLong(1);
     if(id<1 || id>9999999999L)throw new SQLException("Application sequence outside ID range");
    }
    try(PreparedStatement s=c.prepareStatement(INSERT)) {
     s.setLong(1,id);s.setString(2,name);s.setString(3,address);s.setString(4,district);s.setString(5,pincode);
     decimal(s,6,latitude);decimal(s,7,longitude);
     s.setString(8,phone);s.setString(9,email);s.setString(10,authority);s.setString(11,jurisdiction);s.setString(12,number);
     s.setString(13,repName);s.setString(14,title);s.setString(15,repEmail);s.setString(16,repPhone);
     if(s.executeUpdate()!=1)throw new SQLException("Application insert did not affect one row");
    }
    c.commit();
    return Data.map("registration_request_id",id,"status","PENDING");
   } catch(SQLException|RuntimeException e) {
    Data.rollback(c,e);
    // Never return SQL text, constraint names, existing IDs, contacts or applicant data.
    if(e instanceof SQLException && ((SQLException)e).getErrorCode()==1)
     throw new ApiException(409,"REGISTRATION_CONFLICT","The application conflicts with an existing record. Contact the network administrator if assistance is needed.");
    throw e;
   }
  }
 }

 private static ApiException invalid(String field) {
  return new ApiException(400,"INVALID_FIELD","Invalid "+field);
 }
 private static String text(Map<String,Object> body,String field,int maxBytes) {
  Object value=body.get(field);
  if(!(value instanceof String))throw invalid(field);
  String text=((String)value).strip();
  if(text.isEmpty() || text.codePoints().noneMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c))
     || text.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0xD800&&c<=0xDFFF))
     || text.getBytes(StandardCharsets.UTF_8).length>maxBytes)throw invalid(field);
  return text;
 }
 private static String email(Map<String,Object> body,String field) {
  String value=text(body,field,254).toLowerCase(Locale.ROOT);
  if(value.getBytes(StandardCharsets.UTF_8).length>254
     || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
     || value.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)))throw invalid(field);
  return value;
 }
 private static String phone(Map<String,Object> body,String field) {
  String value=text(body,field,20);
  int digits=value.replaceAll("[^0-9]","").length();
  if(!value.matches("[+]?[0-9 ()-]{7,20}") || digits<7 || digits>15)throw invalid(field);
  return value;
 }
 private static String code(Map<String,Object> body,String field,int maxBytes) {
  String value=text(body,field,maxBytes);
  if(!value.matches("[A-Za-z0-9_]+"))throw invalid(field);
  return value.toUpperCase(Locale.ROOT);
 }
 private static BigDecimal coordinate(Map<String,Object> body,String field,int bound) {
  Object value=body.get(field);
  if(value==null)return null;
  if(!(value instanceof Number))throw invalid(field);
  try {
   BigDecimal number=new BigDecimal(value.toString()).stripTrailingZeros();
   if(number.abs().compareTo(BigDecimal.valueOf(bound))>0 || number.scale()>6)throw invalid(field);
   return number;
  } catch(NumberFormatException e) {throw invalid(field);}
 }
 private static void decimal(PreparedStatement s,int index,BigDecimal value) throws SQLException {
  if(value==null)s.setNull(index,Types.NUMERIC);else s.setBigDecimal(index,value);
 }
}
