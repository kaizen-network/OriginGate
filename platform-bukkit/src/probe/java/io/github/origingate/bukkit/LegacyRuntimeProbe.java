package io.github.origingate.bukkit;
import io.github.origingate.core.Log;
import io.github.origingate.core.lookup.maxmind.MaxMindProvider;
import io.github.origingate.core.storage.SqlIpStorage;
import java.nio.file.*;
import java.time.*;
import java.sql.*;
public class LegacyRuntimeProbe {
 public static void main(String[] args) throws Exception {
  try(MaxMindProvider provider=new MaxMindProvider(Paths.get(args[0]),Clock.systemUTC(),Log.NONE)) {
   if(!provider.reload()) throw new AssertionError("MaxMind load failed");
   if(!"GB".equals(provider.lookup("81.2.69.160",Instant.MAX).countryCode()))throw new AssertionError("IPv4 lookup failed");
   if(!"JP".equals(provider.lookup("2001:218::",Instant.MAX).countryCode()))throw new AssertionError("IPv6 lookup failed");
  }
  SqlIpStorage storage=SqlIpStorage.sqlite(Paths.get(args[1]));
  java.lang.reflect.Field field=SqlIpStorage.class.getDeclaredField("connections");field.setAccessible(true);
  try(Connection c=((SqlIpStorage.Connections)field.get(storage)).open();Statement s=c.createStatement();ResultSet r=s.executeQuery("select sqlite_version()")) {
   r.next();String version=r.getString(1);System.out.println("SQLite="+version);
   if(!"3.53.4".equals(version))throw new AssertionError("Server supplied older SQLite: "+version);
  }
  System.out.println("PASS packaged Java runtime "+System.getProperty("java.version")+" MaxMind IPv4/IPv6 and SQLite");
 }
}
