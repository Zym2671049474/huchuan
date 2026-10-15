package org.json;
import java.util.*;
public class JSONObject {
  private final LinkedHashMap<String,Object> m = new LinkedHashMap<>();
  private String str;
  public static final Object NULL = new Object();
  public JSONObject() {}
  public JSONObject put(String k, Object v) { m.put(k, v); return this; }
  public JSONObject put(String k, int v) { m.put(k, (long) v); return this; }
  public JSONObject put(String k, long v) { m.put(k, v); return this; }
  public boolean has(String k) { return m.containsKey(k); }
  public boolean isNull(String k) { Object v=m.get(k); return v==null||v==NULL; }
  public String optString(String k) { return optString(k,""); }
  public String optString(String k, String d) { Object v=m.get(k); return v==null||v==NULL?d:String.valueOf(v); }
  public int optInt(String k) { return optInt(k,0); }
  public int optInt(String k, int d) { Object v=m.get(k); return v instanceof Number?((Number)v).intValue():d; }
  public long optLong(String k) { return optLong(k,0L); }
  public long optLong(String k, long d) { Object v=m.get(k); return v instanceof Number?((Number)v).longValue():d; }
  public String getString(String k) { return String.valueOf(m.get(k)); }
  public int getInt(String k) { return ((Number)m.get(k)).intValue(); }
  public JSONObject(String s) { str=s.trim(); p=0; Object o=readVal(); if(o instanceof JSONObject) m.putAll(((JSONObject)o).m); }
  private int p;
  private void skip(){ while(p<str.length()&&Character.isWhitespace(str.charAt(p)))p++; }
  private char peek(){ if(p>=str.length()) throw new JSONException("eof"); return str.charAt(p); }
  private void expect(char c){ if(peek()!=c) throw new JSONException("expect "+c); }
  private String readStr(){ expect('"'); p++; StringBuilder b=new StringBuilder();
    while(true){ char c=str.charAt(p++); if(c=='"') return b.toString();
      if(c=='\\'){ char e=str.charAt(p++); b.append(e=='n'?'\n':e=='t'?'\t':e); } else b.append(c); } }
  private Object readVal(){ skip(); char c=peek();
    if(c=='"') return readStr();
    if(c=='{'){ p++; JSONObject o=new JSONObject(); skip();
      if(peek()=='}'){p++;return o;}
      while(true){ skip(); String k=readStr(); skip(); expect(':'); p++; o.m.put(k, readVal()); skip();
        char x=peek(); p++; if(x=='}') return o; if(x!=',') throw new JSONException("bad obj"); } }
    if(c=='['){ p++; JSONArray arr=new JSONArray(); skip();
      if(peek()==']'){p++;return arr;}
      while(true){ arr.put(readVal()); skip(); char x=peek(); p++;
        if(x==']') return arr; if(x!=',') throw new JSONException("bad arr"); } }
    int st=p; while(p<str.length()&&",}]".indexOf(str.charAt(p))<0)p++;
    String tok=str.substring(st,p).trim();
    if("null".equals(tok)) return NULL;
    if("true".equals(tok)) return Boolean.TRUE;
    if("false".equals(tok)) return Boolean.FALSE;
    try { return tok.contains(".")?(Object)Double.valueOf(tok):(Object)Long.valueOf(tok); }
    catch(Exception e){ return tok; }
  }
  @Override public String toString(){
    StringBuilder b=new StringBuilder("{"); boolean f=true;
    for(Map.Entry<String,Object> e:m.entrySet()){ if(!f)b.append(','); f=false;
      b.append('"').append(e.getKey()).append("\":");
      Object v=e.getValue();
      if(v==null||v==NULL)b.append("null");
      else if(v instanceof Number||v instanceof Boolean)b.append(v);
      else if(v instanceof JSONObject||v instanceof JSONArray)b.append(v);
      else b.append('"').append(String.valueOf(v).replace("\\","\\\\").replace("\"","\\\"")).append('"');
    } return b.append('}').toString();
  }
}
