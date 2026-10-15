package com.bloodbank.dao;
import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.*;
import java.sql.*;
import java.util.*;
public final class InventoryDao {
 public Map<String,Object> inventory(long hospitalId) throws SQLException {
  try(Connection c=DatabaseConnection.getConnection()) {
   List<Map<String,Object>> bags=new ArrayList<>();String today;
   try(PreparedStatement s=c.prepareStatement("SELECT TO_CHAR(TRUNC(SYSDATE),'YYYY-MM-DD') FROM dual");ResultSet r=s.executeQuery()) {r.next();today=r.getString(1);}
   try(PreparedStatement s=c.prepareStatement("SELECT bag_id,blood_group,expiry_date,CASE WHEN status='AVAILABLE' AND expiry_date<=TRUNC(SYSDATE) THEN 'EXPIRED' ELSE status END AS effective_status FROM blood_bags WHERE hospital_id=? ORDER BY expiry_date,collection_date,bag_id")) {
    s.setLong(1,hospitalId);try(ResultSet r=s.executeQuery()) {while(r.next()) {
     String g=r.getString("blood_group"),status=r.getString("effective_status");
     bags.add(Data.map("bag_id",r.getLong("bag_id"),"blood_group",g.substring(0,g.length()-1),"rh_factor",g.substring(g.length()-1),"expiry_date",Data.date(r,"expiry_date"),"bag_status",status.substring(0,1)+status.substring(1).toLowerCase(Locale.ROOT)));
    }}
   }
   return Data.map("as_of_date",today,"near_expiry_days",7,"bags",bags);
  }
 }
}
