package com.bloodbank.util;
import java.sql.*;
import java.util.*;
public final class Data {
 private Data() {}
 public static Map<String,Object> map(Object... kv) {
  Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<kv.length;i+=2) m.put((String)kv[i],kv[i+1]); return m;
 }
 public static String text(Map<String,?> m,String key,int max) {
  Object v=m.get(key); if(!(v instanceof String) || ((String)v).trim().isEmpty() || ((String)v).trim().length()>max)
   throw new ApiException(400,"INVALID_FIELD","Invalid "+key);
  return ((String)v).trim();
 }
 public static long id(Object v) {
  try { String s=String.valueOf(v); if(!s.matches("[1-9][0-9]{0,9}")) throw new NumberFormatException(); return Long.parseLong(s); }
  catch(NumberFormatException e) { throw new ApiException(400,"INVALID_ID","Expected a positive numeric ID"); }
 }
 public static int integer(Object v,int min,int max,String name) {
  try { int n=new java.math.BigDecimal(String.valueOf(v)).intValueExact(); if(n<min || n>max) throw new ArithmeticException(); return n; }
  catch(Exception e) { throw new ApiException(400,"INVALID_FIELD","Invalid "+name); }
 }
 public static String group(String group,String rh) {
  String s=group==null?"":group.toUpperCase(Locale.ROOT).trim();
  if(rh!=null) s+=rh.trim();
  if(!Arrays.asList("A+","A-","B+","B-","AB+","AB-","O+","O-").contains(s))
   throw new ApiException(400,"INVALID_BLOOD_GROUP","Select a valid blood group and Rh factor");
  return s;
 }
 public static String priority(String v) {
  if(v==null) throw new ApiException(400,"INVALID_PRIORITY","Priority is required");
  switch(v.toUpperCase(Locale.ROOT).trim()) {
   case "CRITICAL":return "CRITICAL";
   case "URGENT":case "HIGH":return "HIGH";
   case "ROUTINE":case "NORMAL":return "NORMAL";
   default:throw new ApiException(400,"INVALID_PRIORITY","Unknown priority");
  }
 }
 public static long next(Connection c,String sequence) throws SQLException {
  // Sequence names are server constants, never request data.
  if(!Arrays.asList("seq_users","seq_hospital_staff","seq_donor","seq_request").contains(sequence)) throw new IllegalArgumentException();
  try(PreparedStatement s=c.prepareStatement("SELECT "+sequence+".NEXTVAL FROM dual");ResultSet r=s.executeQuery()) {r.next();return r.getLong(1);}
 }
 public static void rollback(Connection c,Throwable original) {
  try {c.rollback();} catch(SQLException e) {original.addSuppressed(e);}
 }
 public static String date(ResultSet r,String col) throws SQLException {java.sql.Date d=r.getDate(col);return d==null?null:d.toString();}
 public static Map<String,Object> hospital(ResultSet r) throws SQLException {
  return map("hospital_id",r.getLong("hospital_id"),"name",r.getString("name"),"address",r.getString("address"),"phone",r.getString("phone"),"latitude",r.getBigDecimal("latitude"),"longitude",r.getBigDecimal("longitude"));
 }
 public static long staffHospital(Map<String,Object> user) {
  if(!"HOSPITAL_STAFF".equals(user.get("role")) || user.get("hospital_id")==null)
   throw new ApiException(403,"STAFF_REQUIRED","Approved hospital staff access is required");
  return ((Number)user.get("hospital_id")).longValue();
 }
}
