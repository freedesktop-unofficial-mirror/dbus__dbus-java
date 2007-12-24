/*
   D-Bus Java Implementation
   Copyright (c) 2005-2006 Matthew Johnson

   This program is free software; you can redistribute it and/or modify it
   under the terms of either the GNU General Public License Version 2 or the
   Academic Free Licence Version 2.1.

   Full licence texts are included in the COPYING file with this program.
*/
package org.freedesktop.dbus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.io.PrintWriter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import cx.ath.matthew.debug.Debug;

import org.freedesktop.dbus.exceptions.DBusException;

import static org.freedesktop.dbus.Gettext._;

public class DBusProxy
{
   private static Map<String,Class<? extends DBusInterface>> typecache;
   static {
      typecache = new HashMap<String,Class<? extends DBusInterface>>();
   }
   private static String getImport(Class c)
   {
      if (null == c) return "";
      if (Void.TYPE.equals(c)) return "";
      while (c.isArray()) c = c.getComponentType();
      if (c.isPrimitive()) return "";
      String name = c.getName();
      name = AbstractConnection.dollar_pattern.matcher(name).replaceAll(".");
      return "import "+name+";";
   }
   private static String boxed(String c) {
      if (c.equals("boolean")) return "Boolean";
      if (c.equals("int")) return "Integer";
      if (c.equals("short")) return "Short";
      if (c.equals("long")) return "Long";
      if (c.equals("double")) return "Double";
      if (c.equals("byte")) return "Byte";
      if (c.equals("float")) return "Float";
      if (c.equals("char")) return "Char";
      return c;
   }
   @SuppressWarnings("unchecked")
   public static Object getProxy(Class[] types, AbstractConnection conn, RemoteObject ro) throws DBusException
   {
      if (Debug.debug) Debug.print(Debug.DEBUG, "Creating proxy type: "+Arrays.deepToString(types)+" connection: "+conn+" Details: "+ro);
      // dynamic runtime compilation
      if (DBusCodeGen.canCompile()) {
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Creating compiled proxy");
         DBusCodeGen gen = new DBusCodeGen();

         // pick a random name, check whether we have it already
         String classname = "_"+(long) Math.abs(ro.hashCode());
         if (null != typecache.get(classname)) return typecache.get(classname);

         Vector<Method> methods = new Vector<Method>();

         PrintWriter out = gen.startClass("org.freedesktop.dbus.dynamic", classname);

         // required imports
         out.println("import org.freedesktop.dbus.*;");
         out.println("import org.freedesktop.dbus.exceptions.*;");
         if (Debug.debug)
            out.println("import cx.ath.matthew.debug.Debug;");
         for (Class c: types) {
            out.print("import ");
            out.print(AbstractConnection.dollar_pattern.matcher(c.getName()).replaceAll("."));
            out.println(';');
            // push methods for later use
            for (Method m: c.getDeclaredMethods()) {
               methods.add(m);
               if(null != m.getReturnType() && !Void.TYPE.equals(m.getReturnType())) 
                  out.println(getImport(m.getReturnType()));
               for (Class p: m.getParameterTypes()) 
                  out.println(getImport(p));
            }
         }

         // implements the list of interfaces
         out.print("public class ");
         out.print(classname);
         out.print(" implements ");
         for (Class c: types) {
            out.print(c.getSimpleName());
            out.print(',');
         }
         out.println("DBusInterface {");

         // local variables
         out.println("private AbstractConnection conn;");
         out.println("private RemoteObject ro;");

         // constructor
         out.print("public ");
         out.print(classname);
         out.println("(AbstractConnection conn, RemoteObject ro) { this.conn = conn; this.ro = ro; }");

         // local methods
         out.println("public boolean isRemote() { return true; }");
         out.print("public String toString() { return \"");
         out.print(ro.toString());
         out.println("\"; }");

         // iterate over methods
         for (Method m: methods) {
            out.print("public ");

            // return type
            if (null == m.getReturnType())
               out.print("void");
            else
               out.print(m.getReturnType().getSimpleName());
            out.print(' ');

            // method name
            out.print(m.getName());

            // parameters
            out.print('(');
            Class[] cs = m.getParameterTypes();
            char n = 'a';
            for (int i = 0; i < cs.length; i++, n++) {
               out.print(cs[i].getSimpleName());
               out.print(' ');
               out.print(n);
               if ((i + 1) != cs.length)
                  out.print(',');
            }
            out.println(") {");

            // invoke using proxy for now
            out.println("java.lang.reflect.Method meth = null;");
            out.println("for (Class cl:getClass().getInterfaces()) {");
            out.println("try {");
            out.print("meth = cl.getMethod(\"");
            out.print(m.getName());
            out.print('"');
            for (Class c: m.getParameterTypes()) {
               out.print(',');
               out.print(c.getSimpleName());
               out.print(".class");
            }
            out.println(");");
            out.println("break;");
            out.println("} catch (NoSuchMethodException NSMe) {}");
            out.println('}');
            out.print("Object rv = RemoteInvocationHandler.executeRemoteMethod(ro, meth, conn, RemoteInvocationHandler.CALL_TYPE_SYNC, null");
            for (char q = 'a'; q < n; q++) {
               out.print(",(Object)");
               out.print(q);
            }
            out.println(");");

            // check if we need to return a value
            if(null != m.getReturnType() && !Void.TYPE.equals(m.getReturnType())) {
               out.print("return (");
               out.print(boxed(m.getReturnType().getSimpleName()));
               out.println(") rv;");
            }
            out.println('}');
         }
         out.println('}');

         // compile and instantiate
         try {
            return gen.loadClasses()[0].getConstructor(AbstractConnection.class, RemoteObject.class).newInstance(conn, ro);
         } catch (Exception e) {
            if (AbstractConnection.EXCEPTION_DEBUG && Debug.debug)
               Debug.print(e);
            throw new DBusException(_("Failed to instantiate compiled proxy class: ")+e);
         }
      }

      // runtime proxies
      else {
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Creating handler proxy");
         return Proxy.newProxyInstance(DBusProxy.class.getClassLoader(), 
            types, new RemoteInvocationHandler(conn, ro));
      }
   }
}
