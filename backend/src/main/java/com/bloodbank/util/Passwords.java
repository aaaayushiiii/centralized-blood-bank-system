package com.bloodbank.util;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
/** Versioned PBKDF2-HMAC-SHA256 using the JDK cryptographic provider. */
public final class Passwords {
 private static final int ITERATIONS=600000;
 private static final SecureRandom RANDOM=new SecureRandom();
 private Passwords() {}
 public static String hash(String password) {
  byte[] salt=new byte[16]; RANDOM.nextBytes(salt);
  return "pbkdf2-sha256$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(password,salt,ITERATIONS));
 }
 public static boolean verify(String password,String stored) {
  if(password==null || stored==null) return false;
  try {
   String[] p=stored.split(java.util.regex.Pattern.quote("$"),-1);
   if(p.length!=4 || !p[0].equals("pbkdf2-sha256")) return false;
   int n=Integer.parseInt(p[1]); if(n<ITERATIONS || n>2000000) return false;
   byte[] salt=Base64.getDecoder().decode(p[2]), expected=Base64.getDecoder().decode(p[3]);
   return salt.length==16 && expected.length==32 && MessageDigest.isEqual(expected,derive(password,salt,n));
  } catch(IllegalArgumentException e) { return false; }
 }
 private static byte[] derive(String password,byte[] salt,int n) {
  PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,n,256);
  try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
  catch(GeneralSecurityException e) { throw new IllegalStateException("PBKDF2 unavailable",e); }
  finally { spec.clearPassword(); }
 }
}
