package oracle.jdbc;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
/** Test-only JDBC double; NEVER included in the production classes or jar. */
public final class OracleDriver implements Driver {
 public static final String URL="jdbc:oracle:thin:@TEST-ONLY";
 public static final Queue<Script> scripts=new ConcurrentLinkedQueue<>();
 static {try {DriverManager.registerDriver(new OracleDriver());}catch(SQLException e){throw new ExceptionInInitializerError(e);}}
 public static final class Step {
  final String sql;final List<Map<String,Object>> rows;
  public final Map<Integer,Object> expected=new LinkedHashMap<>(),out=new HashMap<>();
  public final Map<Integer,Integer> outTypes=new HashMap<>();
  public SQLException failure;
  public List<Map<String,Object>> cursor;
  Step(String sql,List<Map<String,Object>> rows){this.sql=sql;this.rows=rows;}
  public Step bind(int index,Object value){expected.put(index,value);return this;}
  public Step output(int index,int type,Object value){out.put(index,value);outTypes.put(index,type);return this;}
 }
 public static final class Script {
  final Queue<Step> steps=new ArrayDeque<>();
  public int commits,rollbacks,openStatements,openResults;
  public boolean closed,transaction;
  @SafeVarargs public final Step query(String sql,Map<String,Object>... rows){Step step=new Step(sql,Arrays.asList(rows));steps.add(step);return step;}
  public Step update(String sql){return query(sql);}
  public void done(int commits,int rollbacks) {
   require(steps.isEmpty(),"Unexecuted SQL expectations");require(this.commits==commits,"Wrong commit count: "+this.commits);require(this.rollbacks==rollbacks,"Wrong rollback count: "+this.rollbacks);
   require(closed && openStatements==0 && openResults==0,"Leaked JDBC resources");
  }
 }
 public Connection connect(String url,Properties props) throws SQLException {
  if(!acceptsURL(url))return null;Script script=scripts.poll();
  if(script==null)throw new SQLNonTransientConnectionException("Test-only disconnected database","08001");
  return proxy(Connection.class,(o,m,a)-> {
   switch(m.getName()) {
    case "prepareStatement":case "prepareCall":
     Step step=script.steps.poll();require(step!=null,"Unexpected SQL: "+a[0]);require(((String)a[0]).contains(step.sql),"Wrong SQL. Expected "+step.sql+" got "+a[0]);return statement(script,step,m.getName().equals("prepareCall"));
    case "setAutoCommit":script.transaction=!(Boolean)a[0];return null;
    case "commit":require(script.transaction,"Commit outside transaction");script.commits++;return null;
    case "rollback":script.rollbacks++;return null;
    case "close":script.closed=true;return null;
    case "isValid":return true;
    case "isClosed":return script.closed;
    case "getAutoCommit":return !script.transaction;
    default:return empty(m.getReturnType());
   }
  });
 }
 private static Object statement(Script script,Step step,boolean call) {
  script.openStatements++;Map<Integer,Object> bound=new HashMap<>();Map<Integer,Integer> registered=new HashMap<>();boolean[] closed={false};
  return proxy((Class<?>)(call?CallableStatement.class:PreparedStatement.class),(o,m,a)-> {
   String name=m.getName();
   if(name.startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer) {bound.put((Integer)a[0],name.equals("setNull")?null:a[1]);return null;}
   switch(name) {
    case "registerOutParameter":registered.put((Integer)a[0],(Integer)a[1]);return null;
    case "execute":case "executeQuery":case "executeUpdate":
     for(Map.Entry<Integer,Object> e:step.expected.entrySet()) require(Objects.equals(e.getValue(),bound.get(e.getKey())),"Wrong bind "+e.getKey()+": "+bound.get(e.getKey()));
     require(registered.equals(step.outTypes),"Incorrect OUT parameters: "+registered);
     if(step.failure!=null)throw step.failure;
     if(name.equals("executeQuery"))return results(script,step.rows);
     return name.equals("executeUpdate")?1:false;
    case "getString":return step.out.get((Integer)a[0]);
    case "getInt":case "getLong":Object number=step.out.get((Integer)a[0]);return number==null?0:((Number)number).intValue();
    case "getObject":return step.cursor==null?step.out.get((Integer)a[0]):results(script,step.cursor);
    case "close":if(!closed[0]){closed[0]=true;script.openStatements--;}return null;
    default:return empty(m.getReturnType());
   }
  });
 }
 private static ResultSet results(Script script,List<Map<String,Object>> rows) {
  script.openResults++;int[] index={-1};boolean[] closed={false};
  return proxy(ResultSet.class,(o,m,a)-> {
   switch(m.getName()) {
    case "next":return ++index[0]<rows.size();
    case "close":if(!closed[0]){closed[0]=true;script.openResults--;}return null;
   }
   if(m.getName().startsWith("get")&&a!=null&&a.length==1) {
    Map<String,Object> row=rows.get(index[0]);Object value=a[0] instanceof Integer?new ArrayList<>(row.values()).get((Integer)a[0]-1):row.get(a[0]);
    switch(m.getName()) {
     case "getLong":return value==null?0L:((Number)value).longValue();
     case "getInt":return value==null?0:((Number)value).intValue();
     case "getDouble":return value==null?0.0:((Number)value).doubleValue();
     case "getBigDecimal":return value==null?null:new java.math.BigDecimal(value.toString());
     case "getString":return value==null?null:value.toString();
     default:return value;
    }
   }return empty(m.getReturnType());
  });
 }
 @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(OracleDriver.class.getClassLoader(),new Class<?>[]{type},h);}
 private static Object empty(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public boolean acceptsURL(String url){return URL.equals(url);}
 public DriverPropertyInfo[] getPropertyInfo(String url,Properties props){return new DriverPropertyInfo[0];}
 public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}
 public Logger getParentLogger(){return Logger.getGlobal();}
}
