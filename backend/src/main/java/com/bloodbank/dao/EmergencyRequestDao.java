package com.bloodbank.dao;
import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.*;
import java.sql.*;
import java.util.*;
public final class EmergencyRequestDao {
 public Map<String,Object> create(Map<String,Object> body,long userId) throws SQLException {
  String group=Data.group(Data.text(body,"blood_group",2),Data.text(body,"rh_factor",1));
  String priority=Data.priority(Data.text(body,"priority",20));int units=Data.integer(body.get("units_requested"),1,999,"units_requested");
  try(Connection c=DatabaseConnection.getConnection()) {
   c.setAutoCommit(false);
   try {
    Map<String,Object> user=AuthDao.current(c,userId);long hospital=Data.staffHospital(user);
    // React supplies no patient identity: this is explicitly a hospital stock requirement.
    long id=Data.next(c,"seq_request");
    try(PreparedStatement s=c.prepareStatement("INSERT INTO request(request_id,requester_user_id,patient_name,contact_phone,blood_group,units_required,priority,hospital_id,status,remarks) VALUES(?,?,?,?,?,?,?,?,'PENDING',?)")) {
     s.setLong(1,id);s.setLong(2,userId);s.setString(3,"Hospital stock request");s.setString(4,(String)user.get("phone"));s.setString(5,group);s.setInt(6,units);s.setString(7,priority);s.setLong(8,hospital);s.setString(9,"Hospital stock requirement; patient identity not collected by React.");s.executeUpdate();
    }
    Map<String,Object> result=Data.map("request",request(c,id,user));c.commit();return result;
   } catch(SQLException|RuntimeException e) {Data.rollback(c,e);throw e;}
  }
 }
 public Map<String,Object> allocations(long id,long userId,boolean dispatch) throws SQLException {
  try(Connection c=DatabaseConnection.getConnection()) {
   c.setAutoCommit(false);
   try {
    Map<String,Object> user=AuthDao.current(c,userId);request(c,id,user);
    String outcome=null,message=null;
    if(dispatch) {
     long hospital=Data.staffHospital(user);
     try(CallableStatement s=c.prepareCall("{call PRC_EMERGENCY_DISPATCH(?,?,?,?,?,?,?,?)}")) {
      s.setLong(1,id);s.setString(2,"USER:"+userId);s.setLong(3,hospital);s.setString(4,"Y");
      s.registerOutParameter(5,Types.VARCHAR);s.registerOutParameter(6,Types.NUMERIC);s.registerOutParameter(7,Types.NUMERIC);s.registerOutParameter(8,Types.VARCHAR);
      s.execute();outcome=s.getString(5);message=s.getString(8);
      if(!Arrays.asList("SUCCESS","PARTIAL","INSUFFICIENT_STOCK").contains(outcome))
       throw new ApiException(outcomeStatus(outcome),"DISPATCH_"+outcome,"Dispatch refused: "+outcome);
     }
    }
    List<Map<String,Object>> allocations=new ArrayList<>();
    try(PreparedStatement s=c.prepareStatement("SELECT allocation_id,bag_id,bag_code,blood_group,expiry_date,allocation_status,bag_status FROM v_request_allocation_detail WHERE request_id=? ORDER BY allocation_id")) {
     s.setLong(1,id);try(ResultSet r=s.executeQuery()) {while(r.next()) allocations.add(Data.map("allocation_id",r.getLong("allocation_id"),"bag_id",r.getLong("bag_id"),"bag_code",r.getString("bag_code"),"blood_group",r.getString("blood_group"),"expiry_date",Data.date(r,"expiry_date"),"allocation_status",r.getString("allocation_status"),"bag_status",r.getString("bag_status")));}
    }
    Map<String,Object> result=Data.map("request",request(c,id,user),"allocations",allocations);
    if(dispatch) {result.put("outcome",outcome);result.put("message",message);}
    c.commit();return result;
   } catch(SQLException|RuntimeException e) {Data.rollback(c,e);throw e;}
  }
 }
 public static int outcomeStatus(String outcome) {
  if("NOT_FOUND".equals(outcome))return 404;
  if("NOT_AUTHORIZED".equals(outcome))return 403;
  if("NOT_ELIGIBLE".equals(outcome)||"BUSY".equals(outcome))return 409;
  if(Arrays.asList("INVALID_QUANTITY","INVALID_HOSPITAL","INVALID_PARAMETER").contains(outcome))return 422;
  return 500;
 }
 private Map<String,Object> request(Connection c,long id,Map<String,Object> user) throws SQLException {
  boolean staff="HOSPITAL_STAFF".equals(user.get("role"));
  try(PreparedStatement s=c.prepareStatement("SELECT request_id,hospital_id,blood_group,units_required,units_allocated,status,priority FROM request WHERE request_id=? AND "+(staff?"hospital_id=?":"requester_user_id=?"))) {
   s.setLong(1,id);s.setLong(2,staff?Data.staffHospital(user):((Number)user.get("user_id")).longValue());
   try(ResultSet r=s.executeQuery()) {
    if(!r.next()) throw new ApiException(404,"REQUEST_NOT_FOUND","Request not found or inaccessible");
    String group=r.getString("blood_group");return Data.map("request_id",r.getLong("request_id"),"hospital_id",r.getLong("hospital_id"),"blood_group",group.substring(0,group.length()-1),"rh_factor",group.substring(group.length()-1),"units_requested",r.getInt("units_required"),"units_allocated",r.getInt("units_allocated"),"request_status",r.getString("status"),"priority",r.getString("priority"));
   }
  }
 }
}
