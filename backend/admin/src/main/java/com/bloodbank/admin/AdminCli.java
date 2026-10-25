package com.bloodbank.admin;

import java.io.Console;
import java.sql.*;
import java.util.*;

/** Passwords are read from a real console, never arguments, web environment or logs. */
public final class AdminCli {
 public static void main(String[] args) {
  Console console=System.console();char[] password=null;int exit=0;
  try {
   if(args.length<1 || args.length>2 || !Set.of("list","view","approve","reject").contains(args[0])
     || (!args[0].equals("list")&&args.length!=2))throw new AdminStore.Refused("Usage: list [after-id] | view ID | approve ID | reject ID");
   if(console==null)throw new AdminStore.Refused("Interactive console required; redirected/scripted approval is disabled");
   String url=System.getenv("ADMIN_ORACLE_URL"),user=System.getenv("ADMIN_ORACLE_USER");
   if(url==null || !url.matches("jdbc:oracle:thin:@//(localhost|127\\.0\\.0\\.1):[0-9]{1,5}/XEPDB1")
     || user==null || !user.matches("BB_REVIEW_[A-Z0-9_]{1,20}"))
    throw new AdminStore.Refused("Set separate ADMIN_ORACLE_URL (local XEPDB1) and ADMIN_ORACLE_USER (BB_REVIEW_...) configuration");
   Class.forName("oracle.jdbc.OracleDriver");
   password=console.readPassword("Reviewer database password: ");
   if(password==null || password.length==0)throw new AdminStore.Refused("Password required");
   final char[] secret=password;
   AdminStore store=new AdminStore(()-> {
    Properties props=new Properties();props.setProperty("user",user);props.setProperty("password",new String(secret));
    props.setProperty("oracle.net.CONNECT_TIMEOUT","10000");props.setProperty("oracle.jdbc.ReadTimeout","20000");
    try {return DriverManager.getConnection(url,props);}finally {props.clear();}
   });
   String command=args[0];
   if(command.equals("list")) {
    long after=args.length==2?AdminStore.id(args[1]):0;
    for(Map<String,String> row:store.list(after))print(console,row);
    console.printf("Up to 100 pending applications; pass the last ID to list the next page.%n");
   } else {
    long id=AdminStore.id(args[1]);Map<String,String> row=store.view(id);print(console,row);
    if(command.equals("approve")) {
     console.printf("Independently check the issuing registry, representative authority, and existing active/inactive hospitals.%nDo not use submitted contact details as the sole source of verification.%n");
     String authority=console.readLine("Verified canonical registration authority: ");
     String jurisdiction=console.readLine("Verified canonical jurisdiction: ");
     String number=console.readLine("Independently verified registration number: ");
     String email=console.readLine("Independently verified representative email: ");
     String registry=console.readLine("Registry verification source/reference and result (max 600 bytes): ");
     String representative=console.readLine("Representative authorization evidence/reference (max 600 bytes): ");
     String legacy=console.readLine("Independent legacy hospital duplicate review evidence (max 600 bytes): ");
     confirm(console,"VERIFIED REGISTRY AND REPRESENTATIVE; APPROVE "+id);
     long hospital=store.approve(id,row,new AdminStore.Review(authority,jurisdiction,number,email,registry,representative,legacy));
     console.printf("APPROVED application %d; active hospital %d. No staff account created.%n",id,hospital);
    } else if(command.equals("reject")) {
     String notes=console.readLine("Verification notes (max 2000 bytes): ");
     String reason=console.readLine("Rejection reason (max 1000 bytes): ");
     confirm(console,"REJECT "+id);store.reject(id,row,notes,reason);console.printf("REJECTED application %d.%n",id);
    }
   }
  } catch(AdminStore.Refused e) {System.err.println("REFUSED: "+e.getMessage());exit=1;}
  catch(SQLException e) {System.err.println("Database operation failed; no success confirmed. Oracle code="+e.getErrorCode()+". Check status before retrying; do not expose connection details.");exit=1;}
  catch(Exception e) {System.err.println("Administrator tool failed; no success confirmed. Check local configuration and application status.");exit=1;}
  finally {if(password!=null)Arrays.fill(password,'\0');}
  if(exit!=0)System.exit(exit);
 }
 private static void confirm(Console console,String phrase) {
  if(!phrase.equals(console.readLine("Type exactly '%s': ",phrase)))throw new AdminStore.Refused("Decision cancelled");
 }
 private static void print(Console console,Map<String,String> row) {
  row.forEach((key,value)->console.printf("%s: %s%n",key,safe(value)));
  console.printf("%n");
 }
 private static String safe(String value) {
  if(value==null)return "(none)";
  StringBuilder out=new StringBuilder();
  value.codePoints().forEach(c->{if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)out.append(String.format("\\u%04x",c));else out.appendCodePoint(c);});
  return out.toString();
 }
}
