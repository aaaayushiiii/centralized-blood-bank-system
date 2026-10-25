package com.bloodbank.config;

import com.bloodbank.util.ApiException;
import java.net.*;

/** Explicit local-demo opt-in. This does not grant Oracle privileges. */
public final class WebAdminPolicy {
 private final boolean enabled;
 public WebAdminPolicy(boolean enabled) {this.enabled=enabled;}
 public static WebAdminPolicy fromEnvironment() {
  return new WebAdminPolicy(Boolean.parseBoolean(System.getenv().getOrDefault("WEB_ADMIN_ENABLED","false")));
 }
 public boolean enabled() {return enabled;}
 public void validateBinding(String address) {
  if(enabled&&!localHost(address))throw new IllegalStateException("Web administrator access requires a literal loopback bind address");
 }
 public void requireLocal(InetAddress local,InetAddress peer,String origin) {
  if(!enabled || local==null || peer==null || !local.isLoopbackAddress() || !peer.isLoopbackAddress()
     || (origin!=null&&!localOrigin(origin)))
   throw new ApiException(403,"ADMIN_UNAVAILABLE","Administrator access requires an enabled local connection");
 }
 static boolean localHost(String host) {
  return "localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host)||"[::1]".equals(host);
 }
 private static boolean localOrigin(String origin) {
  try {
   URI uri=new URI(origin);
   return ("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))&&localHost(uri.getHost())
     &&uri.getRawUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null
     &&(uri.getRawPath()==null||uri.getRawPath().isEmpty())
     &&uri.getPort()>=-1&&uri.getPort()<=65535;
  } catch(URISyntaxException e) {return false;}
 }
}
