package org.json;
import java.util.*;
public class JSONArray {
  private final List<Object> l = new ArrayList<>();
  public JSONArray() {}
  public JSONArray put(Object v){ l.add(v); return this; }
  public JSONArray put(int v){ l.add((long) v); return this; }
  public JSONArray(String s){ String inner=s.substring(1,s.length()-1).trim();
    if(inner.isEmpty())return; for(String p:inner.split(",")){ p=p.trim();
      try{ l.add(Long.valueOf(p)); }catch(Exception e){ l.add(p.replace("\"","")); } } }
  @Override public String toString(){ StringBuilder b=new StringBuilder("[");
    for(int i=0;i<l.size();i++){ if(i>0)b.append(','); Object v=l.get(i);
      if(v instanceof Number)b.append(v); else b.append('"').append(v).append('"'); }
    return b.append(']').toString(); }
}
