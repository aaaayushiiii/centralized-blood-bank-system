package com.bloodbank.dao;
import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.*;
import java.sql.*;
import java.util.*;
public final class HospitalDao {
 public List<Map<String,Object>> getAllHospitals(Long staffHospital) throws SQLException {
  List<Map<String,Object>> rows=new ArrayList<>();
  try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("SELECT hospital_id,name,address,phone,latitude,longitude FROM hospital WHERE is_active='Y' AND (? IS NULL OR hospital_id=?) ORDER BY name")) {
   if(staffHospital==null) {s.setNull(1,Types.NUMERIC);s.setNull(2,Types.NUMERIC);} else {s.setLong(1,staffHospital);s.setLong(2,staffHospital);}
   try(ResultSet r=s.executeQuery()) {while(r.next()) rows.add(Data.hospital(r));}
  } return rows;
 }
 public List<Map<String,Object>> nearby(String pincode,Double lat,Double lon,double radius,String recipient) throws SQLException {
  if(!Double.isFinite(radius) || radius<=0 || radius>500) throw new ApiException(400,"INVALID_RADIUS","Radius must be between 0 and 500 km");
  try(Connection c=DatabaseConnection.getConnection()) {
   if(lat==null || lon==null) {
    if(pincode==null || !pincode.matches("[1-9][0-9]{5}")) throw new ApiException(400,"INVALID_PINCODE","A six-digit pincode or coordinates are required");
    try(PreparedStatement s=c.prepareStatement("SELECT AVG(latitude),AVG(longitude) FROM hospital WHERE pincode=? AND is_active='Y' AND latitude IS NOT NULL AND longitude IS NOT NULL")) {
     s.setString(1,pincode);try(ResultSet r=s.executeQuery()) {
      r.next();if(r.getObject(1)==null) throw new ApiException(422,"LOCATION_UNKNOWN","No hospital coordinates are available for this pincode; supply latitude and longitude");
      lat=r.getDouble(1);lon=r.getDouble(2);
     }
    }
   }
   if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180) throw new ApiException(400,"INVALID_LOCATION","Coordinates are outside their allowed range");
   Map<Long,Map<String,Object>> rows=new LinkedHashMap<>();
   try(CallableStatement s=c.prepareCall("{call PRC_NEARBY_HOSPITALS(?,?,?,?,?)}")) {
    s.setDouble(1,lat);s.setDouble(2,lon);s.setDouble(3,radius);
    if(recipient==null) s.setNull(4,Types.VARCHAR);else s.setString(4,recipient);
    s.registerOutParameter(5,-10); // OracleTypes.CURSOR without a compile-time driver dependency.
    s.execute();try(ResultSet r=(ResultSet)s.getObject(5)) {
     while(r.next()) {
      long id=r.getLong("hospital_id");Map<String,Object> row=rows.get(id);
      if(row==null) {
       Map<String,Object> h=Data.map("hospital_id",id,"name",r.getString("hospital_name"),"address",r.getString("address"),"phone",null,"latitude",r.getBigDecimal("latitude"),"longitude",r.getBigDecimal("longitude"));
       row=Data.map("hospital",h,"distance_km",r.getBigDecimal("distance_km"),"available_units",0);rows.put(id,row);
      }
      row.put("available_units",((Number)row.get("available_units")).intValue()+r.getInt("available_units"));
     }
    }
   }
   // The approved procedure omits phone; fetch it from HOSPITAL, never fabricate it.
   try(PreparedStatement s=c.prepareStatement("SELECT phone FROM hospital WHERE hospital_id=?")) {
    for(Map.Entry<Long,Map<String,Object>> e:rows.entrySet()) {
     s.setLong(1,e.getKey());try(ResultSet r=s.executeQuery()) {if(r.next()) {
      @SuppressWarnings("unchecked") Map<String,Object> h=(Map<String,Object>)e.getValue().get("hospital");h.put("phone",r.getString(1));
     }}
    }
   }
   return new ArrayList<>(rows.values());
  }
 }
}
