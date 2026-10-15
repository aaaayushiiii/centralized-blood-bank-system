package com.bloodbank.util;
import java.math.BigDecimal;
import java.util.*;
/** Strict bounded-depth JSON codec. Rejects malformed input and duplicate keys. */
public final class JsonUtil {
 private JsonUtil() {}
 public static String toJson(Object value) {
  if(value==null)return "null";
  if(value instanceof String)return quote((String)value);
  if(value instanceof Boolean)return value.toString();
  if(value instanceof Number) {
   if((value instanceof Double && !Double.isFinite((Double)value))||(value instanceof Float && !Float.isFinite((Float)value)))throw new IllegalArgumentException("Non-finite JSON number");
   return value.toString();
  }
  if(value instanceof Map) {
   StringJoiner j=new StringJoiner(",","{","}");
   for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())j.add(quote(String.valueOf(e.getKey()))+":"+toJson(e.getValue()));return j.toString();
  }
  if(value instanceof Iterable) {StringJoiner j=new StringJoiner(",","[","]");for(Object v:(Iterable<?>)value)j.add(toJson(v));return j.toString();}
  throw new IllegalArgumentException("Unsupported JSON type");
 }
 private static String quote(String s) {
  StringBuilder b=new StringBuilder("\"");
  for(int i=0;i<s.length();i++) {
   char c=s.charAt(i);
   switch(c) {
    case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;
    case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;
    default:if(c<32 || Character.isSurrogate(c))b.append(String.format("\\u%04x",(int)c));else b.append(c);
   }
  }return b.append('"').toString();
 }
 @SuppressWarnings("unchecked") public static Map<String,Object> parseJsonObject(String json) {
  Parser p=new Parser(json);Object v=p.value(0);p.space();
  if(p.i!=p.s.length() || !(v instanceof Map))throw bad();return (Map<String,Object>)v;
 }
 private static ApiException bad(){return new ApiException(400,"INVALID_JSON","Expected a valid JSON object");}
 private static final class Parser {
  final String s;int i;Parser(String s){if(s==null)throw bad();this.s=s;}
  void space(){while(i<s.length() && " \r\n\t".indexOf(s.charAt(i))>=0)i++;}
  boolean take(char c){space();if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
  Object value(int depth) {
   if(depth>16)throw bad();space();if(i>=s.length())throw bad();char c=s.charAt(i);
   if(c=='"')return string();
   if(c=='{') {
    i++;Map<String,Object> m=new LinkedHashMap<>();if(take('}'))return m;
    do {space();if(i>=s.length()||s.charAt(i)!='"')throw bad();String key=string();if(m.containsKey(key)||!take(':'))throw bad();m.put(key,value(depth+1));if(take('}'))return m;}while(take(','));throw bad();
   }
   if(c=='[') {i++;List<Object> a=new ArrayList<>();if(take(']'))return a;do {a.add(value(depth+1));if(take(']'))return a;}while(take(','));throw bad();}
   for(String literal:Arrays.asList("true","false","null"))if(s.startsWith(literal,i)) {i+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}
   int start=i;while(i<s.length() && ",]} \r\n\t".indexOf(s.charAt(i))<0)i++;
   String n=s.substring(start,i);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
   // Bound exponent as well as input length to avoid huge downstream numeric conversions.
   try {BigDecimal number=new BigDecimal(n);if(Math.abs((long)number.scale())>1000 || number.precision()>1000)throw bad();return number;}
   catch(NumberFormatException e){throw bad();}
  }
  String string() {
   if(s.charAt(i++)!='"')throw bad();StringBuilder b=new StringBuilder();
   while(i<s.length()) {
    char c=s.charAt(i++);if(c=='"')return b.toString();if(c<32)throw bad();
    if(c=='\\') {
     if(i>=s.length())throw bad();char e=s.charAt(i++);
     switch(e) {
      case '"':case '\\':case '/':b.append(e);break;
      case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;
      case 'u':if(i+4>s.length()||!s.substring(i,i+4).matches("[0-9a-fA-F]{4}"))throw bad();try {b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException ex){throw bad();}i+=4;break;
      default:throw bad();
     }
    }else b.append(c);
   }throw bad();
  }
 }
}
