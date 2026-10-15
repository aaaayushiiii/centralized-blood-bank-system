package com.bloodbank.dao;
import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
public final class DonorDao {
 public Map<String,Object> register(Map<String,Object> body) throws SQLException {
  String name=Data.text(body,"full_name",100),phone=Data.text(body,"contact_number",15);
  String group=Data.group(Data.text(body,"blood_group",2),Data.text(body,"rh_factor",1));
  Data.integer(body.get("age"),18,65,"age"); Data.text(body,"gender",20);
  if(!phone.matches("[+]?[0-9 ()-]{7,15}")) throw new ApiException(400,"INVALID_PHONE","Invalid contact number");
  LocalDate last=null;
  if(body.get("last_donation_date")!=null) {
   try {last=LocalDate.parse(Data.text(body,"last_donation_date",10));}
   catch(java.time.format.DateTimeParseException e) {throw new ApiException(400,"INVALID_DATE","Invalid last donation date");}
   if(last.isAfter(LocalDate.now())) throw new ApiException(400,"INVALID_DATE","Last donation date cannot be in the future");
  }
  try(Connection c=DatabaseConnection.getConnection()) {
   c.setAutoCommit(false);
   try {
    long id=Data.next(c,"seq_donor");
    try(PreparedStatement s=c.prepareStatement("INSERT INTO donor(donor_id,donor_code,full_name,blood_group,phone,last_donation_date) VALUES(?,?,?,?,?,?)")) {
     s.setLong(1,id);s.setString(2,"DONOR-"+id);s.setString(3,name);s.setString(4,group);s.setString(5,phone);
     if(last==null)s.setNull(6,Types.DATE);else s.setDate(6,java.sql.Date.valueOf(last));s.executeUpdate();
    }
    c.commit();return Data.map("donor",Data.map("donor_id",id));
   } catch(SQLException|RuntimeException e) {Data.rollback(c,e);throw e;}
  }
 }
}
